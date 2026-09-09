package dev.jsinco.brewery.bukkit.database;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.ingredient.ResolvedIngredientManager;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.bukkit.ingredient.ResolvedIngredientManagerImpl;
import org.bukkit.inventory.ItemStack;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/** Complete immutable payloads captured on the thread admitting an owner persistence write. */
public final class BrewPersistenceSnapshot {
    // The production manager inherits ResolvedIngredientManager.serializeIngredient unchanged:
    // it writes the existing key and IngredientMeta serializers, without any item/recipe lookup.
    // Its stateless encoder is usable before asynchronous ingredient initialization completes.
    // This internal SQL encoding deliberately does not use plugin-supplied alternate codecs.
    private static final ResolvedIngredientManager<ItemStack> ENCODER = new ResolvedIngredientManagerImpl();

    private BrewPersistenceSnapshot() { }

    /** Same stateless owner encoding, with no dependency wait or retained mutable Brew reference. */
    public static String captureNow(Brew brew) {
        return BrewImpl.SERIALIZER.serialize(Objects.requireNonNull(brew, "brew"), ENCODER).toString();
    }

    public static CompletableFuture<String> capture(Brew brew,
            CompletableFuture<ResolvedIngredientManager<ItemStack>> ingredientReadiness) {
        Objects.requireNonNull(ingredientReadiness, "ingredientReadiness");
        // Materialize every step, brewer, ingredient metadata value and brew metadata array now.
        // A shallow Brew/list copy is insufficient: those reachable values may still be mutable.
        final String serialized = captureNow(brew);
        // Preserve startup/failed initialization gating, without retaining or rereading the Brew.
        return ingredientReadiness.thenApply(ignored -> serialized);
    }
}
