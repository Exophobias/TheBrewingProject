package dev.jsinco.brewery.bukkit.database.hydration;

import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.database.PersistenceSupplier;
import dev.jsinco.brewery.util.DecoderEncoder;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static dev.jsinco.brewery.bukkit.database.hydration.WorldBrewerySnapshot.*;

/** One read transaction includes every holder and its brews, including empty inventories. */
public record SqLiteWorldHydrationSession(Executor executor,
        PersistenceSupplier<Connection> connectionSupplier) implements WorldHydrationSession {

    @Override
    public CompletableFuture<WorldBrewerySnapshot> readWorld(UUID worldId) {
        return fetch(() -> {
            try (Connection connection = connectionSupplier.getUnchecked()) {
                connection.setAutoCommit(false);
                try {
                    Map<BreweryLocation, List<BrewRow>> barrelBrews = readBrews(connection, worldId, false);
                    Map<BreweryLocation, List<BrewRow>> distilleryBrews = readBrews(connection, worldId, true);
                    List<BarrelRow> barrels = new ArrayList<>();
                    try (var statement = connection.prepareStatement("SELECT * FROM barrels WHERE world_uuid = ?")) {
                        statement.setBytes(1, DecoderEncoder.asBytes(worldId));
                        try (var rows = statement.executeQuery()) {
                            while (rows.next()) {
                                StructureRow structure = structure(rows, worldId);
                                barrels.add(new BarrelRow(structure, rows.getString("barrel_type"), rows.getInt("size"),
                                        barrelBrews.getOrDefault(structure.unique(), List.of())));
                            }
                        }
                    }
                    List<DistilleryRow> distilleries = new ArrayList<>();
                    try (var statement = connection.prepareStatement("SELECT * FROM distilleries WHERE world_uuid = ?")) {
                        statement.setBytes(1, DecoderEncoder.asBytes(worldId));
                        try (var rows = statement.executeQuery()) {
                            while (rows.next()) {
                                StructureRow structure = structure(rows, worldId);
                                distilleries.add(new DistilleryRow(structure, rows.getLong("start_time"),
                                        distilleryBrews.getOrDefault(structure.unique(), List.of())));
                            }
                        }
                    }
                    List<CauldronRow> cauldrons = new ArrayList<>();
                    try (var statement = connection.prepareStatement("SELECT * FROM cauldrons WHERE world_uuid = ?")) {
                        statement.setBytes(1, DecoderEncoder.asBytes(worldId));
                        try (var rows = statement.executeQuery()) {
                            while (rows.next()) {
                                cauldrons.add(new CauldronRow(location(rows, "cauldron", worldId),
                                        rows.getString("cauldron_type"), rows.getString("brew")));
                            }
                        }
                    }
                    connection.commit();
                    return new WorldBrewerySnapshot(barrels, distilleries, cauldrons);
                } catch (SQLException | RuntimeException failure) {
                    try { connection.rollback(); } catch (SQLException rollback) { failure.addSuppressed(rollback); }
                    throw failure;
                }
            } catch (SQLException failure) {
                throw new PersistenceException(failure);
            }
        });
    }

    private static Map<BreweryLocation, List<BrewRow>> readBrews(Connection connection, UUID worldId,
                                                                 boolean distillery) throws SQLException {
        String table = distillery ? "distillery_brews" : "barrel_brews";
        Map<BreweryLocation, List<BrewRow>> brews = new HashMap<>();
        try (var statement = connection.prepareStatement("SELECT * FROM " + table + " WHERE world_uuid = ?")) {
            statement.setBytes(1, DecoderEncoder.asBytes(worldId));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    brews.computeIfAbsent(location(rows, "unique", worldId), ignored -> new ArrayList<>())
                            .add(new BrewRow(rows.getInt("pos"), distillery && rows.getBoolean("is_distillate"),
                                    rows.getString("brew")));
                }
            }
        }
        return brews;
    }

    private static StructureRow structure(ResultSet row, UUID worldId) throws SQLException {
        return new StructureRow(location(row, "origin", worldId), location(row, "unique", worldId),
                row.getString("transformation"), row.getString("format"));
    }

    private static BreweryLocation location(ResultSet row, String prefix, UUID worldId) throws SQLException {
        return new BreweryLocation(row.getInt(prefix + "_x"), row.getInt(prefix + "_y"),
                row.getInt(prefix + "_z"), worldId);
    }
}
