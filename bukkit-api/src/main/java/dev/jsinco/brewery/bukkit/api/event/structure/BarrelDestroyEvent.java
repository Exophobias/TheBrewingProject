package dev.jsinco.brewery.bukkit.api.event.structure;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.breweries.BarrelAccess;
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

public class BarrelDestroyEvent extends BreweryDestroyEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /**
     * The barrel that was destroyed.
     */
    private final BarrelAccess barrel;
    /**
     * The brews that will be dropped when the barrel is broken. Can be modified.
     */
    private List<Brew> drops;
    private final CompletionStage<Boolean> commitResult;

    public BarrelDestroyEvent(CancelState state, BarrelAccess barrel, @Nullable Player player, Location location, Collection<Brew> drops) {
        // A third-party proposal supplies no owner acknowledgment. Preserve source compatibility
        // without fabricating a committed deletion through the newly exposed outcome API.
        this(state, barrel, player, location, drops, CompletableFuture.completedFuture(false));
    }

    /**
     * A destruction proposal with the owner's eventual deletion result. This does not certify
     * native item delivery or make drops atomic with database persistence.
     *
     * @param commitResult true after durable deletion and live unregistration; false when
     *                     rejected, or exceptional when lifecycle persistence fails
     */
    public BarrelDestroyEvent(CancelState state, BarrelAccess barrel, @Nullable Player player,
                              Location location, Collection<Brew> drops,
                              CompletionStage<Boolean> commitResult) {
        super(state, player, location);
        this.barrel = barrel;
        this.commitResult = Objects.requireNonNull(commitResult, "commitResult")
                .toCompletableFuture().minimalCompletionStage();
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

    public BarrelAccess getBarrel() {
        return this.barrel;
    }

    public List<Brew> getDrops() {
        return this.drops;
    }

    /** Read-only deletion acknowledgment; cancellation of this proposal remains synchronous. */
    public CompletionStage<Boolean> getCommitResult() {
        return commitResult;
    }
}
