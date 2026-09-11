package dev.jsinco.brewery.bukkit.recipe;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.brew.BrewQuality;
import dev.jsinco.brewery.api.brew.BrewScore;
import dev.jsinco.brewery.api.brew.BrewingStep;
import dev.jsinco.brewery.api.recipe.DefaultRecipe;
import dev.jsinco.brewery.api.recipe.Recipe;
import dev.jsinco.brewery.api.recipe.RecipeMatcherResult;
import dev.jsinco.brewery.api.recipe.RecipeResult;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.integration.IntegrationTypes;
import dev.jsinco.brewery.bukkit.brew.BrewAdapterAccess;
import dev.jsinco.brewery.bukkit.util.BukkitMessageUtil;
import dev.jsinco.brewery.configuration.BrewTooltipType;
import dev.jsinco.brewery.configuration.Config;
import dev.jsinco.brewery.configuration.DrunkenModifierSection;
import dev.jsinco.brewery.effect.DrunkStateImpl;
import dev.jsinco.brewery.recipes.RecipeRegistryImpl;
import dev.jsinco.brewery.util.BrewUtil;
import dev.jsinco.brewery.util.MessageUtil;
import io.papermc.paper.datacomponent.DataComponentTypes;
import io.papermc.paper.datacomponent.item.ItemLore;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.translation.Argument;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

public class RecipeMatcherResultImpl implements RecipeMatcherResult<ItemStack> {

    private final @Nullable Recipe<ItemStack> recipe;
    private final List<BrewingStep> matchingSteps;
    private final Brew brew;
    private final BrewScore score;

    public RecipeMatcherResultImpl(@Nullable Recipe<ItemStack> recipe, List<BrewingStep> matchingSteps, Brew brew, BrewScore brewScore) {
        this.recipe = recipe;
        this.matchingSteps = matchingSteps;
        this.brew = brew;
        this.score = brewScore;
    }

    @Override
    public ItemStack toItem(Brew.State state) {
        return toItem(state, quality().orElse(null));
    }

    @Override
    public ItemStack toItem(Brew.State state, @Nullable BrewQuality overrideQuality) {
        return render(state, overrideQuality, true);
    }

    private ItemStack render(Brew.State state, @Nullable BrewQuality overrideQuality, boolean includeLore) {
        RecipeRegistryImpl<ItemStack> recipeRegistry = TheBrewingProject.getInstance().getRecipeRegistry();
        ItemStack itemStack;
        if (overrideQuality == null || recipe == null) {
            itemStack = new ItemStack(Material.POTION);
            BrewAdapterAccess.getDefaultRecipe(null, recipeRegistry, brew, true)
                    .map(DefaultRecipe::result).map(RecipeResult::recipeEffects)
                    .filter(RecipeEffectsImpl.class::isInstance).map(RecipeEffectsImpl.class::cast)
                    .ifPresent(effects -> effects.applyTo(itemStack));
            itemStack.editPersistentDataContainer(pdc -> {
                pdc.set(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE, 0D);
            });
        } else if (!score.completed()) {
            itemStack = new ItemStack(Material.POTION);
        } else {
            RecipeResult<ItemStack> recipeResult = recipe.getRecipeResult(overrideQuality);
            if (BrewRecognition.recognized(score)) {
                itemStack = recipeResult.newLorelessItem();
                if (includeLore) applyLore(itemStack, recipeResult, state);
            } else {
                itemStack = new ItemStack(Material.POTION);
                if (recipeResult.recipeEffects() instanceof RecipeEffectsImpl effects) effects.withoutMessages().applyTo(itemStack);
            }
            itemStack.editPersistentDataContainer(pdc -> BrewAdapterAccess.applyBrewTags(
                    pdc,
                    recipe,
                    Objects.equals(quality().orElse(null), overrideQuality) ? score.score() : BrewQuality.maxScore(overrideQuality),
                    BrewRecognition.recognized(score) ? MiniMessage.miniMessage().serialize(recipeResult.displayName())
                            : BrewRecognition.anonymousName(score)
            ));
        }
        BrewAdapterAccess.hideTooltips(itemStack);
        if (!BrewRecognition.recognized(score)) BrewRecognition.anonymize(itemStack, score);
        applyPersistentData(itemStack, state);
        return itemStack;
    }

    @Override
    public ItemStack toItem(Brew.State state, @Nullable DefaultRecipe<ItemStack> preferredDefaultRecipe) {
        // Incomplete recipe-specific aliases can reveal a match before it is recognizable.
        return toItem(state);
    }

    @Override
    public ItemStack toLorelessItem(Brew.State state) {
        return toLorelessItem(state, quality().orElse(null));
    }

    @Override
    public ItemStack toLorelessItem(Brew.State state, @Nullable BrewQuality overrideQuality) {
        return render(state, overrideQuality, false);
    }

    @Override
    public Optional<Recipe<ItemStack>> recipeMatch() {
        return Optional.ofNullable(recipe);
    }

    @Override
    public Optional<BrewQuality> quality() {
        return Optional.ofNullable(score.brewQuality());
    }

    @Override
    public BrewScore score() {
        return score;
    }

    @Override
    public Optional<RecipeResult<ItemStack>> recipeResult() {
        BrewQuality brewQuality = quality().orElse(null);
        if (recipe != null && brewQuality != null && BrewRecognition.recognized(score)) {
            return Optional.of(recipe.getRecipeResult(brewQuality));
        }
        return Optional.empty();
    }

    @Override
    public List<BrewingStep> matchingSteps() {
        return matchingSteps;
    }

