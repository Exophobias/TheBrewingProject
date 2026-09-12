package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.api.vector.BreweryLocation;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/** Runtime ordering of exact durable ordinary births. Birth identity grants no fixture or serving ownership. */
public final class CauldronPersistenceOrder {
    public static final int MAX_COORDINATES = 65_536;
    public static final int MAX_PENDING_PER_COORDINATE = 256;
    public static final int MAX_INSPECTIONS = 8;
    public enum Write { INSERT, UPDATE, DELETE }
    public record Status(int coordinates, int pendingWrites, int failedCoordinates, int pausedWorlds, boolean stopped) { }
    private final Map<BreweryLocation, Lane> lanes = new HashMap<>();
    private final Map<UUID, Long> epochs = new HashMap<>();
    private final Set<UUID> paused = new HashSet<>();
    private boolean stopped;
    private long revision;
    private int inspections;

    /** Captures original admitted tails; cancellation of a consumer future never drains them. */
    public synchronized Observation observe(List<BreweryLocation> requested) {
        var keys = dev.jsinco.brewery.api.persistence.CauldronPersistenceSnapshot.validateKeys(requested);
        UUID world = keys.getFirst().worldUuid();
        if (stopped || paused.contains(world) || inspections >= MAX_INSPECTIONS)
            throw new IllegalStateException("Cauldron observation is unavailable or at capacity");
        var tails = keys.stream().map(lanes::get).filter(Objects::nonNull)
                .map(lane -> lane.tail).toArray(CompletableFuture[]::new);
        inspections++;
        return new Observation(keys, world, revision, CompletableFuture.allOf(tails));
    }

    public final class Observation {
        private final UUID world;
        private final List<BreweryLocation> keys;
        private final long capturedRevision;
        private final CompletableFuture<Void> ready;
        private boolean released;
        private Observation(List<BreweryLocation> keys, UUID world, long capturedRevision, CompletableFuture<Void> ready) {
            this.keys = keys; this.world = world; this.capturedRevision = capturedRevision; this.ready = ready;
        }
        public void requireKeys(List<BreweryLocation> requested) {
            if (!keys.equals(requested)) throw new IllegalArgumentException("Observation keys changed");
        }
        public CompletableFuture<Void> ready() { return ready.copy(); }
        public boolean current() { synchronized (CauldronPersistenceOrder.this) {
            return !stopped && !paused.contains(world) && capturedRevision == revision;
        } }
        public void requireCurrent() {
            if (!current()) throw new IllegalStateException("Cauldron observation has a stale owner revision");
        }
        public void release() { synchronized (CauldronPersistenceOrder.this) {
            if (!released) { released = true; inspections--; }
        } }
    }

    public final class Owner {
        private final BreweryLocation position;
        private final long epoch;
        private final UUID birthUuid;
        private final Hydration hydration;
        private boolean revoked, terminal;
        private Throwable failure;
        private CompletableFuture<Void> deletion;
        private Owner(BreweryLocation position, long epoch, UUID birthUuid, Hydration hydration) {
            this.position = position; this.epoch = epoch; this.birthUuid = Objects.requireNonNull(birthUuid);
            this.hydration = hydration;
        }
        private CauldronPersistenceOrder order() { return CauldronPersistenceOrder.this; }
        public BreweryLocation position() { return position; }
        public UUID birthUuid() { return birthUuid; }
        public boolean writable() { synchronized (CauldronPersistenceOrder.this) { return available(this); } }
        public boolean failed() { synchronized (CauldronPersistenceOrder.this) { return failure != null; } }
        public CompletableFuture<Void> barrier() { synchronized (CauldronPersistenceOrder.this) {
            Lane lane = lanes.get(position);
            if (failure != null) return CompletableFuture.failedFuture(failure);
            return lane != null && lane.owner == this ? lane.tail.copy() : CompletableFuture.completedFuture(null);
        } }
    }

    private static final class Lane {
        Owner owner;
        CompletableFuture<Void> tail = CompletableFuture.completedFuture(null);
        int pending;
        Throwable failure;
        Lane(Owner owner) { this.owner = owner; }
    }

    public synchronized Owner newOwner(BreweryLocation position) {
        Objects.requireNonNull(position);
        if (stopped) throw new IllegalStateException("Cauldron persistence is stopping");
        epochs.putIfAbsent(position.worldUuid(), 0L);
        return new Owner(position, epoch(position.worldUuid()), UUID.randomUUID(), null);
    }

