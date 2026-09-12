package dev.jsinco.brewery.bukkit.database.hydration;

import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.util.DecoderEncoder;
import dev.jsinco.brewery.util.FileUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class SqLiteWorldHydrationSessionTest {
    @TempDir Path directory;
    private final UUID world = UUID.randomUUID();
    private String jdbc;
    private SqLiteWorldHydrationSession session;

    @BeforeEach
    void setUp() throws Exception {
        jdbc = "jdbc:sqlite:" + directory.resolve("snapshot.db");
        try (var connection = connection(); var statement = connection.createStatement()) {
            for (String sql : FileUtil.readInternalResource("/database/sqlite/create_all_tables.sql").split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
            // Deliberately omit constraints to prove the reader rejects corrupt identity evidence.
            statement.execute("ALTER TABLE cauldrons ADD COLUMN birth_uuid BLOB");
        }
        session = new SqLiteWorldHydrationSession(Runnable::run, this::connection);
    }

    @Test
    void readsRawRowsWithoutBukkitOrIngredientsAndRetainsLongTimeAndExactKeys() throws Exception {
        insert("INSERT INTO barrels VALUES (1,2,3,4,5,6,?, 'matrix','barrel','oak',9)");
        insert("INSERT INTO distilleries VALUES (11,12,13,14,15,16,?, 'matrix','still',4294967299)");
        insert("INSERT INTO barrel_brews VALUES (4,5,6,?, 8,'raw barrel brew')");
        insert("INSERT INTO distillery_brews VALUES (14,15,16,?, 0,0,'raw mixture brew')");
        insert("INSERT INTO distillery_brews VALUES (14,15,16,?, 0,1,'raw distillate brew')");
        insert("INSERT INTO cauldrons VALUES (24,25,26,?,'raw cauldron brew','water',X'00112233445566778899aabbccddeeff')");

        var snapshot = session.readWorld(world).join();
        assertEquals(1, snapshot.barrels().size());
        assertEquals(8, snapshot.barrels().getFirst().brews().getFirst().position());
        assertEquals("raw barrel brew", snapshot.barrels().getFirst().brews().getFirst().serializedBrew());
        var still = snapshot.distilleries().getFirst();
        assertEquals(4294967299L, still.startTime());
        assertEquals(14, still.structure().unique().x());
        assertEquals(11, still.structure().origin().x());
        assertEquals(2, still.brews().size());
        assertEquals(1, still.brews().stream().filter(WorldBrewerySnapshot.BrewRow::distillate).count());
        assertEquals("raw cauldron brew", snapshot.cauldrons().getFirst().serializedBrew());
        assertEquals(UUID.fromString("00112233-4455-6677-8899-aabbccddeeff"), snapshot.cauldrons().getFirst().birthUuid());
        assertThrows(UnsupportedOperationException.class, () -> still.brews().clear());
        assertTrue(session.readWorld(UUID.randomUUID()).join().distilleries().isEmpty());
    }

    @Test
    void emptyInventoriesRetainTheirHolders() throws Exception {
        insert("INSERT INTO barrels VALUES (1,2,3,4,5,6,?, 'matrix','barrel','oak',9)");
        insert("INSERT INTO distilleries VALUES (11,12,13,14,15,16,?, 'matrix','still',7)");
        var snapshot = session.readWorld(world).join();
        assertTrue(snapshot.barrels().getFirst().brews().isEmpty());
        assertTrue(snapshot.distilleries().getFirst().brews().isEmpty());
    }

    @Test
    void failedInventoryQueryCompletesExceptionallyInsteadOfLeavingWorldLoadPending() throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE distillery_brews");
        }
        var failed = session.readWorld(world);
        assertTrue(failed.isDone());
        assertThrows(CompletionException.class, failed::join);
    }

    @Test
    void failedHolderQueryDoesNotReturnAPartialWorldSnapshot() throws Exception {
        insert("INSERT INTO barrels VALUES (1,2,3,4,5,6,?, 'matrix','barrel','oak',9)");
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("DROP TABLE cauldrons");
        }
        assertThrows(CompletionException.class, () -> session.readWorld(world).join());
    }

    @Test void missingMalformedAndDuplicateBirthsRefuseTheWholeWorldSnapshot() throws Exception {
        insert("INSERT INTO cauldrons VALUES(1,2,3,?,'same brew','water',NULL)");
        for (String invalid : java.util.List.of("NULL", "X'01'", "'0011223344556677'", "zeroblob(17)")) {
            try (var c = connection(); var s = c.createStatement()) { s.executeUpdate("UPDATE cauldrons SET birth_uuid=" + invalid); }
            assertThrows(CompletionException.class, () -> session.readWorld(world).join());
        }
        try (var c = connection(); var s = c.createStatement()) {
            s.executeUpdate("UPDATE cauldrons SET birth_uuid=X'00112233445566778899aabbccddeeff'");
        }
        insert("INSERT INTO cauldrons VALUES(2,2,3,?,'same brew','water',X'00112233445566778899aabbccddeeff')");
        assertThrows(CompletionException.class, () -> session.readWorld(world).join());
        try (var c = connection(); var s = c.createStatement()) { s.execute("ALTER TABLE cauldrons DROP COLUMN birth_uuid"); }
        assertThrows(CompletionException.class, () -> session.readWorld(world).join());
    }

    private void insert(String sql) throws Exception {
        try (var connection = connection(); var statement = connection.prepareStatement(sql)) {
            statement.setBytes(1, DecoderEncoder.asBytes(world));
            statement.executeUpdate();
        }
    }

    private Connection connection() throws PersistenceException {
        try { return DriverManager.getConnection(jdbc); }
        catch (SQLException failure) { throw new PersistenceException(failure); }
    }
}
