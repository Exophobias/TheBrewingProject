package dev.jsinco.brewery.bukkit;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class OwnerPublicationQueueTest {
    @Test
    void shutdownSettlesReceiptEvenIfSchedulerDropsItsCallback() {
        OwnerPublicationQueue queue = new OwnerPublicationQueue();
        ArrayList<Runnable> scheduled = new ArrayList<>();
        AtomicBoolean published = new AtomicBoolean();
        var result = queue.submit(false, () -> published.set(true), scheduled::add);
        assertFalse(result.isDone());
        queue.stop();
        assertTrue(result.isCompletedExceptionally());
        scheduled.getFirst().run();
        assertFalse(published.get());
        assertTrue(queue.submit(true, () -> published.set(true), scheduled::add).isCompletedExceptionally());
        assertFalse(published.get());
    }

    @Test
    void successfulPublicationFinishesOnceBeforeLaterStop() {
        OwnerPublicationQueue queue = new OwnerPublicationQueue();
        ArrayList<Runnable> scheduled = new ArrayList<>();
        var count = new java.util.concurrent.atomic.AtomicInteger();
        var result = queue.submit(false, count::incrementAndGet, scheduled::add);
        scheduled.getFirst().run();
        scheduled.getFirst().run();
        queue.stop();
        assertDoesNotThrow(result::join);
        assertEquals(1, count.get());
    }

    @Test
    void cancellingObservationCannotSuppressAlreadyCommittedOwnerWork() {
        OwnerPublicationQueue queue = new OwnerPublicationQueue();
        ArrayList<Runnable> scheduled = new ArrayList<>();
        AtomicBoolean published = new AtomicBoolean();
        var result = queue.submit(false, () -> {
            assertFalse(Thread.holdsLock(queue));
            published.set(true);
        }, scheduled::add);
        result.cancel(false);
        scheduled.getFirst().run();
        assertTrue(published.get());
    }

    @Test
    void completionObserversRunOutsideQueueMonitor() {
        OwnerPublicationQueue queue = new OwnerPublicationQueue();
        ArrayList<Runnable> scheduled = new ArrayList<>();
        var result = queue.submit(false, () -> { }, scheduled::add);
        var observer = result.thenRun(() -> assertFalse(Thread.holdsLock(queue)));
        scheduled.getFirst().run();
        assertDoesNotThrow(observer::join);
        var stopped = queue.submit(false, () -> fail("stopped task"), scheduled::add);
        var stopObserver = stopped.handle((ignored, failure) -> {
            assertFalse(Thread.holdsLock(queue));
            return null;
        });
        queue.stop();
        assertDoesNotThrow(stopObserver::join);
    }

    @Test
    void schedulingAndPublicationFailuresSettleTheirReceipts() {
        OwnerPublicationQueue queue = new OwnerPublicationQueue();
        var rejected = queue.submit(false, () -> fail("must not run"), ignored -> {
            throw new IllegalStateException("scheduler rejected task");
        });
        assertTrue(rejected.isCompletedExceptionally());
        var failed = queue.submit(true, () -> { throw new IllegalStateException("publication failed"); },
                ignored -> fail("already on owner"));
        assertTrue(failed.isCompletedExceptionally());
        queue.stop();
    }
}
