package dev.jsinco.brewery.brew;

import dev.jsinco.brewery.api.brew.BrewingStep;
import dev.jsinco.brewery.api.brew.ScoreType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DistillStepImplTest {

    @Test
    void proximityScores_toleranceOfOneScoresAsBefore() {
        for (int target = 1; target <= 16; target++) {
            for (int actual = 0; actual <= 3 * target; actual++) {
                double expected = Math.sqrt(BrewingStepUtil.nearbyValueScore(target, actual));
                assertEquals(expected, score(new DistillStepImpl(target, 1D), actual),
                        "target " + target + ", actual " + actual);
            }
        }
    }

    @Test
    void proximityScores_overToleranceOnlyAffectsOverDistilling() {
        DistillStepImpl step = new DistillStepImpl(5, 1.5D);
        assertEquals(Math.sqrt(BrewingStepUtil.nearbyValueScore(5, 3)), score(step, 3));
        assertEquals(1D, score(step, 5));
        assertEquals(Math.sqrt(1D - 3D / 7.5D), score(step, 8));
        assertEquals(0D, score(step, 13));
    }

    @Test
    void proximityScores_zeroToleranceRuinsAnyOvershoot() {
        DistillStepImpl step = new DistillStepImpl(5, 0D);
        assertEquals(1D, score(step, 5));
        assertEquals(0D, score(step, 6));
    }

    @Test
    void incrementRuns_keepsOverTolerance() {
        assertEquals(1.5D, new DistillStepImpl(5, 1.5D).incrementRuns().overTolerance());
    }

    @Test
    void overTolerance_defaultsToInherit() {
        assertEquals(BrewingStep.Distill.INHERIT_TOLERANCE, new DistillStepImpl(5).overTolerance());
    }

    private static double score(DistillStepImpl recipeStep, int actualRuns) {
        return recipeStep.proximityScores(new DistillStepImpl(actualRuns))
                .get(ScoreType.DISTILL_AMOUNT)
                .score();
    }
}
