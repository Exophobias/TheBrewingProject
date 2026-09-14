package dev.jsinco.brewery.database.sql;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashMap;
import java.util.Map;

/** Additive schema 5. Every object is published with the database version in the caller's transaction. */
final class CauldronFixtureSchema {
    private CauldronFixtureSchema() { }
    static final Map<String, String> OBJECTS = objects();
    private static Map<String, String> objects() {
        var result = new LinkedHashMap<String, String>();
        result.put("cauldron_fixture_runs", "CREATE TABLE cauldron_fixture_runs (run_uuid BLOB PRIMARY KEY NOT NULL CHECK(typeof(run_uuid)='blob' AND length(run_uuid)=16), request BLOB NOT NULL CHECK(typeof(request)='blob' AND length(request) BETWEEN 1 AND 2048), phase TEXT NOT NULL CHECK(phase IN ('RESERVED','CLOSED')))");
        result.put("cauldron_fixture_lanes", "CREATE TABLE cauldron_fixture_lanes (run_uuid BLOB NOT NULL REFERENCES cauldron_fixture_runs(run_uuid), lane INTEGER NOT NULL CHECK(lane BETWEEN 0 AND 19), world_uuid BLOB NOT NULL CHECK(typeof(world_uuid)='blob' AND length(world_uuid)=16), x INTEGER NOT NULL, y INTEGER NOT NULL, z INTEGER NOT NULL, actor_uuid BLOB NOT NULL CHECK(typeof(actor_uuid)='blob' AND length(actor_uuid)=16), birth_uuid BLOB NOT NULL UNIQUE CHECK(typeof(birth_uuid)='blob' AND length(birth_uuid)=16), active INTEGER NOT NULL CHECK(active IN (0,1)), PRIMARY KEY(run_uuid,lane))");
        result.put("cauldron_fixture_keys", "CREATE UNIQUE INDEX cauldron_fixture_keys ON cauldron_fixture_lanes(world_uuid,x,y,z) WHERE active=1");
        result.put("cauldron_fixture_actors", "CREATE UNIQUE INDEX cauldron_fixture_actors ON cauldron_fixture_lanes(actor_uuid) WHERE active=1");
        for (String operation : new String[] {"INSERT", "UPDATE", "DELETE"}) {
            String name = "cauldron_fixture_" + operation.toLowerCase(java.util.Locale.ROOT);
            String old = "EXISTS(SELECT 1 FROM cauldron_fixture_lanes WHERE active=1 AND world_uuid=OLD.world_uuid AND x=OLD.cauldron_x AND y=OLD.cauldron_y AND z=OLD.cauldron_z)";
            String fresh = old.replace("OLD.", "NEW.");
            String condition = operation.equals("INSERT") ? fresh : operation.equals("DELETE") ? old : "(" + old + " OR " + fresh + ")";
            result.put(name, "CREATE TRIGGER " + name + " BEFORE " + operation + " ON cauldrons WHEN " + condition + " BEGIN SELECT RAISE(ABORT, 'Cauldron fixture coordinate is quarantined'); END");
        }
        return java.util.Collections.unmodifiableMap(result);
    }
    static void rejectPartial(Connection connection) throws SQLException {
        for (String name : OBJECTS.keySet()) {
            try (var statement = connection.prepareStatement("SELECT 1 FROM sqlite_schema WHERE name=? COLLATE NOCASE")) {
                statement.setString(1, name);
                try (var rows = statement.executeQuery()) { if (rows.next()) throw new SQLException("Partial cauldron fixture schema"); }
            }
        }
    }
    static void create(Connection connection) throws SQLException {
        rejectPartial(connection);
        for (String sql : OBJECTS.values()) try (var statement = connection.prepareStatement(sql)) { statement.execute(); }
    }
    static void validate(Connection connection) throws SQLException {
        for (var entry : OBJECTS.entrySet()) {
            try (var statement = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name=?")) {
                statement.setString(1, entry.getKey());
                try (var rows = statement.executeQuery()) {
                    if (!rows.next() || !normalize(entry.getValue()).equals(normalize(rows.getString(1))))
                        throw new SQLException("Invalid cauldron fixture schema: " + entry.getKey());
                }
            }
        }
        CauldronFixtureStorage.readAll(connection);
    }
    private static String normalize(String sql) { return sql == null ? "" : sql.strip().replaceFirst(";\\s*$", "").replaceAll("\\s+", " "); }
}
