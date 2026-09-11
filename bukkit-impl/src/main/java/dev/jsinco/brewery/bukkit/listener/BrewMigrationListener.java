package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.bukkit.brew.BrewAdapterAccess;
import dev.jsinco.brewery.configuration.Config;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.Optional;

public class BrewMigrationListener implements Listener {
    private final java.util.function.Function<ItemStack, Optional<ItemStack>> render;
    private final java.util.function.BooleanSupplier enabled;

    public BrewMigrationListener() { this(BrewMigrationListener::migrateItemStack, () -> Config.config().reencryptItemsInInventories()); }
    BrewMigrationListener(java.util.function.Function<ItemStack, Optional<ItemStack>> render, java.util.function.BooleanSupplier enabled) {
        this.render = java.util.Objects.requireNonNull(render); this.enabled = java.util.Objects.requireNonNull(enabled);
    }

    /**
     * Migrates an ItemStack from whatever encryption method/key
     * was used to 256 bit AES-GCM using the newest secret key.
     */
    private static Optional<ItemStack> migrateItemStack(@Nullable ItemStack item) {
        if (item == null || item.isEmpty()) return Optional.empty();
        Optional<Brew> brewOptional = BrewAdapterAccess.fromItem(item);
        return brewOptional.map(brew -> BrewAdapterAccess.toItem(brew, new Brew.State.Other()))
                .or(() -> dev.jsinco.brewery.bukkit.recipe.BrewRecognition.refreshLegacySealed(item,
                        dev.jsinco.brewery.bukkit.TheBrewingProject.getInstance().getRecipeRegistry()));
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerJoin(PlayerJoinEvent event) {
        if (!enabled.getAsBoolean()) return;
        migrateInventory(event.getPlayer().getInventory());
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerOpenInventory(InventoryOpenEvent event) {
        if (!enabled.getAsBoolean()) return;
        Inventory inventory = event.getInventory();
        if (inventory.getType() == InventoryType.PLAYER) return;
        if (!migrateInventory(inventory)) event.setCancelled(true);
    }

    private boolean migrateInventory(Inventory inventory) {
        var plugin = dev.jsinco.brewery.bukkit.TheBrewingProject.getInstance();
        var owner = plugin.getBreweryRegistry().getFromInventory(inventory);
        java.util.function.BooleanSupplier current = () -> plugin.getBreweryRegistry().getFromInventory(inventory) == owner
                && !(owner instanceof dev.jsinco.brewery.bukkit.breweries.distillery.BukkitDistillery still && still.isAtomicMovePending());
        if (!current.getAsBoolean()) return false;
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            if (!current.getAsBoolean()) return false;
            ItemStack original = inventory.getItem(slot);
            if (original == null || original.isEmpty()) continue;
            ItemStack before = original.clone();
            Optional<ItemStack> replacement = render.apply(before.clone());
            if (!current.getAsBoolean() || !before.equals(inventory.getItem(slot))) return false;
            if (replacement.isPresent()) {
                ItemStack migrated = replacement.get().clone();
                // Recipe rendering represents one brew. Encryption migration must conserve the
                // entire existing stack, including custom stackable brew outputs. If a changed
                // recipe can no longer hold that quantity, preserve the original slot instead.
                if (migrated.isEmpty() || before.getAmount() > migrated.getMaxStackSize()) return false;
                migrated.setAmount(before.getAmount());
                inventory.setItem(slot, migrated);
            }
        }
        return current.getAsBoolean();
    }
}
