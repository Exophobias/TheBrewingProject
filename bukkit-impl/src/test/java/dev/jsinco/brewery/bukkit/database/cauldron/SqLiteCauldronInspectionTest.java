package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.api.persistence.CauldronPersistenceSnapshot;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.util.DecoderEncoder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class SqLiteCauldronInspectionTest {
    @TempDir Path directory;
    private String jdbc;
    private final CauldronPersistenceOrder order = new CauldronPersistenceOrder();
    private final BreweryLocation key = new BreweryLocation(1, 64, 3, UUID.randomUUID());
    private final ArrayDeque<Runnable> tasks = new ArrayDeque<>();
    @BeforeEach void setup() throws Exception {
        jdbc = "jdbc:sqlite:" + directory.resolve("inspection.db");
        try (var c = DriverManager.getConnection(jdbc); var s = c.createStatement()) {
            // No PK allows reproducing duplicate physical rows, which must never imply absence.
            s.execute("CREATE TABLE cauldrons(cauldron_x INTEGER,cauldron_y INTEGER,cauldron_z INTEGER,world_uuid BLOB,brew TEXT,cauldron_type TEXT,birth_uuid BLOB)");
        }
    }
    private SqLiteCauldronInspectionSession session() {
        return new SqLiteCauldronInspectionSession(tasks::addLast, () -> {
            try { return DriverManager.getConnection(jdbc); }
            catch (java.sql.SQLException e) { throw new dev.jsinco.brewery.database.PersistenceException(e); }
        });
    }
    private UUID row(BreweryLocation at, String brew, String type) throws Exception {
        UUID birth = UUID.randomUUID();
        try (var c = DriverManager.getConnection(jdbc); var s = c.prepareStatement("INSERT INTO cauldrons VALUES(?,?,?,?,?,?,?)")) {
            s.setInt(1, at.x()); s.setInt(2, at.y()); s.setInt(3, at.z());
            s.setBytes(4, DecoderEncoder.asBytes(at.worldUuid())); s.setString(5, brew); s.setString(6, type);
            s.setBytes(7, DecoderEncoder.asBytes(birth)); s.executeUpdate();
        }
        return birth;
    }
    private void pump() { while (!tasks.isEmpty()) tasks.removeFirst().run(); }
    private CompletableFuture<CauldronPersistenceSnapshot> read(List<BreweryLocation> keys) {
        var observation = order.observe(keys);
        return session().inspect(keys, observation).whenComplete((value, failure) -> observation.release());
    }

    @Test void missingMalformedAndDuplicatedBirthsRefuseEvidenceInsteadOfImplyingAbsence() throws Exception {
        row(key, "same brew", "water");
        for (String invalid : List.of("NULL", "X'01'", "'0011223344556677'", "zeroblob(17)")) {
            try (var c = DriverManager.getConnection(jdbc); var s = c.createStatement()) {
                s.executeUpdate("UPDATE cauldrons SET birth_uuid=" + invalid);
            }
            var failed = read(List.of(key)); pump(); assertThrows(CompletionException.class, failed::join);
        }
        var second = new BreweryLocation(2, 64, 3, key.worldUuid()); row(second, "same brew", "water");
        try (var c = DriverManager.getConnection(jdbc); var s = c.createStatement()) {
            s.executeUpdate("UPDATE cauldrons SET birth_uuid=X'00112233445566778899aabbccddeeff'");
        }
        var failed = read(List.of(key, second)); pump(); assertThrows(CompletionException.class, failed::join);
        assertTrue(new CauldronPersistenceSnapshot.Row("older provider", Optional.empty()).birthUuid().isEmpty());
    }

    @Test void exactRowsRetainUnknownSerializationLegacyNullTypeAndForeignControl() throws Exception {
        var foreign = new BreweryLocation(1, 64, 3, UUID.randomUUID());
        var absent = new BreweryLocation(2, 64, 3, key.worldUuid());
        UUID birth = row(key, "{\"future\":\"\\u2603\",\"nested\":[1,2]}", null); row(foreign, "untouched", "future-type");
        var result = read(List.of(key, absent)); pump();
        assertEquals(List.of(new CauldronPersistenceSnapshot.Entry(key, Optional.of(new CauldronPersistenceSnapshot.Row(
                "{\"future\":\"\\u2603\",\"nested\":[1,2]}", Optional.empty(), Optional.of(birth)))),
                new CauldronPersistenceSnapshot.Entry(absent, Optional.empty())), result.join().entries());
        var control = read(List.of(foreign)); pump();
        assertEquals("untouched", control.join().entries().getFirst().row().orElseThrow().serializedBrew());
        assertEquals(Optional.of("future-type"), control.join().entries().getFirst().row().orElseThrow().cauldronType());
    }
    @Test void originalDependencyMustCompleteEvenAfterConsumerCancelsItsCopy() {
        var dependency = new CompletableFuture<Void>();
        var owner = order.newOwner(key);
        var caller = order.admit(owner, CauldronPersistenceOrder.Write.INSERT, ignored -> dependency);
        var result = read(List.of(key)); caller.cancel(true);
        assertTrue(tasks.isEmpty()); assertFalse(result.isDone());
        dependency.complete(null); pump(); assertTrue(result.join().entries().getFirst().row().isEmpty());
    }
    @Test void failedOriginalDependencyCannotBecomeAnEmptyRowObservation() {
        var dependency = new CompletableFuture<Void>();
        order.admit(order.newOwner(key), CauldronPersistenceOrder.Write.INSERT, ignored -> dependency);
        var result = read(List.of(key)); dependency.completeExceptionally(new IllegalStateException("original insert failed")); pump();
        assertThrows(CompletionException.class, result::join); assertTrue(tasks.isEmpty());
    }
    @Test void insertionThenDeletionAbaInvalidatesAnEarlierAbsentObservation() {
        var observation = order.observe(List.of(key));
        var result = session().inspect(List.of(key), observation); pump(); assertTrue(result.join().entries().getFirst().row().isEmpty());
        var owner = order.newOwner(key);
        order.admit(owner, CauldronPersistenceOrder.Write.INSERT, ignored -> CompletableFuture.completedFuture(null)).join();
        order.admit(owner, CauldronPersistenceOrder.Write.DELETE, ignored -> CompletableFuture.completedFuture(null)).join();
        assertEquals(0, order.status().coordinates()); assertFalse(observation.current()); observation.release();
    }
    @Test void laterAdmissionBeforeQueuedReadRefusesInsteadOfReadingNewGeneration() {
        var result = read(List.of(key));
        order.admit(order.newOwner(key), CauldronPersistenceOrder.Write.INSERT, ignored -> CompletableFuture.completedFuture(null));
        pump(); assertThrows(CompletionException.class, result::join);
    }
    @Test void reloadInvalidatesColdAbsentInspectionEvenWithoutAnyRuntimeOwnerEpoch() {
        var observation = order.observe(List.of(key));
        var result = session().inspect(List.of(key), observation); pump();
        assertTrue(result.join().entries().getFirst().row().isEmpty()); assertTrue(observation.current());
        order.invalidateAll(); assertFalse(observation.current()); observation.release();
    }
    @Test void lifecycleChangesWhileOpeningConnectionInvalidateReadAndDoNotWrite() throws Exception {
        row(key, "control", "water");
        var observation = order.observe(List.of(key));
        var reader = new SqLiteCauldronInspectionSession(tasks::addLast, () -> {
            order.invalidate(key.worldUuid());
            try { return DriverManager.getConnection(jdbc); }
            catch (java.sql.SQLException e) { throw new dev.jsinco.brewery.database.PersistenceException(e); }
        });
        var result = reader.inspect(List.of(key), observation); pump();
        assertThrows(CompletionException.class, result::join); observation.release();
        try (var c = DriverManager.getConnection(jdbc); var s = c.createStatement(); var rows = s.executeQuery("SELECT brew FROM cauldrons")) {
            assertTrue(rows.next()); assertEquals("control", rows.getString(1)); assertFalse(rows.next());
        }
    }
    @Test void duplicateRowsNullBrewAndMissingTableEachRefuse() throws Exception {
        row(key, "one", "water"); row(key, "two", "water");
        var duplicate = read(List.of(key)); pump(); assertThrows(CompletionException.class, duplicate::join);
        var malformed = new BreweryLocation(2, 64, 3, key.worldUuid()); row(malformed, null, "water");
        var nullBrew = read(List.of(malformed)); pump(); assertThrows(CompletionException.class, nullBrew::join);
        try (var c = DriverManager.getConnection(jdbc); var s = c.createStatement()) { s.execute("DROP TABLE cauldrons"); }
        var missing = read(List.of(key)); pump(); assertThrows(CompletionException.class, missing::join);
    }
    @Test void textBudgetRejectsOversizedActualSqlRow() throws Exception {
        row(key, "x".repeat(SqLiteCauldronInspectionSession.MAX_TEXT_CHARS + 1), "water");
        var result = read(List.of(key)); pump(); assertThrows(CompletionException.class, result::join);
    }
    @Test void embeddedNullCannotBypassPreMaterializationSqlByteBudget() throws Exception {
        row(key, "prefix\u0000" + "x".repeat(SqLiteCauldronInspectionSession.MAX_TEXT_CHARS + 1), "water");
        var materialized = new java.util.concurrent.atomic.AtomicInteger();
        var observation = order.observe(List.of(key));
        var reader = new SqLiteCauldronInspectionSession(tasks::addLast, () -> {
            try {
                var actual = DriverManager.getConnection(jdbc);
                return (java.sql.Connection) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                        new Class<?>[]{java.sql.Connection.class}, (p, m, args) -> {
                            Object value = invoke(actual, m, args);
                            if (!m.getName().equals("prepareStatement")) return value;
                            var statement = (java.sql.PreparedStatement) value;
                            return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                    new Class<?>[]{java.sql.PreparedStatement.class}, (sp, sm, sa) -> {
                                        Object result = invoke(statement, sm, sa);
                                        if (!sm.getName().equals("executeQuery")) return result;
                                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                                                new Class<?>[]{java.sql.ResultSet.class}, (rp, rm, ra) -> {
                                                    if (rm.getName().equals("getObject")) materialized.incrementAndGet();
                                                    return invoke(result, rm, ra);
                                                });
                                    });
                        });
            } catch (java.sql.SQLException e) { throw new dev.jsinco.brewery.database.PersistenceException(e); }
        });
        var result = reader.inspect(List.of(key), observation); pump();
        assertThrows(CompletionException.class, result::join);
        assertEquals(0, materialized.get(), "length(TEXT) stops at NUL; reject by SQL byte length before allocating the raw string");
        observation.release();
    }
    private static Object invoke(Object target, java.lang.reflect.Method method, Object[] args) throws Throwable {
        try { return method.invoke(target, args); }
        catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
    }
    @Test void strictKeyAndConcurrentAdmissionBoundsAreAppliedBeforeQueuing() {
        assertThrows(IllegalArgumentException.class, () -> read(List.of()));
        assertThrows(IllegalArgumentException.class, () -> read(List.of(key, key)));
        assertThrows(IllegalArgumentException.class, () -> read(List.of(key, new BreweryLocation(0, 0, 0, UUID.randomUUID()))));
        assertThrows(IllegalArgumentException.class, () -> read(java.util.stream.IntStream.range(0, 33)
                .mapToObj(i -> new BreweryLocation(i, 0, 0, key.worldUuid())).toList()));
        var permits = new ArrayList<CauldronPersistenceOrder.Observation>();
        for (int i = 0; i < CauldronPersistenceOrder.MAX_INSPECTIONS; i++) permits.add(order.observe(List.of(key)));
        assertThrows(IllegalStateException.class, () -> order.observe(List.of(key))); assertTrue(tasks.isEmpty());
        permits.forEach(CauldronPersistenceOrder.Observation::release);
        var reusable = order.observe(List.of(key)); reusable.release(); reusable.release();
        var wrongKey = new BreweryLocation(2, 64, 3, key.worldUuid());
        assertThrows(IllegalArgumentException.class, () -> session().inspect(List.of(wrongKey), reusable));
        order.stop(); assertThrows(IllegalStateException.class, () -> order.observe(List.of(key)));
    }
}
