package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.api.breweries.Cauldron;
import dev.jsinco.brewery.api.persistence.CauldronPersistenceReceipt;
import dev.jsinco.brewery.api.persistence.CauldronPersistenceSnapshot;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.TheBrewingProjectApi;
import dev.jsinco.brewery.bukkit.breweries.BukkitCauldron;
import dev.jsinco.brewery.bukkit.database.SessionTypes;
import dev.jsinco.brewery.bukkit.listener.ListenerUtil;
import dev.jsinco.brewery.database.sql.SqlDatabase;
import org.bukkit.Bukkit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Optional exact-key observation seam; it creates no fixtures and has no cold deletion authority. */
public final class CauldronPersistenceAccess {
    private CauldronPersistenceAccess() { }

    public static CauldronPersistenceReceipt inspect(TheBrewingProject provider, List<BreweryLocation> requested) {
        var keys = CauldronPersistenceSnapshot.validateKeys(requested);
        SqlDatabase database = requireProvider(provider);
        var observation = provider.getCauldronPersistenceOrder().observe(keys);
        return issue(provider, database, keys, CauldronPersistenceReceipt.Kind.INSPECTION,
                CompletableFuture.completedFuture(null), observation);
    }

    public static CauldronPersistenceReceipt retire(TheBrewingProject provider, Cauldron requested) {
        SqlDatabase database = requireProvider(provider);
        if (!(requested instanceof BukkitCauldron holder)) throw new IllegalArgumentException("Expected exact native cauldron holder");
        holder.persistenceOwner(provider.getCauldronPersistenceOrder());
        var keys = List.of(holder.position());
        // Hold one read slot across external teardown. Release and recapture synchronously on the
        // same owning thread after DELETE admission, so capacity cannot disappear after mutation.
        var reservation = provider.getCauldronPersistenceOrder().observe(keys);
        final CompletableFuture<Void> original;
        try { original = ListenerUtil.retireCauldron(provider, holder); }
        catch (Exception failure) { throw new IllegalStateException("Cauldron retirement admission refused", failure); }
        finally { reservation.release(); }
        var observation = provider.getCauldronPersistenceOrder().observe(keys);
        return issue(provider, database, keys, CauldronPersistenceReceipt.Kind.RETIREMENT, original, observation);
    }

    private static CauldronPersistenceReceipt issue(TheBrewingProject provider, SqlDatabase database,
            List<BreweryLocation> keys, CauldronPersistenceReceipt.Kind kind, CompletableFuture<Void> original,
            CauldronPersistenceOrder.Observation observation) {
        var receipt = new Receipt(provider, database, keys, kind, observation, original);
        try {
            var session = database.startSession(SessionTypes.CAULDRON_INSPECTION_SESSION_TYPE);
            original.thenCompose(ignored -> session.inspect(keys, observation)).whenComplete((snapshot, failure) -> {
                observation.release();
                try {
                    if (failure != null) { receipt.done.completeExceptionally(failure); return; }
                    if (!receipt.providerCurrent() || !observation.current()) {
                        receipt.done.completeExceptionally(new IllegalStateException("Cauldron observation owner changed")); return;
                    }
                    if (kind == CauldronPersistenceReceipt.Kind.RETIREMENT
                            && snapshot.entries().stream().anyMatch(entry -> entry.row().isPresent())) {
                        receipt.done.completeExceptionally(new IllegalStateException("Original deletion did not leave absent cauldron SQL")); return;
                    }
                    receipt.done.complete(snapshot);
                } catch (Throwable callbackFailure) {
                    receipt.done.completeExceptionally(callbackFailure);
                }
            });
        } catch (Exception failure) {
            // An original DELETE may already be queued. Do not discard its acknowledgment or
            // release its observer slot until the accepted work has actually finished.
            original.whenComplete((ignored, originalFailure) -> {
                observation.release();
                if (originalFailure != null) failure.addSuppressed(originalFailure);
                receipt.done.completeExceptionally(failure);
            });
        }
        return receipt;
    }

    private static SqlDatabase requireProvider(TheBrewingProject provider) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Cauldron receipt admission requires the primary server thread");
        SqlDatabase database = provider.getDatabase();
        if (!provider.isEnabled() || database == null || TheBrewingProject.getInstance() != provider
                || Bukkit.getServicesManager().load(TheBrewingProjectApi.class) != provider)
            throw new IllegalStateException("Cauldron persistence provider is unavailable");
        return database;
    }

    private static final class Receipt implements CauldronPersistenceReceipt {
        private final TheBrewingProject provider;
        private final SqlDatabase database;
        private final List<BreweryLocation> keys;
        private final Kind kind;
        private final CauldronPersistenceOrder.Observation observation;
        private final CompletableFuture<Void> original;
        private final CompletableFuture<CauldronPersistenceSnapshot> done = new CompletableFuture<>();
        private Receipt(TheBrewingProject provider, SqlDatabase database, List<BreweryLocation> keys,
                        Kind kind, CauldronPersistenceOrder.Observation observation, CompletableFuture<Void> original) {
            this.provider = provider; this.database = database; this.keys = List.copyOf(keys);
            this.kind = kind; this.observation = observation;
            this.original = original;
        }
        private boolean providerCurrent() {
            return provider.isEnabled() && provider.getDatabase() == database && TheBrewingProject.getInstance() == provider
                    && Bukkit.getServicesManager().load(TheBrewingProjectApi.class) == provider;
        }
        public Kind kind() { return kind; }
        public List<BreweryLocation> keys() { return keys; }
        public State state() { return !done.isDone() ? State.PENDING : done.isCompletedExceptionally() ? State.FAILED : State.OBSERVED; }
        public boolean deletionAcknowledged() {
            return kind == Kind.RETIREMENT && original.isDone() && !original.isCompletedExceptionally();
        }
        public Optional<CauldronPersistenceSnapshot> observation() {
            return state() == State.OBSERVED ? Optional.of(done.join()) : Optional.empty();
        }
        public CompletionStage<CauldronPersistenceSnapshot> completion() { return done.minimalCompletionStage(); }
        public boolean current() {
            if (!Bukkit.isPrimaryThread() || state() != State.OBSERVED || !providerCurrent() || !observation.current()) return false;
            return kind != Kind.RETIREMENT || keys.stream().allMatch(key ->
                    provider.getBreweryRegistry().getActiveSinglePositionStructure(key).isEmpty());
        }
    }
}
