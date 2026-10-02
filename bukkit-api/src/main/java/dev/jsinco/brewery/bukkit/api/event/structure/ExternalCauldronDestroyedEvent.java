package dev.jsinco.brewery.bukkit.api.event.structure;

import dev.jsinco.brewery.api.persistence.ExternalCauldronLease;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

/** A lawful removal already passed protection. This notification cannot veto world damage. */
public final class ExternalCauldronDestroyedEvent extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final ExternalCauldronLease lease;
    private final CompletionStage<ExternalCauldronLease> retirement;
    public ExternalCauldronDestroyedEvent(ExternalCauldronLease lease, CompletionStage<ExternalCauldronLease> retirement) {
        this.lease = Objects.requireNonNull(lease); this.retirement = Objects.requireNonNull(retirement);
    }
    public ExternalCauldronLease getLease() { return lease; }
    public CompletionStage<ExternalCauldronLease> getRetirement() { return retirement; }
    @Override public HandlerList getHandlers() { return HANDLERS; }
    public static HandlerList getHandlerList() { return HANDLERS; }
}
