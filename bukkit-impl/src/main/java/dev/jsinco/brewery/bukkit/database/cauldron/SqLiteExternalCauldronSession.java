package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.api.persistence.ExternalCauldronLease;
import dev.jsinco.brewery.api.persistence.ExternalCauldronLeaseRequest;
import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.database.PersistenceSupplier;
import dev.jsinco.brewery.database.sql.ExternalCauldronStorage;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public record SqLiteExternalCauldronSession(Executor executor, PersistenceSupplier<Connection> connectionSupplier)
        implements ExternalCauldronSession {
    public CompletableFuture<ExternalCauldronLease> acquire(ExternalCauldronLeaseRequest request) {
        return query(connection -> ExternalCauldronStorage.acquire(connection, request));
    }
    public CompletableFuture<Optional<ExternalCauldronLease>> inspect(ExternalCauldronLeaseRequest request) {
        return query(connection -> ExternalCauldronStorage.inspect(connection, request));
    }
    public CompletableFuture<ExternalCauldronLease> retire(ExternalCauldronLeaseRequest request, ExternalCauldronLease.State state) {
        return query(connection -> ExternalCauldronStorage.retire(connection, request, state));
    }
    public CompletableFuture<Optional<ExternalCauldronLease>> inspectAfter(ExternalCauldronLeaseRequest request, CompletableFuture<?> dependency) {
        return dependency.handle((ignored, failure) -> null).thenCompose(ignored -> inspect(request));
    }
    public CompletableFuture<ExternalCauldronLease> retireAfter(ExternalCauldronLeaseRequest request, ExternalCauldronLease.State state, CompletableFuture<?> dependency) {
        return dependency.handle((ignored, failure) -> null).thenCompose(ignored -> retire(request, state));
    }
    private <T> CompletableFuture<T> query(Query<T> query) {
        return fetch(() -> {
            try (var connection = connectionSupplier.get()) { return query.run(connection); }
            catch (SQLException failure) { throw new PersistenceException(failure); }
        });
    }
    @FunctionalInterface private interface Query<T> { T run(Connection connection) throws SQLException; }
}
