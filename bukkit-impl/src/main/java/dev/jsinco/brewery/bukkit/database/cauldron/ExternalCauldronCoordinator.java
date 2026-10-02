package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.api.persistence.ExternalCauldronLease;
import dev.jsinco.brewery.api.persistence.ExternalCauldronLeaseRequest;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.bukkit.api.event.structure.ExternalCauldronDestroyedEvent;
import dev.jsinco.brewery.bukkit.database.SessionTypes;
import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.database.sql.ExternalCauldronStorage;
import dev.jsinco.brewery.database.sql.SqlDatabase;
import java.sql.SQLException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;

/** Production ownership, with exact durable generations and fail-closed pending/uncertain outcomes. */
public final class ExternalCauldronCoordinator {
    private final SqlDatabase database;
    private final CauldronPersistenceOrder order;
    private final BooleanSupplier current;
    private final Predicate<BreweryLocation> runtimeAbsent, emptyPhysical, cauldronPhysical, loaded;
    private final Consumer<Runnable> main;
    private final Consumer<ExternalCauldronDestroyedEvent> destruction;
    private final Map<UUID, Entry> entries = new HashMap<>();

    public ExternalCauldronCoordinator(SqlDatabase database, CauldronPersistenceOrder order, BooleanSupplier current,
            Predicate<BreweryLocation> runtimeAbsent, Predicate<BreweryLocation> emptyPhysical,
            Predicate<BreweryLocation> cauldronPhysical, Predicate<BreweryLocation> loaded,
            Consumer<Runnable> main, Consumer<ExternalCauldronDestroyedEvent> destruction) {
        this.database = database; this.order = order; this.current = current; this.runtimeAbsent = runtimeAbsent;
        this.emptyPhysical = emptyPhysical; this.cauldronPhysical = cauldronPhysical; this.loaded = loaded;
        this.main = main; this.destruction = destruction;
    }
    /** Restore every active fence, including unloaded worlds, before native hydration/input. */
    public void initialize() throws PersistenceException, SQLException {
        try (var connection = database.getConnection()) {
            for (var lease : ExternalCauldronStorage.active(connection)) {
                order.reserveExternal(lease.request());
                var entry = new Entry(lease.request()); entry.observed = lease; entries.put(lease.request().leaseId(), entry);
            }
        }
    }
    public CompletableFuture<ExternalCauldronLease> acquire(ExternalCauldronLeaseRequest request) {
        requireCurrent(); Objects.requireNonNull(request);
        Entry existing = entries.get(request.leaseId());
        if (existing != null) {
            requireExact(existing, request);
            if (existing.invalid) return CompletableFuture.failedFuture(new IllegalStateException("External generation is retired or retiring"));
            if (existing.pending != null) return existing.pending.copy();
            if (existing.observed != null && isCurrent(existing.observed)) return CompletableFuture.completedFuture(existing.observed);
        }
        if (!runtimeAbsent.test(request.location()) || !emptyPhysical.test(request.location()))
            return CompletableFuture.failedFuture(new IllegalStateException("Acquisition requires a loaded empty cauldron without native contents"));
        boolean newFence = order.reserveExternal(request);
        var entry = existing == null ? new Entry(request) : existing;
        entries.put(request.leaseId(), entry);
        var done = new CompletableFuture<ExternalCauldronLease>(); entry.pending = done;
        try {
            var original = database.startSession(SessionTypes.EXTERNAL_CAULDRON_SESSION_TYPE).acquire(request);
            entry.storageTail = original;
            original.whenComplete((lease, failure) -> onMain(done, () -> {
                requireCurrent();
                if (failure != null) {
                    if (newFence && !entry.invalid && cleanRefusal(failure)) {
                        order.releaseExternal(request); entries.remove(request.leaseId(), entry);
                    }
                    done.completeExceptionally(failure); return;
                }
                requireExact(lease, request); entry.observed = lease;
                if (entry.invalid || !order.externalOwned(request) || !cauldronPhysical.test(request.location())
                        || newFence && !emptyPhysical.test(request.location())) {
                    if (!entry.invalid && loaded.test(request.location())) destroy(request.location());
                    done.completeExceptionally(new IllegalStateException("Cauldron was removed before acquisition publication")); return;
                }
                done.complete(lease);
            }));
        } catch (Throwable failure) { done.completeExceptionally(failure); }
        done.whenComplete((ignored, failure) -> { if (entry.pending == done) entry.pending = null; });
        return done.copy();
    }
    public CompletableFuture<Optional<ExternalCauldronLease>> inspect(ExternalCauldronLeaseRequest request) {
        requireCurrent(); Objects.requireNonNull(request);
        var entry = entries.get(request.leaseId());
        if (entry != null) {
            requireExact(entry, request);
            if (!entry.invalid && loaded.test(request.location()) && !cauldronPhysical.test(request.location())) destroy(request.location());
        }
        var done = new CompletableFuture<Optional<ExternalCauldronLease>>();
        CompletableFuture<?> barrier = entry == null ? CompletableFuture.completedFuture(null) : entry.storageTail;
        try {
            database.startSession(SessionTypes.EXTERNAL_CAULDRON_SESSION_TYPE).inspectAfter(request, barrier)
                .whenComplete((lease, failure) -> onMain(done, () -> {
                    requireCurrent();
                    if (failure != null) { done.completeExceptionally(failure); return; }
                    if (entry != null && lease.isPresent()) {
                        requireExact(lease.get(), request); entry.observed = lease.get();
                        if (lease.get().state() != ExternalCauldronLease.State.ACTIVE && order.externalOwned(request)) {
                            order.releaseExternal(request); entries.remove(request.leaseId(), entry);
                        }
                    }
                    done.complete(lease);
                }));
        } catch (Throwable failure) { done.completeExceptionally(failure); }
        return done.copy();
    }
    public CompletableFuture<ExternalCauldronLease> release(ExternalCauldronLeaseRequest request) {
        requireCurrent();
        if (!emptyPhysical.test(request.location()) || !runtimeAbsent.test(request.location()))
            return CompletableFuture.failedFuture(new IllegalStateException("Release requires an empty native-free cauldron"));
        var entry = entries.get(request.leaseId());
        if (entry != null) { requireExact(entry, request); entry.invalid = true; }
        return retire(request, entry, ExternalCauldronLease.State.RELEASED);
    }
    /** Invalidate authority immediately, then queue retirement behind original admitted SQL. */
    public void destroy(BreweryLocation location) {
        requireCurrent();
        var request = order.externalAt(location).orElse(null);
        var entry = request == null ? null : entries.get(request.leaseId());
        if (entry == null || entry.invalid) return;
        entry.invalid = true;
        var retirement = retire(entry.request, entry, ExternalCauldronLease.State.DESTROYED);
        destruction.accept(new ExternalCauldronDestroyedEvent(new ExternalCauldronLease(entry.request, ExternalCauldronLease.State.ACTIVE), retirement.minimalCompletionStage()));
    }
    private CompletableFuture<ExternalCauldronLease> retire(ExternalCauldronLeaseRequest request, Entry entry, ExternalCauldronLease.State state) {
        var done = new CompletableFuture<ExternalCauldronLease>();
        var barrier = entry == null ? CompletableFuture.completedFuture(null) : entry.storageTail;
        try {
            var original = database.startSession(SessionTypes.EXTERNAL_CAULDRON_SESSION_TYPE).retireAfter(request, state, barrier);
            if (entry != null) entry.storageTail = original;
            original.whenComplete((lease, failure) -> onMain(done, () -> {
                requireCurrent();
                if (failure != null) { done.completeExceptionally(failure); return; }
                requireExact(lease, request);
                if (lease.state() == ExternalCauldronLease.State.ACTIVE) throw new IllegalStateException("Retirement failed to become terminal");
                if (order.externalOwned(request)) order.releaseExternal(request);
                if (entry != null) entries.remove(request.leaseId(), entry);
                done.complete(lease);
            }));
        } catch (Throwable failure) { done.completeExceptionally(failure); }
        return done.copy();
    }
    public boolean isCurrent(ExternalCauldronLease lease) {
        if (!current.getAsBoolean() || lease == null || lease.state() != ExternalCauldronLease.State.ACTIVE) return false;
        var entry = entries.get(lease.request().leaseId());
        return entry != null && !entry.invalid && lease.equals(entry.observed) && order.externalAvailable(lease.request())
                && runtimeAbsent.test(lease.request().location()) && cauldronPhysical.test(lease.request().location());
    }
    public void reconcileLoaded(UUID world, int chunkX, int chunkZ) {
        requireCurrent();
        for (var entry : List.copyOf(entries.values())) {
            var key = entry.request.location();
            if (key.worldUuid().equals(world) && (chunkX == Integer.MIN_VALUE || (key.x() >> 4) == chunkX && (key.z() >> 4) == chunkZ)
                    && loaded.test(key) && !cauldronPhysical.test(key)) destroy(key);
        }
    }
    private void requireCurrent() { if (!current.getAsBoolean()) throw new IllegalStateException("External cauldron provider or owning thread changed"); }
    private static void requireExact(Entry entry, ExternalCauldronLeaseRequest request) {
        if (!entry.request.equals(request)) throw new IllegalStateException("External generation identity changed");
    }
    private static void requireExact(ExternalCauldronLease lease, ExternalCauldronLeaseRequest request) {
        if (lease == null || !lease.request().equals(request)) throw new IllegalStateException("External storage response identity changed");
    }
    private <T> void onMain(CompletableFuture<T> done, Runnable work) {
        try { main.accept(() -> { try { work.run(); } catch (Throwable failure) { done.completeExceptionally(failure); } }); }
        catch (Throwable failure) { done.completeExceptionally(failure); }
    }
    private static boolean cleanRefusal(Throwable failure) {
        while (failure != null) {
            if (failure instanceof ExternalCauldronStorage.Refusal) return true;
            if (failure instanceof SQLException) return false;
            failure = failure.getCause();
        }
        return false;
    }
    private static final class Entry {
        final ExternalCauldronLeaseRequest request;
        ExternalCauldronLease observed;
        boolean invalid;
        CompletableFuture<?> storageTail = CompletableFuture.completedFuture(null);
        CompletableFuture<ExternalCauldronLease> pending;
        Entry(ExternalCauldronLeaseRequest request) { this.request = request; }
    }
}
