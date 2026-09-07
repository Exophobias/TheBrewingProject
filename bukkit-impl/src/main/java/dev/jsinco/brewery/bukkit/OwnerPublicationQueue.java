package dev.jsinco.brewery.bukkit;

import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Settles owner work even when server shutdown discards its scheduled callback. */
public final class OwnerPublicationQueue {
    private final Set<CompletableFuture<Void>> pending = new HashSet<>();
    private boolean stopped;

    public CompletableFuture<Void> submit(boolean owned, Runnable action, Consumer<Runnable> scheduler) {
        CompletableFuture<Void> result = new CompletableFuture<>();
        synchronized (this) {
            if (stopped) return CompletableFuture.failedFuture(stopping());
            pending.add(result);
        }
        Runnable publication = () -> {
            synchronized (this) {
                // Claim the internal task exactly once. The public future is an observation,
                // so cancelling/timing it out cannot discard committed owner publication.
                if (!pending.remove(result)) return;
            }
            // Owner actions and observer callbacks must never run under the queue monitor.
            try {
                action.run();
                result.complete(null);
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        };
        try {
            if (owned) publication.run();
            else scheduler.accept(publication);
        } catch (Throwable failure) {
            synchronized (this) {
                pending.remove(result);
            }
            result.completeExceptionally(failure);
        }
        return result.minimalCompletionStage().toCompletableFuture();
    }

    public void stop() {
        Set<CompletableFuture<Void>> cancelled;
        synchronized (this) {
            stopped = true;
            cancelled = Set.copyOf(pending);
            pending.clear();
        }
        cancelled.forEach(result -> result.completeExceptionally(stopping()));
    }

    private static CancellationException stopping() {
        return new CancellationException("Brewery owner publication stopped; reload durable state");
    }
}
