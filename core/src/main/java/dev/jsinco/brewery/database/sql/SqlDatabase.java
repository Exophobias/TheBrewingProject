package dev.jsinco.brewery.database.sql;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.database.PersistenceHandler;
import dev.jsinco.brewery.database.Session;
import dev.jsinco.brewery.database.SessionType;
import dev.jsinco.brewery.util.FileUtil;
import org.jspecify.annotations.NonNull;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

public class SqlDatabase implements PersistenceHandler {

    private static final int BREWERY_DATABASE_VERSION = 5;
    private static final Set<String> TABLES = Set.of("barrels", "barrel_brews", "cauldrons", "distilleries",
            "distillery_brews", "version", "drunk_states_v2", "modifiers", "time");
    private static final Map<String, String> BIRTH_OBJECTS = Map.of(
            "cauldrons_birth_uuid_unique", "birth_uuid_index.sql",
            "cauldrons_birth_uuid_insert", "birth_uuid_insert_guard.sql",
            "cauldrons_birth_uuid_update", "birth_uuid_update_guard.sql");
    private final DatabaseDriver driver;
    private HikariDataSource hikariDataSource;
    private final ScheduledExecutorService executor = Executors.newSingleThreadScheduledExecutor();
    private final DatabaseWorkTracker work = new DatabaseWorkTracker(executor);
    private CompletableFuture<Void> closing;

    public SqlDatabase(DatabaseDriver databaseDriver) {
        this.driver = databaseDriver;
    }

