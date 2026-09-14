package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.api.persistence.*;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.bukkit.database.SessionTypes;
import dev.jsinco.brewery.database.sql.CauldronFixtureStorage;
import dev.jsinco.brewery.database.sql.SqlDatabase;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;

/** One provider lifetime. No native fixture creation or row deletion is admitted by this boundary. */
public final class CauldronFixtureCoordinator {
    private final SqlDatabase database;
    private final CauldronPersistenceOrder order;
    private final BooleanSupplier current;
    private final Predicate<BreweryLocation> runtimeAbsent;
    private final Consumer<Runnable> main;
    private final Map<UUID, Receipt> pending = new HashMap<>();

    public CauldronFixtureCoordinator(SqlDatabase database, CauldronPersistenceOrder order, BooleanSupplier current,
                                      Predicate<BreweryLocation> runtimeAbsent, Consumer<Runnable> main) {
        this.database = database; this.order = order; this.current = current;
        this.runtimeAbsent = runtimeAbsent; this.main = main;
    }
    /** Before listener registration/world hydration. Malformed owner metadata fails initialization. */
    public void initialize() throws dev.jsinco.brewery.database.PersistenceException, java.sql.SQLException {
        try (var connection = database.getConnection()) {
            for (var snapshot : CauldronFixtureStorage.readAll(connection))
                if (snapshot.phase() == CauldronFixtureSnapshot.Phase.RESERVED)
                    order.restoreFixtures(snapshot.request().runId(), snapshot.request().keys());
        }
    }
    public CauldronFixtureReceipt execute(CauldronFixtureRequest request, CauldronFixtureSession.Operation operation) {
        requireCurrent();
        var existing = pending.get(request.runId());
        if (existing != null && existing.state() != CauldronFixtureReceipt.State.PENDING) {
            pending.remove(request.runId(), existing); existing = null;
        }
        if (existing != null) {
            if (!existing.request.equals(request) || existing.operation != operation)
                throw new IllegalStateException("Original fixture operation is still pending");
            return existing;
        }
        if (pending.size() >= 8) throw new IllegalStateException("Fixture operations are at capacity");
        boolean newFence = false;
        if (operation != CauldronFixtureSession.Operation.INSPECT) {
            requireRuntimeAbsent(request);
            newFence = order.reserveFixtures(request.runId(), request.keys());
        }
        var receipt = new Receipt(request, operation, newFence);
        pending.put(request.runId(), receipt);
        try {
            var original = database.startSession(SessionTypes.CAULDRON_FIXTURE_SESSION_TYPE).execute(request, operation);
            original.whenComplete((snapshot, failure) -> {
                try { main.accept(() -> finish(receipt, snapshot, failure)); }
                catch (Throwable unavailable) { receipt.done.completeExceptionally(unavailable); }
            });
        } catch (Throwable failure) { finish(receipt, null, failure); }
        return receipt;
    }
    private void finish(Receipt receipt, CauldronFixtureSnapshot snapshot, Throwable failure) {
        try {
            requireCurrent();
            if (failure != null) {
                if (receipt.newFence && causedByRefusal(failure)
                        && receipt.request.keys().stream().allMatch(runtimeAbsent))
                    order.releaseFixtures(receipt.request.runId(), receipt.request.keys());
                receipt.done.completeExceptionally(failure); return;
            }
            if (!receipt.request.equals(snapshot.request())) throw new IllegalStateException("Fixture response identity changed");
            if (receipt.operation != CauldronFixtureSession.Operation.INSPECT) requireRuntimeAbsent(receipt.request);
            if (receipt.operation == CauldronFixtureSession.Operation.CLOSE) {
                if (snapshot.phase() != CauldronFixtureSnapshot.Phase.CLOSED
                        || snapshot.lanes().stream().anyMatch(CauldronFixtureSnapshot.Lane::persistedCauldronPresent))
                    throw new IllegalStateException("Fixture retirement is not proved");
                order.releaseFixtures(receipt.request.runId(), receipt.request.keys());
            }
            receipt.observedRevision = order.revision();
            receipt.done.complete(snapshot);
        } catch (Throwable unavailable) { receipt.done.completeExceptionally(unavailable); }
        finally { pending.remove(receipt.request.runId(), receipt); }
    }
    private static boolean causedByRefusal(Throwable error) {
        while (error != null) {
            if (error instanceof CauldronFixtureStorage.Refusal) return true;
            // An unresolved rollback wraps the refusal: never unwrap that into a clean refusal.
            if (error instanceof java.sql.SQLException && !(error instanceof CauldronFixtureStorage.Refusal)) return false;
            error = error.getCause();
        }
        return false;
    }
    private void requireCurrent() { if (!current.getAsBoolean()) throw new IllegalStateException("Fixture provider or owning thread changed"); }
    private void requireRuntimeAbsent(CauldronFixtureRequest request) {
        if (!request.keys().stream().allMatch(runtimeAbsent)) throw new IllegalStateException("Fixture coordinate contains a runtime owner");
    }
    private final class Receipt implements CauldronFixtureReceipt {
        private final CauldronFixtureRequest request;
        private final CauldronFixtureSession.Operation operation;
        private final boolean newFence;
        private final CompletableFuture<CauldronFixtureSnapshot> done = new CompletableFuture<>();
        private long observedRevision;
        private Receipt(CauldronFixtureRequest request, CauldronFixtureSession.Operation operation, boolean newFence) {
            this.request = request; this.operation = operation; this.newFence = newFence;
        }
        public State state() { return !done.isDone() ? State.PENDING : done.isCompletedExceptionally() ? State.FAILED : State.OBSERVED; }
        public Optional<CauldronFixtureSnapshot> observation() { return state() == State.OBSERVED ? Optional.of(done.join()) : Optional.empty(); }
        public CompletionStage<CauldronFixtureSnapshot> completion() { return done.minimalCompletionStage(); }
        public boolean current() {
            return state() == State.OBSERVED && current.getAsBoolean() && order.revision() == observedRevision
                    && request.keys().stream().allMatch(runtimeAbsent)
                    && (done.join().phase() == CauldronFixtureSnapshot.Phase.RESERVED
                        ? order.fixtureOwned(request.runId(), request.keys())
                        : request.keys().stream().noneMatch(order::fixtureBlocked));
        }
    }
}
