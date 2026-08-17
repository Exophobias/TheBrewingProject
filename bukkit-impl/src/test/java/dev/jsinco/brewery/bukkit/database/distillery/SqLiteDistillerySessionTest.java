package dev.jsinco.brewery.bukkit.database.distillery;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.breweries.DistilleryAccess;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.DistillStepImpl;
import dev.jsinco.brewery.bukkit.ingredient.ResolvedIngredientManagerImpl;
import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.util.DecoderEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SqLiteDistillerySessionTest {

    private static final BreweryLocation LOCATION = new BreweryLocation(
            14, 72, -9, UUID.fromString("43e04a8b-e2f6-494b-a065-f990d5114512")
    );

    @TempDir
    Path tempDirectory;

    private String jdbcUrl;
    private ResolvedIngredientManagerImpl ingredientManager;
    private SqLiteDistillerySession session;

    @BeforeEach
    void setUp() throws SQLException {
        jdbcUrl = "jdbc:sqlite:" + tempDirectory.resolve("brewery.db");
        ingredientManager = new ResolvedIngredientManagerImpl();
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE distilleries (
                        unique_x INTEGER,
                        unique_y INTEGER,
                        unique_z INTEGER,
                        world_uuid BINARY(16),
                        PRIMARY KEY (unique_x, unique_y, unique_z, world_uuid)
                    )
                    """);
            statement.execute("""
                    CREATE TABLE distillery_brews (
                        unique_x INTEGER,
                        unique_y INTEGER,
                        unique_z INTEGER,
                        world_uuid BINARY(16),
                        pos INTEGER,
                        is_distillate INTEGER,
                        brew JSON
                    )
                    """);
        }
        insertDistillery();
        session = new SqLiteDistillerySession(
                Runnable::run,
                () -> {
                    try {
                        return DriverManager.getConnection(jdbcUrl);
                    } catch (SQLException failure) {
                        throw new PersistenceException(failure);
                    }
                },
                CompletableFuture.completedFuture(ingredientManager)
        );
    }

    @Test
    void moveBrewsAtomicallyCommitsWholeBatch() throws SQLException {
        Brew firstSource = brew(1);
        Brew secondSource = brew(3);
        Brew firstResult = brew(2);
        Brew secondResult = brew(4);
        insert(0, false, firstSource);
        insert(1, false, secondSource);

        boolean committed = session.moveBrewsAtomically(LOCATION, List.of(
                new DistilleryAccess.AtomicBrewMove(0, 3, firstSource, firstResult),
                new DistilleryAccess.AtomicBrewMove(1, 4, secondSource, secondResult)
        )).join();

        assertTrue(committed);
        assertNull(find(0, false));
        assertNull(find(1, false));
        assertSameBrew(firstResult, find(3, true));
        assertSameBrew(secondResult, find(4, true));
        assertEquals(2, rowCount());
    }

    @Test
    void moveBrewsAtomicallyRollsBackDeletionWhenInsertFails() throws SQLException {
        Brew source = brew(5);
        insert(0, false, source);
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER fail_atomic_destination
                    BEFORE INSERT ON distillery_brews
                    WHEN NEW.is_distillate = 1
                    BEGIN
                        SELECT RAISE(FAIL, 'injected destination failure');
                    END
                    """);
        }

        CompletionException failure = assertThrows(CompletionException.class, () ->
                session.moveBrewsAtomically(LOCATION, List.of(
                        new DistilleryAccess.AtomicBrewMove(0, 2, source, brew(6))
                )).join()
        );

        DistillerySession.AtomicMovePersistenceException atomicFailure =
                assertInstanceOf(DistillerySession.AtomicMovePersistenceException.class, failure.getCause());
        assertTrue(atomicFailure.rollbackConfirmed());
        assertSameBrew(source, find(0, false));
        assertNull(find(2, true));
        assertEquals(1, rowCount());
    }

    @Test
    void ordinaryTransferBatchRollsBackEarlierMovesWhenLaterInsertFails() throws SQLException {
        Brew firstSource = brew(7);
        Brew secondSource = brew(9);
        insert(0, false, firstSource);
        insert(1, false, secondSource);
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER fail_second_atomic_destination
                    BEFORE INSERT ON distillery_brews
                    WHEN NEW.is_distillate = 1 AND NEW.pos = 4
                    BEGIN
                        SELECT RAISE(FAIL, 'injected second destination failure');
                    END
                    """);
        }

        CompletionException failure = assertThrows(CompletionException.class, () ->
                session.moveBrewsAtomically(LOCATION, List.of(
                        new DistilleryAccess.AtomicBrewMove(0, 3, firstSource, brew(8)),
                        new DistilleryAccess.AtomicBrewMove(1, 4, secondSource, brew(10))
                )).join()
        );

        DistillerySession.AtomicMovePersistenceException atomicFailure =
                assertInstanceOf(DistillerySession.AtomicMovePersistenceException.class, failure.getCause());
        assertTrue(atomicFailure.rollbackConfirmed());
        assertSameBrew(firstSource, find(0, false));
        assertSameBrew(secondSource, find(1, false));
        assertNull(find(3, true));
        assertNull(find(4, true));
        assertEquals(2, rowCount());
    }

    @Test
    void removeMixtureBrewsAtomicallyCommitsWholeBatch() throws SQLException {
        Brew firstSource = brew(11);
        Brew secondSource = brew(12);
        insert(0, false, firstSource);
        insert(1, false, secondSource);
        insert(2, true, brew(13));

        boolean committed = session.removeMixtureBrewsAtomically(LOCATION, List.of(
                new DistilleryAccess.AtomicBrewRemoval(0, firstSource),
                new DistilleryAccess.AtomicBrewRemoval(1, secondSource)
        )).join();

        assertTrue(committed);
        assertNull(find(0, false));
        assertNull(find(1, false));
        assertSameBrew(brew(13), find(2, true));
        assertEquals(1, rowCount());
    }

    @Test
    void removeMixtureBrewsAtomicallyRollsBackEarlierDeletionWhenLaterDeleteFails() throws SQLException {
        Brew firstSource = brew(14);
        Brew secondSource = brew(15);
        insert(0, false, firstSource);
        insert(1, false, secondSource);
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER fail_second_atomic_removal
                    BEFORE DELETE ON distillery_brews
                    WHEN OLD.is_distillate = 0 AND OLD.pos = 1
                    BEGIN
                        SELECT RAISE(FAIL, 'injected second removal failure');
                    END
                    """);
        }

        CompletionException failure = assertThrows(CompletionException.class, () ->
                session.removeMixtureBrewsAtomically(LOCATION, List.of(
                        new DistilleryAccess.AtomicBrewRemoval(0, firstSource),
                        new DistilleryAccess.AtomicBrewRemoval(1, secondSource)
                )).join()
        );

        DistillerySession.AtomicMovePersistenceException atomicFailure =
                assertInstanceOf(DistillerySession.AtomicMovePersistenceException.class, failure.getCause());
        assertTrue(atomicFailure.rollbackConfirmed());
        assertSameBrew(firstSource, find(0, false));
        assertSameBrew(secondSource, find(1, false));
        assertEquals(2, rowCount());
    }

    @Test
    void removeMixtureBrewsAtomicallyMarksThrownCommitOutcomeAmbiguous() throws SQLException {
        Brew source = brew(16);
        insert(0, false, source);
        SqLiteDistillerySession ambiguousCommitSession = new SqLiteDistillerySession(
                Runnable::run,
                () -> commitThenThrowConnection(openConnection()),
                CompletableFuture.completedFuture(ingredientManager)
        );

        CompletionException failure = assertThrows(CompletionException.class, () ->
                ambiguousCommitSession.removeMixtureBrewsAtomically(LOCATION, List.of(
                        new DistilleryAccess.AtomicBrewRemoval(0, source)
                )).join()
        );

        DistillerySession.AtomicMovePersistenceException atomicFailure =
                assertInstanceOf(DistillerySession.AtomicMovePersistenceException.class, failure.getCause());
        assertFalse(atomicFailure.rollbackConfirmed());
        // The proxy committed and then reported failure, modelling the JDBC ambiguity that forces
        // BukkitDistillery to retain its internal reservation until authoritative reload.
        assertNull(find(0, false));
        assertEquals(0, rowCount());
    }

    @Test
    void consumeDistilleryAtomicallyDeletesStructureAndEveryBrew() throws SQLException {
        insert(0, false, brew(17));
        insert(2, true, brew(18));

        boolean committed = session.consumeDistilleryAtomically(LOCATION).join();

        assertTrue(committed);
        assertEquals(0, rowCount());
        assertEquals(0, structureCount());
    }

    @Test
    void consumeDistilleryAtomicallyRollsBackBrewsWhenStructureDeleteFails() throws SQLException {
        Brew mixture = brew(19);
        Brew distillate = brew(20);
        insert(0, false, mixture);
        insert(2, true, distillate);
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TRIGGER fail_atomic_structure_delete
                    BEFORE DELETE ON distilleries
                    BEGIN
                        SELECT RAISE(FAIL, 'injected structure delete failure');
                    END
                    """);
        }

        CompletionException failure = assertThrows(CompletionException.class, () ->
                session.consumeDistilleryAtomically(LOCATION).join()
        );

        DistillerySession.AtomicMovePersistenceException atomicFailure =
                assertInstanceOf(DistillerySession.AtomicMovePersistenceException.class, failure.getCause());
        assertTrue(atomicFailure.rollbackConfirmed());
        assertSameBrew(mixture, find(0, false));
        assertSameBrew(distillate, find(2, true));
        assertEquals(2, rowCount());
        assertEquals(1, structureCount());
    }

    @Test
    void consumeDistilleryAtomicallyMarksThrownCommitOutcomeAmbiguous() throws SQLException {
        insert(0, false, brew(21));
        SqLiteDistillerySession ambiguousCommitSession = new SqLiteDistillerySession(
                Runnable::run,
                () -> commitThenThrowConnection(openConnection()),
                CompletableFuture.completedFuture(ingredientManager)
        );

        CompletionException failure = assertThrows(CompletionException.class, () ->
                ambiguousCommitSession.consumeDistilleryAtomically(LOCATION).join()
        );

        DistillerySession.AtomicMovePersistenceException atomicFailure =
                assertInstanceOf(DistillerySession.AtomicMovePersistenceException.class, failure.getCause());
        assertFalse(atomicFailure.rollbackConfirmed());
        assertEquals(0, rowCount(), "the proxy committed before reporting the ambiguous failure");
        assertEquals(0, structureCount());
    }

    private static Brew brew(int distillationRuns) {
        return new BrewImpl(List.of(new DistillStepImpl(distillationRuns)));
    }

    private Connection openConnection() throws PersistenceException {
        try {
            return DriverManager.getConnection(jdbcUrl);
        } catch (SQLException failure) {
            throw new PersistenceException(failure);
        }
    }

    private static Connection commitThenThrowConnection(Connection delegate) {
        return (Connection) Proxy.newProxyInstance(
                Connection.class.getClassLoader(),
                new Class<?>[]{Connection.class},
                (proxy, method, arguments) -> {
                    try {
                        Object result = method.invoke(delegate, arguments);
                        if (method.getName().equals("commit")) {
                            throw new SQLException("injected ambiguous commit result");
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                }
        );
    }

    private void insert(int position, boolean distillate, Brew brew) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO distillery_brews VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            setLocation(statement);
            statement.setInt(5, position);
            statement.setBoolean(6, distillate);
            statement.setString(7, BrewImpl.SERIALIZER.serialize(brew, ingredientManager).toString());
            statement.executeUpdate();
        }
    }

    private void insertDistillery() throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO distilleries VALUES (?, ?, ?, ?)")) {
            setLocation(statement);
            statement.executeUpdate();
        }
    }

    private Brew find(int position, boolean distillate) throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             PreparedStatement statement = connection.prepareStatement("""
                     SELECT brew FROM distillery_brews
                     WHERE unique_x = ? AND unique_y = ? AND unique_z = ? AND world_uuid = ?
                       AND pos = ? AND is_distillate = ?
                     """)) {
            setLocation(statement);
            statement.setInt(5, position);
            statement.setBoolean(6, distillate);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return BrewImpl.SERIALIZER.deserialize(
                        com.google.gson.JsonParser.parseString(resultSet.getString("brew")), ingredientManager
                );
            }
        }
    }

    private int rowCount() throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM distillery_brews")) {
            return resultSet.getInt(1);
        }
    }

    private int structureCount() throws SQLException {
        try (Connection connection = DriverManager.getConnection(jdbcUrl);
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery("SELECT COUNT(*) FROM distilleries")) {
            return resultSet.getInt(1);
        }
    }

    private static void setLocation(PreparedStatement statement) throws SQLException {
        statement.setInt(1, LOCATION.x());
        statement.setInt(2, LOCATION.y());
        statement.setInt(3, LOCATION.z());
        statement.setBytes(4, DecoderEncoder.asBytes(LOCATION.worldUuid()));
    }

    private static void assertSameBrew(Brew expected, Brew actual) {
        assertEquals(expected.getSteps(), actual.getSteps());
        assertEquals(expected.meta(), actual.meta());
    }
}
