package dev.jsinco.brewery.bukkit;

import java.util.Objects;
import java.util.function.BooleanSupplier;

/**
 * Serializes atomic brewery reservations with lifecycle refreshes that replace live holders.
 */
public final class AtomicMutationGate {

    private boolean refreshPending;

    /**
     * Runs a reservation attempt only while no registry/database refresh owns the lifecycle.
     */
    public synchronized boolean reserve(BooleanSupplier reservation) {
        Objects.requireNonNull(reservation, "reservation");
        return !refreshPending && reservation.getAsBoolean();
    }

    /**
     * Claims the lifecycle for a refresh only when no atomic holder is currently reserved.
     */
    public synchronized boolean beginRefresh(BooleanSupplier reservationPending) {
        Objects.requireNonNull(reservationPending, "reservationPending");
        if (refreshPending || reservationPending.getAsBoolean()) {
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
}
