package dev.jsinco.brewery.recipes;

import dev.jsinco.brewery.api.brew.BrewScore;
import dev.jsinco.brewery.api.brew.BrewingStep;
import dev.jsinco.brewery.api.ingredient.Ingredient;
import dev.jsinco.brewery.api.recipe.QualityData;
import dev.jsinco.brewery.api.recipe.Recipe;
import dev.jsinco.brewery.api.recipe.RecipeResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class RecipeRegistryLifecycleTest {
    @Test
    void delayedOlderLoadCannotMixWithNewGeneration() {
        var registry = new RecipeRegistryImpl<Object>();
        var original = new TestRecipe("original");
        registry.registerRecipe(original);
        long slow = registry.beginLoad();
        long newer = registry.beginLoad();
        assertSame(original, registry.getRecipe("ORIGINAL").orElseThrow());
        var current = new TestRecipe("current");
        assertTrue(registry.publishLoad(newer, List.of(current), Map.of()));
        assertFalse(registry.publishLoad(slow, List.of(new TestRecipe("obsolete")), Map.of()));
        assertEquals(List.of(current), registry.getRecipes());
    }

    @Test
    void shutdownAndClearInvalidateOutstandingLoads() {
        var registry = new RecipeRegistryImpl<Object>();
        long loading = registry.beginLoad();
        registry.invalidatePendingLoads();
        assertFalse(registry.publishLoad(loading, List.of(new TestRecipe("late")), Map.of()));
        loading = registry.beginLoad();
        registry.clear();
        assertFalse(registry.publishLoad(loading, List.of(new TestRecipe("late")), Map.of()));
        assertTrue(registry.getRecipes().isEmpty());
    }

    @Test
    void staleRecipeRemovalCannotDeleteReplacement() {
        var registry = new RecipeRegistryImpl<Object>();
        var old = new TestRecipe("same-name");
        var replacement = new TestRecipe("same-name");
        registry.registerRecipe(old);
        registry.registerRecipe(replacement);
        registry.unRegisterRecipe(old);
        assertSame(replacement, registry.getRecipe("same-name").orElseThrow());
    }

    @Test
    void exposedCollectionsAreStableImmutableSnapshots() {
        var registry = new RecipeRegistryImpl<Object>();
        var recipe = new TestRecipe("retained");
        registry.registerRecipe(recipe);
        var before = registry.getRecipes();
        assertThrows(UnsupportedOperationException.class, before::clear);
        registry.clear();
        assertEquals(List.of(recipe), before);
        assertTrue(registry.getRecipes().isEmpty());
    }

    private record TestRecipe(String name) implements Recipe<Object> {
        public String getRecipeName() { return name; }
        public double getBrewDifficulty() { return 1; }
        public List<BrewingStep> getSteps() { return List.of(); }
        public QualityData<RecipeResult<Object>> getRecipeResults() { return null; }
        public Ingredient toIngredient(double score) { throw new UnsupportedOperationException(); }
        public BrewScore score(List<BrewingStep> steps) { throw new UnsupportedOperationException(); }
    }
}
