package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.api.breweries.CauldronType;
import dev.jsinco.brewery.api.ingredient.ResolvedIngredientManager;
import dev.jsinco.brewery.api.persistence.CauldronPersistenceReceipt;
import dev.jsinco.brewery.api.persistence.CauldronPersistenceSnapshot;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.DistillStepImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.TheBrewingProjectApi;
import dev.jsinco.brewery.bukkit.breweries.BukkitCauldron;
import dev.jsinco.brewery.bukkit.database.SessionTypes;
import dev.jsinco.brewery.bukkit.testutil.CauldronOwnerServerMock;
import dev.jsinco.brewery.util.DecoderEncoder;
import org.bukkit.Bukkit;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Public owner API with real SQLite; MockBukkit supplies registry/lifecycle, not client physics. */
class CauldronPersistenceReceiptTest {
    private CauldronOwnerServerMock server;
    private TheBrewingProject plugin;
    private WorldMock world;
    @BeforeEach void start() throws Exception {
        server = MockBukkit.mock(new CauldronOwnerServerMock()); world = server.addSimpleWorld("cauldron-receipts");
        plugin = MockBukkit.load(TheBrewingProject.class); plugin.getResolvedIngredientManager().join();
        plugin.getDatabase().flush().join(); server.publishGlobal();
        assertTrue(plugin.getAtomicMutationGate().reserve(() -> true));
    }
    @AfterEach void stop() { MockBukkit.unmock(); }
    private Holder holder(int x, int value) {
        return new Holder(new BreweryLocation(x, 65, 3, world.getUID()), value);
    }
    private void persist(Holder holder) throws Exception {
        plugin.getDatabase().startSession(SessionTypes.CAULDRON_SESSION_TYPE).insertCauldron(holder).join();
        plugin.getBreweryRegistry().addActiveSinglePositionStructure(holder);
    }
    private void await(CauldronPersistenceReceipt receipt) throws Exception {
        try { receipt.completion().toCompletableFuture().get(5, TimeUnit.SECONDS); }
        catch (ExecutionException expectedFailure) { /* Assert the owner state separately. */ }
    }
    private String row(Holder holder) throws Exception {
        try (var c = plugin.getDatabase().getConnection(); var s = c.prepareStatement(
                "SELECT brew FROM cauldrons WHERE cauldron_x=? AND cauldron_y=? AND cauldron_z=? AND world_uuid=?")) {
            s.setInt(1, holder.position().x()); s.setInt(2, holder.position().y()); s.setInt(3, holder.position().z());
            s.setBytes(4, DecoderEncoder.asBytes(holder.position().worldUuid()));
            try (var rows = s.executeQuery()) { return rows.next() ? rows.getString(1) : null; }
        }
    }
    private final class Holder extends BukkitCauldron {
        private int destroyed;
        private Runnable callback = () -> { };
        private Holder(BreweryLocation at, int value) { super(new BrewImpl(List.of(new DistillStepImpl(value))), at, CauldronType.WATER); }
        @Override public void destroy() { destroyed++; callback.run(); }
    }

