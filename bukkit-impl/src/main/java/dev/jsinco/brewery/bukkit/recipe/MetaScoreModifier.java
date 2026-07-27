package dev.jsinco.brewery.bukkit.recipe;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.brew.BrewQuality;
import dev.jsinco.brewery.api.brew.BrewScore;
import dev.jsinco.brewery.api.brew.PartialBrewScore;
import dev.jsinco.brewery.api.brew.ScoreType;
import dev.jsinco.brewery.api.meta.MetaDataType;
import dev.jsinco.brewery.api.util.KeyUtil;
import dev.jsinco.brewery.util.MessageUtil;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.translation.Argument;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Map;

/**
 * Scales a matched score by a multiplier stored on the brew under {@link #SCORE_MODIFIER_KEY}, so metadata
 * written onto a brew can influence what that brew becomes.
 * <p>
 * The multiplier applies to {@link BrewScore#score()}, the product clamped to [0, 1], which moves both the
 * {@link BrewQuality} the brew lands on and the percentage shown by {@link BrewScore#displayName()}. A
 * multiplier rather than an offset, as the score it applies to is itself a product of partial scores.
 * <p>
 * {@link BrewScore#rawScore()} is left alone, so the multiplier decides how well a recipe was hit, never
 * which recipe was hit.
 */
public final class MetaScoreModifier {

    /**
     * Metadata key holding the score multiplier: {@code brewery:score_modifier}
     */
    public static final Key SCORE_MODIFIER_KEY = KeyUtil.brewery("score_modifier");

    private MetaScoreModifier() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * @param brew  The brew the score was calculated on
     * @param score The score to modify
     * @return The score with this brew's multiplier applied, or the same score if there is none to apply
     */
    public static BrewScore apply(Brew brew, BrewScore score) {
        // Checked first, meta() throws if a value of another type is stored under the key
        Double modifier = brew.hasMeta(SCORE_MODIFIER_KEY, MetaDataType.DOUBLE)
                ? brew.meta(SCORE_MODIFIER_KEY, MetaDataType.DOUBLE)
                : null;
        if (modifier == null || !Double.isFinite(modifier)) {
            return score;
        }
        return new ModifiedScore(score, modifier);
    }

    private record ModifiedScore(BrewScore delegate, double modifier) implements BrewScore {

        @Override
        public @Nullable BrewQuality brewQuality() {
            return BrewQuality.quality(score()).orElse(null);
        }

        @Override
        public Map<ScoreType, PartialBrewScore> getPartialScores(int stepIndex) {
            return delegate.getPartialScores(stepIndex);
        }

        @Override
        public double score() {
            return Math.clamp(delegate.score() * modifier, 0D, 1D);
        }

        @Override
        public double rawScore() {
            return delegate.rawScore();
        }

        @Override
        public boolean completed() {
            return delegate.completed();
        }

        @Override
        public Component displayName() {
            return Component.translatable(
                    "tbp.brew.tooltip.quality-display",
                    Argument.tagResolver(MessageUtil.getValueDisplayTagResolver(score() * 100))
            );
        }

        @Override
        public double brewDifficulty() {
            return delegate.brewDifficulty();
        }

        @Override
        public int compareTo(@NonNull BrewScore other) {
            return delegate.compareTo(other);
        }
    }
}