    private long epoch(UUID world) { return epochs.getOrDefault(world, 0L); }
    private boolean available(Owner owner) {
        if (owner.order() != this || stopped || owner.revoked || owner.terminal || owner.failure != null
                || paused.contains(owner.position.worldUuid()) || owner.epoch != epoch(owner.position.worldUuid())) return false;
        Lane lane = lanes.get(owner.position);
        if (owner.hydration != null && (lane == null || lane.owner != owner)) return false;
        return lane == null || lane.failure == null && lane.pending < MAX_PENDING_PER_COORDINATE
                && (lane.owner == owner || lane.owner.terminal);
    }

    /** Capture runs immediately. Only its immutable SQL operation may wait for predecessor/readiness. */
    public CompletableFuture<Void> admit(Owner owner, Write kind,
            Function<CompletableFuture<Void>, CompletableFuture<Void>> capture) {
        Objects.requireNonNull(owner); Objects.requireNonNull(kind); Objects.requireNonNull(capture);
        final Lane lane;
        final CompletableFuture<Void> previous;
        final CompletableFuture<Void> admitted = new CompletableFuture<>();
        synchronized (this) {
            if (owner.order() != this) throw new IllegalStateException("Cauldron owner belongs to another database lifecycle");
            if (kind == Write.DELETE && owner.deletion != null) return owner.deletion.copy();
            if (!available(owner)) throw new IllegalStateException("Cauldron source is stale, retired, failed or unavailable");
            Lane existing = lanes.get(owner.position);
            if (existing == null) {
                if (kind != Write.INSERT) throw new IllegalStateException("Cauldron source has no admitted insert or exact hydration");
                if (lanes.size() >= MAX_COORDINATES) throw new IllegalStateException("Cauldron persistence coordinate limit reached");
                existing = new Lane(owner); lanes.put(owner.position, existing);
            } else if (existing.owner != owner) {
                if (kind != Write.INSERT || !existing.owner.terminal)
                    throw new IllegalStateException("A replacement needs the previous owner's admitted terminal deletion");
                existing.owner = owner;
            } else if (kind == Write.INSERT) {
                throw new IllegalStateException("Cauldron owner was already inserted or hydrated");
            }
            lane = existing;
            previous = lane.tail;
            lane.tail = admitted;
            lane.pending++;
            revision = Math.incrementExact(revision);
            if (kind == Write.DELETE) { owner.terminal = true; owner.deletion = admitted; }
        }
        try {
            Objects.requireNonNull(capture.apply(previous.copy()), "No cauldron persistence receipt")
                    .whenComplete((ignored, failure) -> finish(owner, lane, admitted, failure));
        } catch (RuntimeException | Error failure) {
            finish(owner, lane, admitted, failure);
        }
        return admitted.copy();
    }

    private void finish(Owner owner, Lane lane, CompletableFuture<Void> admitted, Throwable failure) {
        while (failure instanceof CompletionException && failure.getCause() != null) failure = failure.getCause();
        synchronized (this) {
            lane.pending--;
            if (failure != null) {
                if (owner.failure == null) owner.failure = failure;
                if (lane.failure == null) lane.failure = failure;
                if (lane.owner.failure == null) lane.owner.failure = failure;
            }
        }
        if (failure == null) admitted.complete(null); else admitted.completeExceptionally(failure);
        synchronized (this) {
            // Complete the full admitted future before making an idle lane invisible to a drain.
            // The old Owner stays terminal forever after this coordinate is discarded.
            if (lane.pending == 0 && lane.failure == null && lane.owner.terminal && lane.tail == admitted)
                lanes.remove(owner.position, lane);
        }
    }

    /** Issued by the current world hydration lifecycle, before its ordered SQL read. */
    public synchronized Hydration beginHydration(UUID world, BooleanSupplier lifecycleCurrent) {
        invalidate(world);
        var permit = new Hydration(world, epoch(world), Objects.requireNonNull(lifecycleCurrent));
        permit.drained = drain(world);
        return permit;
    }

