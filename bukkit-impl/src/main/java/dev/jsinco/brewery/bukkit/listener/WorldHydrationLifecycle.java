package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.bukkit.AtomicMutationGate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

/** Invalidates generations before holder removal, even while an unload still exposes the old World. */
final class WorldHydrationLifecycle {
    private final AtomicMutationGate gate;
    private final Map<UUID, Load> current = new HashMap<>();
    private boolean stopped;

    WorldHydrationLifecycle(AtomicMutationGate gate) {
        this.gate = gate;
    }

    synchronized Load begin(UUID worldId) {
        if (stopped) throw new IllegalStateException("World hydration is stopped");
        invalidate(worldId);
        Load load = new Load(worldId, gate.beginHydration());
        current.put(worldId, load);
        return load;
    }

    synchronized boolean isCurrent(Load load) {
        return !stopped && current.get(load.worldId) == load && !load.result.isDone();
    }

    synchronized void publish(Load load, Runnable publication) {
        if (!isCurrent(load)) return;
        try {
            publication.run();
            load.lease.close();
            load.result.complete(null);
        } catch (Throwable failure) {
            fail(load, failure);
        }
    }

    synchronized void fail(Load load, Throwable failure) {
        if (isCurrent(load)) {
            // Keep reservations closed on incomplete hydration until an explicit retry/unload.
            load.result.completeExceptionally(failure);
        }
    }

    synchronized void invalidate(UUID worldId) {
        Load old = current.remove(worldId);
        if (old != null) {
            old.lease.close();
            old.result.completeExceptionally(new CancellationException("World hydration generation replaced"));
        }
    }

    synchronized void invalidateAll() {
        for (UUID worldId : current.keySet().toArray(UUID[]::new)) invalidate(worldId);
    }

    synchronized void stop() {
        stopped = true;
        gate.stop();
        invalidateAll();
    }

    static final class Load {
        private final UUID worldId;
        private final AtomicMutationGate.Hydration lease;
        private final CompletableFuture<Void> result = new CompletableFuture<>();

        private Load(UUID worldId, AtomicMutationGate.Hydration lease) {
            this.worldId = worldId;
            this.lease = lease;
        }

        CompletableFuture<Void> result() { return result; }
    }
}
