package dev.jsinco.brewery.bukkit.api.event.transaction;

import dev.jsinco.brewery.api.brew.Brew;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.Optional;

/** A detached runtime observation of an output published to its inventory destination.
 * This is not durable delivery or permission to replay an item. */
public record InventoryExtractionReceipt(Optional<Brew> sourceBrew, ItemStack item) {
    public InventoryExtractionReceipt {
        sourceBrew = Objects.requireNonNull(sourceBrew, "sourceBrew");
        item = Objects.requireNonNull(item, "item").clone();
        if (item.isEmpty()) throw new IllegalArgumentException("An extraction output must not be empty");
    }

    @Override public ItemStack item() { return item.clone(); }
}
