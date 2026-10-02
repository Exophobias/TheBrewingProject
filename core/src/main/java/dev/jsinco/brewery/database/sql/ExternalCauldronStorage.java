package dev.jsinco.brewery.database.sql;

import dev.jsinco.brewery.api.persistence.ExternalCauldronLease;
import dev.jsinco.brewery.api.persistence.ExternalCauldronLeaseRequest;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.util.DecoderEncoder;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.*;

/** Production coordinate generations. Terminal rows are retained so old requests cannot resurrect stock. */
public final class ExternalCauldronStorage {
    public static final int MAX_ACTIVE = 65_536;
    public static final int MAX_HISTORY = 262_144;
    static final Map<String, String> OBJECTS = schema();
    private ExternalCauldronStorage() { }
    public static final class Refusal extends SQLException {
        public Refusal(String message) { super(message); }
    }
    private static Map<String, String> schema() {
        var result = new LinkedHashMap<String, String>();
        result.put("external_cauldron_leases", "CREATE TABLE external_cauldron_leases (lease_uuid BLOB PRIMARY KEY NOT NULL CHECK(typeof(lease_uuid)='blob' AND length(lease_uuid)=16), owner TEXT NOT NULL CHECK(typeof(owner)='text' AND length(owner) BETWEEN 1 AND 64), world_uuid BLOB NOT NULL CHECK(typeof(world_uuid)='blob' AND length(world_uuid)=16), x INTEGER NOT NULL CHECK(typeof(x)='integer' AND x BETWEEN -2147483648 AND 2147483647), y INTEGER NOT NULL CHECK(typeof(y)='integer' AND y BETWEEN -2147483648 AND 2147483647), z INTEGER NOT NULL CHECK(typeof(z)='integer' AND z BETWEEN -2147483648 AND 2147483647), phase TEXT NOT NULL CHECK(typeof(phase)='text' AND phase IN ('ACTIVE','RELEASED','DESTROYED')))");
        result.put("external_cauldron_active_key", "CREATE UNIQUE INDEX external_cauldron_active_key ON external_cauldron_leases(world_uuid,x,y,z) WHERE phase='ACTIVE'");
        result.put("brew_consumable_signing_key", "CREATE TABLE brew_consumable_signing_key (singleton INTEGER PRIMARY KEY CHECK(singleton=0), secret BLOB NOT NULL CHECK(typeof(secret)='blob' AND length(secret)=32))");
        for (String operation : new String[] {"INSERT", "UPDATE", "DELETE"}) {
            String name = "external_cauldron_" + operation.toLowerCase(Locale.ROOT);
            String old = "EXISTS(SELECT 1 FROM external_cauldron_leases WHERE phase='ACTIVE' AND world_uuid=OLD.world_uuid AND x=OLD.cauldron_x AND y=OLD.cauldron_y AND z=OLD.cauldron_z)";
            String fresh = old.replace("OLD.", "NEW.");
            String condition = operation.equals("INSERT") ? fresh : operation.equals("DELETE") ? old : "(" + old + " OR " + fresh + ")";
            result.put(name, "CREATE TRIGGER " + name + " BEFORE " + operation + " ON cauldrons WHEN " + condition + " BEGIN SELECT RAISE(ABORT, 'External cauldron ownership excludes native brewing'); END");
        }
        for (String operation : new String[] {"INSERT", "UPDATE"}) {
            String name = "external_cauldron_fixture_" + operation.toLowerCase(Locale.ROOT);
            result.put(name, "CREATE TRIGGER " + name + " BEFORE " + operation + " ON cauldron_fixture_lanes WHEN NEW.active=1 AND EXISTS(SELECT 1 FROM external_cauldron_leases WHERE phase='ACTIVE' AND world_uuid=NEW.world_uuid AND x=NEW.x AND y=NEW.y AND z=NEW.z) BEGIN SELECT RAISE(ABORT, 'External cauldron ownership excludes fixtures'); END");
        }
        return Collections.unmodifiableMap(result);
    }
    public static void rejectPartial(Connection connection) throws SQLException {
        for (String name : OBJECTS.keySet()) try (var statement = connection.prepareStatement("SELECT 1 FROM sqlite_schema WHERE name=? COLLATE NOCASE")) {
            statement.setString(1, name);
            try (var rows = statement.executeQuery()) { if (rows.next()) throw new SQLException("Partial external cauldron/proof schema"); }
        }
    }
    public static void create(Connection connection) throws SQLException {
        rejectPartial(connection);
        for (String sql : OBJECTS.values()) try (var statement = connection.prepareStatement(sql)) { statement.execute(); }
        byte[] secret = new byte[32]; new SecureRandom().nextBytes(secret);
        try (var statement = connection.prepareStatement("INSERT INTO brew_consumable_signing_key(singleton,secret) VALUES(0,?)")) {
            statement.setBytes(1, secret); one(statement.executeUpdate());
        }
    }
    public static void validate(Connection connection) throws SQLException {
        validateSchema(connection); signingKey(connection);
        try (var statement = connection.prepareStatement("SELECT count(*) FROM external_cauldron_leases"); var rows = statement.executeQuery()) {
            if (!rows.next() || rows.getLong(1) > MAX_HISTORY) throw new SQLException("External lease history exceeds its bound");
        }
        try (var statement = connection.prepareStatement("SELECT * FROM external_cauldron_leases"); var rows = statement.executeQuery()) {
            int active = 0;
            while (rows.next()) {
                var lease = decode(rows);
                if (lease.state() == ExternalCauldronLease.State.ACTIVE) {
                    if (++active > MAX_ACTIVE) throw new SQLException("External active lease capacity exceeded");
                    requireNoNativeContents(connection, lease.request().location());
                }
            }
        }
    }
    private static void validateSchema(Connection connection) throws SQLException {
        for (var entry : OBJECTS.entrySet()) try (var statement = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
            statement.setString(1, entry.getKey());
            try (var rows = statement.executeQuery()) {
                if (!rows.next() || !normalize(entry.getValue()).equals(normalize(rows.getString(1))))
                    throw new SQLException("Invalid external cauldron/proof schema: " + entry.getKey());
            }
        }
    }
    private static String normalize(String sql) { return sql == null ? "" : sql.strip().replaceFirst(";\\s*$", "").replaceAll("\\s+", " "); }
    public static byte[] signingKey(Connection connection) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT singleton,secret,typeof(singleton),typeof(secret) FROM brew_consumable_signing_key"); var rows = statement.executeQuery()) {
            if (!rows.next() || rows.getLong(1) != 0 || !"integer".equals(rows.getString(3)) || !"blob".equals(rows.getString(4)))
                throw new SQLException("Missing or malformed consumable signing identity");
            byte[] secret = rows.getBytes(2);
            if (secret == null || secret.length != 32 || rows.next()) throw new SQLException("Invalid consumable signing identity");
            return secret.clone();
        }
    }
    public static List<ExternalCauldronLease> active(Connection connection) throws SQLException {
        validateSchema(connection);
        var result = new ArrayList<ExternalCauldronLease>();
        try (var statement = connection.prepareStatement("SELECT * FROM external_cauldron_leases WHERE phase='ACTIVE' LIMIT " + (MAX_ACTIVE + 1)); var rows = statement.executeQuery()) {
            while (rows.next()) result.add(decode(rows));
        }
        if (result.size() > MAX_ACTIVE) throw new SQLException("External active lease capacity exceeded");
        return List.copyOf(result);
    }
    public static Optional<ExternalCauldronLease> inspect(Connection connection, ExternalCauldronLeaseRequest request) throws SQLException {
        validateSchema(connection);
        var found = find(connection, request.leaseId());
        if (found.isPresent()) requireExact(found.get(), request);
        return found;
    }
    public static ExternalCauldronLease acquire(Connection connection, ExternalCauldronLeaseRequest request) throws SQLException {
        return transaction(connection, () -> {
            validateSchema(connection);
            var previous = inspect(connection, request);
            if (previous.isPresent()) {
                if (previous.get().state() != ExternalCauldronLease.State.ACTIVE) throw new Refusal("External generation is already retired");
                requireNoNativeContents(connection, request.location());
                return previous.get();
            }
            requireNoNativeContents(connection, request.location());
            try (var statement = connection.prepareStatement("SELECT count(*),sum(phase='ACTIVE') FROM external_cauldron_leases"); var rows = statement.executeQuery()) {
                if (!rows.next() || rows.getLong(1) >= MAX_HISTORY || rows.getLong(2) >= MAX_ACTIVE)
                    throw new Refusal("External cauldron lease capacity reached");
            }
            try (var statement = connection.prepareStatement("SELECT 1 FROM external_cauldron_leases WHERE phase='ACTIVE' AND world_uuid=? AND x=? AND y=? AND z=?")) {
                bindKey(statement, request.location(), 1);
                try (var rows = statement.executeQuery()) { if (rows.next()) throw new Refusal("Coordinate is externally owned"); }
            }
            try (var statement = connection.prepareStatement("INSERT INTO external_cauldron_leases VALUES(?,?,?,?,?,?,'ACTIVE')")) {
                statement.setBytes(1, DecoderEncoder.asBytes(request.leaseId())); statement.setString(2, request.owner());
                bindKey(statement, request.location(), 3); one(statement.executeUpdate());
            }
            return inspect(connection, request).orElseThrow(() -> new SQLException("External lease publication failed"));
        });
    }
    public static ExternalCauldronLease retire(Connection connection, ExternalCauldronLeaseRequest request, ExternalCauldronLease.State terminal) throws SQLException {
        if (terminal == ExternalCauldronLease.State.ACTIVE) throw new IllegalArgumentException("Retirement must be terminal");
        return transaction(connection, () -> {
            var previous = inspect(connection, request).orElseThrow(() -> new Refusal("Unknown external generation"));
            if (previous.state() != ExternalCauldronLease.State.ACTIVE) return previous;
            try (var statement = connection.prepareStatement("UPDATE external_cauldron_leases SET phase=? WHERE lease_uuid=? AND phase='ACTIVE'")) {
                statement.setString(1, terminal.name()); statement.setBytes(2, DecoderEncoder.asBytes(request.leaseId())); one(statement.executeUpdate());
            }
            return inspect(connection, request).orElseThrow(() -> new SQLException("External lease retirement disappeared"));
        });
    }
    private static Optional<ExternalCauldronLease> find(Connection connection, UUID id) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT * FROM external_cauldron_leases WHERE lease_uuid=?")) {
            statement.setBytes(1, DecoderEncoder.asBytes(id));
            try (var rows = statement.executeQuery()) { return rows.next() ? Optional.of(decode(rows)) : Optional.empty(); }
        }
    }
    private static void requireExact(ExternalCauldronLease lease, ExternalCauldronLeaseRequest request) throws Refusal {
        if (!lease.request().equals(request)) throw new Refusal("External generation identity changed");
    }
    private static ExternalCauldronLease decode(java.sql.ResultSet rows) throws SQLException {
        try {
            var key = new BreweryLocation(rows.getInt("x"), rows.getInt("y"), rows.getInt("z"), uuid(rows.getBytes("world_uuid")));
            return new ExternalCauldronLease(new ExternalCauldronLeaseRequest(uuid(rows.getBytes("lease_uuid")), rows.getString("owner"), key),
                    ExternalCauldronLease.State.valueOf(rows.getString("phase")));
        } catch (RuntimeException malformed) { throw new SQLException("Malformed external cauldron lease", malformed); }
    }
    private static UUID uuid(byte[] value) throws SQLException {
        if (value == null || value.length != 16) throw new SQLException("Malformed external generation UUID");
        return DecoderEncoder.asUuid(value);
    }
    private static void requireNoNativeContents(Connection connection, BreweryLocation key) throws SQLException {
        try (var statement = connection.prepareStatement("SELECT 1 FROM cauldrons WHERE world_uuid=? AND cauldron_x=? AND cauldron_y=? AND cauldron_z=?")) {
            bindKey(statement, key, 1);
            try (var rows = statement.executeQuery()) { if (rows.next()) throw new Refusal("Coordinate contains native persisted contents"); }
        }
        try (var statement = connection.prepareStatement("SELECT 1 FROM cauldron_fixture_lanes WHERE active=1 AND world_uuid=? AND x=? AND y=? AND z=?")) {
            bindKey(statement, key, 1);
            try (var rows = statement.executeQuery()) { if (rows.next()) throw new Refusal("Coordinate is fixture-quarantined"); }
        }
    }
    private static void bindKey(PreparedStatement statement, BreweryLocation key, int offset) throws SQLException {
        statement.setBytes(offset, DecoderEncoder.asBytes(key.worldUuid())); statement.setInt(offset + 1, key.x());
        statement.setInt(offset + 2, key.y()); statement.setInt(offset + 3, key.z());
    }
    private static void one(int count) throws SQLException { if (count != 1) throw new SQLException("External cauldron write count changed"); }
    @FunctionalInterface private interface Work<T> { T run() throws SQLException; }
    private static <T> T transaction(Connection connection, Work<T> action) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("External cauldron session requires its own transaction");
        connection.setAutoCommit(false); boolean resolved = false;
        try {
            T result = action.run(); connection.commit(); resolved = true; return result;
        } catch (SQLException | RuntimeException | Error failure) {
            try { connection.rollback(); resolved = true; }
            catch (SQLException rollback) {
                failure.addSuppressed(rollback);
                try { connection.close(); } catch (SQLException close) { failure.addSuppressed(close); }
                throw new SQLException("External cauldron transaction outcome is unresolved", failure);
            }
            throw failure;
        } finally { if (resolved) connection.setAutoCommit(true); }
    }
}