    void applyPersistentData(ItemStack itemStack, Brew.State state) {
        if (BrewRecognition.recognized(score)) RecipeEffectsImpl.applyBrewDurationReward(itemStack, brew);
        itemStack.editPersistentDataContainer(pdc -> {
            if (state instanceof BrewImpl.State.Seal) {
                BrewAdapterAccess.applyBrewMeta(pdc, brew);
            } else {
                BrewAdapterAccess.applyBrewData(pdc, brew);
            }
        });
        TheBrewingProject.getInstance().getIntegrationManager()
                .retrieve(IntegrationTypes.ITEM)
                .forEach(integration -> integration.decorateBrewItem(itemStack, brew));
        BrewRecognition.markCurrent(itemStack);
    }

    private void applyLore(ItemStack itemStack, RecipeResult<ItemStack> recipeResult, Brew.State state) {
        Stream.Builder<Component> fullLoreBuilder = Stream.builder();
        TagResolver resolver = TagResolver.resolver(MessageUtil.recipeEffectsResolver(recipeResult.recipeEffects()), MessageUtil.brewScoreResolver(score));
        for (BrewTooltipType tooltipType : Config.config().brewTooltipOrder()) {
            if (!recipeResult.appendBrewInfoLore() && BrewTooltipType.RECIPE_LORE != tooltipType) {
                continue;
            }
            switch (tooltipType) {
                case RECIPE_LORE -> recipeResult.lore().stream()
                        .map(line -> MessageUtil.miniMessage(line, MessageUtil.getScoreTagResolver(score)))
                        .forEach(fullLoreBuilder);
                case SCORE -> {
                    switch (state) {
                        case Brew.State.Brewing() ->
                                fullLoreBuilder.add(Component.translatable("tbp.brew.tooltip.quality-brewing", Argument.tagResolver(resolver)));
                        case Brew.State.Other() ->
                                fullLoreBuilder.add(Component.translatable("tbp.brew.tooltip.quality", Argument.tagResolver(resolver)));
                        case Brew.State.Seal(String ignored) ->
                                fullLoreBuilder.add(Component.translatable("tbp.brew.tooltip.quality-sealed", Argument.tagResolver(resolver)));
                    }
                }
                case MODIFIER -> applyDrunkenTooltips(state, fullLoreBuilder, resolver, recipeResult);
                case SEALED_TEXT -> {
                    if (state instanceof Brew.State.Seal(String message) && message != null) {
                        fullLoreBuilder.add(Component.translatable("tbp.brew.tooltip.volume", Argument.tagResolver(
                                TagResolver.resolver(resolver, Placeholder.parsed("volume", message)))
                        ));
                    }
                }
                case BREWERS -> applyBrewersTooltip(brew, fullLoreBuilder);
                case STEPS -> {
                    switch (state) {
                        case Brew.State.Brewing ignored -> {
                            MessageUtil.compileBrewInfo(matchingSteps, score, false).forEach(fullLoreBuilder::add);
                        }
                        case Brew.State.Other ignored -> {
                            addLastStepLore(fullLoreBuilder, score, state);
                        }
                        case Brew.State.Seal ignored -> {
                            addLastStepLore(fullLoreBuilder, score, state);
                        }
                    }
                }
                case EMPTY_LINE -> fullLoreBuilder.add(Component.empty());
            }
        }
        itemStack.setData(DataComponentTypes.LORE, ItemLore.lore(
                fullLoreBuilder.build()
                        .map(component -> component.decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE))
                        .map(component -> component.colorIfAbsent(NamedTextColor.GRAY))
                        .map(component -> GlobalTranslator.render(component, Config.config().language()))
                        .toList()
        ));
    }

    private void addLastStepLore(Stream.Builder<Component> streamBuilder, BrewScore score, Brew.State state) {
        int lastIndex = matchingSteps.size() - 1;
        BrewingStep lastCompleted = matchingSteps.getLast();
        streamBuilder.add(lastCompleted.infoDisplay(state,
                        MessageUtil.getBrewStepTagResolver(
                                lastCompleted,
                                score.getPartialScores(lastIndex),
                                score.brewDifficulty(),
                                BrewUtil.hasPreviousIngredientStep(brew.getCompletedSteps(), lastIndex))
                )
        );
    }

    private void applyDrunkenTooltips(Brew.State state, Stream.Builder<Component> streamBuilder, TagResolver resolver, RecipeResult<ItemStack> recipeResult) {
        DrunkenModifierSection.modifiers().drunkenTooltips()
                .stream()
                .filter(modifierTooltip -> modifierTooltip.filter().evaluate(DrunkStateImpl.compileVariables(recipeResult.recipeEffects().getModifiers(), null, 0D)) > 0)
                .map(modifierTooltip -> modifierTooltip.getTooltip(state))
                .filter(Objects::nonNull)
                .map(miniMessage -> MessageUtil.miniMessage(miniMessage, resolver))
                .forEach(streamBuilder::add);
    }


    private void applyBrewersTooltip(Brew brew, Stream.Builder<Component> streamBuilder) {
        Collection<UUID> brewers = switch (Config.config().brewersDisplay()) {
            case NONE -> List.of();
            case FIRST_STEP -> brew.getCompletedSteps().stream().findFirst()
                    .map(BrewingStep::brewers)
                    .orElseGet(LinkedHashSet::new);
            case LEAD_BREWER -> brew.leadBrewer().stream().toList();
            case LAST_STEP -> brew.lastCompletedStep()
                    .brewers();
            case ALL -> brew.getBrewers();
        };
        if (!brewers.isEmpty()) {
            streamBuilder.add(
                    MessageUtil.translated("tbp.brew.tooltip.brewer",
                            Placeholder.component("brewers", brewers.stream()
                                    .map(BukkitMessageUtil::uuidToPlayerName)
                                    .collect(Component.toComponent()))
                    ));
        }
    }
}
