package dev.jsinco.brewery.bukkit.api.event.structure;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.breweries.DistilleryAccess;
import dev.jsinco.brewery.api.util.CancelState;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class DistilleryDestroyEvent extends BreweryDestroyEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /**
     * The distillery that was destroyed.
     */
    private final DistilleryAccess distillery;
    /**
     * The brews that will be dropped when the distillery is broken. Can be modified.
     */
    private List<Brew> drops;
    private final CompletionStage<Boolean> commitResult;

    public DistilleryDestroyEvent(CancelState state, DistilleryAccess distillery, @Nullable Player player, Location location, Collection<Brew> drops) {
        this(state, distillery, player, location, drops, CompletableFuture.completedFuture(true));
    }

    /**
     * Creates a pre-commit destruction authorization event with its eventual durable result.
     *
     * @param commitResult completes true only after durable deletion and live publication, false
     *                     when cancelled/rejected, and exceptionally on lifecycle failure
     */
    public DistilleryDestroyEvent(CancelState state, DistilleryAccess distillery,
                                  @Nullable Player player, Location location,
                                  Collection<Brew> drops,
                                  CompletionStage<Boolean> commitResult) {
        super(state, player, location);
        this.distillery = distillery;
        this.commitResult = Objects.requireNonNull(commitResult, "commitResult")
                .toCompletableFuture()
                .minimalCompletionStage();
        setDrops(drops);
    }

    /**
     * Replaces the list of drops with the provided collection.
     *
     * @param drops collection of brews to drop
     */
    public void setDrops(Collection<Brew> drops) {
        this.drops = new ArrayList<>(drops);
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

    public List<Brew> getDrops() {
        return this.drops;
    }

    /**
     * The durable outcome of this destruction proposal. Cancellation remains synchronous;
     * irreversible listener effects should run only when this stage completes true.
     *
     * @return a read-only view of the destruction's eventual commit result
     */
    public CompletionStage<Boolean> getCommitResult() {
        return commitResult;
    }
}
