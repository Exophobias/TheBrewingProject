package dev.jsinco.brewery.database.sql;

import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.util.FileUtil;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.sqlite.Function;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class SqlDatabaseBirthMigrationTest {
    @TempDir Path directory;

    @Test
    void freshDatabaseRequiresDistinctExplicitSixteenByteBlobBirthsAndReopensUnchanged() throws Exception {
        SqlDatabase database = new SqlDatabase(DatabaseDriver.SQLITE);
        try {
            database.init(directory.toFile());
            try (Connection connection = database.getConnection()) {
                assertEquals("4", scalar(connection, "SELECT version FROM version"));
                assertEquals("1", scalar(connection, "PRAGMA foreign_keys"));
                assertEquals("1", scalar(connection, "SELECT \"notnull\" FROM pragma_table_xinfo('cauldrons') WHERE name='birth_uuid'"));
                execute(connection, "INSERT INTO cauldrons(cauldron_x, birth_uuid) VALUES (1, X'000102030405060708090A0B0C0D0E0F')");
                execute(connection, "INSERT INTO cauldrons(cauldron_x, birth_uuid) VALUES (2, X'100102030405060708090A0B0C0D0E0F')");
                assertThrows(SQLException.class, () -> execute(connection,
                        "INSERT INTO cauldrons(cauldron_x, birth_uuid) VALUES (3, X'000102030405060708090A0B0C0D0E0F')"));
                assertThrows(SQLException.class, () -> execute(connection,
                        "UPDATE cauldrons SET birth_uuid = X'000102030405060708090A0B0C0D0E0F' WHERE cauldron_x = 2"));
                for (String invalid : List.of("NULL", "X''", "X'0102'", "zeroblob(17)", "'0123456789abcdef'", "16")) {
                    assertThrows(SQLException.class, () -> execute(connection,
                            "INSERT INTO cauldrons(cauldron_x, birth_uuid) VALUES (3, " + invalid + ")"), invalid);
                    assertThrows(SQLException.class, () -> execute(connection,
                            "UPDATE cauldrons SET birth_uuid = " + invalid + " WHERE cauldron_x = 1"), invalid);
                }
                assertThrows(SQLException.class, () -> execute(connection, "INSERT INTO cauldrons(cauldron_x) VALUES (3)"));
            }
        } finally {
            database.close().join();
        }
        try (Connection connection = open()) {
            List<String> before = snapshot(connection);
            initialize(connection);
            assertEquals(before, snapshot(connection));
            assertEquals("2", scalar(connection, "SELECT count(DISTINCT birth_uuid) FROM cauldrons"));
        }
    }

    @Test
    void migrationPreservesExactRowsExtensionsIndexesViewsAndTriggersWithoutFiringThem() throws Exception {
        try (Connection connection = open()) {
            legacy(connection, 3);
            extensions(connection);
            List<String> beforeRows = legacyRows(connection);
            String trigger = scalar(connection, "SELECT sql FROM sqlite_schema WHERE name='extension_cauldron_update'");
            String index = scalar(connection, "SELECT sql FROM sqlite_schema WHERE name='extension_cauldron_index'");
            String view = scalar(connection, "SELECT sql FROM sqlite_schema WHERE name='extension_cauldron_view'");
            initialize(connection);
            assertEquals(beforeRows, legacyRows(connection));
            assertEquals("untouched", scalar(connection, "SELECT value FROM extension_control"));
            assertEquals("0199", scalar(connection, "SELECT hex(extension_marker) FROM version"));
            assertEquals(trigger, scalar(connection, "SELECT sql FROM sqlite_schema WHERE name='extension_cauldron_update'"));
            assertEquals(index, scalar(connection, "SELECT sql FROM sqlite_schema WHERE name='extension_cauldron_index'"));
            assertEquals(view, scalar(connection, "SELECT sql FROM sqlite_schema WHERE name='extension_cauldron_view'"));
            assertEquals("2", scalar(connection, "SELECT count(DISTINCT birth_uuid) FROM cauldrons WHERE typeof(birth_uuid)='blob' AND length(birth_uuid)=16"));
            assertEquals("4", scalar(connection, "SELECT version FROM version"));
            List<String> current = snapshot(connection);
            initialize(connection);
            assertEquals(current, snapshot(connection));
            execute(connection, "UPDATE cauldrons SET cauldron_type='test' WHERE cauldron_x=1");
            assertEquals("changed", scalar(connection, "SELECT value FROM extension_control"));
        }
    }

    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2})
    void supportedOldEdgesKeepForeignKeyCleanupAndReachFour(int version) throws Exception {
        try (Connection connection = open()) {
            legacy(connection, version);
            execute(connection, "PRAGMA foreign_keys=OFF");
            execute(connection, "INSERT INTO barrels(unique_x,unique_y,unique_z,world_uuid) VALUES(1,2,3,zeroblob(16))");
            execute(connection, "INSERT INTO barrel_brews(unique_x,unique_y,unique_z,world_uuid,brew) VALUES(1,2,3,zeroblob(16),'retained')");
            execute(connection, "INSERT INTO distilleries(unique_x,unique_y,unique_z,world_uuid) VALUES(1,2,3,zeroblob(16))");
            execute(connection, "INSERT INTO distillery_brews(unique_x,unique_y,unique_z,world_uuid,brew) VALUES(1,2,3,zeroblob(16),'retained')");
            if (version <= 1) {
                execute(connection, "INSERT INTO barrel_brews(unique_x,unique_y,unique_z,world_uuid,brew) VALUES(9,9,9,zeroblob(16),'orphan')");
                execute(connection, "INSERT INTO distillery_brews(unique_x,unique_y,unique_z,world_uuid,brew) VALUES(9,9,9,zeroblob(16),'orphan')");
            }
            execute(connection, "PRAGMA foreign_keys=ON");
            initialize(connection);
            assertEquals("4", scalar(connection, "SELECT version FROM version"));
            assertEquals("1", scalar(connection, "PRAGMA foreign_keys"));
            assertEquals("retained", scalar(connection, "SELECT brew FROM barrel_brews"));
            assertEquals("retained", scalar(connection, "SELECT brew FROM distillery_brews"));
            assertEquals("1", scalar(connection, "SELECT count(*) FROM barrel_brews"));
            assertEquals("1", scalar(connection, "SELECT count(*) FROM distillery_brews"));
            assertEquals("2", scalar(connection, "SELECT count(DISTINCT birth_uuid) FROM cauldrons WHERE cauldron_type IS NULL"));
            assertThrows(SQLException.class, () -> execute(connection,
                    "INSERT INTO barrel_brews(unique_x,unique_y,unique_z,world_uuid) VALUES(9,9,9,zeroblob(16))"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"ALTER TABLE cauldrons ADD birth_uuid", "UPDATE cauldrons SET birth_uuid",
            "CREATE UNIQUE INDEX", "CREATE TRIGGER cauldrons_birth_uuid_update", "INSERT INTO version", "COMMIT", "CLOSE"})
    void interruptedMigrationRollsBackSchemaBackfillVersionAndExtensionTriggers(String stage) throws Exception {
        List<String> before;
        try (Connection connection = open()) {
            legacy(connection, 3);
            extensions(connection);
            before = snapshot(connection);
            assertThrows(SQLException.class, () -> initialize(failAfter(connection, stage)));
        }
        try (Connection connection = open()) {
            assertEquals(before, snapshot(connection));
            initialize(connection);
            assertEquals("4", scalar(connection, "SELECT version FROM version"));
            assertEquals("untouched", scalar(connection, "SELECT value FROM extension_control"));
        }
    }

    @Test
    void failedRollbackClosesWithoutEnablingAutocommitOrCommittingPartialSchema() throws Exception {
        List<String> before;
        try (Connection connection = open()) {
            legacy(connection, 3);
            extensions(connection);
            before = snapshot(connection);
            Connection interrupted = failAfter(connection, "UPDATE cauldrons SET birth_uuid");
            AtomicBoolean enabledAutocommit = new AtomicBoolean();
            Connection failedRollback = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("rollback")) throw new SQLException("Injected rollback failure");
                        if (method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(arguments[0])) {
                            enabledAutocommit.set(true);
                        }
                        try {
                            return method.invoke(interrupted, arguments);
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            SQLException failure = assertThrows(SQLException.class, () -> initialize(failedRollback));
            assertTrue(failure.getMessage().contains("after UPDATE"));
            assertEquals(1, failure.getSuppressed().length);
            assertTrue(failure.getSuppressed()[0].getMessage().contains("rollback failure"));
            assertFalse(enabledAutocommit.get());
            assertTrue(connection.isClosed());
        }
        try (Connection connection = open()) {
            assertEquals(before, snapshot(connection));
            initialize(connection);
            assertEquals("4", scalar(connection, "SELECT version FROM version"));
        }
    }

    @Test
    void versionChangedByAnotherConnectionAfterPreflightIsRejectedBeforeMigration() throws Exception {
        try (Connection connection = open()) {
            legacy(connection, 3);
            extensions(connection);
            execute(connection, "UPDATE version SET version=5");
            List<String> changedDatabase = snapshot(connection);
            execute(connection, "UPDATE version SET version=3");
            AtomicBoolean injected = new AtomicBoolean();
            Connection raced = (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(),
                    new Class<?>[]{Connection.class}, (proxy, method, arguments) -> {
                        try {
                            Object result = method.invoke(connection, arguments);
                            if (method.getName().equals("setAutoCommit") && Boolean.FALSE.equals(arguments[0])) {
                                try (Connection other = open()) {
                                    execute(other, "UPDATE version SET version=5");
                                }
                                injected.set(true);
                            }
                            return result;
                        } catch (InvocationTargetException failure) {
                            throw failure.getCause();
                        }
                    });
            assertThrows(SQLException.class, () -> initialize(raced));
            assertTrue(injected.get());
            assertEquals(changedDatabase, snapshot(connection));
            assertEquals("5", scalar(connection, "SELECT version FROM version"));
            assertEquals("0", scalar(connection, "SELECT count(*) FROM pragma_table_xinfo('cauldrons') WHERE name='birth_uuid'"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"null", "short", "text", "collision"})
    void invalidOrCollidingBackfillRollsBackAndCanRetry(String kind) throws Exception {
        List<String> before;
        try (Connection connection = open()) {
            legacy(connection, 3);
            extensions(connection);
            before = snapshot(connection);
            AtomicInteger sequence = new AtomicInteger();
            Function.create(connection, "randomblob", new Function() {
                @Override protected void xFunc() throws SQLException {
                    int next = sequence.incrementAndGet();
                    switch (kind) {
                        case "null" -> result();
                        case "text" -> result("0123456789abcde" + next);
                        case "short" -> result(new byte[]{(byte) next});
                        case "collision" -> result(new byte[16]);
                        default -> throw new AssertionError(kind);
                    }
                }
            });
            assertThrows(SQLException.class, () -> initialize(connection));
            assertEquals(before, snapshot(connection));
        }
        try (Connection connection = open()) {
            initialize(connection);
            assertEquals("2", scalar(connection, "SELECT count(DISTINCT birth_uuid) FROM cauldrons"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"NULL", "'unknown'", "-1", "3.5", "5", "4294967299"})
    void malformedOrFutureVersionIsRejectedWithoutWrites(String value) throws Exception {
        try (Connection connection = open()) {
            legacy(connection, 3);
            execute(connection, "UPDATE version SET version=" + value);
            assertRejectedUnchanged(connection);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "DELETE FROM version",
            "INSERT INTO version VALUES(3,1)",
            "UPDATE version SET singleton_value=1",
            "DROP TABLE version",
            "DROP TABLE time",
            "ALTER TABLE cauldrons RENAME COLUMN brew TO broken",
            "ALTER TABLE cauldrons ADD birth_uuid BLOB",
            "CREATE INDEX cauldrons_birth_uuid_unique ON cauldrons(brew)",
            "CREATE TRIGGER cauldrons_birth_uuid_insert BEFORE INSERT ON cauldrons BEGIN SELECT 1; END"
    })
    void partialLegacySchemaIsRejectedWithoutWrites(String mutation) throws Exception {
        try (Connection connection = open()) {
            legacy(connection, 3);
            execute(connection, mutation);
            assertRejectedUnchanged(connection);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"index", "insert", "update", "forged", "malformed", "nullable"})
    void incompleteOrMalformedCurrentBirthSchemaIsRejectedWithoutRepair(String damage) throws Exception {
        try (Connection connection = open()) {
            initialize(connection);
            execute(connection, "INSERT INTO cauldrons(cauldron_x,birth_uuid) VALUES(1,randomblob(16))");
            switch (damage) {
                case "index" -> execute(connection, "DROP INDEX cauldrons_birth_uuid_unique");
                case "insert" -> execute(connection, "DROP TRIGGER cauldrons_birth_uuid_insert");
                case "update" -> execute(connection, "DROP TRIGGER cauldrons_birth_uuid_update");
                case "forged" -> {
                    execute(connection, "DROP TRIGGER cauldrons_birth_uuid_insert");
                    execute(connection, "CREATE TRIGGER cauldrons_birth_uuid_insert BEFORE INSERT ON cauldrons BEGIN SELECT 1; END");
                }
                case "malformed" -> {
                    execute(connection, "DROP TRIGGER cauldrons_birth_uuid_update");
                    execute(connection, "UPDATE cauldrons SET birth_uuid=X'01'");
                    execute(connection, FileUtil.readInternalResource("/database/sqlite/birth_uuid_update_guard.sql"));
                }
                case "nullable" -> {
                    execute(connection, "DROP TRIGGER cauldrons_birth_uuid_insert");
                    execute(connection, "DROP TRIGGER cauldrons_birth_uuid_update");
                    execute(connection, "DROP INDEX cauldrons_birth_uuid_unique");
                    execute(connection, "ALTER TABLE cauldrons DROP COLUMN birth_uuid");
                    execute(connection, "ALTER TABLE cauldrons ADD birth_uuid BLOB DEFAULT X''");
                    execute(connection, "UPDATE cauldrons SET birth_uuid=randomblob(16)");
                    for (String file : List.of("birth_uuid_index.sql", "birth_uuid_insert_guard.sql", "birth_uuid_update_guard.sql")) {
                        execute(connection, FileUtil.readInternalResource("/database/sqlite/" + file));
                    }
                }
                default -> fail(damage);
            }
            assertRejectedUnchanged(connection);
        }
    }

    @Test
    void failedFreshCreationLeavesNoPartialTablesAndCanRetry() throws Exception {
        try (Connection connection = open()) {
            assertThrows(SQLException.class, () -> initialize(failAfter(connection, "INSERT INTO version")));
            assertEquals(List.of(), snapshot(connection));
            initialize(connection);
            assertEquals("4", scalar(connection, "SELECT version FROM version"));
        }
    }

    @Test
    void failedRealPoolInitializationClosesConnectionsAndSessionAdmission() throws Exception {
        try (Connection connection = open()) {
            legacy(connection, 3);
            execute(connection, "UPDATE version SET version=5");
        }
        SqlDatabase database = new SqlDatabase(DatabaseDriver.SQLITE);
        assertThrows(SQLException.class, () -> database.init(directory.toFile()));
        assertThrows(PersistenceException.class, database::getConnection);
        assertTrue(database.close().isDone());
    }

    private Connection open() throws SQLException {
        Connection connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("brewery.db"));
        execute(connection, "PRAGMA foreign_keys=ON");
        return connection;
    }

    private static void initialize(Connection connection) throws SQLException {
        SqlDatabase database = new SqlDatabase(DatabaseDriver.SQLITE);
        try {
            database.createTables(connection);
        } finally {
            database.close().join();
        }
    }

    private static void legacy(Connection connection, int version) throws SQLException {
        // This unchanged bundled resource is the published schema-3 shape, before birth migration.
        for (String sql : FileUtil.readInternalResource("/database/sqlite/create_all_tables.sql").split(";")) {
            if (!sql.isBlank()) execute(connection, sql);
        }
        if (version < 3) execute(connection, "ALTER TABLE cauldrons DROP COLUMN cauldron_type");
        if (version == 0) {
            execute(connection, "DROP TABLE version");
            execute(connection, "CREATE TABLE version(version INTEGER)");
            execute(connection, "INSERT INTO version VALUES(0)");
        } else {
            execute(connection, "INSERT INTO version VALUES(" + version + ",0)");
        }
        for (int x : List.of(1, 2)) {
            execute(connection, "INSERT INTO cauldrons(cauldron_x,cauldron_y,cauldron_z,world_uuid,brew) VALUES("
                    + x + ",64,-7,X'000102030405060708090A0B0C0D0E0F','{\"same\":\"brew\"}')");
        }
    }

    private static void extensions(Connection connection) throws SQLException {
        execute(connection, "ALTER TABLE version ADD extension_marker BLOB NOT NULL DEFAULT X'FFAA'");
        execute(connection, "UPDATE version SET extension_marker=X'0199'");
        execute(connection, "ALTER TABLE cauldrons ADD extension_payload BLOB");
        execute(connection, "UPDATE cauldrons SET extension_payload=X'0000FFCA',cauldron_type='extension:type'");
        execute(connection, "CREATE TABLE extension_control(value TEXT)");
        execute(connection, "INSERT INTO extension_control VALUES('untouched')");
        execute(connection, "CREATE INDEX extension_cauldron_index ON cauldrons(extension_payload)");
        execute(connection, "CREATE VIEW extension_cauldron_view AS SELECT brew,extension_payload FROM cauldrons");
        execute(connection, "CREATE TRIGGER extension_cauldron_update AFTER UPDATE ON cauldrons BEGIN "
                + "UPDATE extension_control SET value='changed'; "
                + "UPDATE cauldrons SET brew='changed',extension_payload=X'FF' WHERE rowid=NEW.rowid; END");
    }

    private static List<String> legacyRows(Connection connection) throws SQLException {
        return rows(connection, "SELECT rowid,cauldron_x,cauldron_y,cauldron_z,hex(world_uuid),typeof(brew),brew,cauldron_type,hex(extension_payload) FROM cauldrons ORDER BY rowid");
    }

    private static List<String> snapshot(Connection connection) throws SQLException {
        List<String> result = new ArrayList<>(rows(connection, "SELECT type,name,tbl_name,sql FROM sqlite_schema ORDER BY name"));
        for (String table : rows(connection, "SELECT name FROM sqlite_schema WHERE type='table' ORDER BY name")) {
            result.addAll(rows(connection, "SELECT * FROM \"" + table.replace("\"", "\"\"") + "\" ORDER BY rowid"));
        }
        return result;
    }

    private static void assertRejectedUnchanged(Connection connection) throws SQLException {
        List<String> before = snapshot(connection);
        assertThrows(SQLException.class, () -> initialize(connection));
        assertEquals(before, snapshot(connection));
    }

    private static List<String> rows(Connection connection, String sql) throws SQLException {
        List<String> values = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(sql); ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= rows.getMetaData().getColumnCount(); i++) {
                    Object value = rows.getObject(i);
                    row.add(value instanceof byte[] bytes ? java.util.HexFormat.of().formatHex(bytes) : String.valueOf(value));
                }
                values.add(String.join("|", row));
            }
        }
        return values;
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        return rows(connection, sql).getFirst();
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private static Connection failAfter(Connection connection, String stage) {
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    if (method.getName().equals("commit") && (stage.equals("COMMIT") || stage.equals("CLOSE"))) {
                        if (stage.equals("CLOSE")) connection.close();
                        throw new SQLException("Injected interruption before commit");
                    }
                    try {
                        Object result = method.invoke(connection, arguments);
                        if (method.getName().equals("prepareStatement") && arguments[0] instanceof String sql
                                && sql.strip().replaceAll("\\s+", " ").startsWith(stage)) {
                            PreparedStatement delegate = (PreparedStatement) result;
                            return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(), new Class<?>[]{PreparedStatement.class},
                                    (statementProxy, statementMethod, statementArguments) -> {
                                        try {
                                            Object executed = statementMethod.invoke(delegate, statementArguments);
                                            if (statementMethod.getName().equals("execute")) throw new SQLException("Injected interruption after " + stage);
                                            return executed;
                                        } catch (InvocationTargetException failure) {
                                            throw failure.getCause();
                                        }
                                    });
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }
}
