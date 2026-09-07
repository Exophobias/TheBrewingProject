package dev.jsinco.brewery.database.sql;

import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseWorkTrackerTest {
    private final ArrayDeque<Runnable> queue = new ArrayDeque<>();
    private final DatabaseWorkTracker work = new DatabaseWorkTracker(queue::addLast);

    @Test
    void drainWaitsForInventoryChildTaskQueuedAfterFlushBegins() {
        AtomicBoolean stored = new AtomicBoolean();
        work.admit(() -> CompletableFuture.runAsync(() -> { }, work)
                .thenCompose(ignored -> CompletableFuture.runAsync(() -> stored.set(true), work)));
        var drain = work.drain();
        queue.removeFirst().run();
        assertFalse(drain.isDone(), "An empty sentinel would have run ahead of the child task");
        queue.removeFirst().run();
        assertTrue(stored.get());
        assertTrue(drain.isDone());
    }

    @Test
    void dependencyWaitIsAdmittedEvenBeforeFirstSqlTaskExists() {
        CompletableFuture<Void> ingredients = new CompletableFuture<>();
        work.admit(() -> ingredients.thenRunAsync(() -> { }, work));
        var drain = work.drain();
        assertTrue(queue.isEmpty());
        assertFalse(drain.isDone());
        ingredients.complete(null);
        assertFalse(drain.isDone());
        queue.removeFirst().run();
        assertTrue(drain.isDone());
    }

    @Test
    void failedComposedOperationDoesNotLeaveDrainPending() {
        var operation = work.admit(() -> CompletableFuture.runAsync(() -> {
            throw new IllegalStateException("Injected SQL failure");
        }, work));
        var drain = work.drain();
        queue.removeFirst().run();
        assertThrows(CompletionException.class, operation::join);
        assertTrue(drain.isDone());
    }

    @Test
    void closeRefusesNewOperationsButAllowsAdmittedChildWritesToFinish() {
        CompletableFuture<Void> parent = new CompletableFuture<>();
        work.admit(() -> parent.thenRunAsync(() -> { }, work));
        var closed = work.closeAdmission();
        var refused = work.admit(() -> fail("Must not begin another session operation"));
        assertThrows(CompletionException.class, refused::join);
        assertFalse(closed.isDone());
        parent.complete(null);
        queue.removeFirst().run();
        assertTrue(closed.isDone());
    }
}
