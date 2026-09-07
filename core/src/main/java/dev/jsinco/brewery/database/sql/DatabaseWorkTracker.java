package dev.jsinco.brewery.database.sql;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Supplier;

/** Tracks both composed session results and queued tasks, including tasks queued by completions. */
final class DatabaseWorkTracker implements Executor {
    private final Executor delegate;
    private final List<CompletableFuture<Void>> drains = new ArrayList<>();
    private int pending;
    private boolean closed;

    DatabaseWorkTracker(Executor delegate) { this.delegate = delegate; }

    CompletableFuture<?> admit(Supplier<CompletableFuture<?>> operation) {
        synchronized (this) {
            if (closed) return CompletableFuture.failedFuture(new RejectedExecutionException("Database is closing"));
            pending++;
        }
        try {
            CompletableFuture<?> result = operation.get();
            result.whenComplete((ignored, failure) -> finish());
            return result;
        } catch (Throwable failure) {
            finish();
            throw failure;
        }
    }

    @Override
    public void execute(Runnable command) {
        // Already-admitted session operations may still enqueue their child tasks during close.
        synchronized (this) { pending++; }
        try {
            delegate.execute(() -> {
                try { command.run(); } finally { finish(); }
            });
        } catch (RuntimeException failure) {
            finish();
            throw failure;
        }
    }

    synchronized CompletableFuture<Void> drain() {
        if (pending == 0) return CompletableFuture.completedFuture(null);
        CompletableFuture<Void> drain = new CompletableFuture<>();
        drains.add(drain);
        return drain;
    }

    synchronized CompletableFuture<Void> closeAdmission() {
        closed = true;
        return drain();
    }

    private void finish() {
        List<CompletableFuture<Void>> ready;
        synchronized (this) {
            pending--;
            if (pending != 0) return;
            ready = List.copyOf(drains);
            drains.clear();
        }
        // Completing futures can invoke arbitrary callers; never do that while holding the tracker lock.
        ready.forEach(future -> future.complete(null));
    }
}
