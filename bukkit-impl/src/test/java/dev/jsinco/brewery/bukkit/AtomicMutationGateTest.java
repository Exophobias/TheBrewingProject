package dev.jsinco.brewery.bukkit;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AtomicMutationGateTest {

    @Test
    void refreshRefusesAnExistingReservation() {
        AtomicMutationGate gate = new AtomicMutationGate();

        assertFalse(gate.beginRefresh(() -> true));
        assertTrue(gate.reserve(() -> true),
                "a refused refresh must not leave the lifecycle gate claimed");
    }

    @Test
    void claimedRefreshPreventsAReservationUntilItEnds() {
        AtomicMutationGate gate = new AtomicMutationGate();
        AtomicBoolean reservationRan = new AtomicBoolean();

        assertTrue(gate.beginRefresh(() -> false));
        assertFalse(gate.reserve(() -> {
            reservationRan.set(true);
            return true;
        }));
        assertFalse(reservationRan.get(), "reservation supplier must not run during refresh");

        gate.endRefresh();
        assertTrue(gate.reserve(() -> true));
    }

    @Test
    void callbackFailureDoesNotStrandTheGate() {
        AtomicMutationGate gate = new AtomicMutationGate();

        assertThrows(IllegalStateException.class,
                () -> gate.beginRefresh(() -> {
                    throw new IllegalStateException("injected pending check failure");
                }));
        assertTrue(gate.reserve(() -> true));
    }
}
