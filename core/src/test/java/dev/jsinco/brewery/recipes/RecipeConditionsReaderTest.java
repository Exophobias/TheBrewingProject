package dev.jsinco.brewery.recipes;

import dev.jsinco.brewery.api.ingredient.BaseIngredient;
import dev.jsinco.brewery.api.ingredient.Ingredient;
import dev.jsinco.brewery.api.ingredient.IngredientManager;
import dev.jsinco.brewery.api.recipe.RecipeCondition;
import dev.jsinco.brewery.api.recipe.ScoreCondition;
import dev.jsinco.brewery.api.util.BreweryKey;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.simpleyaml.configuration.file.YamlConfiguration;

import java.awt.Color;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;

class RecipeConditionsReaderTest {
    @Test
    void resolvesBareAndAmountQualifiedIngredientsOnceAndWaitsForReadiness() {
        var requests = new ArrayList<String>();
        var pending = new CompletableFuture<Optional<Ingredient>>();
        IngredientManager<?> manager = manager(requests, pending);
        var config = new YamlConfiguration();
        config.set("final-step.type", "COOK");
        config.set("final-step.ingredients", List.of("wheat", "sugar/excessive", "apple/lacking"));

        var loading = RecipeConditionsReader.fromConfigSection(config, manager);
        assertFalse(loading.isDone());
        assertEquals(List.of("wheat", "sugar", "apple"), requests);
        pending.complete(Optional.of(new TestIngredient(new BreweryKey("minecraft", "wheat"))));
        var condition = (RecipeCondition.LastStep) loading.join().getFirst();
        var amounts = ((ScoreCondition.IngredientsCondition) condition.conditions().getFirst()).conditions();
        assertEquals(ScoreCondition.AmountCondition.ANY, amounts.get(new TestIngredient(new BreweryKey("minecraft", "wheat"))));
        assertEquals(ScoreCondition.AmountCondition.EXCESSIVE, amounts.get(new TestIngredient(new BreweryKey("minecraft", "sugar"))));
        assertEquals(ScoreCondition.AmountCondition.LACKING, amounts.get(new TestIngredient(new BreweryKey("minecraft", "apple"))));
    }

    @Test
    void invalidAmountDoesNotBecomeAnUnconditionalMatch() {
        var config = new YamlConfiguration();
        config.set("final-step.type", "COOK");
        config.set("final-step.ingredients", List.of("wheat/typo"));
        var requests = new ArrayList<String>();
        assertThrows(IllegalArgumentException.class, () -> RecipeConditionsReader.fromConfigSection(
                config, manager(requests, new CompletableFuture<>())));
        assertTrue(requests.isEmpty());
    }

    private IngredientManager<?> manager(List<String> requests, CompletableFuture<Optional<Ingredient>> pending) {
        return (IngredientManager<?>) Proxy.newProxyInstance(IngredientManager.class.getClassLoader(),
                new Class<?>[]{IngredientManager.class}, (proxy, method, args) -> {
                    if (method.getName().equals("getIngredient") && args[0] instanceof String name) {
                        requests.add(name);
                        return name.equals("wheat") ? pending : CompletableFuture.completedFuture(
                                Optional.of(new TestIngredient(new BreweryKey("minecraft", name))));
                    }
                    throw new AssertionError("Unexpected ingredient call: " + method.getName());
                });
    }

    private record TestIngredient(BreweryKey key) implements BaseIngredient {
        public Component displayName() { return Component.text(key.key()); }
        public Optional<Color> color() { return Optional.empty(); }
    }
}
