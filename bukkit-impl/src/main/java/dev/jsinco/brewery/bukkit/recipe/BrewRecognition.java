package dev.jsinco.brewery.bukkit.recipe;

import dev.jsinco.brewery.api.brew.BrewQuality;
import dev.jsinco.brewery.api.brew.BrewScore;
import dev.jsinco.brewery.api.recipe.Recipe;
import dev.jsinco.brewery.api.recipe.RecipeRegistry;
import dev.jsinco.brewery.api.recipe.RecipeResult;
import dev.jsinco.brewery.bukkit.brew.BrewAdapterAccess;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.PotionContents;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;

import java.util.List;
import java.util.Optional;

/** Recipe recognition is feedback for a completed, promising batch, never a failed guess. */
public final class BrewRecognition {
    private static final NamespacedKey PRESENTATION_VERSION = new NamespacedKey("brewery", "presentation_version");
    private BrewRecognition() { }

    public static boolean recognized(BrewScore score) {
        return score.completed() && Double.isFinite(score.score()) && score.score() >= 0.6
                && (score.brewQuality() == BrewQuality.GOOD || score.brewQuality() == BrewQuality.EXCELLENT);
    }

    public static String anonymousName(BrewScore score) {
        if (!score.completed()) return "Unfinished Brew";
        return score.brewQuality() == null ? "Ruined Brew" : "Poor Brew";
    }

    public static void anonymize(ItemStack item, BrewScore score) {
        anonymize(item, anonymousName(score));
    }

    private static void anonymize(ItemStack item, String name) {
        item.setData(DataComponentTypes.CUSTOM_NAME,
                Component.text(name).decoration(TextDecoration.ITALIC, false));
        item.setData(DataComponentTypes.LORE, ItemLore.lore(List.of()));
        item.setData(DataComponentTypes.POTION_CONTENTS,
                PotionContents.potionContents().customColor(Color.fromRGB(0x796B52)).build());
        item.unsetData(DataComponentTypes.CUSTOM_MODEL_DATA);
        item.unsetData(DataComponentTypes.ITEM_MODEL);
        item.unsetData(DataComponentTypes.ENCHANTMENTS);
        item.unsetData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE);
    }

    public static void markCurrent(ItemStack item) {
        item.editPersistentDataContainer(pdc -> pdc.set(PRESENTATION_VERSION, PersistentDataType.INTEGER, 1));
    }

    /** A stored ingredient score must not bypass the failed-batch presentation rule. */
    public static ItemStack ingredientItem(Recipe<ItemStack> recipe, double score) {
        BrewQuality quality = BrewQuality.quality(score).orElse(null);
        ItemStack item;
        boolean known = Double.isFinite(score) && score >= 0.6 && quality != null;
        if (known) {
            item = recipe.getRecipeResult(quality).newLorelessItem();
        } else {
            item = new ItemStack(Material.POTION);
            if (quality != null && recipe.getRecipeResult(quality).recipeEffects() instanceof RecipeEffectsImpl effects) {
                effects.withoutMessages().applyTo(item);
            }
            anonymize(item, quality == null ? "Ruined Brew" : "Poor Brew");
        }
        String name = known ? recipe.getRecipeResult(quality).name() : quality == null ? "Ruined Brew" : "Poor Brew";
        item.editPersistentDataContainer(pdc -> BrewAdapterAccess.applyBrewTags(pdc, recipe, score, name));
        BrewAdapterAccess.hideTooltips(item);
        markCurrent(item);
        return item;
    }

    /**
     * Old seals have no brewing history to rescore. Refresh only their presentation from the
     * stored result, preserving quantity, effects and metadata. Never award a new proof bonus.
     */
    public static Optional<ItemStack> refreshLegacySealed(ItemStack original, RecipeRegistry<ItemStack> recipes) {
        if (!BrewAdapterAccess.isBrew(original)
                || original.getPersistentDataContainer().has(PRESENTATION_VERSION)) return Optional.empty();
        ItemStack item = original.clone();
        String id = item.getPersistentDataContainer().get(BrewAdapterAccess.BREWERY_TAG, PersistentDataType.STRING);
        double score = item.getPersistentDataContainer().getOrDefault(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE, 0D);
        BrewQuality quality = BrewQuality.quality(score).orElse(null);
        Optional<Recipe<ItemStack>> recipe = recipes.getRecipe(id);
        if (recipe.isPresent() && Double.isFinite(score) && score >= 0.6 && quality != null) {
            RecipeResult<ItemStack> result = recipe.get().getRecipeResult(quality);
            item.setData(DataComponentTypes.CUSTOM_NAME, result.displayName().decoration(TextDecoration.ITALIC, false));
            item.setData(DataComponentTypes.LORE, ItemLore.lore(result.staticLore()));
            item.setData(DataComponentTypes.POTION_CONTENTS, PotionContents.potionContents()
                    .customColor(Color.fromRGB(result.brewColor().getRGB() & 0xFFFFFF)).build());
            item.editPersistentDataContainer(pdc -> pdc.set(BrewAdapterAccess.BREWERY_DISPLAY_NAME,
                    PersistentDataType.STRING, result.name()));
        } else {
            String name = quality == null || recipe.isEmpty() ? "Ruined Brew" : "Poor Brew";
            anonymize(item, name);
            RecipeEffectsImpl.fromItem(item).map(RecipeEffectsImpl::withoutMessages).ifPresent(effects -> effects.applyTo(item));
            item.editPersistentDataContainer(pdc -> pdc.set(BrewAdapterAccess.BREWERY_DISPLAY_NAME, PersistentDataType.STRING, name));
        }
        BrewAdapterAccess.hideTooltips(item);
        markCurrent(item);
        return Optional.of(item);
    }
}
