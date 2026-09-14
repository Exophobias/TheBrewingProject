package dev.jsinco.brewery.bukkit.api;

import dev.jsinco.brewery.api.brew.BrewManager;
import dev.jsinco.brewery.api.config.Configuration;
import dev.jsinco.brewery.api.effect.DrunksManager;
import dev.jsinco.brewery.api.effect.modifier.ModifierManager;
import dev.jsinco.brewery.api.ingredient.IngredientManager;
import dev.jsinco.brewery.api.ingredient.ResolvedIngredientManager;
import dev.jsinco.brewery.api.integration.IntegrationManager;
import dev.jsinco.brewery.api.recipe.RecipeRegistry;
import dev.jsinco.brewery.api.structure.PlacedStructureRegistry;
import dev.jsinco.brewery.bukkit.api.effect.DrunkEventManager;
import org.bukkit.inventory.ItemStack;

import java.util.concurrent.CompletableFuture;

public interface TheBrewingProjectApi {

    /**
     * Reserve 1..20 empty coordinates and preissue durable births before native input is possible.
     * This increment quarantines ALL input at those keys; no fixture creation is authorized.
     * Persist the exact request/capability before calling. Unknown completion retains ownership.
     */
    default dev.jsinco.brewery.api.persistence.CauldronFixtureReceipt reserveCauldronFixtures(
            dev.jsinco.brewery.api.persistence.CauldronFixtureRequest request) {
        throw new UnsupportedOperationException("Durable cauldron fixture reservations are unavailable");
    }
    /** Exact read-only lookup, including after restart or a lost acquire/close response. */
    default dev.jsinco.brewery.api.persistence.CauldronFixtureReceipt inspectCauldronFixtures(
            dev.jsinco.brewery.api.persistence.CauldronFixtureRequest request) {
        throw new UnsupportedOperationException("Durable cauldron fixture reservations are unavailable");
    }
    /**
     * Cleanup-only retirement of the exact empty reservation. Deletes no cauldron or player value.
     * Any live/persisted cauldron refuses; CLOSED tombstones permit exact close retries.
     * Main-thread admission/current() and current provider identity are required by all three methods.
     */
    default dev.jsinco.brewery.api.persistence.CauldronFixtureReceipt closeCauldronFixtures(
            dev.jsinco.brewery.api.persistence.CauldronFixtureRequest request) {
        throw new UnsupportedOperationException("Durable cauldron fixture reservations are unavailable");
    }

    /**
     * Inspect 1..32 distinct cauldron keys in one world after their already accepted writes.
     * Runs SELECT only, with bounded row/text and concurrent-read budgets. Admission and current()
     * require the primary server thread (Folia region ownership is not supported by this seam).
     * A receipt goes stale on any later cauldron write or hydration, even at another coordinate.
     */
    default dev.jsinco.brewery.api.persistence.CauldronPersistenceReceipt inspectPersistedCauldrons(
            java.util.List<dev.jsinco.brewery.api.vector.BreweryLocation> keys) {
        dev.jsinco.brewery.api.persistence.CauldronPersistenceSnapshot.validateKeys(keys);
        throw new UnsupportedOperationException("Exact cauldron inspection is unavailable");
    }

    /**
     * Retire only this exact current native holder, including ordinary display teardown. Acknowledges
     * its original ordered DELETE plus absent-row SQL readback; never uses a caller-supplied future.
     * May refuse after teardown callbacks change ownership. This destroys its brew, so callers must
     * independently own the fixture/content and preserve their cleanup evidence before admission.
     * No drops, inventory delivery, block restoration, cold deletion authority or retry is implied.
     */
    default dev.jsinco.brewery.api.persistence.CauldronPersistenceReceipt retireCauldron(
            dev.jsinco.brewery.api.breweries.Cauldron cauldron) {
        throw new UnsupportedOperationException("Acknowledged cauldron retirement is unavailable");
    }

    /**
     * Read persisted parent and brew rows for 1..32 distinct keys in the same world, in one
     * owner-ordered read transaction. Includes orphan brew rows; does not hydrate holders, load
     * chunks, mutate SQL, or confer a reservation. Live ownership must be checked separately.
     * Invalid requests and unsupported/failed reads never masquerade as empty persistence.
     */
    default CompletableFuture<dev.jsinco.brewery.api.persistence.BreweryPersistenceSnapshot>
            inspectPersistedStructures(java.util.List<dev.jsinco.brewery.api.vector.BreweryLocation> keys) {
        dev.jsinco.brewery.api.persistence.BreweryPersistenceSnapshot.validateKeys(keys);
        return CompletableFuture.failedFuture(new UnsupportedOperationException("Exact persistence inspection is unavailable"));
    }

    /**
     * @return A brew manager instance that helps you create and read brews
     */
    BrewManager<ItemStack> getBrewManager();

    /**
     * @return A modifier manager that helps you access modifiers
     */
    ModifierManager getModifierManager();

    /**
     * @return A drunks manager that helps you manage player drunkeness and plan events
     */
    DrunksManager getDrunksManager();

    /**
     * @return A registry of every recipe
     */
    RecipeRegistry<ItemStack> getRecipeRegistry();

    /**
     * @return A registry of every structure placed in the world
     */
    PlacedStructureRegistry getPlacedStructureRegistry();

    /**
     * @return An integration manager, that allows you to register integrations
     */
    IntegrationManager getIntegrationManager();

    /**
     * @return Access to some configuration options
     */
    Configuration getConfiguration();

    /**
     * @return An ingredient-item stack bridge
     */
    IngredientManager<ItemStack> getIngredientManager();

    /**
     * @return An ingredient-item stack bridge future
     */
    CompletableFuture<ResolvedIngredientManager<ItemStack>> getResolvedIngredientManager();

    /**
     * @return A manager for events
     */
    DrunkEventManager getDrunkenEventManager();
}
