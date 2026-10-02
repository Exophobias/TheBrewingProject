package dev.jsinco.brewery.api.brew;

import java.util.Objects;

/** Owner-verified completed product. This is classification, never an item-delivery receipt. */
public record VerifiedBrewConsumable(String canonicalRecipeId, String recipeRevision, int proofVersion,
                                    boolean completed, double score, BrewQuality quality, boolean sealed) {
    public VerifiedBrewConsumable {
        Objects.requireNonNull(canonicalRecipeId); Objects.requireNonNull(recipeRevision); Objects.requireNonNull(quality);
        if (!canonicalRecipeId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")
                || !recipeRevision.matches("[a-f0-9]{64}") || proofVersion != 1 || !completed
                || !Double.isFinite(score) || score < .6 || score > 1
                || BrewQuality.quality(score).orElse(null) != quality)
            throw new IllegalArgumentException("Invalid verified brew classification");
    }
}
