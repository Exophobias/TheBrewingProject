package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.bukkit.AtomicMutationGate;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class WorldHydrationLifecycleTest {
    private final AtomicMutationGate gate = new AtomicMutationGate();
    private final WorldHydrationLifecycle lifecycle = new WorldHydrationLifecycle(gate);
    private final UUID world = UUID.randomUUID();

    @Test
    void asynchronousHydrationKeepsReservationsClosedAfterSynchronousReloadEnds() {
        assertTrue(gate.beginRefresh(() -> false));
        var load = lifecycle.begin(world);
        gate.endRefresh();
        assertFalse(gate.reserve(() -> fail("Reservation callback must not run")));
        lifecycle.publish(load, () -> assertFalse(gate.reserve(() -> true)));
        load.result().join();
        assertTrue(gate.reserve(() -> true));
    }

    @Test
    void delayedOldGenerationCannotReplaceNewGeneration() {
        AtomicInteger holders = new AtomicInteger();
        var old = lifecycle.begin(world);
        lifecycle.invalidateAll(); // Before registry clear and next read.
        var replacement = lifecycle.begin(world);
        lifecycle.publish(replacement, () -> holders.set(2));
        lifecycle.publish(old, () -> holders.set(1));
        assertEquals(2, holders.get());
        assertThrows(CancellationException.class, old.result()::join);
        assertTrue(gate.reserve(() -> true));
    }

    @Test
    void unloadInvalidationRejectsCompletionWhileBukkitStillExposesSameWorld() {
        var load = lifecycle.begin(world);
        lifecycle.invalidate(world);
        lifecycle.publish(load, () -> fail("Unloading world must not regain holders"));
        assertThrows(CancellationException.class, load.result()::join);
        assertTrue(gate.reserve(() -> true));
    }

    @Test
    void everyCurrentWorldMustPublishBeforeNewReservations() {
        var first = lifecycle.begin(world);
        var second = lifecycle.begin(UUID.randomUUID());
        lifecycle.publish(first, () -> { });
        assertFalse(gate.reserve(() -> true));
        lifecycle.publish(second, () -> { });
        assertTrue(gate.reserve(() -> true));
        lifecycle.invalidateAll(); // Completed leases must not decrement a second time.
        assertTrue(gate.reserve(() -> true));
    }

    @Test
    void readFailureTerminatesResultAndKeepsIncompleteGenerationClosedUntilRetry() {
        var failed = lifecycle.begin(world);
        lifecycle.fail(failed, new IllegalStateException("Injected SQL read failure"));
        assertThrows(CompletionException.class, failed.result()::join);
        assertFalse(gate.reserve(() -> true));
        var retry = lifecycle.begin(world);
        lifecycle.publish(failed, () -> fail("Failed generation cannot publish"));
        lifecycle.publish(retry, () -> { });
        assertTrue(gate.reserve(() -> true));
    }

    @Test
    void publicationFailureTerminatesResultAndCannotBeRetriedBySameCompletion() {
        var load = lifecycle.begin(world);
        lifecycle.publish(load, () -> { throw new IllegalStateException("Invalid persisted inventory"); });
        assertThrows(CompletionException.class, load.result()::join);
        lifecycle.publish(load, () -> fail("A failed snapshot needs a fresh read"));
        assertFalse(gate.reserve(() -> true));
    }

    @Test
    void stopTerminatesPendingPublicationsAndPermanentlyRejectsReservations() {
        var load = lifecycle.begin(world);
        lifecycle.stop();
        lifecycle.publish(load, () -> fail("Stopping plugin must not construct holders"));
        assertThrows(CancellationException.class, load.result()::join);
        assertThrows(IllegalStateException.class, () -> lifecycle.begin(world));
        assertFalse(gate.reserve(() -> true));
        assertFalse(gate.beginRefresh(() -> false));
    }
}
