package dev.jsinco.brewery.recipes;

import com.google.common.base.Preconditions;
import dev.jsinco.brewery.api.brew.BrewScore;
import dev.jsinco.brewery.api.brew.BrewingStep;
import dev.jsinco.brewery.api.ingredient.BaseIngredient;
import dev.jsinco.brewery.api.ingredient.Ingredient;
import dev.jsinco.brewery.api.recipe.DefaultRecipe;
import dev.jsinco.brewery.api.recipe.Recipe;
import dev.jsinco.brewery.api.recipe.RecipeRegistry;
import dev.jsinco.brewery.api.util.Logger;
import dev.jsinco.brewery.api.util.Pair;
import dev.jsinco.brewery.util.BrewUtil;
import org.jspecify.annotations.NonNull;

import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class RecipeRegistryImpl<I> implements RecipeRegistry<I> {


    private final Map<String, Recipe<I>> recipes = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<String, DefaultRecipe<I>> defaultRecipes = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<BaseIngredient, Set<Recipe<I>>> baseIngredientToRecipes = Collections.synchronizedMap(new HashMap<>());
    private long loadGeneration;

    /** Starts an isolated load while the previous complete recipe set remains readable. */
    public synchronized long beginLoad() {
        return ++loadGeneration;
    }

    public synchronized void invalidatePendingLoads() {
        ++loadGeneration;
    }

    /** Publishes recipes and defaults together, only if this load is still current. */
    public synchronized boolean publishLoad(long generation, Collection<? extends Recipe<I>> loaded,
                                            Map<String, DefaultRecipe<I>> defaults) {
        if (generation != loadGeneration) {
            return false;
        }
        RecipeRegistryImpl<I> candidate = new RecipeRegistryImpl<>();
        loaded.forEach(candidate::registerRecipe);
        defaults.forEach(candidate::registerDefaultRecipe);
        recipes.clear();
        recipes.putAll(candidate.recipes);
        defaultRecipes.clear();
        defaultRecipes.putAll(candidate.defaultRecipes);
        baseIngredientToRecipes.clear();
        baseIngredientToRecipes.putAll(candidate.baseIngredientToRecipes);
        return true;
    }


    public synchronized void registerRecipes(@NonNull Map<String, Recipe<I>> recipes) {
        this.clear();
        recipes.forEach((ignored, recipe) -> registerRecipe(recipe));
    }

    @Override
    public synchronized Optional<Recipe<I>> getRecipe(@NonNull String recipeName) {
        Preconditions.checkNotNull(recipeName);

        // Try case-sensitive first
        Recipe<I> recipe = recipes.get(recipeName);
        if (recipe != null) {
            return Optional.of(recipe);
        }

        // Then try case-insensitive
        for (Map.Entry<String, Recipe<I>> entry : recipes.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(recipeName)) {
                return Optional.of(entry.getValue());
            }
        }

        return Optional.empty();
    }

    @Override
    public synchronized Collection<Recipe<I>> getRecipes() {
        return List.copyOf(recipes.values());
    }

    @Override
    public synchronized Collection<Recipe<I>> possibleRecipes(List<BrewingStep> steps) {
        Set<Recipe<I>> recipes = null;
        for (BaseIngredient baseIngredient : BrewUtil.getRecipeIngredients(steps)) {
            if (recipes == null) {
                recipes = new HashSet<>(baseIngredientToRecipes.getOrDefault(baseIngredient, Set.of()));
            }
            if (recipes.isEmpty()) {
                return recipes;
            }
            recipes.removeIf(recipe -> !BrewUtil.getRecipeIngredients(recipe)
                    .contains(baseIngredient)
            );
        }
        return recipes == null ? Set.of() : recipes;
    }

    @Override
    public Optional<Recipe<I>> closestRecipe(List<BrewingStep> steps) {
        List<Pair<Recipe<I>, BrewScore>> possibleRecipes = possibleRecipes(steps)
                .stream()
                .map(recipe -> new Pair<>(recipe, recipe.score(steps)))
                .toList();
        return possibleRecipes.stream()
                .filter(pair -> pair.second().completed())
                .max(Comparator.comparingDouble(pair -> pair.second().rawScore()))
                .or(() -> possibleRecipes.stream()
                        .max(Comparator.comparingDouble(pair -> pair.second().rawScore()))
                ).map(Pair::first);
    }

    @Override
    public synchronized void registerRecipe(Recipe<I> recipe) {
        Recipe<I> previous = recipes.get(recipe.getRecipeName());
        if (previous != null) {
            unRegisterRecipe(previous);
        }
        recipes.put(recipe.getRecipeName(), recipe);
        for (BaseIngredient recipeIngredient : BrewUtil.getRecipeIngredients(recipe)) {
            baseIngredientToRecipes.computeIfAbsent(recipeIngredient, ignored -> Collections.newSetFromMap(new ConcurrentHashMap<>()))
                    .add(recipe);
        }
    }

    @Override
    public synchronized void unRegisterRecipe(Recipe<I> recipe) {
        if (recipes.get(recipe.getRecipeName()) != recipe) {
            return;
        }
        recipes.remove(recipe.getRecipeName());
        for (BaseIngredient baseIngredient : BrewUtil.getRecipeIngredients(recipe)) {
            Set<Recipe<I>> ingredientRecipes = baseIngredientToRecipes.get(baseIngredient);
            if (ingredientRecipes == null) {
                continue;
            }
            ingredientRecipes.removeIf(recipe0 -> recipe0.getRecipeName().equals(recipe.getRecipeName()));
            if (ingredientRecipes.isEmpty()) {
                baseIngredientToRecipes.remove(baseIngredient);
            }
        }
    }

    @Override
    public synchronized Optional<DefaultRecipe<I>> getDefaultRecipe(@NonNull String recipeName) {
        Preconditions.checkNotNull(recipeName);
        return Optional.ofNullable(defaultRecipes.get(recipeName));
    }

    @Override
    public synchronized Collection<DefaultRecipe<I>> getDefaultRecipes() {
        return List.copyOf(defaultRecipes.values());
    }

    @Override
    public synchronized void registerDefaultRecipe(String name, DefaultRecipe<I> recipe) {
        if (recipe == null) {
            Logger.logWarn("Default recipe was null, ignoring: " + name);
            return;
        }
        defaultRecipes.put(name, recipe);
    }

    @Override
    public synchronized void unRegisterDefaultRecipe(String name) {
        defaultRecipes.remove(name);
    }

    @Override
    public synchronized boolean isRegisteredIngredient(Ingredient ingredient) {
        return ingredient.findMatch(baseIngredientToRecipes.keySet())
                .isPresent();
    }

    @Override
    public synchronized Set<BaseIngredient> registeredIngredients() {
        return Set.copyOf(baseIngredientToRecipes.keySet());
    }

    public synchronized void clear() {
        invalidatePendingLoads();
        recipes.clear();
        defaultRecipes.clear();
        baseIngredientToRecipes.clear();
    }
}