    public synchronized void invalidate(UUID world) {
        revision = Math.incrementExact(revision);
        epochs.put(world, Math.incrementExact(epoch(world)));
        paused.add(world);
        lanes.forEach((key, lane) -> { if (key.worldUuid().equals(world)) lane.owner.revoked = true; });
    }
    public synchronized void invalidateAll() {
        // A cold absent-key inspection need not have any runtime owner/epoch yet.
        revision = Math.incrementExact(revision);
        Set<UUID> worlds = new HashSet<>(epochs.keySet());
        lanes.keySet().forEach(key -> worlds.add(key.worldUuid()));
        worlds.forEach(this::invalidate);
    }
    public synchronized void stop() { stopped = true; invalidateAll(); }

    private CompletableFuture<Void> drain(UUID world) {
        return CompletableFuture.allOf(lanes.entrySet().stream().filter(entry -> entry.getKey().worldUuid().equals(world))
                .map(entry -> entry.getValue().tail).toArray(CompletableFuture[]::new));
    }

    public final class Hydration {
        private final UUID world;
        private final long epoch;
        private final BooleanSupplier lifecycleCurrent;
        private CompletableFuture<Void> drained;
        private boolean adopted;
        private Hydration(UUID world, long epoch, BooleanSupplier lifecycleCurrent) {
            this.world = world; this.epoch = epoch; this.lifecycleCurrent = lifecycleCurrent;
        }
        public CompletableFuture<Void> drained() { return drained.copy(); }
        public CauldronPersistenceOrder order() { return CauldronPersistenceOrder.this; }
        /** Restore an exact persisted identity only inside this drained, unpublished lifecycle. */
        public Owner restoreOwner(BreweryLocation position, UUID birthUuid) {
            synchronized (CauldronPersistenceOrder.this) {
                requireCurrent();
                if (adopted || !world.equals(Objects.requireNonNull(position).worldUuid()))
                    throw new IllegalStateException("Cauldron birth is outside its unpublished hydration");
                return new Owner(position, epoch, birthUuid, this);
            }
        }
        private void requireCurrent() {
            if (stopped || epoch != CauldronPersistenceOrder.this.epoch(world) || !paused.contains(world)
                    || !lifecycleCurrent.getAsBoolean() || !drained.isDone() || drained.isCompletedExceptionally())
                throw new IllegalStateException("Cauldron hydration lacks its exact drained lifecycle authority");
        }
        /** Called with exact SQL-created holders, before publishing any of those holders. */
        public void adopt(List<Owner> owners) {
            synchronized (CauldronPersistenceOrder.this) {
                requireCurrent();
                if (adopted) throw new IllegalStateException("Cauldron hydration already adopted");
                Set<BreweryLocation> keys = new HashSet<>();
                Set<UUID> births = new HashSet<>();
                lanes.forEach((key, lane) -> { if (!key.worldUuid().equals(world)) births.add(lane.owner.birthUuid); });
                for (Owner owner : owners) {
                    if (owner.order() != CauldronPersistenceOrder.this || !world.equals(owner.position.worldUuid()) || owner.epoch != epoch || owner.revoked
                            || owner.hydration != this || owner.terminal || owner.failure != null
                            || !keys.add(owner.position) || !births.add(owner.birthUuid))
                        throw new IllegalStateException("Invalid hydrated cauldron owner capability");
                }
                long elsewhere = lanes.keySet().stream().filter(key -> !key.worldUuid().equals(world)).count();
                if (elsewhere + owners.size() > MAX_COORDINATES) throw new IllegalStateException("Cauldron persistence coordinate limit reached");
                lanes.entrySet().removeIf(entry -> entry.getKey().worldUuid().equals(world));
                owners.forEach(owner -> lanes.put(owner.position, new Lane(owner)));
                adopted = true;
            }
        }
        /** Publication already succeeded while the separate world lifecycle gate is still held. */
        public void published() {
            synchronized (CauldronPersistenceOrder.this) {
                requireCurrent();
                if (!adopted) throw new IllegalStateException("Cauldron hydration was not adopted");
                paused.remove(world);
            }
        }
    }

    public synchronized boolean unresolved(UUID world) {
        return lanes.entrySet().stream().anyMatch(entry -> (world == null || entry.getKey().worldUuid().equals(world))
                && (entry.getValue().pending != 0 || !entry.getValue().tail.isDone() || entry.getValue().failure != null));
    }
    public synchronized Status status() {
        return new Status(lanes.size(), lanes.values().stream().mapToInt(lane -> lane.pending).sum(),
                (int) lanes.values().stream().filter(lane -> lane.failure != null).count(), paused.size(), stopped);
    }
}
