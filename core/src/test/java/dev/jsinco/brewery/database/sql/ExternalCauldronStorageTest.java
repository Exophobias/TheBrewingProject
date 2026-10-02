package dev.jsinco.brewery.database.sql;

import dev.jsinco.brewery.api.persistence.*;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.util.DecoderEncoder;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExternalCauldronStorageTest {
    @TempDir Path directory;
    private final BreweryLocation key = new BreweryLocation(1, 64, 3, UUID.randomUUID());
    private Connection open() throws SQLException { return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("brewery.db")); }
    private ExternalCauldronLeaseRequest request() { return new ExternalCauldronLeaseRequest(UUID.randomUUID(), "patriamforging", key); }
    private void initialize(Connection connection) throws SQLException {
        var database = new SqlDatabase(DatabaseDriver.SQLITE);
        try { database.createTables(connection); } finally { database.close().join(); }
    }
    private void sql(Connection connection, String sql) throws SQLException { try (var statement = connection.createStatement()) { statement.execute(sql); } }
    private void insert(Connection connection, BreweryLocation location) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO cauldrons(world_uuid,cauldron_x,cauldron_y,cauldron_z,brew,birth_uuid) VALUES(?,?,?,?,'[]',?)")) {
            statement.setBytes(1, DecoderEncoder.asBytes(location.worldUuid())); statement.setInt(2, location.x());
            statement.setInt(3, location.y()); statement.setInt(4, location.z()); statement.setBytes(5, DecoderEncoder.asBytes(UUID.randomUUID()));
            assertEquals(1, statement.executeUpdate());
        }
    }
    @Test void exactGenerationPersistsAndTerminalIdentityCannotResurrect() throws Exception {
        var request = request(); byte[] signing;
        try (var connection = open()) {
            initialize(connection); signing = ExternalCauldronStorage.signingKey(connection);
            var lease = ExternalCauldronStorage.acquire(connection, request);
            assertEquals(lease, ExternalCauldronStorage.acquire(connection, request));
            assertThrows(ExternalCauldronStorage.Refusal.class, () -> ExternalCauldronStorage.acquire(connection, request()));
            var impostor = new ExternalCauldronLeaseRequest(request.leaseId(), "anotherplugin", key);
            assertThrows(ExternalCauldronStorage.Refusal.class, () -> ExternalCauldronStorage.inspect(connection, impostor));
            assertThrows(ExternalCauldronStorage.Refusal.class, () -> ExternalCauldronStorage.retire(connection, impostor, ExternalCauldronLease.State.RELEASED));
        }
        try (var connection = open()) {
            initialize(connection); assertArrayEquals(signing, ExternalCauldronStorage.signingKey(connection));
            assertEquals(List.of(ExternalCauldronStorage.inspect(connection, request).orElseThrow()), ExternalCauldronStorage.active(connection));
            var destroyed = ExternalCauldronStorage.retire(connection, request, ExternalCauldronLease.State.DESTROYED);
            assertEquals(ExternalCauldronLease.State.DESTROYED, destroyed.state());
            assertEquals(destroyed, ExternalCauldronStorage.retire(connection, request, ExternalCauldronLease.State.RELEASED));
            assertThrows(ExternalCauldronStorage.Refusal.class, () -> ExternalCauldronStorage.acquire(connection, request));
            assertEquals(ExternalCauldronLease.State.ACTIVE, ExternalCauldronStorage.acquire(connection, request()).state());
        }
    }
    @Test void persistedEmptyNativeRowsAndFixturesAreOwnersAndSqlCannotBypassActiveLease() throws Exception {
        var request = request(); var control = new BreweryLocation(8,64,3,key.worldUuid());
        try (var connection = open()) {
            initialize(connection); insert(connection, key);
            assertThrows(ExternalCauldronStorage.Refusal.class, () -> ExternalCauldronStorage.acquire(connection, request));
            sql(connection, "DELETE FROM cauldrons");
            var fixture = new CauldronFixtureRequest(UUID.randomUUID(), UUID.randomUUID(), List.of(new CauldronFixtureRequest.Lane(key, UUID.randomUUID())));
            CauldronFixtureStorage.reserve(connection, fixture);
            assertThrows(ExternalCauldronStorage.Refusal.class, () -> ExternalCauldronStorage.acquire(connection, request));
            CauldronFixtureStorage.close(connection, fixture); ExternalCauldronStorage.acquire(connection, request);
            assertThrows(SQLException.class, () -> insert(connection, key));
            assertThrows(SQLException.class, () -> CauldronFixtureStorage.reserve(connection,
                    new CauldronFixtureRequest(UUID.randomUUID(), UUID.randomUUID(), fixture.lanes())));
            insert(connection, control);
            assertThrows(SQLException.class, () -> sql(connection, "UPDATE cauldrons SET cauldron_x=1"));
            sql(connection, "UPDATE cauldrons SET brew='unrelated owner survives'");
            ExternalCauldronStorage.retire(connection, request, ExternalCauldronLease.State.RELEASED);
            insert(connection, key);
        }
    }
    @Test void lostCommitAcknowledgementRequiresExactInspectionAndCannotMintAnotherGeneration() throws Exception {
        var request = request();
        try (var connection = open()) {
            initialize(connection);
            Connection uncertain = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                try { Object result = method.invoke(connection, args); if (method.getName().equals("commit")) throw new SQLException("Lost commit reply"); return result; }
                catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            assertThrows(SQLException.class, () -> ExternalCauldronStorage.acquire(uncertain, request));
        }
        try (var connection = open()) {
            initialize(connection);
            assertEquals(ExternalCauldronLease.State.ACTIVE, ExternalCauldronStorage.inspect(connection, request).orElseThrow().state());
            assertThrows(ExternalCauldronStorage.Refusal.class, () -> ExternalCauldronStorage.acquire(connection, request()));
        }
    }
    @Test void brokenCurrentProofIdentityOrOwnershipFenceIsRefusedWithoutRepair() throws Exception {
        try (var connection = open()) {
            initialize(connection); sql(connection, "DELETE FROM brew_consumable_signing_key");
            assertThrows(SQLException.class, () -> initialize(connection));
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM brew_consumable_signing_key")) {
                assertTrue(rows.next()); assertEquals(0, rows.getInt(1));
            }
        }
    }
    @Test void schemaFiveMigrationKeepsExistingRowsAndCreatesStableIdentityAtomically() throws Exception {
        try (var connection = open()) {
            initialize(connection); insert(connection, key);
            for (var entry : new ArrayList<>(ExternalCauldronStorage.OBJECTS.entrySet()).reversed()) {
                String kind = entry.getValue().startsWith("CREATE TABLE") ? "TABLE" : entry.getValue().startsWith("CREATE UNIQUE INDEX") ? "INDEX" : "TRIGGER";
                sql(connection, "DROP " + kind + " " + entry.getKey());
            }
            sql(connection, "UPDATE version SET version=5");
            Connection interrupted = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class}, (proxy, method, args) -> {
                if (method.getName().equals("commit")) throw new SQLException("Interrupted migration");
                try { return method.invoke(connection, args); } catch (InvocationTargetException failure) { throw failure.getCause(); }
            });
            assertThrows(SQLException.class, () -> initialize(interrupted));
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT version FROM version")) { assertTrue(rows.next()); assertEquals(5,rows.getInt(1)); }
            ExternalCauldronStorage.rejectPartial(connection);
            initialize(connection);
            try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT brew FROM cauldrons")) { assertTrue(rows.next()); assertEquals("[]", rows.getString(1)); }
            byte[] secret = ExternalCauldronStorage.signingKey(connection); initialize(connection);
            assertArrayEquals(secret, ExternalCauldronStorage.signingKey(connection));
        }
    }
}
