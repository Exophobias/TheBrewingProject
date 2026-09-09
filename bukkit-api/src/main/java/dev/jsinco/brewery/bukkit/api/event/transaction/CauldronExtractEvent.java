package dev.jsinco.brewery.bukkit.api.event.transaction;

import com.google.common.base.Preconditions;
import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.breweries.Cauldron;
import dev.jsinco.brewery.bukkit.api.event.PermissibleBreweryEvent;
import dev.jsinco.brewery.bukkit.api.transaction.ItemSource;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.ItemStack;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

public class CauldronExtractEvent extends PermissibleBreweryEvent {

    private final Cauldron cauldron;
    /**
     * Use {@link #getItemResult()} instead. Will cause weird issues with newer api
     */
    @Deprecated(forRemoval = true)
    private ItemSource.BrewBasedSource brewSource;
    private ItemSource itemResult;
    private final @Nullable Player player;
    private final CompletionStage<Optional<CauldronExtractionReceipt>> completionResult;

    public CauldronExtractEvent(Cauldron cauldron, ItemSource.BrewBasedSource brewSource,
                                dev.jsinco.brewery.api.util.@NonNull CancelState state, @Nullable Player player) {
        this(cauldron, brewSource, state, player, CompletableFuture.completedFuture(Optional.empty()));
    }

    /**
     * Creates a synchronous proposal with its owner's eventual runtime observation. Legacy
     * proposals have no observation. Empty completion means no proven completed extraction,
     * not proof of rollback or unchanged inputs; an exceptional completion can include partial
     * native side effects. This stage never grants item replay or certifies durable delivery.
     */
    public CauldronExtractEvent(Cauldron cauldron, ItemSource.BrewBasedSource brewSource,
                                dev.jsinco.brewery.api.util.@NonNull CancelState state, @Nullable Player player,
                                CompletionStage<Optional<CauldronExtractionReceipt>> completionResult) {
        super(state);
        this.cauldron = cauldron;
        this.brewSource = brewSource;
        this.itemResult = brewSource;
        this.player = player;
        this.completionResult = Objects.requireNonNull(completionResult, "completionResult")
                .toCompletableFuture().minimalCompletionStage();
    }

    /**
     * @param brew The brew to set
     */
    public void setResult(@NonNull Brew brew) {
        Preconditions.checkNotNull(brew);
        brewSource = new ItemSource.BrewBasedSource(brew, new Brew.State.Other());
        itemResult = brewSource;
    }

    /**
     * Set resulting item
     *
     * @param result The item to set
     */
    public void setItemResult(@NonNull ItemStack result) {
        Preconditions.checkNotNull(result);
        itemResult = new ItemSource.ItemBasedSource(result);
    }

    private static final HandlerList HANDLERS = new HandlerList();

    @Override
    public @NonNull HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public Cauldron getCauldron() {
        return this.cauldron;
    }

    @Deprecated
    public ItemSource.BrewBasedSource getBrewSource() {
        return this.brewSource;
    }

    public ItemSource getItemResult() {
        return this.itemResult;
    }

    /**
     * Read-only runtime completion. TBP settles this synchronously on the owning execution
     * thread after finishing the proposal, including refused and failed outcomes. Consumers
     * must use the receipt's item snapshot rather than re-rendering this mutable proposal.
     */
    public CompletionStage<Optional<CauldronExtractionReceipt>> getCompletionResult() {
        return completionResult;
    }

    @Nullable
    public Player getPlayer() {
        return this.player;
    }
}
