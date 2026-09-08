package dev.jsinco.brewery.bukkit.database.hydration;

import dev.jsinco.brewery.api.persistence.BreweryPersistenceSnapshot;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.database.PersistenceSupplier;
import dev.jsinco.brewery.util.DecoderEncoder;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import static dev.jsinco.brewery.api.persistence.BreweryPersistenceSnapshot.*;

/** SELECT-only transaction on the normal single-owner persistence executor. */
public record SqLiteExactStructureInspectionSession(Executor executor,
        PersistenceSupplier<Connection> connectionSupplier) implements ExactStructureInspectionSession {
    static final int MAX_BREW_ROWS = 2048;
    static final int MAX_TEXT_CHARS = 8 * 1024 * 1024;
    private static final String WHERE = " WHERE unique_x=? AND unique_y=? AND unique_z=? AND world_uuid=?";

    @Override public CompletableFuture<BreweryPersistenceSnapshot> inspect(List<BreweryLocation> requested) {
        List<BreweryLocation> keys = BreweryPersistenceSnapshot.validateKeys(requested);
        return fetch(() -> {
            try (Connection connection = connectionSupplier.getUnchecked()) {
                connection.setAutoCommit(false);
                try {
                    List<Entry> entries = new ArrayList<>();
                    int[] budget = {MAX_BREW_ROWS, MAX_TEXT_CHARS};
                    for (var key : keys) {
                        var distillery = readDistillery(connection, key);
                        var barrel = readBarrel(connection, key);
                        entries.add(new Entry(key, distillery, barrel, readBrews(connection, key, true, budget),
                                readBrews(connection, key, false, budget)));
                    }
                    connection.commit();
                    return new BreweryPersistenceSnapshot(entries);
                } catch (SQLException | RuntimeException | Error failure) {
                    try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                    // SELECT only: no writes can be committed by a pool's connection-state reset.
                    throw failure;
                }
            } catch (SQLException failure) { throw new PersistenceException(failure); }
        });
    }

    private static Optional<DistilleryRow> readDistillery(Connection c, BreweryLocation key) throws SQLException {
        try (var statement = c.prepareStatement("SELECT * FROM distilleries" + WHERE)) {
            bind(statement, key);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                var value = new DistilleryRow(structure(rows, key), integer(rows, "start_time"));
                if (rows.next()) throw new SQLException("Duplicate distillery parent rows");
                return Optional.of(value);
            }
        }
    }
    private static Optional<BarrelRow> readBarrel(Connection c, BreweryLocation key) throws SQLException {
        try (var statement = c.prepareStatement("SELECT * FROM barrels" + WHERE)) {
            bind(statement, key);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                var value = new BarrelRow(structure(rows, key), rows.getString("barrel_type"), Math.toIntExact(integer(rows, "size")));
                if (rows.next()) throw new SQLException("Duplicate barrel parent rows");
                return Optional.of(value);
            }
        }
    }
    private static List<BrewRow> readBrews(Connection c, BreweryLocation key, boolean distillery, int[] budget) throws SQLException {
        String table = distillery ? "distillery_brews" : "barrel_brews";
        try (var statement = c.prepareStatement("SELECT *,length(brew) AS brew_length FROM " + table + WHERE + " ORDER BY pos" + (distillery ? ",is_distillate" : ""))) {
            bind(statement, key);
            try (var rows = statement.executeQuery()) {
                List<BrewRow> result = new ArrayList<>();
                while (rows.next()) {
                    if (--budget[0] < 0) throw new SQLException("Exact inspection brew-row budget exceeded");
                    if (integer(rows, "brew_length") > budget[1]) throw new SQLException("Exact inspection text budget exceeded");
                    String brew = rows.getString("brew");
                    if (brew == null || brew.length() > budget[1]) throw new SQLException("Exact inspection text budget exceeded");
                    budget[1] -= brew.length();
                    int position = Math.toIntExact(integer(rows, "pos"));
                    long output = distillery ? integer(rows, "is_distillate") : 0;
                    if (position < 0 || output < 0 || output > 1) throw new SQLException("Invalid persisted brew slot");
                    result.add(new BrewRow(position, output == 1, brew));
                }
                return result;
            }
        }
    }
    private static StructureRow structure(ResultSet row, BreweryLocation key) throws SQLException {
        return new StructureRow(new BreweryLocation(Math.toIntExact(integer(row, "origin_x")),
                Math.toIntExact(integer(row, "origin_y")), Math.toIntExact(integer(row, "origin_z")), key.worldUuid()),
                key, row.getString("transformation"), row.getString("format"));
    }
    private static long integer(ResultSet rows, String column) throws SQLException {
        Object value = rows.getObject(column);
        if (!(value instanceof Integer) && !(value instanceof Long)) throw new SQLException("Invalid integer field: " + column);
        return ((Number) value).longValue();
    }
    private static void bind(PreparedStatement statement, BreweryLocation key) throws SQLException {
        statement.setInt(1, key.x()); statement.setInt(2, key.y()); statement.setInt(3, key.z());
        statement.setBytes(4, DecoderEncoder.asBytes(key.worldUuid()));
    }
}
