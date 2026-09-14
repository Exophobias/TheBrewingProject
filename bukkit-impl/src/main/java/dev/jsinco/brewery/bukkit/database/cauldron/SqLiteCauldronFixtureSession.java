package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.api.persistence.CauldronFixtureRequest;
import dev.jsinco.brewery.api.persistence.CauldronFixtureSnapshot;
import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.database.PersistenceSupplier;
import dev.jsinco.brewery.database.sql.CauldronFixtureStorage;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

public record SqLiteCauldronFixtureSession(Executor executor, PersistenceSupplier<Connection> connectionSupplier)
        implements CauldronFixtureSession {
    @Override public CompletableFuture<CauldronFixtureSnapshot> execute(CauldronFixtureRequest request, Operation operation) {
        return fetch(() -> {
            try (var connection = connectionSupplier.get()) {
                return switch (operation) {
                    case RESERVE -> CauldronFixtureStorage.reserve(connection, request);
                    case INSPECT -> CauldronFixtureStorage.inspect(connection, request);
                    case CLOSE -> CauldronFixtureStorage.close(connection, request);
                };
            } catch (SQLException failure) { throw new PersistenceException(failure); }
        });
    }
}
