package dev.jsinco.brewery.bukkit.database.hydration;

import dev.jsinco.brewery.api.persistence.BreweryPersistenceSnapshot;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.bukkit.api.TheBrewingProjectApi;
import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.util.DecoderEncoder;
import dev.jsinco.brewery.util.FileUtil;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SqLiteExactStructureInspectionSessionTest {
    @TempDir Path directory;
    private final UUID world = UUID.randomUUID();
    private final BreweryLocation key = new BreweryLocation(4, 5, 6, world);
    private String jdbc;
    private SqLiteExactStructureInspectionSession session;

    @BeforeEach void setup() throws Exception {
        jdbc = "jdbc:sqlite:" + directory.resolve("exact.db");
        try (var c = connection(); var s = c.createStatement()) {
            s.execute("PRAGMA journal_mode=WAL");
            for (String sql : FileUtil.readInternalResource("/database/sqlite/create_all_tables.sql").split(";"))
                if (!sql.isBlank()) s.execute(sql);
        }
        session = new SqLiteExactStructureInspectionSession(Runnable::run, this::connection);
    }

    @Test void exactParentAndRawMixtureOutputRowsPreserveAllIdentityWithoutHydration() throws Exception {
        insert("INSERT INTO distilleries VALUES(1,2,3,4,5,6,?,'matrix','still',4294967299)");
        insert("INSERT INTO distillery_brews VALUES(4,5,6,?,0,0,'mixture')");
        insert("INSERT INTO distillery_brews VALUES(4,5,6,?,0,1,'output')");
        var entry = session.inspect(List.of(key)).join().entries().getFirst();
        assertEquals(key, entry.location());
        var parent = entry.distillery().orElseThrow();
        assertEquals(4294967299L, parent.startTime());
        assertEquals(new BreweryLocation(1, 2, 3, world), parent.structure().origin());
        assertEquals("matrix", parent.structure().transformation());
        assertEquals("still", parent.structure().format());
        assertEquals(List.of(new BreweryPersistenceSnapshot.BrewRow(0, false, "mixture"),
                new BreweryPersistenceSnapshot.BrewRow(0, true, "output")), entry.distilleryBrews());
        assertFalse(entry.absent());
        assertTrue(entry.barrel().isEmpty());
    }

    @Test void barrelParentTypeSizeAndOrphanBrewsRemainVisibleWithoutParent() throws Exception {
        insert("INSERT INTO barrels VALUES(1,2,3,4,5,6,?,'identity','barrel','oak',9)");
        insert("INSERT INTO barrel_brews VALUES(4,5,6,?,8,'barrel output')");
        insert("INSERT INTO distillery_brews VALUES(4,5,6,?,7,1,'orphan output')");
        var entry = session.inspect(List.of(key)).join().entries().getFirst();
        assertEquals("oak", entry.barrel().orElseThrow().type());
        assertEquals(9, entry.barrel().orElseThrow().size());
        assertEquals(8, entry.barrelBrews().getFirst().position());
        assertTrue(entry.distillery().isEmpty());
        assertEquals("orphan output", entry.distilleryBrews().getFirst().serializedBrew());
        assertFalse(entry.absent());
    }

    @Test void absentKeysAndOtherWorldOrNeighbourCannotBeConfused() throws Exception {
        insert("INSERT INTO distilleries VALUES(1,2,3,4,5,6,?,'matrix','still',7)");
        assertTrue(session.inspect(List.of(new BreweryLocation(4, 5, 7, world))).join().entries().getFirst().absent());
        assertTrue(session.inspect(List.of(new BreweryLocation(4, 5, 6, UUID.randomUUID()))).join().entries().getFirst().absent());
    }

    @Test void snapshotListsAreImmutableAndInputIsCapturedBeforeQueueing() {
        List<Runnable> queued = new ArrayList<>();
        var delayed = new SqLiteExactStructureInspectionSession(queued::add, this::connection);
        var keys = new ArrayList<>(List.of(key));
        var pending = delayed.inspect(keys);
        keys.clear();
        assertFalse(pending.isDone());
        queued.removeFirst().run();
        var snapshot = pending.join();
        assertEquals(key, snapshot.entries().getFirst().location());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entries().getFirst().distilleryBrews().clear());
        assertThrows(UnsupportedOperationException.class, () -> snapshot.entries().getFirst().barrelBrews().clear());
    }

    @Test void invalidKeysNeverQueueOrOpenAConnection() {
        var forbidden = new SqLiteExactStructureInspectionSession(task -> fail("queued invalid request"),
                () -> { fail("opened connection"); return null; });
        assertThrows(NullPointerException.class, () -> forbidden.inspect(null));
        assertThrows(IllegalArgumentException.class, () -> forbidden.inspect(List.of()));
        assertThrows(IllegalArgumentException.class, () -> forbidden.inspect(List.of(key, key)));
        assertThrows(IllegalArgumentException.class, () -> forbidden.inspect(List.of(key,
                new BreweryLocation(1, 2, 3, UUID.randomUUID()))));
        assertThrows(NullPointerException.class, () -> forbidden.inspect(java.util.Arrays.asList(key, null)));
        var many = new ArrayList<BreweryLocation>();
        for (int i = 0; i < 33; i++) many.add(new BreweryLocation(i, 2, 3, world));
        assertThrows(IllegalArgumentException.class, () -> forbidden.inspect(many));
        assertEquals(32, session.inspect(many.subList(0, 32)).join().entries().size());
    }

    @Test void oldApiDefaultFailsClosedInsteadOfClaimingAbsence() {
        var old = (TheBrewingProjectApi) Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[]{TheBrewingProjectApi.class}, (proxy, method, arguments) ->
                        InvocationHandler.invokeDefault(proxy, method, arguments));
        var failure = assertThrows(CompletionException.class, () -> old.inspectPersistedStructures(List.of(key)).join());
        assertInstanceOf(UnsupportedOperationException.class, failure.getCause());
    }

    @Test void missingTableOrConnectionFailureIsExceptionalNeverPartialEvidence() throws Exception {
        insert("INSERT INTO distilleries VALUES(1,2,3,4,5,6,?,'matrix','still',7)");
        try (var c = connection(); var s = c.createStatement()) { s.execute("DROP TABLE barrel_brews"); }
        assertThrows(CompletionException.class, () -> session.inspect(List.of(key)).join());
        var failed = new SqLiteExactStructureInspectionSession(Runnable::run,
                () -> { throw new PersistenceException(new SQLException("unavailable")); });
        assertThrows(CompletionException.class, () -> failed.inspect(List.of(key)).join());
    }

    @Test void oneReadTransactionDoesNotMixRowsAcrossConcurrentCommittedOwnerChange() throws Exception {
        insert("INSERT INTO distilleries VALUES(1,2,3,4,5,6,?,'matrix','still',7)");
        insert("INSERT INTO distillery_brews VALUES(4,5,6,?,0,1,'before')");
        AtomicBoolean changed = new AtomicBoolean();
        List<String> statements = new ArrayList<>();
        var coherent = new SqLiteExactStructureInspectionSession(Runnable::run, () -> {
            Connection physical = connection();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class},
                    (proxy, method, args) -> {
                        if (method.getName().equals("prepareStatement")) {
                            String sql = (String) args[0];
                            statements.add(sql);
                            if (sql.startsWith("SELECT * FROM barrels") && changed.compareAndSet(false, true)) {
                                try (var other = connection(); var write = other.createStatement()) {
                                    write.execute("UPDATE distillery_brews SET brew='after'");
                                }
                            }
                        }
                        try { return method.invoke(physical, args); }
                        catch (InvocationTargetException e) { throw e.getCause(); }
                    });
        });
        assertEquals("before", coherent.inspect(List.of(key)).join().entries().getFirst()
                .distilleryBrews().getFirst().serializedBrew());
        assertTrue(changed.get());
        assertTrue(statements.stream().allMatch(sql -> sql.startsWith("SELECT ")));
        assertEquals("after", session.inspect(List.of(key)).join().entries().getFirst()
                .distilleryBrews().getFirst().serializedBrew());
    }

    @Test void malformedIntegerMetadataCannotBeCoercedIntoProof() throws Exception {
        insert("INSERT INTO distilleries VALUES(1,2,3,4,5,6,?,'matrix','still','not a time')");
        assertThrows(CompletionException.class, () -> session.inspect(List.of(key)).join());
    }

    @Test void excessRowsOrTextRefuseRatherThanTruncatingCleanEvidence() throws Exception {
        try (var c = connection(); var s = c.prepareStatement("INSERT INTO distillery_brews VALUES(4,5,6,?,?,0,'x')")) {
            for (int i = 0; i <= SqLiteExactStructureInspectionSession.MAX_BREW_ROWS; i++) {
                s.setBytes(1, DecoderEncoder.asBytes(world)); s.setInt(2, i); s.addBatch();
            }
            s.executeBatch();
        }
        assertThrows(CompletionException.class, () -> session.inspect(List.of(key)).join());
        try (var c = connection(); var s = c.createStatement()) { s.execute("DELETE FROM distillery_brews"); }
        try (var c = connection(); var s = c.prepareStatement("INSERT INTO barrel_brews VALUES(4,5,6,?,0,?)")) {
            s.setBytes(1, DecoderEncoder.asBytes(world)); s.setString(2, "x".repeat(SqLiteExactStructureInspectionSession.MAX_TEXT_CHARS + 1));
            s.executeUpdate();
        }
        assertThrows(CompletionException.class, () -> session.inspect(List.of(key)).join());
    }

    private void insert(String sql) throws Exception {
        try (var c = connection(); var s = c.prepareStatement(sql)) { s.setBytes(1, DecoderEncoder.asBytes(world)); s.executeUpdate(); }
    }
    private Connection connection() throws PersistenceException {
        try { return DriverManager.getConnection(jdbc); }
        catch (SQLException failure) { throw new PersistenceException(failure); }
    }
}
