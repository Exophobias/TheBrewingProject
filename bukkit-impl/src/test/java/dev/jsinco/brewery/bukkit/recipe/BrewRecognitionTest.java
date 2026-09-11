package dev.jsinco.brewery.bukkit.recipe;

import dev.jsinco.brewery.api.brew.*;
import dev.jsinco.brewery.api.moment.Interval;
import dev.jsinco.brewery.api.recipe.QualityData;
import dev.jsinco.brewery.api.recipe.Recipe;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.DistillStepImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.brew.BrewAdapterAccess;
import dev.jsinco.brewery.bukkit.testutil.ComponentItemStackMock;
import dev.jsinco.brewery.bukkit.testutil.ItemComponentBridgeFixture;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import dev.jsinco.brewery.recipes.RecipeImpl;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BrewRecognitionTest {
    private TheBrewingProject plugin;
    private AutoCloseable potionFactory;
    private AutoCloseable componentBridge;

    @BeforeEach void setUp() throws Exception {
        MockBukkit.mock(new TBPServerMock());
        plugin = MockBukkit.load(TheBrewingProject.class);
        potionFactory = ComponentItemStackMock.installPotionFactory();
        componentBridge = ItemComponentBridgeFixture.install();
    }

    @AfterEach void tearDown() throws Exception {
        try {
            if (componentBridge != null) componentBridge.close();
            if (potionFactory != null) potionFactory.close();
        } finally {
            MockBukkit.unmock();
        }
    }

    @Test void recognitionRequiresGoodCompletedFiniteScore() {
        assertTrue(BrewRecognition.recognized(score(.6, true, BrewQuality.GOOD)));
        assertTrue(BrewRecognition.recognized(score(1, true, BrewQuality.EXCELLENT)));
        assertFalse(BrewRecognition.recognized(score(.599, true, BrewQuality.BAD)));
        assertFalse(BrewRecognition.recognized(score(1, false, BrewQuality.EXCELLENT)));
        assertFalse(BrewRecognition.recognized(score(1, true, BrewQuality.BAD)));
        assertFalse(BrewRecognition.recognized(score(1, true, null)));
        for (double invalid : new double[]{Double.NaN, Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY}) {
            assertFalse(BrewRecognition.recognized(score(invalid, true, BrewQuality.EXCELLENT)));
        }
        assertEquals("Unfinished Brew", BrewRecognition.anonymousName(score(1, false, BrewQuality.EXCELLENT)));
        assertEquals("Poor Brew", BrewRecognition.anonymousName(score(.599, true, BrewQuality.BAD)));
        assertEquals("Ruined Brew", BrewRecognition.anonymousName(score(0, true, null)));
    }

    @Test void qualityOverrideDoesNotRevealPoorOrUnfinishedRecipe() {
        Recipe<ItemStack> recipe = recipe();
        List<BrewingStep> steps = recipe.getSteps();
        Brew brew = new BrewImpl(steps);
        for (BrewScore score : List.of(score(.599, true, BrewQuality.BAD), score(1, false, BrewQuality.EXCELLENT))) {
            RecipeMatcherResultImpl result = new RecipeMatcherResultImpl(recipe, steps, brew, score);
            assertTrue(result.recipeResult().isEmpty());
            ItemStack item = result.toItem(new Brew.State.Seal("one bottle"), BrewQuality.EXCELLENT);
            assertEquals(BrewRecognition.anonymousName(score), plain(item.getData(DataComponentTypes.CUSTOM_NAME)));
            assertTrue(item.getData(DataComponentTypes.LORE).lines().isEmpty());
            RecipeEffectsImpl.fromItem(item).ifPresent(effects -> assertNull(effects.getMessage()));
        }
    }

    @Test void ingredientAndDeprecatedApiRenderingFollowTheSameBoundary() {
        Recipe<ItemStack> recipe = recipe();
        ItemStack poor = BrewRecognition.ingredientItem(recipe, .599);
        assertEquals("Poor Brew", plain(poor.getData(DataComponentTypes.CUSTOM_NAME)));
        assertNull(RecipeEffectsImpl.fromItem(poor).orElseThrow().getMessage());
        ItemStack good = BrewRecognition.ingredientItem(recipe, .6);
        assertEquals("Public recipe name", plain(good.getData(DataComponentTypes.CUSTOM_NAME)));
        ItemStack deprecated = recipe.getRecipeResult(BrewQuality.EXCELLENT).newBrewItem(
                score(.599, true, BrewQuality.BAD), new BrewImpl(recipe.getSteps()), new Brew.State.Other());
        assertEquals("Poor Brew", plain(deprecated.getData(DataComponentTypes.CUSTOM_NAME)));
        assertNull(RecipeEffectsImpl.fromItem(deprecated).orElseThrow().getMessage());
    }

    @Test void oldPoorSealLosesRecognitionButKeepsQuantityEffectsAndMetadata() {
        Recipe<ItemStack> recipe = recipe();
        ItemStack original = oldSeal(recipe, .599);
        ItemStack refreshed = BrewRecognition.refreshLegacySealed(original, plugin.getRecipeRegistry()).orElseThrow();
        assertEquals("Poor Brew", plain(refreshed.getData(DataComponentTypes.CUSTOM_NAME)));
        assertTrue(refreshed.getData(DataComponentTypes.LORE).lines().isEmpty());
        assertEquals(4, refreshed.getAmount());
        RecipeEffectsImpl effects = RecipeEffectsImpl.fromItem(refreshed).orElseThrow();
        assertEquals(new Interval(100, 100), effects.getEffects().getFirst().durationRange());
        assertNull(effects.getMessage());
        assertEquals("preserved", refreshed.getPersistentDataContainer().get(new NamespacedKey("test", "brewer"), PersistentDataType.STRING));
        assertEquals("Old revealing name", plain(original.getData(DataComponentTypes.CUSTOM_NAME)), "migration must return a copy");
        assertTrue(BrewRecognition.refreshLegacySealed(refreshed, plugin.getRecipeRegistry()).isEmpty());
    }

    @Test void oldGoodSealReceivesCurrentPublicLoreWithoutAwardingNewEffects() {
        Recipe<ItemStack> recipe = recipe();
        ItemStack refreshed = BrewRecognition.refreshLegacySealed(oldSeal(recipe, .6), plugin.getRecipeRegistry()).orElseThrow();
        assertEquals("Public recipe name", plain(refreshed.getData(DataComponentTypes.CUSTOM_NAME)));
        assertEquals(List.of("Public clue"), refreshed.getData(DataComponentTypes.LORE).lines().stream().map(BrewRecognitionTest::plain).toList());
        assertEquals(4, refreshed.getAmount());
        assertEquals(new Interval(100, 100), RecipeEffectsImpl.fromItem(refreshed).orElseThrow().getEffects().getFirst().durationRange());
        assertTrue(BrewRecognition.refreshLegacySealed(refreshed, plugin.getRecipeRegistry()).isEmpty());
    }

    private Recipe<ItemStack> recipe() {
        var effects = new RecipeEffectsImpl.Builder().effects(List.of(
                new RecipeEffectImpl(PotionEffectType.SPEED, new Interval(100, 100), new Interval(1, 1))))
                .message("Recipe-specific consumption message").build();
        var result = new BukkitRecipeResult.Builder().name("Public recipe name").lore(List.of("Public clue"))
                .appendBrewInfoLore(false).recipeEffects(effects).build();
        Recipe<ItemStack> recipe = new RecipeImpl.Builder<ItemStack>("recognition_fixture")
                .steps(List.of(new DistillStepImpl(1))).recipeResults(QualityData.equalValued(result)).build();
        plugin.getRecipeRegistry().registerRecipe(recipe);
        return recipe;
    }

    private ItemStack oldSeal(Recipe<ItemStack> recipe, double score) {
        ItemStack item = new ComponentItemStackMock(Material.POTION);
        item.setAmount(4);
        item.setData(DataComponentTypes.CUSTOM_NAME, Component.text("Old revealing name"));
        item.setData(DataComponentTypes.LORE, ItemLore.lore(List.of(Component.text("Old revealing process"))));
        ((RecipeEffectsImpl) recipe.getRecipeResult(BrewQuality.GOOD).recipeEffects()).applyTo(item);
        item.editPersistentDataContainer(pdc -> {
            BrewAdapterAccess.applyBrewTags(pdc, recipe, score, "Old revealing name");
            BrewAdapterAccess.applyBrewMeta(pdc, new BrewImpl(recipe.getSteps()));
            pdc.set(new NamespacedKey("test", "brewer"), PersistentDataType.STRING, "preserved");
        });
        assertTrue(BrewAdapterAccess.fromItem(item).isEmpty());
        return item;
    }

    private static String plain(Component component) { return PlainTextComponentSerializer.plainText().serialize(component); }

    static BrewScore score(double value, boolean completed, BrewQuality quality) {
        return new BrewScore() {
            @Override public BrewQuality brewQuality() { return quality; }
            @Override public Map<ScoreType, PartialBrewScore> getPartialScores(int i) { return Map.of(); }
            @Override public double score() { return value; }
            @Override public double rawScore() { return value; }
            @Override public boolean completed() { return completed; }
            @Override public Component displayName() { return Component.empty(); }
            @Override public double brewDifficulty() { return 1; }
            @Override public int compareTo(BrewScore other) { return Double.compare(value, other.score()); }
        };
    }
}
