package dev.jsinco.brewery.bukkit.recipe;

import dev.jsinco.brewery.api.brew.*;
import dev.jsinco.brewery.api.breweries.CauldronType;
import dev.jsinco.brewery.api.meta.MetaDataType;
import dev.jsinco.brewery.api.moment.Moment;
import dev.jsinco.brewery.api.moment.PassedMoment;
import dev.jsinco.brewery.api.recipe.QualityData;
import dev.jsinco.brewery.api.recipe.Recipe;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.CookStepImpl;
import dev.jsinco.brewery.brew.DistillStepImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.integration.IntegrationTypes;
import dev.jsinco.brewery.bukkit.api.integration.RecipeScoreIntegration;
import dev.jsinco.brewery.bukkit.ingredient.SimpleIngredient;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import dev.jsinco.brewery.recipes.RecipeImpl;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class RecipeScoreIntegrationTest {
    private TheBrewingProject plugin;
    @BeforeEach void setUp() {
        MockBukkit.mock(new TBPServerMock());
        plugin = MockBukkit.load(TheBrewingProject.class);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void certifiedNormalizationRunsBeforeZeroFilteringAndCutsRunAfterward() {
        var cook = new CookStepImpl(new PassedMoment(8 * Moment.MINUTE),
                Map.of(new SimpleIngredient(Material.WHEAT), 3), CauldronType.WATER);
        List<BrewingStep> required = List.of(cook, new DistillStepImpl(6, 0));
        Recipe<ItemStack> recipe = new RecipeImpl.Builder<ItemStack>("integration_fixture")
                .brewDifficulty(9).steps(required).recipeResults(QualityData.equalValued(BukkitRecipeResult.GENERIC)).build();
        plugin.getRecipeRegistry().registerRecipe(recipe);
        Brew brew = new BrewImpl(List.of(cook, new DistillStepImpl(10)))
                .withMeta(MetaScoreModifier.SCORE_MODIFIER_KEY, MetaDataType.DOUBLE, .5D);
        assertEquals(0, recipe.score(brew.getCompletedSteps()).rawScore());
        assertTrue(RecipeMatcherImpl.builder().matchAgainstOnly(recipe).build().match(brew).recipeMatch().isEmpty());
        AtomicInteger calls = new AtomicInteger();
        plugin.getIntegrationManager().register(IntegrationTypes.RECIPE_SCORE, new RecipeScoreIntegration() {
            @Override public String getId() { return "certified-test"; }
            @Override public boolean isEnabled() { return true; }
            @Override public BrewScore score(Brew actual, Recipe<ItemStack> candidate, List<BrewingStep> steps, BrewScore original) {
                calls.incrementAndGet();
                assertSame(brew, actual);
                assertSame(recipe, candidate);
                assertEquals(0, original.rawScore());
                assertEquals(10, ((BrewingStep.Distill) steps.getLast()).runs());
                return candidate.score(required);
            }
        });
        var result = RecipeMatcherImpl.builder().matchAgainstOnly(recipe).build().match(brew);
        assertSame(recipe, result.recipeMatch().orElseThrow());
        assertTrue(calls.get() > 0);
        assertEquals(1, result.score().rawScore());
        assertEquals(.5, result.score().score(), 1e-12, "ordinary cuts must still affect the normalized score");
        assertFalse(BrewRecognition.recognized(result.score()));
        assertEquals(10, ((BrewingStep.Distill) brew.lastCompletedStep()).runs());
    }
}
