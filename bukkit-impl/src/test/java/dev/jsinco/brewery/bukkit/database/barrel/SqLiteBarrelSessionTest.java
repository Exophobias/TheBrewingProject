package dev.jsinco.brewery.bukkit.database.barrel;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.breweries.BarrelType;
import dev.jsinco.brewery.api.util.BreweryKey;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.DistillStepImpl;
import dev.jsinco.brewery.bukkit.breweries.barrel.BukkitBarrel;
import dev.jsinco.brewery.bukkit.database.hydration.SqLiteWorldHydrationSession;
import dev.jsinco.brewery.bukkit.ingredient.ResolvedIngredientManagerImpl;
import dev.jsinco.brewery.bukkit.structure.PlacedBreweryStructure;
import dev.jsinco.brewery.bukkit.structure.StructurePlacerUtils;
import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.util.FileUtil;
import org.bukkit.Location;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.*;

class SqLiteBarrelSessionTest {
    @TempDir Path directory;
    private String jdbc;
    private BukkitBarrel barrel;
    private SqLiteBarrelSession session;
    private final ResolvedIngredientManagerImpl ingredients = new ResolvedIngredientManagerImpl();

    @BeforeEach
    void setUp() throws Exception {
        var world = MockBukkit.mock().addSimpleWorld("barrel");
        StructurePlacerUtils.constructSmallOakBarrel(world);
        var found = PlacedBreweryStructure.<BukkitBarrel>findValid(StructurePlacerUtils.matchingStructure(),
                new Location(world, -3, 1, 1)).orElseThrow().first();
        BarrelType type = new BarrelType() {
            public double proximityScore(BarrelType ignored) { return 1; }
            public BreweryKey key() { return BreweryKey.parse("oak"); }
        };
        barrel = new BukkitBarrel(new Location(world, -3, 1, 1), found, 9, type);
        found.setHolder(barrel);
        barrel.getInventory().set(brew(3), 1);
        jdbc = "jdbc:sqlite:" + directory.resolve("barrels.db");
        try (var connection = connection(); var statement = connection.createStatement()) {
            for (String sql : FileUtil.readInternalResource("/database/sqlite/create_all_tables.sql").split(";")) {
                if (!sql.isBlank()) statement.execute(sql);
            }
        }
        session = new SqLiteBarrelSession(Runnable::run, this::connection, CompletableFuture.completedFuture(ingredients));
    }

    @AfterEach
    void tearDown() { MockBukkit.unmock(); }

    @Test
    void failedHolderInsertTerminatesTheReturnedFuture() throws Exception {
        trigger("barrels");
        var result = session.insertBarrel(barrel);
        assertTrue(result.isDone(), "Persistence failure must not strand lifecycle drain");
        assertThrows(CompletionException.class, result::join);
        assertEquals(0, count("barrels"));
    }

    @Test
    void failedInventoryInsertRollsBackTheHolderAndTerminatesTheReturnedFuture() throws Exception {
        trigger("barrel_brews");
        var result = session.insertBarrel(barrel);
        assertTrue(result.isDone());
        assertThrows(CompletionException.class, result::join);
        assertEquals(0, count("barrels"));
        assertEquals(0, count("barrel_brews"));
    }

    @Test
    void successfulInsertPublishesACompleteDurableHolderAndInventory() throws Exception {
        session.insertBarrel(barrel).join();
        assertEquals(1, count("barrels"));
        assertEquals(1, count("barrel_brews"));
        var snapshot = new SqLiteWorldHydrationSession(Runnable::run, this::connection)
                .readWorld(barrel.getWorld().getUID()).join();
        assertEquals(1, snapshot.barrels().getFirst().brews().getFirst().position());
    }

    @Test
    void queuedInsertUsesInventorySnapshotCapturedAtAdmission() {
        var queue = new ArrayDeque<Runnable>();
        var queued = new SqLiteBarrelSession(queue::addLast, this::connection,
                CompletableFuture.completedFuture(ingredients));
        var insert = queued.insertBarrel(barrel);
        barrel.getInventory().set(brew(7), 1);
        queue.removeFirst().run();
        insert.join();
        var snapshot = new SqLiteWorldHydrationSession(Runnable::run, this::connection)
                .readWorld(barrel.getWorld().getUID()).join();
        assertEquals(BrewImpl.SERIALIZER.serialize(brew(3), ingredients).toString(),
                snapshot.barrels().getFirst().brews().getFirst().serializedBrew());
    }

    private static Brew brew(int runs) { return new BrewImpl(List.of(new DistillStepImpl(runs))); }

    private void trigger(String table) throws Exception {
        try (var connection = connection(); var statement = connection.createStatement()) {
            statement.execute("CREATE TRIGGER injected_failure BEFORE INSERT ON " + table
                    + " BEGIN SELECT RAISE(FAIL, 'injected insert failure'); END");
        }
    }

    private int count(String table) throws Exception {
        try (var connection = connection(); var statement = connection.createStatement();
             var result = statement.executeQuery("SELECT COUNT(*) FROM " + table)) {
            return result.getInt(1);
        }
    }

    private Connection connection() throws PersistenceException {
        try { return DriverManager.getConnection(jdbc); }
        catch (SQLException failure) { throw new PersistenceException(failure); }
    }
}
