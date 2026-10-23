package dev.jsinco.brewery.bukkit.api.event.transaction;

import dev.jsinco.brewery.api.breweries.DistilleryAccess;
import dev.jsinco.brewery.bukkit.api.event.PermissibleBreweryEvent;
import dev.jsinco.brewery.bukkit.api.transaction.ItemSource;
import dev.jsinco.brewery.bukkit.api.transaction.ItemTransactionSession;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class DistilleryExtractEvent extends PermissibleBreweryEvent implements ItemTransactionEvent<ItemSource.ItemBasedSource> {

    private final CompletionStage<Optional<InventoryExtractionReceipt>> completionResult;

    private static final HandlerList HANDLERS = new HandlerList();
    private final DistilleryAccess distillery;
    private final ItemTransactionSession<ItemSource.ItemBasedSource> transactionSession;
    private final @Nullable Player player;

    public DistilleryExtractEvent(DistilleryAccess distillery, ItemTransactionSession<ItemSource.ItemBasedSource> transactionSession,
                                  dev.jsinco.brewery.api.util.@NonNull CancelState state, @Nullable Player player) {
        this(distillery, transactionSession, state, player, CompletableFuture.completedFuture(Optional.empty()));
    }

    /** Proposes an extraction with a read-only runtime completion owned by TBP.
     * Empty means no output publication was proven; it does not certify rollback. */
    public DistilleryExtractEvent(DistilleryAccess distillery, ItemTransactionSession<ItemSource.ItemBasedSource> transactionSession,
                                 dev.jsinco.brewery.api.util.@NonNull CancelState state, @Nullable Player player,
                                 CompletionStage<Optional<InventoryExtractionReceipt>> completionResult) {
        super(state);
        this.completionResult = Objects.requireNonNull(completionResult, "completionResult")
                .toCompletableFuture().minimalCompletionStage();
        this.distillery = distillery;
        this.transactionSession = transactionSession;
        this.player = player;
    }

    /** Settles on the owner thread after deferred destination publication, refusal or failure.
     * Consumers must use the receipt snapshot instead of this mutable proposal's result. */
    public CompletionStage<Optional<InventoryExtractionReceipt>> getCompletionResult() {
        return completionResult;
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

    public ItemTransactionSession<ItemSource.ItemBasedSource> getTransactionSession() {
        return this.transactionSession;
    }

    @Nullable
    public Player getPlayer() {
        return this.player;
    }
}