    @Test void exactNativeRetirementAcknowledgesSqlAndPreservesForeignControl() throws Exception {
        var owner = holder(1, 1); var control = holder(9, 9); persist(owner); persist(control);
        String controlBefore = row(control);
        var receipt = plugin.retireCauldron(owner); await(receipt);
        assertEquals(CauldronPersistenceReceipt.State.OBSERVED, receipt.state()); assertTrue(receipt.current());
        assertTrue(receipt.deletionAcknowledged()); assertEquals(1, owner.destroyed); assertNull(row(owner));
        assertEquals(List.of(new CauldronPersistenceSnapshot.Entry(owner.position(), Optional.empty())), receipt.observation().orElseThrow().entries());
        assertEquals(controlBefore, row(control)); assertSame(control, plugin.getBreweryRegistry().getActiveSinglePositionStructure(control.position()).orElseThrow());
        assertEquals(0, control.destroyed); assertThrows(IllegalStateException.class, () -> plugin.retireCauldron(owner));
    }
    @Test void callerCompletedOrCancelledFuturesCannotAcknowledgeAnOriginalPendingDelete() throws Exception {
        var owner = holder(1, 1);
        var ingredients = new CompletableFuture<ResolvedIngredientManager<ItemStack>>();
        var delayed = new SqLiteCauldronSession(Runnable::run, plugin.getDatabase()::getConnection, ingredients, plugin.getCauldronPersistenceOrder());
        var insert = delayed.insertCauldron(owner); plugin.getBreweryRegistry().addActiveSinglePositionStructure(owner);
        try {
            var receipt = plugin.retireCauldron(owner);
            var forged = new CauldronPersistenceSnapshot(List.of(new CauldronPersistenceSnapshot.Entry(owner.position(), Optional.empty())));
            assertTrue(receipt.completion().toCompletableFuture().complete(forged));
            assertTrue(receipt.completion().toCompletableFuture().cancel(true)); insert.cancel(true);
            assertEquals(CauldronPersistenceReceipt.State.PENDING, receipt.state()); assertFalse(receipt.deletionAcknowledged());
            assertFalse(receipt.current()); assertTrue(receipt.observation().isEmpty());
            ingredients.complete(plugin.getResolvedIngredientManager().join()); await(receipt);
            assertTrue(receipt.deletionAcknowledged()); assertEquals(CauldronPersistenceReceipt.State.OBSERVED, receipt.state());
            assertTrue(receipt.current()); assertNull(row(owner));
        } finally { ingredients.complete(plugin.getResolvedIngredientManager().join()); }
    }
    @Test void teardownCallbackReplacementIsNotDeletedByTheOriginalHolder() throws Exception {
        var owner = holder(1, 1); var replacement = holder(1, 2); var control = holder(9, 9); persist(owner); persist(control);
        String before = row(control);
        owner.callback = () -> {
            try {
                plugin.getDatabase().startSession(SessionTypes.CAULDRON_SESSION_TYPE).removeCauldron(owner).join();
                persist(replacement);
            } catch (Exception e) { throw new IllegalStateException(e); }
        };
        assertThrows(IllegalStateException.class, () -> plugin.retireCauldron(owner));
        assertNotNull(row(replacement)); assertTrue(row(replacement).contains("2"));
        assertSame(replacement, plugin.getBreweryRegistry().getActiveSinglePositionStructure(owner.position()).orElseThrow());
        assertEquals(0, replacement.destroyed); assertEquals(before, row(control));
    }
    @Test void callbackProviderReplacementRefusesBeforeDelete() throws Exception {
        var owner = holder(1, 1); persist(owner); String before = row(owner);
        owner.callback = () -> Bukkit.getServicesManager().unregister(TheBrewingProjectApi.class, plugin);
        assertThrows(IllegalStateException.class, () -> plugin.retireCauldron(owner));
        assertEquals(before, row(owner)); assertSame(owner, plugin.getBreweryRegistry().getActiveSinglePositionStructure(owner.position()).orElseThrow());
    }
    @Test void callbackDatabaseReplacementRefusesBeforeAnyDeleteInEitherDatabase() throws Exception {
        var owner = holder(1, 1); persist(owner); String before = row(owner);
        var database = plugin.getDatabase();
        var replacement = new dev.jsinco.brewery.database.sql.SqlDatabase(dev.jsinco.brewery.database.sql.DatabaseDriver.SQLITE);
        var field = TheBrewingProject.class.getDeclaredField("database"); field.setAccessible(true);
        owner.callback = () -> {
            try { field.set(plugin, replacement); }
            catch (IllegalAccessException e) { throw new IllegalStateException(e); }
        };
        try { assertThrows(IllegalStateException.class, () -> plugin.retireCauldron(owner)); }
        finally { field.set(plugin, database); replacement.close().join(); }
        assertEquals(before, row(owner));
        assertSame(owner, plugin.getBreweryRegistry().getActiveSinglePositionStructure(owner.position()).orElseThrow());
    }
    @Test void asynchronousSqlDeleteFailureCannotBecomeAbsenceOrSuccessfulAcknowledgment() throws Exception {
        var owner = holder(1, 1); var control = holder(9, 9); persist(owner); persist(control);
        String before = row(owner), controlBefore = row(control);
        try (var c = plugin.getDatabase().getConnection(); var s = c.createStatement()) {
            s.execute("CREATE TRIGGER fail_retirement BEFORE DELETE ON cauldrons WHEN OLD.cauldron_x=1 BEGIN SELECT RAISE(ABORT,'injected delete failure'); END");
        }
        var receipt = plugin.retireCauldron(owner); await(receipt);
        assertEquals(CauldronPersistenceReceipt.State.FAILED, receipt.state()); assertFalse(receipt.deletionAcknowledged());
        assertFalse(receipt.current()); assertTrue(receipt.observation().isEmpty()); assertEquals(before, row(owner));
        assertEquals(controlBefore, row(control)); assertThrows(IllegalStateException.class, () -> plugin.retireCauldron(owner));
    }
    @Test void nativeReplacementInvalidatesPriorSuccessfulSqlObservation() throws Exception {
        var owner = holder(1, 1); persist(owner);
        var receipt = plugin.retireCauldron(owner); await(receipt);
        assertTrue(receipt.deletionAcknowledged());
        // A later native admission invalidates the old absence proof even after success.
        var replacement = holder(1, 2); persist(replacement);
        assertFalse(receipt.current()); assertNotNull(row(replacement));
        var latest = plugin.inspectPersistedCauldrons(List.of(owner.position())); await(latest);
        assertTrue(latest.current()); assertFalse(latest.deletionAcknowledged());
        assertTrue(latest.observation().orElseThrow().entries().getFirst().row().isPresent());
    }
    @Test void originalDeleteCanSucceedWhileARecreatedSqlRowRefusesAbsentReadback() throws Exception {
        var owner = holder(1, 1); var control = holder(9, 9); persist(owner); persist(control);
        String before = row(control);
        try (var c = plugin.getDatabase().getConnection(); var s = c.createStatement()) {
            s.execute("CREATE TRIGGER replace_deleted_cauldron AFTER DELETE ON cauldrons WHEN OLD.cauldron_x=1 "
                    + "BEGIN INSERT INTO cauldrons VALUES(OLD.cauldron_x,OLD.cauldron_y,OLD.cauldron_z,OLD.world_uuid,'foreign-after-delete','water'); END");
        }
        var receipt = plugin.retireCauldron(owner); await(receipt);
        assertTrue(receipt.deletionAcknowledged()); assertEquals(CauldronPersistenceReceipt.State.FAILED, receipt.state());
        assertFalse(receipt.current()); assertTrue(receipt.observation().isEmpty());
        assertEquals("foreign-after-delete", row(owner)); assertEquals(before, row(control));
        assertThrows(IllegalStateException.class, () -> plugin.retireCauldron(owner));
    }
    @Test void completionCallbackInsertDeleteAbaCannotBlessOldAbsence() throws Exception {
        var owner = holder(1, 1); persist(owner);
        var receipt = plugin.retireCauldron(owner); await(receipt); assertTrue(receipt.current());
        var replacement = holder(1, 2);
        receipt.completion().thenRun(() -> {
            try {
                persist(replacement);
                plugin.getDatabase().startSession(SessionTypes.CAULDRON_SESSION_TYPE).removeCauldron(replacement).join();
                plugin.getBreweryRegistry().removeActiveSinglePositionStructure(replacement);
            } catch (Exception e) { throw new IllegalStateException(e); }
        }).toCompletableFuture().join();
        assertNull(row(owner)); assertFalse(receipt.current()); assertTrue(receipt.deletionAcknowledged());
    }
    @Test void providerAndHydrationDriftCannotCertifyOldCompletedObservations() throws Exception {
        var owner = holder(1, 1); persist(owner);
        var first = plugin.inspectPersistedCauldrons(List.of(owner.position())); await(first); assertTrue(first.current());
        plugin.getCauldronPersistenceOrder().invalidate(world.getUID()); assertFalse(first.current());
        Bukkit.getServicesManager().unregister(TheBrewingProjectApi.class, plugin);
        assertFalse(first.current()); assertThrows(IllegalStateException.class, () -> plugin.inspectPersistedCauldrons(List.of(owner.position())));
        assertNotNull(row(owner));
    }
    @Test void providerReplacementWhileOriginalSqlPendingRefusesObservationAfterCompletion() throws Exception {
        var owner = holder(1, 1); var ingredients = new CompletableFuture<ResolvedIngredientManager<ItemStack>>();
        var delayed = new SqLiteCauldronSession(Runnable::run, plugin.getDatabase()::getConnection, ingredients, plugin.getCauldronPersistenceOrder());
        delayed.insertCauldron(owner); plugin.getBreweryRegistry().addActiveSinglePositionStructure(owner);
        try {
            var receipt = plugin.retireCauldron(owner);
            Bukkit.getServicesManager().unregister(TheBrewingProjectApi.class, plugin);
            ingredients.complete(plugin.getResolvedIngredientManager().join()); await(receipt);
            assertTrue(receipt.deletionAcknowledged()); assertEquals(CauldronPersistenceReceipt.State.FAILED, receipt.state());
            assertFalse(receipt.current()); assertNull(row(owner));
        } finally { ingredients.complete(plugin.getResolvedIngredientManager().join()); }
    }
    @Test void twentyExactRetirementsUseOneFreshBatchAbsenceInsteadOfReplayingStaleReceipts() throws Exception {
        var owners = new ArrayList<Holder>();
        for (int i = 0; i < 20; i++) { var owner = holder(i + 1, i + 1); persist(owner); owners.add(owner); }
        var control = holder(50, 99); persist(control); String before = row(control);
        var receipts = new ArrayList<CauldronPersistenceReceipt>();
        for (var owner : owners) { var receipt = plugin.retireCauldron(owner); await(receipt); receipts.add(receipt); }
        assertTrue(receipts.stream().allMatch(CauldronPersistenceReceipt::deletionAcknowledged));
        assertTrue(receipts.subList(0, 19).stream().noneMatch(CauldronPersistenceReceipt::current));
        var batch = plugin.inspectPersistedCauldrons(owners.stream().map(Holder::position).toList()); await(batch);
        assertTrue(batch.current()); assertEquals(20, batch.observation().orElseThrow().entries().size());
        assertTrue(batch.observation().orElseThrow().entries().stream().allMatch(entry -> entry.row().isEmpty()));
        assertTrue(owners.stream().allMatch(owner -> owner.destroyed == 1)); assertEquals(before, row(control));
        assertSame(control, plugin.getBreweryRegistry().getActiveSinglePositionStructure(control.position()).orElseThrow());
    }
    @Test void extractionReservationWrongIdentityAndWrongThreadRefuseBeforeNativeMutation() throws Exception {
        var owner = holder(1, 1); persist(owner); String before = row(owner);
        var impostor = holder(1, 1);
        assertThrows(IllegalStateException.class, () -> plugin.retireCauldron(impostor));
        try (var extraction = owner.reserveExtraction().orElseThrow()) {
            assertThrows(IllegalStateException.class, () -> plugin.retireCauldron(owner));
        }
        assertThrows(ExecutionException.class, () -> CompletableFuture.supplyAsync(() -> plugin.retireCauldron(owner)).get(5, TimeUnit.SECONDS));
        assertEquals(before, row(owner)); assertEquals(0, owner.destroyed); assertEquals(0, impostor.destroyed);
    }
}
