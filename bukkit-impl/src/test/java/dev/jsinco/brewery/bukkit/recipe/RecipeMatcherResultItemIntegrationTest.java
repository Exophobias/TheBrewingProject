package dev.jsinco.brewery.bukkit.recipe;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.brew.BrewingStep;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.DistillStepImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.integration.IntegrationTypes;
import dev.jsinco.brewery.bukkit.api.integration.ItemIntegration;
import dev.jsinco.brewery.bukkit.brew.BrewAdapterAccess;
import dev.jsinco.brewery.bukkit.testutil.ItemStackMockPDC;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import dev.jsinco.brewery.recipes.BrewScoreImpl;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.packs.ResourcePack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RecipeMatcherResultItemIntegrationTest {

    private static final NamespacedKey DECORATED = new NamespacedKey("test", "decorated");

    private RecordingIntegration integration;
    private RecipeMatcherResultImpl result;

    @BeforeEach
    void setUp() {
        MockBukkit.mock(new RenderServerMock());
        TheBrewingProject plugin = MockBukkit.load(TheBrewingProject.class);
        integration = new RecordingIntegration();
        plugin.getIntegrationManager().register(IntegrationTypes.ITEM, integration);

        List<BrewingStep> steps = List.of(new DistillStepImpl(1));
        Brew brew = new BrewImpl(steps);
        result = new RecipeMatcherResultImpl(
                null, steps, brew, BrewScoreImpl.failed(steps));
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void decoratesEveryRenderStateAfterBrewData() {
        List<Brew.State> states = List.of(
                new Brew.State.Brewing(),
                new Brew.State.Other(),
                new Brew.State.Seal("one bottle")
        );

        for (Brew.State state : states) {
            ItemStack rendered = new ItemStackMockPDC(Material.POTION);
            result.applyPersistentData(rendered, state);
            assertTrue(rendered.getPersistentDataContainer()
                    .has(DECORATED, PersistentDataType.BYTE));
            if (state instanceof Brew.State.Seal) {
                assertFalse(BrewAdapterAccess.fromItem(rendered).isPresent(),
                        "sealed output must keep the decoration without exposing brew steps");
            }
        }

        assertEquals(states.size(), integration.calls);
        assertTrue(integration.alwaysSawBrewPersistentData,
                "decorators must run after TBP writes ordinary or sealed brew data");
    }

    private static final class RenderServerMock extends TBPServerMock {

        @Override
        public @Nullable ResourcePack getServerResourcePack() {
            return null;
        }
    }

    private static final class RecordingIntegration implements ItemIntegration {

        private int calls;
        private boolean alwaysSawBrewPersistentData = true;

        @Override
        public String getId() {
            return "render-test";
        }

        @Override
        public boolean isEnabled() {
            return true;
        }

        @Override
        public void decorateBrewItem(ItemStack itemStack, Brew brew) {
            calls++;
            boolean hasBrewData = itemStack.getPersistentDataContainer().getKeys().stream()
                    .anyMatch(key -> "meta".equals(key.getKey()));
            alwaysSawBrewPersistentData &= hasBrewData;
            itemStack.editPersistentDataContainer(pdc ->
                    pdc.set(DECORATED, PersistentDataType.BYTE, (byte) 1));
        }

        @Override
        public Optional<ItemStack> createItem(String id) {
            return Optional.empty();
        }

        @Override
        public boolean isIngredient(String id) {
            return false;
        }

        @Override
        public @Nullable Component displayName(String id) {
            return null;
        }

        @Override
        public @Nullable String getItemId(ItemStack itemStack) {
            return null;
        }

        @Override
        public CompletableFuture<Void> initialized() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
