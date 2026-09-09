package dev.jsinco.brewery.bukkit.api.event.transaction;

import dev.jsinco.brewery.api.vector.BreweryLocation;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.UUID;

/**
 * One synchronous runtime extraction observed by the owner. The approved hand and serving
 * transitioned and an exact native item entity was present when the owner finished. This is
 * handoff evidence only: it does not certify pickup, durable serving persistence, crash-safe
 * delivery, or permission to replay the item. The entity may subsequently change or disappear.
 */
public record CauldronExtractionReceipt(UUID playerId, BreweryLocation cauldron,
                                        int levelBefore, int levelAfter, boolean inputConsumed,
                                        UUID itemEntityId, ItemStack item) {
    public CauldronExtractionReceipt {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(cauldron, "cauldron");
        Objects.requireNonNull(itemEntityId, "itemEntityId");
        Objects.requireNonNull(item, "item");
        if (levelBefore < 1 || levelAfter != levelBefore - 1 || item.isEmpty())
            throw new IllegalArgumentException("Invalid observed cauldron extraction");
        item = item.clone();
    }

    /** A detached snapshot of the actual materialized output; mutation cannot alter the receipt. */
    @Override public ItemStack item() { return item.clone(); }
}
