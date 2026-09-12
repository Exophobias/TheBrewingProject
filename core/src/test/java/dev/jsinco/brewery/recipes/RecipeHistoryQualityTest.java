package dev.jsinco.brewery.recipes;

import dev.jsinco.brewery.api.brew.BrewingStep;
import dev.jsinco.brewery.api.breweries.CauldronType;
import dev.jsinco.brewery.api.ingredient.BaseIngredient;
import dev.jsinco.brewery.api.moment.PassedMoment;
import dev.jsinco.brewery.api.recipe.QualityData;
import dev.jsinco.brewery.api.recipe.RecipeResult;
import dev.jsinco.brewery.api.util.BreweryKey;
import dev.jsinco.brewery.brew.CookStepImpl;
import dev.jsinco.brewery.brew.DistillStepImpl;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.lang.reflect.Proxy;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Real history scoring evidence reused by Testing's public loaded-recipe orchestration checks. */
class RecipeHistoryQualityTest {
    @ParameterizedTest @CsvSource({"5,EXCELLENT", "7,GOOD", "8,BAD", "10,FAILED"})
    void actualDistillationHistoryDeterminesCompletedQualityWithoutAnyOverride(int runs, String expected) {
        BaseIngredient wheat = new BaseIngredient() {
            public BreweryKey key() { return BreweryKey.minecraft("wheat"); }
            public Component displayName() { return Component.text("Wheat"); }
            public Optional<java.awt.Color> color() { return Optional.empty(); }
        };
        var cook = new CookStepImpl(new PassedMoment(100), Map.of(wheat, 3), CauldronType.WATER);
        @SuppressWarnings("unchecked") RecipeResult<Object> unusedResult = (RecipeResult<Object>) Proxy.newProxyInstance(
                RecipeResult.class.getClassLoader(), new Class<?>[] {RecipeResult.class},
                (self, method, args) -> { throw new AssertionError("Scoring must not render an item"); });
        var recipe = new RecipeImpl.Builder<Object>("representative_spirit").brewDifficulty(2)
                .steps(List.of(cook, new DistillStepImpl(5, 1)))
                .recipeResults(QualityData.equalValued(unusedResult)).build();
        List<BrewingStep> history = List.of(new CookStepImpl(new PassedMoment(100), Map.of(wheat, 3), CauldronType.WATER),
                new DistillStepImpl(runs));
        var score = recipe.score(history);
        assertTrue(score.completed());
        assertEquals(expected, score.brewQuality() == null ? "FAILED" : score.brewQuality().name());
        if (expected.equals("FAILED")) assertEquals(0D, score.score());
        else assertTrue(score.score() > 0D);
        assertFalse(((BrewScoreImpl) score).hasQualityOverride());
    }
}
