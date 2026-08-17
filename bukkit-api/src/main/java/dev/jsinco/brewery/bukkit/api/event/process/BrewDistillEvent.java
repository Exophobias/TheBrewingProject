package dev.jsinco.brewery.bukkit.api.event.process;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.breweries.DistilleryAccess;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * An event that triggers whenever a brew distills. A distillery only processes while its inventory is open, or while a
 * hopper is pulling from one of its containers, meaning this event can trigger without a player being involved.
 * Several of these events can be called within the same tick, one for each brew being distilled.
 * <p>
 * This is a cancellable, pre-commit event: listeners synchronously cancel or transform {@link #getResult()}, then use
 * {@link #getCommitResult()} to defer irreversible effects until the transfer is durable and published in memory.
 */
public class BrewDistillEvent extends BrewProcessEvent {
    private static final HandlerList HANDLERS = new HandlerList();

    private final DistilleryAccess distillery;
    private final CompletionStage<Boolean> commitResult;
    private boolean batchDeferred;

    /**
     * Creates a legacy event with no asynchronous lifecycle owner. Its commit result is already {@code true}, preserving
     * the immediate behavior of events manually constructed before the commit contract was introduced. Producers that
     * persist a transfer asynchronously should use {@link #BrewDistillEvent(DistilleryAccess, Brew, Brew,
     * CompletionStage)} instead.
     */
    public BrewDistillEvent(DistilleryAccess distillery, Brew source, Brew result) {
        this(distillery, source, result, CompletableFuture.completedFuture(true));
    }

    /**
     * Creates a pre-commit event tied to its eventual durable result.
     *
     * @param distillery distillery proposing the transfer
     * @param source source mixture brew
     * @param result proposed distillate brew, which listeners may replace synchronously
     * @param commitResult completes {@code true} only after durable commit and live publication, {@code false} when the
     *                     proposed transfer is cancelled or rejected, and exceptionally on persistence/publication error
     */
    public BrewDistillEvent(DistilleryAccess distillery, Brew source, Brew result,
                            CompletionStage<Boolean> commitResult) {
        super(source, result);
        this.distillery = Objects.requireNonNull(distillery, "distillery");
        // CompletionStage#toCompletableFuture exposes mutation methods. A listener completing the
        // producer's actual future could otherwise forge a commit before persistence settles.
        // MinimalStage returns a detached future from toCompletableFuture while retaining every
        // read/composition operation listeners need.
        this.commitResult = Objects.requireNonNull(commitResult, "commitResult")
                .toCompletableFuture()
                .minimalCompletionStage();
    }

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public DistilleryAccess getDistillery() {
        return this.distillery;
    }

    /**
     * The durable outcome of this proposed transfer. Result mutation and cancellation must still happen synchronously in
     * the event handler; irreversible effects should run only when this stage completes with {@code true}.
     *
     * @return read-only view of the transfer's eventual commit result
     */
    public CompletionStage<Boolean> getCommitResult() {
        return commitResult;
    }

    /**
     * Requests that the producer defer the entire batch containing this event instead of
     * committing a partial transfer. TBP's stock distillery pipeline responds by discarding every
     * move already proposed in the current due batch, completing each fired event's commit result
     * {@code false}, and leaving all source brews untouched. A plain event cancellation remains a
     * per-brew decision and does not imply batch deferral.
     * <p>
     * This is an opt-in producer contract: code that manually creates distillation events should
     * check {@link #isBatchDeferred()} if it also batches their persistence.
     */
    public void deferBatch() {
        this.batchDeferred = true;
    }

    /**
     * @return whether a listener requested that this event's whole producer batch be deferred
     */
    public boolean isBatchDeferred() {
        return batchDeferred;
    }
}
