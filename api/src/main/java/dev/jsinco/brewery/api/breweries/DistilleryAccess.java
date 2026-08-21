package dev.jsinco.brewery.api.breweries;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.structure.MultiblockStructure;
import dev.jsinco.brewery.api.util.CancelState;
import dev.jsinco.brewery.api.util.Holder;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface DistilleryAccess extends SelfSchedulingBrewery {

    /** A pre-authorized exact-holder reservation for later durable no-drop consumption. */
    interface AtomicConsumption {

        /** True while this reservation still owns the exact live distillery. */
        boolean isValid();

        /** Starts the durable delete after the initiating world action has been accepted. */
        CompletableFuture<Boolean> commit();

        /** Releases the reservation without consuming the distillery. */
        void abort();
    }
    /**
     * Open this distillery inventory for the player with the specified UUID
     *
     * @param location The location to open from
     * @param player   The player UUID
     * @return True if canceled
     */
    CancelState open(@NonNull BreweryLocation location, Holder.@NonNull Player player);

    /**
     * Closes the distillery inventory for all viewers
     *
     * @param silent Whether to play a close sound or not
     */
    void close(boolean silent);

    /**
     * Destroy the distillery, dropping all contained items
     *
     * @param breweryLocation The location to destroy from
     */
    void destroy(BreweryLocation breweryLocation);

    /**
     * @return This distillery mixture inventory
     */
    BrewInventory getMixture();

    /**
     * @return This distillery distillate inventory
     */
    BrewInventory getDistillate();

    /**
     * @return The underlying distillery structure
     */
    MultiblockStructure<? extends DistilleryAccess> getStructure();

    /**
     * Whether this distillery is quarantined for an atomic inventory or lifecycle mutation.
     * While this is true, callers must not open, tick, extract from, or destroy the distillery
     * through another path. The default preserves compatibility with older implementations.
     *
     * @return true while an atomic mutation owns the distillery
     */
    default boolean isAtomicMovePending() {
        return false;
    }

    /**
     * Whether acknowledged atomic consumption leaves every world block for the caller.
     * <p>
     * This capability is part of the no-drop contract: the lifecycle owner can authorize and
     * durably delete its own rows, but it cannot know which physical blocks an initiating
     * explosion and its protection listeners allowed to be destroyed. Implementations returning
     * {@code true} promise that {@link #consumeWithoutDropsAtomically(BreweryLocation)} removes no
     * world geometry. The default keeps older implementations fail-closed for callers that need
     * this stronger contract.
     *
     * @return true when the caller retains exclusive control of physical block removal
     */
    default boolean atomicConsumptionPreservesWorldGeometry() {
        return false;
    }

    /**
     * Runs destruction permission/event authorization and reserves this exact live holder before
     * an external caller creates an irreversible world action.
     * <p>
     * A non-empty result owns the distillery until exactly one of
     * {@link AtomicConsumption#commit()} or {@link AtomicConsumption#abort()} is called. The
     * reservation prevents ticking, access, extraction, ordinary destruction, or another atomic
     * mutation in the meantime. Implementations must leave all world geometry untouched.
     *
     * @param location exact structure component selected by the caller
     * @param player initiating player, or null for fire/explosion/automation
     * @return an authorized reservation, or empty when permission, an event, ownership, or a busy
     *         holder refuses the proposal
     */
    default Optional<AtomicConsumption> prepareAtomicConsumption(
            @NonNull BreweryLocation location,
            Holder.@Nullable Player player) {
        return Optional.empty();
    }

    /**
     * Atomically consumes this entire distillery without dropping its brews.
     * <p>
     * Implementations first run their normal destruction authorization event, then reserve the
     * live holder, remove every persisted brew and the structure row in one transaction, and only
     * after commit close viewers and remove runtime registrations. Implementations advertising
     * {@link #atomicConsumptionPreservesWorldGeometry()} leave <em>all</em> world blocks intact;
     * the caller must remove only the positions authorized by its own initiating event and
     * protection checks after this future completes {@code true}.
     * <p>
     * This method must be called from the distillery's owning region. {@code false} means the
     * request was cancelled, the holder was busy or no longer owns {@code location}, or the
     * durable row was already absent; the logical distillery was not consumed. Failures complete
     * exceptionally. Implementations must quarantine a commit-attempt ambiguity or a committed
     * publication failure until authoritative reload rather than exposing possibly stale brews.
     *
     * @param location exact structure component through which consumption was requested
     * @return a future completed only after durable deletion and live no-drop publication
     */
    default CompletableFuture<Boolean> consumeWithoutDropsAtomically(
            @NonNull BreweryLocation location) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Atomic no-drop distillery consumption is not supported by this implementation"
        ));
    }

    /**
     * Atomically moves a batch of brews from this distillery's mixture inventory to its distillate
     * inventory. Implementations validate every source and destination before changing anything,
     * persist the whole batch in one database transaction, and publish the in-memory changes only
     * after that transaction commits.
     * <p>
     * This method must be called from the distillery's owning region. A result of {@code false}
     * means that the distillery was busy or that an in-memory/database precondition no longer
     * matched; no requested move was applied. Persistence failures complete the future
     * exceptionally. Callers should keep any higher-level gameplay session locked until the
     * returned future completes.
     *
     * @param moves non-empty, non-overlapping source/destination moves
     * @return a future completed after both durable persistence and in-memory publication
     */
    default CompletableFuture<Boolean> moveBrewsAtomically(@NonNull List<AtomicBrewMove> moves) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Atomic distillery moves are not supported by this implementation"
        ));
    }

    /**
     * Atomically removes an exact batch of brews from this distillery's mixture inventory.
     * Implementations validate every source before changing anything, persist the whole removal
     * in one database transaction, and publish the in-memory changes only after that transaction
     * commits.
     * <p>
     * This method must be called from the distillery's owning region. A result of {@code false}
     * means that the distillery was busy or that an in-memory/database precondition no longer
     * matched; no requested removal was applied. Persistence failures complete the future
     * exceptionally. Callers should keep any higher-level gameplay session locked until the
     * returned future completes.
     *
     * @param removals non-empty, non-overlapping exact mixture removals
     * @return a future completed after both durable persistence and in-memory publication
     */
    default CompletableFuture<Boolean> removeMixtureBrewsAtomically(
            @NonNull List<AtomicBrewRemoval> removals) {
        return CompletableFuture.failedFuture(new UnsupportedOperationException(
                "Atomic distillery removals are not supported by this implementation"
        ));
    }

    /**
     * One entry in an atomic mixture-to-distillate move.
     *
     * @param mixturePosition source slot in the mixture inventory
     * @param distillatePosition destination slot in the distillate inventory
     * @param expectedMixture exact brew expected in the source slot
     * @param distillate brew to persist in the destination slot
     */
    record AtomicBrewMove(int mixturePosition, int distillatePosition,
                          @NonNull Brew expectedMixture, @NonNull Brew distillate) {
        public AtomicBrewMove {
            if (mixturePosition < 0 || distillatePosition < 0) {
                throw new IllegalArgumentException("Distillery inventory positions cannot be negative");
            }
            Objects.requireNonNull(expectedMixture, "expectedMixture");
            Objects.requireNonNull(distillate, "distillate");
        }
    }

    /**
     * One exact source entry in an atomic mixture removal.
     *
     * @param mixturePosition source slot in the mixture inventory
     * @param expectedMixture exact brew expected in the source slot
     */
    record AtomicBrewRemoval(int mixturePosition, @NonNull Brew expectedMixture) {
        public AtomicBrewRemoval {
            if (mixturePosition < 0) {
                throw new IllegalArgumentException("Distillery inventory positions cannot be negative");
            }
            Objects.requireNonNull(expectedMixture, "expectedMixture");
        }
    }

}
