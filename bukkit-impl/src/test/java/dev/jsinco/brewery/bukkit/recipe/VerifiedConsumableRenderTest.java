package dev.jsinco.brewery.bukkit.recipe;

import dev.jsinco.brewery.api.brew.*;
import dev.jsinco.brewery.api.recipe.*;
import dev.jsinco.brewery.brew.*;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.brew.*;
import dev.jsinco.brewery.bukkit.testutil.*;
import dev.jsinco.brewery.recipes.RecipeImpl;
import java.util.*;
import org.bukkit.*;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import static org.junit.jupiter.api.Assertions.*;

class VerifiedConsumableRenderTest {
    private TheBrewingProject plugin;
    private AutoCloseable potionFactory, componentBridge;
    private Recipe<ItemStack> recipe;
    @BeforeEach void start() throws Exception {
        MockBukkit.mock(new TBPServerMock()); plugin = MockBukkit.load(TheBrewingProject.class);
        potionFactory = ComponentItemStackMock.installPotionFactory(); componentBridge = ItemComponentBridgeFixture.install();
        var result = new BukkitRecipeResult.Builder().name("Smith's Oil").appendBrewInfoLore(false).build();
        recipe = new RecipeImpl.Builder<ItemStack>("smiths_oil").steps(List.of(new DistillStepImpl(1)))
                .recipeResults(QualityData.equalValued(result)).build();
        plugin.getRecipeRegistry().registerRecipe(recipe);
    }
    @AfterEach void stop() throws Exception {
        try { if (componentBridge != null) componentBridge.close(); if (potionFactory != null) potionFactory.close(); }
        finally { MockBukkit.unmock(); }
    }
    private RecipeMatcherResultImpl match(double score, boolean completed) {
        var brew = new BrewImpl(recipe.getSteps());
        return new RecipeMatcherResultImpl(recipe, brew.getSteps(), brew,
                BrewRecognitionTest.score(score, completed, BrewQuality.quality(score).orElse(null)));
    }
    @Test void recognizedCompletedFreshAndSealedOutputsCarryAuthenticatedGradeAndRevision() throws Exception {
        for (double grade : new double[]{.6, .8, 1}) for (Brew.State state : List.of(new Brew.State.Other(), new Brew.State.Seal("one bottle"))) {
            var item = match(grade, true).toItem(state);
            var descriptor = plugin.inspectVerifiedConsumable(item).orElseThrow();
            assertEquals("brewery:smiths_oil", descriptor.canonicalRecipeId());
            assertEquals(VerifiedConsumableService.recipeRevision(recipe), descriptor.recipeRevision());
            assertEquals(grade, descriptor.score()); assertEquals(BrewQuality.quality(grade).orElseThrow(), descriptor.quality());
            assertTrue(descriptor.completed()); assertEquals(1, descriptor.proofVersion());
            assertEquals(state instanceof Brew.State.Seal, descriptor.sealed());
            assertEquals(!(state instanceof Brew.State.Seal), BrewAdapterAccess.fromItem(item).isPresent());
            byte[] secret;
            try (var connection = plugin.getDatabase().getConnection()) { secret = dev.jsinco.brewery.database.sql.ExternalCauldronStorage.signingKey(connection); }
            assertEquals(Optional.of(descriptor), new VerifiedConsumableService(secret).inspect(item), "the provider lifetime does not determine proof validity");
        }
    }
    @Test void poorUnfinishedOverriddenAndUnprovenSealsDoNotRevealAConsumable() {
        for (var result : List.of(match(.599, true), match(1, false))) {
            assertTrue(plugin.inspectVerifiedConsumable(result.toItem(new Brew.State.Seal("one bottle"), BrewQuality.EXCELLENT)).isEmpty());
            assertTrue(plugin.inspectVerifiedConsumable(result.toItem(new Brew.State.Other())).isEmpty());
        }
        assertTrue(plugin.inspectVerifiedConsumable(match(.7, true).toItem(new Brew.State.Seal("one bottle"), BrewQuality.EXCELLENT)).isEmpty());
        var unproven = BrewRecognition.ingredientItem(recipe, 1);
        BrewAdapterAccess.applyBrewMeta(unproven.getItemMeta().getPersistentDataContainer(), new BrewImpl(recipe.getSteps()));
        assertTrue(plugin.inspectVerifiedConsumable(unproven).isEmpty());
    }
    @Test void historyMutationAndQualityTagEditsInvalidateProofAndAValidRerenderReissuesIt() {
        var item = match(1, true).toItem(new Brew.State.Other());
        assertTrue(plugin.inspectVerifiedConsumable(item).isPresent());
        var changed = new BrewImpl(List.of(new DistillStepImpl(2)));
        item.editPersistentDataContainer(pdc -> BrewAdapterAccess.applyBrewData(pdc, changed));
        assertTrue(plugin.inspectVerifiedConsumable(item).isEmpty());
        var refreshed = new RecipeMatcherResultImpl(recipe, changed.getSteps(), changed, BrewRecognitionTest.score(.7, true, BrewQuality.GOOD)).toItem(new Brew.State.Other());
        assertEquals(BrewQuality.GOOD, plugin.inspectVerifiedConsumable(refreshed).orElseThrow().quality());
        refreshed.editPersistentDataContainer(pdc -> pdc.set(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE, 1D));
        assertTrue(plugin.inspectVerifiedConsumable(refreshed).isEmpty());
    }
    @Test void sealCannotInheritFreshHistoryOrCarryAnOldProofIntoAnUnfinishedRender() {
        var item = match(1,true).toItem(new Brew.State.Other());
        plugin.getVerifiedConsumableService().refresh(item, new BrewImpl(recipe.getSteps()), recipe,
                BrewRecognitionTest.score(1,false,BrewQuality.EXCELLENT), false);
        assertTrue(plugin.inspectVerifiedConsumable(item).isEmpty());
        var sealed = match(1,true).toItem(new Brew.State.Seal("one bottle"));
        sealed.editPersistentDataContainer(pdc -> BrewAdapterAccess.applyBrewData(pdc, new BrewImpl(recipe.getSteps())));
        assertTrue(plugin.inspectVerifiedConsumable(sealed).isEmpty());
    }
    @Test void tamperedSignatureAndDifferentProviderIdentityAreRejected() {
        var item = match(1,true).toItem(new Brew.State.Seal("one bottle"));
        assertTrue(new VerifiedConsumableService(new byte[32]).inspect(item).isEmpty());
        var key = TheBrewingProject.key("verified_consumable");
        byte[] proof = item.getPersistentDataContainer().get(key, PersistentDataType.BYTE_ARRAY);
        proof[proof.length - 1] ^= 1;
        item.editPersistentDataContainer(pdc -> pdc.set(key, PersistentDataType.BYTE_ARRAY, proof));
        assertTrue(plugin.inspectVerifiedConsumable(item).isEmpty());
    }
    @Test void rendersWithoutFullCompletionEvidenceInvalidateInheritedProof() {
        var item = match(1,true).toItem(new Brew.State.Seal("one bottle"));
        assertTrue(plugin.inspectVerifiedConsumable(item).isPresent());
        VerifiedConsumableService.invalidate(item);
        assertTrue(plugin.inspectVerifiedConsumable(item).isEmpty());
        assertTrue(plugin.inspectVerifiedConsumable(match(1,true).toItem(new Brew.State.Seal("one bottle"))).isPresent());
    }
}
