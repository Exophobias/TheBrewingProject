package dev.jsinco.brewery.database.sql;

import dev.jsinco.brewery.api.persistence.CauldronFixtureRequest;
import dev.jsinco.brewery.api.persistence.CauldronFixtureSnapshot;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.util.DecoderEncoder;
import java.io.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.*;

/** Exact durable empty-key reservations. This store never writes or deletes a cauldron row. */
public final class CauldronFixtureStorage {
    public static final int MAX_RUNS = 128;
    private CauldronFixtureStorage() { }
    /** A refusal before any commit, with an acknowledged rollback. Other failures retain the runtime fence. */
    public static final class Refusal extends SQLException {
        public Refusal(String message) { super(message); }
    }
    public static CauldronFixtureSnapshot reserve(Connection connection, CauldronFixtureRequest request) throws SQLException {
        return transaction(connection, () -> {
            var existing = find(connection, request.runId());
            if (existing.isPresent()) {
                requireRequest(existing.get(), request);
                if (existing.get().phase() == CauldronFixtureSnapshot.Phase.CLOSED) throw new Refusal("Fixture run was already closed");
                requireEmpty(existing.get());
                return existing.get();
            }
            try (var statement = connection.prepareStatement("SELECT count(*) FROM cauldron_fixture_runs"); var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getInt(1) >= MAX_RUNS) throw new Refusal("Fixture reservation history is at capacity");
            }
            for (var lane : request.lanes()) {
                if (present(connection, lane.location())) throw new Refusal("Fixture coordinate contains a persisted cauldron");
                try (var statement = connection.prepareStatement("SELECT 1 FROM cauldron_fixture_lanes WHERE active=1 AND ((world_uuid=? AND x=? AND y=? AND z=?) OR actor_uuid=?) LIMIT 1")) {
                    bindKey(statement, lane.location(), 1); statement.setBytes(5, bytes(lane.actorId()));
                    try (var rows = statement.executeQuery()) { if (rows.next()) throw new Refusal("Fixture coordinate or actor is already reserved"); }
                }
            }
            try (var statement = connection.prepareStatement("INSERT INTO cauldron_fixture_runs(run_uuid,request,phase) VALUES(?,?,'RESERVED')")) {
                statement.setBytes(1, bytes(request.runId())); statement.setBytes(2, encode(request)); one(statement.executeUpdate());
            }
            try (var statement = connection.prepareStatement("INSERT INTO cauldron_fixture_lanes(run_uuid,lane,world_uuid,x,y,z,actor_uuid,birth_uuid,active) VALUES(?,?,?,?,?,?,?,?,1)")) {
                for (int i = 0; i < request.lanes().size(); i++) {
                    var lane = request.lanes().get(i);
                    statement.setBytes(1, bytes(request.runId())); statement.setInt(2, i);
                    bindKey(statement, lane.location(), 3); statement.setBytes(7, bytes(lane.actorId()));
                    statement.setBytes(8, bytes(UUID.randomUUID())); one(statement.executeUpdate());
                }
            }
            return require(connection, request);
        });
    }
    public static CauldronFixtureSnapshot inspect(Connection connection, CauldronFixtureRequest request) throws SQLException {
        return transaction(connection, () -> require(connection, request));
    }
    public static CauldronFixtureSnapshot close(Connection connection, CauldronFixtureRequest request) throws SQLException {
        return transaction(connection, () -> {
            var snapshot = require(connection, request);
            requireEmpty(snapshot);
            if (snapshot.phase() == CauldronFixtureSnapshot.Phase.CLOSED) return snapshot;
            try (var statement = connection.prepareStatement("UPDATE cauldron_fixture_lanes SET active=0 WHERE run_uuid=? AND active=1")) {
                statement.setBytes(1, bytes(request.runId()));
                if (statement.executeUpdate() != request.lanes().size()) throw new SQLException("Fixture lane retirement count changed");
            }
            try (var statement = connection.prepareStatement("UPDATE cauldron_fixture_runs SET phase='CLOSED' WHERE run_uuid=? AND request=? AND phase='RESERVED'")) {
                statement.setBytes(1, bytes(request.runId())); statement.setBytes(2, encode(request)); one(statement.executeUpdate());
            }
            return require(connection, request);
        });
    }
    public static List<CauldronFixtureSnapshot> readAll(Connection connection) throws SQLException {
        List<UUID> runs = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT run_uuid FROM cauldron_fixture_runs LIMIT " + (MAX_RUNS + 1)); var rows = statement.executeQuery()) {
            while (rows.next()) runs.add(uuid(rows.getBytes(1)));
        }
        if (runs.size() > MAX_RUNS) throw new SQLException("Fixture history exceeds its bound");
        List<CauldronFixtureSnapshot> result = new ArrayList<>();
        int expectedLanes = 0;
        for (UUID run : runs) {
            var snapshot = find(connection, run).orElseThrow(() -> new SQLException("Fixture run disappeared"));
            expectedLanes += snapshot.lanes().size(); result.add(snapshot);
        }
        try (var statement = connection.prepareStatement("SELECT count(*) FROM cauldron_fixture_lanes"); var rows = statement.executeQuery()) {
            if (!rows.next() || rows.getLong(1) != expectedLanes) throw new SQLException("Orphan or missing fixture lanes");
        }
        return List.copyOf(result);
    }
    private static CauldronFixtureSnapshot require(Connection connection, CauldronFixtureRequest request) throws SQLException {
        var snapshot = find(connection, request.runId()).orElseThrow(() -> new Refusal("No exact durable fixture reservation"));
        requireRequest(snapshot, request); return snapshot;
    }
    private static void requireRequest(CauldronFixtureSnapshot snapshot, CauldronFixtureRequest request) throws Refusal {
        if (!snapshot.request().equals(request)) throw new Refusal("Fixture request or capability changed");
    }
    private static void requireEmpty(CauldronFixtureSnapshot snapshot) throws Refusal {
        if (snapshot.lanes().stream().anyMatch(CauldronFixtureSnapshot.Lane::persistedCauldronPresent))
            throw new Refusal("Fixture contains an unowned cauldron; preserve quarantine");
    }
    private static Optional<CauldronFixtureSnapshot> find(Connection connection, UUID run) throws SQLException {
        CauldronFixtureRequest request;
        CauldronFixtureSnapshot.Phase phase;
        try (var statement = connection.prepareStatement("SELECT request,phase,length(request) FROM cauldron_fixture_runs WHERE run_uuid=?")) {
            statement.setBytes(1, bytes(run));
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) return Optional.empty();
                if (rows.getInt(3) > 2048) throw new SQLException("Oversized fixture request");
                request = decode(rows.getBytes(1));
                try { phase = CauldronFixtureSnapshot.Phase.valueOf(rows.getString(2)); }
                catch (RuntimeException badPhase) { throw new SQLException("Malformed fixture phase", badPhase); }
                if (!request.runId().equals(run)) throw new SQLException("Fixture run identity changed");
            }
        }
        List<CauldronFixtureSnapshot.Lane> lanes = new ArrayList<>();
        try (var statement = connection.prepareStatement("SELECT * FROM cauldron_fixture_lanes WHERE run_uuid=? ORDER BY lane LIMIT 21")) {
            statement.setBytes(1, bytes(run));
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    int ordinal = lanes.size();
                    if (ordinal >= request.lanes().size() || rows.getInt("lane") != ordinal)
                        throw new SQLException("Fixture lane ordering changed");
                    var expected = request.lanes().get(ordinal);
                    var key = new BreweryLocation(rows.getInt("x"), rows.getInt("y"), rows.getInt("z"), uuid(rows.getBytes("world_uuid")));
                    if (!key.equals(expected.location()) || !uuid(rows.getBytes("actor_uuid")).equals(expected.actorId())
                            || rows.getInt("active") != (phase == CauldronFixtureSnapshot.Phase.RESERVED ? 1 : 0))
                        throw new SQLException("Fixture lane identity or state changed");
                    lanes.add(new CauldronFixtureSnapshot.Lane(uuid(rows.getBytes("birth_uuid")), present(connection, key)));
                }
            }
        }
        try { return Optional.of(new CauldronFixtureSnapshot(request, phase, lanes)); }
        catch (RuntimeException malformed) { throw new SQLException("Malformed fixture lanes", malformed); }
    }
    private static boolean present(Connection connection, BreweryLocation key) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM cauldrons WHERE world_uuid=? AND cauldron_x=? AND cauldron_y=? AND cauldron_z=?")) {
            bindKey(statement, key, 1);
            try (var rows = statement.executeQuery()) { return rows.next(); }
        }
    }
    private static void bindKey(java.sql.PreparedStatement statement, BreweryLocation key, int first) throws SQLException {
        statement.setBytes(first, bytes(key.worldUuid())); statement.setInt(first + 1, key.x());
        statement.setInt(first + 2, key.y()); statement.setInt(first + 3, key.z());
    }
    private static void one(int count) throws SQLException { if (count != 1) throw new SQLException("Fixture write count changed"); }
    private static byte[] bytes(UUID id) { return DecoderEncoder.asBytes(id); }
    private static UUID uuid(byte[] bytes) throws SQLException {
        if (bytes == null || bytes.length != 16) throw new SQLException("Malformed fixture UUID");
        return DecoderEncoder.asUuid(bytes);
    }
    private static byte[] encode(CauldronFixtureRequest request) {
        try {
            var bytes = new ByteArrayOutputStream(); var output = new DataOutputStream(bytes);
            output.writeInt(1); writeUuid(output, request.runId()); writeUuid(output, request.capability());
            output.writeInt(request.lanes().size());
            for (var lane : request.lanes()) {
                writeUuid(output, lane.location().worldUuid()); output.writeInt(lane.location().x());
                output.writeInt(lane.location().y()); output.writeInt(lane.location().z()); writeUuid(output, lane.actorId());
            }
            return bytes.toByteArray();
        } catch (IOException impossible) { throw new UncheckedIOException(impossible); }
    }
    private static CauldronFixtureRequest decode(byte[] bytes) throws SQLException {
        if (bytes == null || bytes.length > 2048) throw new SQLException("Malformed fixture request");
        try {
            var input = new DataInputStream(new ByteArrayInputStream(bytes));
            if (input.readInt() != 1) throw new IOException("Unknown fixture protocol");
            UUID run = readUuid(input), capability = readUuid(input);
            int count = input.readInt(); if (count < 1 || count > 20) throw new IOException("Fixture lane limit");
            List<CauldronFixtureRequest.Lane> lanes = new ArrayList<>();
            for (int i = 0; i < count; i++) {
                UUID world = readUuid(input); int x = input.readInt(), y = input.readInt(), z = input.readInt();
                lanes.add(new CauldronFixtureRequest.Lane(new BreweryLocation(x, y, z, world), readUuid(input)));
            }
            var request = new CauldronFixtureRequest(run, capability, lanes);
            if (input.available() != 0 || !Arrays.equals(bytes, encode(request))) throw new IOException("Noncanonical fixture request");
            return request;
        } catch (IOException | RuntimeException malformed) { throw new SQLException("Malformed fixture request", malformed); }
    }
    private static void writeUuid(DataOutputStream output, UUID id) throws IOException { output.writeLong(id.getMostSignificantBits()); output.writeLong(id.getLeastSignificantBits()); }
    private static UUID readUuid(DataInputStream input) throws IOException { return new UUID(input.readLong(), input.readLong()); }
    @FunctionalInterface private interface Work<T> { T run() throws SQLException; }
    private static <T> T transaction(Connection connection, Work<T> action) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Fixture session requires its own transaction");
        connection.setAutoCommit(false); boolean resolved = false;
        try {
            T result = action.run(); connection.commit(); resolved = true; return result;
        } catch (SQLException | RuntimeException | Error failure) {
            try { connection.rollback(); resolved = true; }
            catch (SQLException rollback) {
                failure.addSuppressed(rollback);
                try { connection.close(); } catch (SQLException close) { failure.addSuppressed(close); }
                throw new SQLException("Fixture transaction outcome is unresolved", failure);
            }
            throw failure;
        } finally { if (resolved) connection.setAutoCommit(true); }
    }
}
