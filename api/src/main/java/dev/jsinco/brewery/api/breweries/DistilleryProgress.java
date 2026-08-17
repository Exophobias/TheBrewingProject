package dev.jsinco.brewery.api.breweries;

/**
 * Progress of a distillery's current distillation run.
 *
 * <p>Kept separate from {@link DistilleryAccess} so that implementations can adopt it without every
 * existing implementor of that interface having to change.
 */
public interface DistilleryProgress {

    /**
     * Only describes an ongoing distillation while {@link #isProcessing()} is true, and can exceed
     * {@link #getProcessTime()}, as a finished run is applied only while the distillery is accessed
     *
     * @return The time spent on the current distillation run in ticks
     */
    long getTimeProcessed();

    /**
     * @return The time a single distillation run takes in ticks
     */
    long getProcessTime();

    /**
     * @return The maximum number of brews processed by one completed distillation cycle
     */
    default int getProcessAmount() {
        return 1;
    }

    /**
     * @return True if the mixture inventory has brews to distill
     */
    boolean isProcessing();
}
