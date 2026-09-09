package dev.jsinco.brewery.bukkit.database;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.brew.BrewingStep;
import dev.jsinco.brewery.api.breweries.BarrelType;
import dev.jsinco.brewery.api.breweries.CauldronType;
import dev.jsinco.brewery.api.ingredient.*;
import dev.jsinco.brewery.api.meta.MetaDataType;
import dev.jsinco.brewery.api.moment.PassedMoment;
import dev.jsinco.brewery.api.util.BreweryKey;
import dev.jsinco.brewery.brew.*;
import dev.jsinco.brewery.bukkit.ingredient.BreweryIngredient;
import dev.jsinco.brewery.bukkit.ingredient.ResolvedIngredientManagerImpl;
import net.kyori.adventure.key.Key;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class BrewPersistenceSnapshotTest {
    @Test void allFourStepsAndNestedMutableValuesAreCapturedBeforeIngredientReadiness() {
        var ingredientMeta = new LinkedHashMap<IngredientMeta<?>, Object>();
        ingredientMeta.put(IngredientMeta.SCORE, 0.5D);
        Ingredient ingredient = new IngredientWithMeta(new BreweryIngredient(BreweryKey.parse("test:wheat")),
                ingredientMeta);
        var inputs = new LinkedHashMap<Ingredient, Integer>();
        inputs.put(ingredient, 4);
        var brewers = new LinkedHashSet<>(List.of(UUID.randomUUID()));
        var barrelKey = new BreweryKey[]{BreweryKey.parse("test:oak")};
        BarrelType type = new BarrelType() {
            public double proximityScore(BarrelType other) { return 1; }
            public BreweryKey key() { return barrelKey[0]; }
        };
        var steps = new ArrayList<BrewingStep>(List.of(
                new CookStepImpl(new PassedMoment(40), inputs, CauldronType.WATER, brewers, 2),
                new MixStepImpl(new PassedMoment(30), inputs, CauldronType.BREW, brewers, 3),
                new AgeStepImpl(new PassedMoment(200), type, brewers, 4),
                new DistillStepImpl(2, brewers, 5)));
        byte[] nested = {1, 2, 3};
        Brew brew = new BrewImpl(steps).withMeta(Key.key("test", "array"), MetaDataType.BYTE_ARRAY, nested);
        var manager = new ResolvedIngredientManagerImpl();
        String expected = BrewImpl.SERIALIZER.serialize(brew, manager).toString();
        var readiness = new CompletableFuture<ResolvedIngredientManager<ItemStack>>();
        var captured = BrewPersistenceSnapshot.capture(brew, readiness);
        assertFalse(captured.isDone());
        inputs.clear();
        ingredientMeta.put(IngredientMeta.SCORE, 0.9D);
        brewers.clear();
        steps.clear();
        nested[0] = 99;
        barrelKey[0] = BreweryKey.parse("test:changed");
        readiness.complete(manager);
        assertEquals(expected, captured.join());
        assertTrue(expected.contains("score=0.5"));
        assertTrue(expected.contains("test:oak"));
        assertNotEquals(expected, BrewImpl.SERIALIZER.serialize(brew, manager).toString());
    }

    @Test void failedIngredientInitializationStillRefusesTheCapturedWrite() {
        var readiness = new CompletableFuture<ResolvedIngredientManager<ItemStack>>();
        var captured = BrewPersistenceSnapshot.capture(new BrewImpl(List.of(new DistillStepImpl(1))), readiness);
        var failure = new IllegalStateException("ingredient startup failed");
        readiness.completeExceptionally(failure);
        assertSame(failure, assertThrows(CompletionException.class, captured::join).getCause());
    }

    @Test void cancellingOneCaptureDoesNotCancelSharedInitializationOrAnotherWrite() {
        var readiness = new CompletableFuture<ResolvedIngredientManager<ItemStack>>();
        Brew brew = new BrewImpl(List.of(new DistillStepImpl(1)));
        var abandoned = BrewPersistenceSnapshot.capture(brew, readiness);
        var retained = BrewPersistenceSnapshot.capture(brew, readiness);
        assertTrue(abandoned.cancel(true));
        assertFalse(readiness.isDone());
        readiness.complete(new ResolvedIngredientManagerImpl());
        assertDoesNotThrow(retained::join);
    }
}
