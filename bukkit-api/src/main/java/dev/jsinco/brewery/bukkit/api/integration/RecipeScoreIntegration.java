package dev.jsinco.brewery.bukkit.api.integration;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.brew.BrewScore;
import dev.jsinco.brewery.api.brew.BrewingStep;
import dev.jsinco.brewery.api.integration.Integration;
import dev.jsinco.brewery.api.recipe.Recipe;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NonNull;

import java.util.List;

/**
 * Applies a plugin's recipe-scoring policy or independently validated brewing achievement.
 * The original brew and its measurements must never be changed by this callback.
 * This runs before the ordinary score multiplier and before zero-score candidates
 * are discarded, so certified extra processing need not destroy a valid recipe.
 * Implementations must preserve incomplete steps and return the original score when
 * their policy does not apply. Any achievement-based exception must validate its
 * certificate against this exact recipe and matching chain.
 */
public interface RecipeScoreIntegration extends Integration {
    @NonNull BrewScore score(@NonNull Brew brew, @NonNull Recipe<ItemStack> recipe,
                            @NonNull List<BrewingStep> matchingSteps, @NonNull BrewScore original);
}
