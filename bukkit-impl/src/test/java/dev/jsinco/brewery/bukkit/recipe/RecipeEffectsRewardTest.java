package dev.jsinco.brewery.bukkit.recipe;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.effect.modifier.DrunkenModifier;
import dev.jsinco.brewery.api.moment.Interval;
import dev.jsinco.brewery.api.util.BreweryKey;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.DistillStepImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.integration.IntegrationTypes;
import dev.jsinco.brewery.bukkit.api.integration.ItemIntegration;
import dev.jsinco.brewery.bukkit.testutil.ItemStackMockPDC;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.potion.PotionEffectTypeCategory;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class RecipeEffectsRewardTest {
    private TheBrewingProject plugin;
    @BeforeEach void setUp() {
        MockBukkit.mock(new TBPServerMock());
        plugin = MockBukkit.load(TheBrewingProject.class);
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void rewardExtendsOnlyFiniteBeneficialDurationsAndPreservesOtherProperties() {
        var amplifier = new Interval(1, 3);
        List<RecipeEffectImpl> effects = List.of(
                new RecipeEffectImpl(PotionEffectType.SPEED, new Interval(100, 200), amplifier),
                new RecipeEffectImpl(PotionEffectType.POISON, new Interval(100, 200), amplifier),
                new RecipeEffectImpl(PotionEffectType.INSTANT_HEALTH, new Interval(1, 1), amplifier),
                new RecipeEffectImpl(PotionEffectType.GLOWING, new Interval(100, 200), amplifier),
                new RecipeEffectImpl(PotionEffectType.NIGHT_VISION, new Interval(-1, -1), amplifier));
        assertEquals(PotionEffectTypeCategory.NEUTRAL, PotionEffectType.GLOWING.getCategory());
        var modifier = new DrunkenModifier("fixture", null, null, 0, 100, Component.text("Fixture"));
        var original = new RecipeEffectsImpl.Builder().effects(effects).addModifiers(Map.of(modifier, 12D))
                .events(List.of(BreweryKey.parse("test:event"))).title("title").message("message").actionBar("action").build();
        var rewarded = original.withBeneficialDurationMultiplier(1.25);
        assertEquals(new Interval(125, 250), rewarded.getEffects().getFirst().durationRange());
        assertEquals(amplifier, rewarded.getEffects().getFirst().amplifierRange());
        for (int i = 1; i < effects.size(); i++) assertEquals(effects.get(i), rewarded.getEffects().get(i));
        assertEquals(original.getModifiers(), rewarded.getModifiers());
        assertEquals(original.events(), rewarded.events());
        assertEquals("title", rewarded.getTitle());
        assertEquals("message", rewarded.getMessage());
        assertEquals("action", rewarded.getActionBar());
        assertEquals(new Interval(100, 200), original.getEffects().getFirst().durationRange());
        for (double invalid : new double[]{.9, Double.NaN, Double.POSITIVE_INFINITY}) {
            assertSame(original, original.withBeneficialDurationMultiplier(invalid));
        }
    }

    @Test void removingMessagesPreservesActualEffectsEventsAndModifiers() {
        var modifier = new DrunkenModifier("fixture", null, null, 0, 100, Component.text("Fixture"));
        var original = new RecipeEffectsImpl.Builder().effects(List.of(speed()))
                .addModifiers(Map.of(modifier, 12D)).events(List.of(BreweryKey.parse("test:event")))
                .title("recipe title").message("recipe message").actionBar("recipe action").build();
        var anonymous = original.withoutMessages();
        assertNull(anonymous.getTitle());
        assertNull(anonymous.getMessage());
        assertNull(anonymous.getActionBar());
        assertEquals(original.getEffects(), anonymous.getEffects());
        assertEquals(original.events(), anonymous.events());
        assertEquals(original.getModifiers(), anonymous.getModifiers());
        assertEquals("recipe message", original.getMessage());
    }

    @Test void freshRenderingAppliesTheLargestEnabledRewardOnce() {
        plugin.getIntegrationManager().register(IntegrationTypes.ITEM, new Reward("first", 1.25, true));
        plugin.getIntegrationManager().register(IntegrationTypes.ITEM, new Reward("second", 1.1, true));
        plugin.getIntegrationManager().register(IntegrationTypes.ITEM, new Reward("disabled", 2, false));
        plugin.getIntegrationManager().register(IntegrationTypes.ITEM, new Reward("invalid", Double.NaN, true));
        var original = new RecipeEffectsImpl.Builder().effects(List.of(speed())).build();
        Brew brew = new BrewImpl(List.of(new DistillStepImpl(1)));
        ItemStack item = new ItemStackMockPDC(Material.POTION);
        for (int render = 0; render < 3; render++) {
            original.applyTo(item);
            RecipeEffectsImpl.applyBrewDurationReward(item, brew);
            assertEquals(new Interval(125, 250), RecipeEffectsImpl.fromItem(item).orElseThrow().getEffects().getFirst().durationRange());
        }
        assertEquals(new Interval(100, 200), original.getEffects().getFirst().durationRange());
    }

    private static RecipeEffectImpl speed() {
        return new RecipeEffectImpl(PotionEffectType.SPEED, new Interval(100, 200), new Interval(1, 1));
    }

    private record Reward(String getId, double multiplier, boolean isEnabled) implements ItemIntegration {
        @Override public double beneficialEffectDurationMultiplier(Brew brew) { return multiplier; }
        @Override public Optional<ItemStack> createItem(String id) { return Optional.empty(); }
        @Override public boolean isIngredient(String id) { return false; }
        @Override public Component displayName(String id) { return null; }
        @Override public String getItemId(ItemStack item) { return null; }
        @Override public CompletableFuture<Void> initialized() { return CompletableFuture.completedFuture(null); }
    }
}
