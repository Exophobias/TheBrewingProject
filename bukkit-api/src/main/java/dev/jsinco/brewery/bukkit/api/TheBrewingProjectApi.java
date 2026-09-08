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
