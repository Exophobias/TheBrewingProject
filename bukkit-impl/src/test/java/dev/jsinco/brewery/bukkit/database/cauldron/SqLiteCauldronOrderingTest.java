package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.breweries.CauldronType;
import dev.jsinco.brewery.api.ingredient.ResolvedIngredientManager;
import dev.jsinco.brewery.api.meta.MetaDataType;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.DistillStepImpl;
import dev.jsinco.brewery.bukkit.breweries.BukkitCauldron;
import dev.jsinco.brewery.bukkit.database.BrewPersistenceSnapshot;
import dev.jsinco.brewery.bukkit.ingredient.ResolvedIngredientManagerImpl;
import net.kyori.adventure.key.Key;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actual SQLite statements with deliberately delayed ingredient resolution and separately created sessions. */
class SqLiteCauldronOrderingTest {
    @TempDir Path directory;
    private String jdbc;
    private final CauldronPersistenceOrder order = new CauldronPersistenceOrder();
    private final BreweryLocation key = new BreweryLocation(1, 64, 3, UUID.randomUUID());
    private final ArrayDeque<Runnable> sql = new ArrayDeque<>();
    private final CompletableFuture<ResolvedIngredientManager<ItemStack>> ingredients = new CompletableFuture<>();

    @BeforeEach void create() throws Exception {
        jdbc = "jdbc:sqlite:" + directory.resolve("cauldron.db");
        try (var connection = DriverManager.getConnection(jdbc); var statement = connection.createStatement()) {
            statement.execute("CREATE TABLE cauldrons (cauldron_x INTEGER NOT NULL, cauldron_y INTEGER NOT NULL, cauldron_z INTEGER NOT NULL, world_uuid BLOB NOT NULL, brew TEXT NOT NULL, cauldron_type TEXT, PRIMARY KEY(cauldron_x,cauldron_y,cauldron_z,world_uuid))");
        }
    }
    private SqLiteCauldronSession session() { return new SqLiteCauldronSession(sql::addLast, () -> {
        try { return DriverManager.getConnection(jdbc); }
        catch (SQLException failure) { throw new dev.jsinco.brewery.database.PersistenceException(failure); }
    }, ingredients, order); }
    private void pump() { while (!sql.isEmpty()) sql.removeFirst().run(); }
    private String stored() throws Exception {
        try (var connection = DriverManager.getConnection(jdbc); var statement = connection.createStatement();
             var rows = statement.executeQuery("SELECT brew || '|' || cauldron_type FROM cauldrons")) {
            String row = rows.next() ? rows.getString(1) : null; assertFalse(rows.next()); return row;
        }
    }
    private Brew brew(int n) { return new BrewImpl(List.of(new DistillStepImpl(n))); }
    private final class MutableHolder extends BukkitCauldron {
        Brew current; CauldronType type = CauldronType.WATER;
        MutableHolder(Brew initial) { super(initial, key, CauldronType.WATER, order); current = initial; }
        @Override public Brew getBrew() { return current; }
        @Override public CauldronType getCauldronType() { return type; }
    }

    @Test void insertAndUpdateFreezeNestedPayloadAndTypeBeforeIngredientFutureCompletes() throws Exception {
        byte[] value = {1, 2};
        var owner = new MutableHolder(brew(1).withMeta(Key.key("test", "bytes"), MetaDataType.BYTE_ARRAY, value));
        String first = BrewPersistenceSnapshot.captureNow(owner.current) + "|water";
        var insertion = session().insertCauldron(owner);
        value[0] = 9; owner.current = brew(2); owner.type = CauldronType.BREW;
        String second = BrewPersistenceSnapshot.captureNow(owner.current) + "|brew";
        var update = session().updateCauldron(owner);
        owner.current = brew(3); owner.type = CauldronType.LAVA;
        assertTrue(sql.isEmpty()); assertFalse(insertion.isDone()); assertFalse(update.isDone());
        ingredients.complete(new ResolvedIngredientManagerImpl());
        assertEquals(1, sql.size()); sql.removeFirst().run(); insertion.join(); assertEquals(first, stored());
        assertEquals(1, sql.size()); sql.removeFirst().run(); update.join(); assertEquals(second, stored());
    }

    @Test void actualInsertUpdateDeleteCannotReverseWhenOneIngredientFutureCompletes() throws Exception {
        var owner = new MutableHolder(brew(1));
        var inserted = session().insertCauldron(owner); owner.current = brew(2);
        var updated = session().updateCauldron(owner); var deleted = session().removeCauldron(owner);
        assertFalse(deleted.isDone()); assertTrue(sql.isEmpty());
        ingredients.complete(new ResolvedIngredientManagerImpl()); pump();
        CompletableFuture.allOf(inserted, updated, deleted).join(); assertNull(stored());
        assertFalse(owner.persistenceAvailable()); assertEquals(0, order.status().coordinates());
    }

    @Test void replacementInsertWaitsOldTerminalDeleteAndLateOldWritesCannotDeleteReplacement() throws Exception {
        var a = new MutableHolder(brew(1)); var b = new MutableHolder(brew(2));
        var inserted = session().insertCauldron(a);
        assertThrows(IllegalStateException.class, () -> session().insertCauldron(b));
        var deleted = session().removeCauldron(a); b.type = CauldronType.BREW;
        var replacement = session().insertCauldron(b);
        assertThrows(IllegalStateException.class, () -> session().updateCauldron(a));
        var repeatedDelete = session().removeCauldron(a);
        inserted.cancel(true); repeatedDelete.cancel(true);
        ingredients.complete(new ResolvedIngredientManagerImpl()); pump();
        replacement.join(); deleted.join();
        assertEquals(BrewPersistenceSnapshot.captureNow(b.current) + "|brew", stored());
        session().removeCauldron(a).join(); assertTrue(sql.isEmpty());
        assertEquals(BrewPersistenceSnapshot.captureNow(b.current) + "|brew", stored());
    }

    @Test void sqlFailureLatchesAcrossSessionsAndLeavesOldDurableRowUntouched() throws Exception {
        var owner = new MutableHolder(brew(1));
        ingredients.complete(new ResolvedIngredientManagerImpl());
        var insert = session().insertCauldron(owner); pump(); insert.join(); String before = stored();
        try (var connection = DriverManager.getConnection(jdbc); var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER fail_update BEFORE UPDATE ON cauldrons BEGIN SELECT RAISE(ABORT, 'injected'); END");
        }
        owner.current = brew(2); var update = session().updateCauldron(owner); pump();
        assertThrows(CompletionException.class, update::join);
        assertEquals(before, stored()); assertFalse(owner.persistenceAvailable());
        assertTrue(order.unresolved(key.worldUuid()));
        assertThrows(IllegalStateException.class, () -> session().removeCauldron(owner));
        assertThrows(IllegalStateException.class, () -> session().insertCauldron(new MutableHolder(brew(3))));
    }

    @Test void failedIngredientReadinessNeverQueuesAnyDeleteOrReplacement() throws Exception {
        var a = new MutableHolder(brew(1));
        var insert = session().insertCauldron(a); var delete = session().removeCauldron(a);
        var replacement = session().insertCauldron(new MutableHolder(brew(2)));
        ingredients.completeExceptionally(new IllegalStateException("ingredients unavailable")); pump();
        assertThrows(CompletionException.class, insert::join); assertThrows(CompletionException.class, delete::join);
        assertThrows(CompletionException.class, replacement::join); assertNull(stored()); assertTrue(sql.isEmpty());
        assertEquals(1, order.status().failedCoordinates());
    }
}