    public void init(File dataFolder) throws IOException, SQLException {
        HikariConfig config = switch (driver) {
            case SQLITE -> getHikariConfigForSqlite(dataFolder);
            default -> throw new UnsupportedOperationException("Currently not implemented");
        };
        config.setConnectionInitSql("PRAGMA foreign_keys = ON;");
        this.hikariDataSource = new HikariDataSource(config);
        try (Connection connection = hikariDataSource.getConnection()) {
            createTables(connection);
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                close().join();
            } catch (RuntimeException cleanupFailure) {
                failure.addSuppressed(cleanupFailure);
            }
            throw failure;
        }
    }

    public Connection getConnection() throws PersistenceException {
        try {
            return hikariDataSource.getConnection();
        } catch (SQLException e) {
            throw new PersistenceException(e);
        }
    }

    private static @NonNull HikariConfig getHikariConfigForSqlite(File dataFolder) throws IOException {
        File databaseFile = new File(dataFolder, "brewery.db");
        if (!databaseFile.exists() && !databaseFile.getParentFile().mkdirs() && !databaseFile.createNewFile()) {
            throw new IOException("Could not create file or dirs");
        }
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setPoolName("SQLiteConnectionPool");
        hikariConfig.setDriverClassName("org.sqlite.JDBC");
        hikariConfig.setJdbcUrl("jdbc:sqlite:" + databaseFile);
        return hikariConfig;
    }

    // Package access permits real JDBC failure-injection tests without a runtime test hook.
    void createTables(Connection connection) throws SQLException {
        if (!connection.getAutoCommit()) throw new SQLException("Database initialization requires autocommit");
        Integer previousVersion = readVersion(connection);
        if (previousVersion != null) {
            validateCauldrons(connection, previousVersion);
            if (previousVersion >= 3) validateTables(connection);
            if (previousVersion == BREWERY_DATABASE_VERSION) {
                validateBirths(connection);
                CauldronFixtureSchema.validate(connection);
                return;
            }
        }
        if (previousVersion != null && previousVersion == 4) validateBirths(connection);
        else rejectBirthObjects(connection);
        CauldronFixtureSchema.rejectPartial(connection);
        if (previousVersion != null && previousVersion < 3) {
            // These published migrations change PRAGMA foreign_keys, which is a no-op in a transaction.
            createLegacyTables(connection);
            for (int version = previousVersion; version < 3; version++) {
                runLegacyMigration(version, connection);
                setVersion(connection, version + 1);
            }
        }
        connection.setAutoCommit(false);
        boolean transactionResolved = false;
        try {
            Integer expectedVersion = previousVersion == null ? null : Integer.valueOf(Math.max(3, previousVersion));
            if (!Objects.equals(expectedVersion, readVersion(connection))) {
                throw new SQLException("Database version changed during initialization");
            }
            if (previousVersion == null) createLegacyTables(connection);
            if (previousVersion == null || previousVersion < 4) {
                validateCauldrons(connection, 3);
                migrateBirths(connection);
            }
            validateBirths(connection);
            CauldronFixtureSchema.create(connection);
            CauldronFixtureSchema.validate(connection);
            setVersion(connection, BREWERY_DATABASE_VERSION);
            if (!Integer.valueOf(BREWERY_DATABASE_VERSION).equals(readVersion(connection))) {
                throw new SQLException("Database version publication failed");
            }
            connection.commit();
            transactionResolved = true;
        } catch (SQLException | RuntimeException | Error failure) {
            try {
                connection.rollback();
                transactionResolved = true;
            } catch (SQLException rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
                try {
                    connection.close();
                } catch (SQLException closeFailure) {
                    failure.addSuppressed(closeFailure);
                }
            }
            throw failure;
        } finally {
            // setAutoCommit(true) commits an open transaction, so never use it after a failed rollback.
            if (transactionResolved) connection.setAutoCommit(true);
        }
    }

    private static Integer readVersion(Connection connection) throws SQLException {
        Map<String, String> objects = schemaObjects(connection);
        if (!objects.containsKey("version")) {
            if (TABLES.stream().anyMatch(objects::containsKey)) {
                throw new SQLException("Incomplete brewery database: missing version");
            }
            return null;
        }
        if (!"table".equals(objects.get("version"))) throw new SQLException("Invalid database version table");
        int version;
        try (PreparedStatement statement = connection.prepareStatement("SELECT version, typeof(version) AS kind FROM version");
             ResultSet rows = statement.executeQuery()) {
            if (!rows.next() || !"integer".equals(rows.getString("kind"))) {
                throw new SQLException("Missing or malformed database version");
            }
            long value = rows.getLong("version");
            if (value < 0 || value > BREWERY_DATABASE_VERSION || rows.next()) {
                throw new SQLException("Unsupported or ambiguous database version");
            }
            version = (int) value;
        }
        if (version > 0) {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT singleton_value, typeof(singleton_value) AS kind FROM version");
                 ResultSet rows = statement.executeQuery()) {
                if (!rows.next() || !"integer".equals(rows.getString("kind")) || rows.getLong(1) != 0) {
                    throw new SQLException("Invalid database version singleton");
                }
            }
        }
        return version;
    }

    private static Map<String, String> schemaObjects(Connection connection) throws SQLException {
        Map<String, String> result = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT name, type FROM sqlite_schema");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) result.put(rows.getString("name").toLowerCase(Locale.ROOT), rows.getString("type"));
        }
        return result;
    }

    private static void validateTables(Connection connection) throws SQLException {
        Map<String, String> objects = schemaObjects(connection);
        for (String table : TABLES) {
            if (!"table".equals(objects.get(table))) throw new SQLException("Incomplete brewery database: " + table);
        }
    }

    private record Column(String type, int notNull, String defaultValue, int primaryKey, int hidden) { }

    private static void validateCauldrons(Connection connection, int version) throws SQLException {
        if (!"table".equals(schemaObjects(connection).get("cauldrons"))) {
            throw new SQLException("Missing cauldrons table");
        }
        Map<String, Column> columns = new HashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("PRAGMA table_xinfo(cauldrons)");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                columns.put(rows.getString("name").toLowerCase(Locale.ROOT), new Column(rows.getString("type"),
                        rows.getInt("notnull"), rows.getString("dflt_value"), rows.getInt("pk"), rows.getInt("hidden")));
            }
        }
        requireColumn(columns, "cauldron_x", "INTEGER", 1);
        requireColumn(columns, "cauldron_y", "INTEGER", 2);
        requireColumn(columns, "cauldron_z", "INTEGER", 3);
        requireColumn(columns, "world_uuid", "BINARY(16)", 4);
        requireColumn(columns, "brew", "JSON", 0);
        if (version >= 3) requireColumn(columns, "cauldron_type", "TEXT", 0);
        else if (columns.containsKey("cauldron_type")) throw new SQLException("Partial cauldron type migration");
        if (version >= 4) {
            requireColumn(columns, "birth_uuid", "BLOB", 0);
            Column birth = columns.get("birth_uuid");
            if (birth.notNull() != 1 || !"X''".equalsIgnoreCase(birth.defaultValue())) {
                throw new SQLException("Invalid cauldron birth column constraints");
            }
        } else if (columns.containsKey("birth_uuid")) {
            throw new SQLException("Partial cauldron birth migration");
        }
    }

    private static void requireColumn(Map<String, Column> columns, String name, String type, int primaryKey) throws SQLException {
        Column column = columns.get(name);
        if (column == null || !type.equalsIgnoreCase(column.type()) || column.primaryKey() != primaryKey || column.hidden() != 0) {
            throw new SQLException("Invalid cauldron column: " + name);
        }
    }

    private static void rejectBirthObjects(Connection connection) throws SQLException {
        Map<String, String> objects = schemaObjects(connection);
        for (String name : BIRTH_OBJECTS.keySet()) {
            if (objects.containsKey(name)) throw new SQLException("Partial cauldron birth schema: " + name);
        }
    }

    private void createLegacyTables(Connection connection) throws SQLException {
        executeScript(connection, "/database/" + driver.name().toLowerCase(Locale.ROOT) + "/create_all_tables.sql");
    }

    private static void runLegacyMigration(int version, Connection connection) throws SQLException {
        String file = switch (version) {
            case 0 -> "version_migration.sql";
            case 1 -> "foreign_keys_on_migration.sql";
            case 2 -> "add_cauldron_type_migration.sql";
            default -> throw new SQLException("Unimplemented migration from version: " + version);
        };
        try {
            executeScript(connection, "/database/migration/" + file);
        } finally {
            if (version == 1) execute(connection, "PRAGMA foreign_keys = ON");
        }
    }

    private static void migrateBirths(Connection connection) throws SQLException {
        // Backfilling metadata must not invoke extension gameplay/audit UPDATE triggers.
        // Their exact definitions, and any rollback restoration, remain in this transaction.
        record Trigger(String name, String sql) { }
        List<Trigger> triggers = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name, sql FROM sqlite_schema WHERE type = 'trigger' AND tbl_name = 'cauldrons' COLLATE NOCASE ORDER BY rowid");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) triggers.add(new Trigger(rows.getString("name"), rows.getString("sql")));
        }
        for (Trigger trigger : triggers) execute(connection, "DROP TRIGGER \"" + trigger.name().replace("\"", "\"\"") + "\"");
        executeScript(connection, "/database/migration/add_cauldron_birth_migration.sql");
        for (Trigger trigger : triggers) execute(connection, trigger.sql());
        for (String resource : BIRTH_OBJECTS.values()) execute(connection, birthResource(resource));
    }

    private static void validateBirths(Connection connection) throws SQLException {
        validateCauldrons(connection, 4);
        for (Map.Entry<String, String> object : BIRTH_OBJECTS.entrySet()) {
            try (PreparedStatement statement = connection.prepareStatement("SELECT sql FROM sqlite_schema WHERE name = ?")) {
                statement.setString(1, object.getKey());
                try (ResultSet rows = statement.executeQuery()) {
                    if (!rows.next() || !normalizeSql(birthResource(object.getValue())).equals(normalizeSql(rows.getString(1)))) {
                        throw new SQLException("Invalid cauldron birth schema: " + object.getKey());
                    }
                }
            }
        }
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT 1 FROM cauldrons WHERE typeof(birth_uuid) <> 'blob' OR length(birth_uuid) <> 16 LIMIT 1");
             ResultSet rows = statement.executeQuery()) {
            if (rows.next()) throw new SQLException("Malformed persisted cauldron birth");
        }
    }

    private static String normalizeSql(String sql) {
        return sql == null ? "" : sql.strip().replaceFirst(";\\s*$", "").replaceAll("\\s+", " ");
    }

    private static String birthResource(String file) {
        return FileUtil.readInternalResource("/database/sqlite/" + file);
    }

    private static void setVersion(Connection connection, int version) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(FileUtil.readInternalResource("/database/generic/set_version.sql"))) {
            statement.setInt(1, version);
            statement.execute();
        }
    }

    private static void executeScript(Connection connection, String resource) throws SQLException {
        String script = FileUtil.readInternalResource(resource);
        if (script.isBlank()) throw new SQLException("Missing database resource: " + resource);
        for (String sql : script.split(";")) if (!sql.isBlank()) execute(connection, sql);
    }

    private static void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }


    @Override
    public CompletableFuture<Void> flush() {
        return work.drain();
    }

    /** Rejects new session operations, drains admitted work, then releases the pool and worker. */
    public synchronized CompletableFuture<Void> close() {
        if (closing == null) {
            closing = work.closeAdmission().thenRun(() -> {
                try {
                    if (hikariDataSource != null) hikariDataSource.close();
                } finally {
                    executor.shutdown();
                }
            });
        }
        return closing;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized <T extends Session<T>> T startSession(SessionType<T> sessionType) throws PersistenceException {
        if (closing != null) throw new PersistenceException(new IllegalStateException("Database is closing"));
        T session = sessionType.retrieve(work, this);
        // Session interfaces expose their complete persistence future. Tracking that result covers
        // dependency waits before a first SQL task exists, not just currently queued executor work.
        return (T) Proxy.newProxyInstance(session.getClass().getClassLoader(), session.getClass().getInterfaces(),
                (proxy, method, arguments) -> {
                    if (CompletableFuture.class.isAssignableFrom(method.getReturnType())) {
                        return work.admit(() -> {
                            try {
                                return (CompletableFuture<?>) method.invoke(session, arguments);
                            } catch (InvocationTargetException failure) {
                                return CompletableFuture.failedFuture(failure.getCause());
                            } catch (ReflectiveOperationException failure) {
                                return CompletableFuture.failedFuture(failure);
                            }
                        });
                    }
                    try {
                        return method.invoke(session, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    @Override
    public DatabaseDriver driver() {
        return driver;
    }
}
