package dev.jsinco.brewery.bukkit;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Serializes atomic brewery reservations with lifecycle refreshes that replace live holders.
 */
public final class AtomicMutationGate {

    private boolean refreshPending;
    private int pendingHydrations;
    private boolean stopping;

    /**
     * Runs a reservation attempt only while no registry/database refresh owns the lifecycle.
     */
    public synchronized boolean reserve(BooleanSupplier reservation) {
        Objects.requireNonNull(reservation, "reservation");
        return !refreshPending && pendingHydrations == 0 && !stopping && reservation.getAsBoolean();
    }

    /**
     * Claims the lifecycle for a refresh only when no atomic holder is currently reserved.
     */
    public synchronized boolean beginRefresh(BooleanSupplier reservationPending) {
        Objects.requireNonNull(reservationPending, "reservationPending");
        if (stopping || refreshPending || reservationPending.getAsBoolean()) {
            return false;
        }
        refreshPending = true;
        return true;
    }

    public synchronized void endRefresh() {
        if (!refreshPending) {
            throw new IllegalStateException("No brewery lifecycle refresh is pending");
        }
        refreshPending = false;
    }

    /** A refresh may finish synchronously while its replacement holders are still loading. */
    public synchronized Hydration beginHydration() {
        if (stopping) {
            throw new IllegalStateException("The brewery lifecycle is stopping");
        }
        pendingHydrations++;
        return new Hydration();
    }

    public synchronized void stop() {
        stopping = true;
    }

    public final class Hydration implements AutoCloseable {
        private boolean closed;

        private Hydration() { }

        @Override
        public void close() {
            synchronized (AtomicMutationGate.this) {
                if (!closed) {
                    closed = true;
                    pendingHydrations--;
                }
            }
        }
    }
}
