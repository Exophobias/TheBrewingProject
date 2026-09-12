package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.api.persistence.CauldronPersistenceSnapshot;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.database.PersistenceSupplier;
import dev.jsinco.brewery.database.UncheckedPersistenceException;
import dev.jsinco.brewery.util.DecoderEncoder;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/** Bounded SELECT-only transaction, after the captured original cauldron tails. */
public record SqLiteCauldronInspectionSession(Executor executor, PersistenceSupplier<Connection> connectionSupplier)
        implements CauldronInspectionSession {
    static final int MAX_TEXT_CHARS = 8 * 1024 * 1024;
    static final int MAX_TEXT_BYTES = 8 * 1024 * 1024;
    @Override public CompletableFuture<CauldronPersistenceSnapshot> inspect(List<BreweryLocation> requested,
            CauldronPersistenceOrder.Observation observation) {
        var keys = CauldronPersistenceSnapshot.validateKeys(requested);
        Objects.requireNonNull(observation);
        observation.requireKeys(keys);
        return observation.ready().thenApplyAsync(ignored -> {
            observation.requireCurrent();
            try (var connection = connectionSupplier.getUnchecked()) {
                connection.setAutoCommit(false);
                try {
                    var entries = new ArrayList<CauldronPersistenceSnapshot.Entry>();
                    var births = new HashSet<java.util.UUID>();
                    int budget = MAX_TEXT_CHARS;
                    long byteBudget = MAX_TEXT_BYTES;
                    for (var key : keys) {
                        try (var statement = connection.prepareStatement("SELECT brew,cauldron_type,length(CAST(brew AS BLOB)),length(CAST(cauldron_type AS BLOB)),birth_uuid,length(birth_uuid),typeof(birth_uuid) "
                                + "FROM cauldrons WHERE cauldron_x=? AND cauldron_y=? AND cauldron_z=? AND world_uuid=? LIMIT 2")) {
                            statement.setInt(1, key.x()); statement.setInt(2, key.y()); statement.setInt(3, key.z());
                            statement.setBytes(4, DecoderEncoder.asBytes(key.worldUuid()));
                            try (var rows = statement.executeQuery()) {
                                Optional<CauldronPersistenceSnapshot.Row> row = Optional.empty();
                                if (rows.next()) {
                                    if (rows.getLong(6) != 16 || !"blob".equals(rows.getString(7)))
                                        throw new SQLException("Invalid persisted cauldron birth");
                                    long length = rows.getLong(3);
                                    if (rows.wasNull() || length < 0 || length > byteBudget) throw new SQLException("Invalid cauldron brew byte length");
                                    long typeLength = rows.getLong(4);
                                    if (typeLength < 0 || typeLength > byteBudget - length) throw new SQLException("Cauldron byte budget exceeded");
                                    // SQLite length(TEXT) stops at NUL and counts codepoints. Bound
                                    // raw bytes before retrieving either potentially large string.
                                    byteBudget -= length + typeLength;
                                    Object brew = rows.getObject(1), type = rows.getObject(2);
                                    if (!(brew instanceof String text) || type != null && !(type instanceof String))
                                        throw new SQLException("Cauldron SQL text has an unsupported type");
                                    long characters = (long) text.length() + (type == null ? 0 : ((String) type).length());
                                    if (characters > budget) throw new SQLException("Cauldron text budget exceeded");
                                    budget -= (int) characters;
                                    var birth = DecoderEncoder.asUuid(rows.getBytes(5));
                                    if (!births.add(birth)) throw new SQLException("Duplicate persisted cauldron birth");
                                    row = Optional.of(new CauldronPersistenceSnapshot.Row(text, Optional.ofNullable((String) type), Optional.of(birth)));
                                    if (rows.next()) throw new SQLException("Duplicate cauldron rows at an exact key");
                                }
                                entries.add(new CauldronPersistenceSnapshot.Entry(key, row));
                            }
                        }
                    }
                    observation.requireCurrent();
                    connection.commit();
                    return new CauldronPersistenceSnapshot(entries);
                } catch (SQLException | RuntimeException | Error failure) {
                    try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                    throw failure;
                }
            } catch (SQLException failure) { throw new UncheckedPersistenceException(failure); }
        }, executor);
    }
}
