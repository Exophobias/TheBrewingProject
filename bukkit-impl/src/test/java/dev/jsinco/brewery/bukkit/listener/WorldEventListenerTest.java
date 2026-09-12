package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.structure.StructureType;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.DistillStepImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.breweries.BreweryRegistry;
import dev.jsinco.brewery.bukkit.breweries.distillery.BukkitDistillery;
import dev.jsinco.brewery.bukkit.database.SessionTypes;
import dev.jsinco.brewery.bukkit.database.hydration.WorldBrewerySnapshot;
import dev.jsinco.brewery.bukkit.database.hydration.WorldHydrationSession;
import dev.jsinco.brewery.bukkit.structure.PlacedBreweryStructure;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import dev.jsinco.brewery.database.Session;
import dev.jsinco.brewery.database.SessionType;
import dev.jsinco.brewery.database.sql.DatabaseDriver;
import dev.jsinco.brewery.database.sql.SqlDatabase;
import dev.jsinco.brewery.structure.PlacedStructureRegistryImpl;
import dev.jsinco.brewery.util.DecoderEncoder;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.plugin.Plugin;
import org.joml.Matrix3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

import static dev.jsinco.brewery.bukkit.database.hydration.WorldBrewerySnapshot.*;
import static org.junit.jupiter.api.Assertions.*;

class WorldEventListenerTest {
    private PublicationServer server;
    private TheBrewingProject plugin;
    private WorldMock world;
    private ControlledDatabase database;
    private PlacedStructureRegistryImpl structures;
    private BreweryRegistry registry;
    private WorldEventListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock(new PublicationServer());
        world = server.addSimpleWorld("hydration");
        plugin = MockBukkit.load(TheBrewingProject.class);
        plugin.getResolvedIngredientManager().join();
        plugin.getDatabase().flush().join();
        server.publish();
        database = new ControlledDatabase();
        structures = new PlacedStructureRegistryImpl();
        registry = new BreweryRegistry();
        listener = new WorldEventListener(database, structures, registry);
    }

    @AfterEach
    void tearDown() {
        if (listener != null) listener.stop();
        if (database != null) database.close().join();
        MockBukkit.unmock();
    }

    @Test
    void asynchronousRowsCreateAndPublishCompleteInventoriesOnlyOnServerThread() {
        var rows = snapshot();
        var sql = database.nextRead();
        var load = listener.loadWorld(world);
        CompletableFuture.runAsync(() -> sql.complete(rows)).join();
        assertFalse(load.isDone());
        assertTrue(allStructures().isEmpty());
        assertFalse(plugin.getAtomicMutationGate().reserve(() -> true));
        server.publish();
        load.join();
        assertEquals(2, allStructures().size());
        assertEquals(1, registry.getActiveSinglePositionStructure().size());
        var distillery = (BukkitDistillery) structures.getStructures(StructureType.DISTILLERY).iterator().next().getHolder();
        assertNotNull(distillery);
        assertEquals(rows.distilleries().getFirst().structure().unique(), distillery.getStructure().getUnique());
        assertEquals(4294967299L, distillery.getStartTime());
        assertNotNull(distillery.getMixture().getBrews()[1]);
        assertNotNull(distillery.getDistillate().getBrews()[2]);
        assertSame(distillery, registry.getFromInventory(distillery.getMixture().getInventory()));
        assertTrue(plugin.getAtomicMutationGate().reserve(() -> true));
    }

    @Test
    void queuedPublicationCannotRepopulateAnUnloadingWorldStillVisibleToBukkit() {
        var sql = database.nextRead();
        var load = listener.loadWorld(world);
        sql.complete(snapshot());
        listener.onWorldUnload(new WorldUnloadEvent(world));
        assertSame(world, Bukkit.getWorld(world.getUID()));
        server.publish();
        assertThrows(CancellationException.class, load::join);
        assertTrue(allStructures().isEmpty());
        assertTrue(registry.getActiveSinglePositionStructure().isEmpty());
    }

    @Test
    void reloadBeforeReadCompletionRejectsOldSnapshotAfterNewHoldersPublish() {
        var firstRead = database.nextRead();
        var first = listener.loadWorld(world);
        listener.invalidateAll();
        structures.clear();
        registry.clear();
        var secondRead = database.nextRead();
        var second = listener.loadWorld(world);
        secondRead.complete(snapshot());
        server.publish();
        second.join();
        var current = allStructures().iterator().next();
        var staleRows = snapshot();
        CompletableFuture.runAsync(() -> firstRead.complete(staleRows)).join();
        server.publish();
        assertThrows(CancellationException.class, first::join);
        assertTrue(allStructures().contains(current));
        assertEquals(2, allStructures().size());
    }

    @Test
    void invalidLaterInventoryDoesNotExposeEarlierConstructedBarrelOrCauldron() {
        var valid = snapshot();
        var still = valid.distilleries().getFirst();
        var invalid = new WorldBrewerySnapshot(valid.barrels(), List.of(new DistilleryRow(still.structure(),
                still.startTime(), List.of(new BrewRow(999, false, brew())))), valid.cauldrons());
        var sql = database.nextRead();
        var load = listener.loadWorld(world);
        sql.complete(invalid);
        server.publish();
        assertThrows(CompletionException.class, load::join);
        assertTrue(allStructures().isEmpty());
        assertTrue(registry.getActiveSinglePositionStructure().isEmpty());
        assertFalse(plugin.getAtomicMutationGate().reserve(() -> true));
    }

    private java.util.Set<dev.jsinco.brewery.api.structure.MultiblockStructure<?>> allStructures() {
        var all = new java.util.HashSet<>(structures.getStructures(StructureType.BARREL));
        all.addAll(structures.getStructures(StructureType.DISTILLERY));
        return all;
    }

    private WorldBrewerySnapshot snapshot() {
        return new WorldBrewerySnapshot(
                List.of(new BarrelRow(structure("small_barrel", 20), "oak", 9, List.of(new BrewRow(3, false, brew())))),
                List.of(new DistilleryRow(structure("bamboo_distillery", 80), 4294967299L,
                        List.of(new BrewRow(1, false, brew()), new BrewRow(2, true, brew())))),
                List.of(new CauldronRow(new BreweryLocation(140, 65, 140, world.getUID()), "water", brew(), java.util.UUID.randomUUID())));
    }

    private StructureRow structure(String name, int coordinate) {
        var format = plugin.getStructureRegistry().getStructure(name).orElseThrow();
        var origin = new Location(world, coordinate, 65, coordinate);
        var placed = new PlacedBreweryStructure<>(format, new Matrix3d(), origin);
        return new StructureRow(new BreweryLocation(coordinate, 65, coordinate, world.getUID()),
                placed.positions().getLast(), DecoderEncoder.serializeTransformation(new Matrix3d()), name);
    }

    private String brew() {
        return BrewImpl.SERIALIZER.serialize(new BrewImpl(List.of(new DistillStepImpl(3))),
                plugin.getResolvedIngredientManager().join()).toString();
    }

    private static final class ControlledDatabase extends SqlDatabase {
        private final ArrayDeque<CompletableFuture<WorldBrewerySnapshot>> reads = new ArrayDeque<>();

        private ControlledDatabase() { super(DatabaseDriver.SQLITE); }

        CompletableFuture<WorldBrewerySnapshot> nextRead() {
            var next = new CompletableFuture<WorldBrewerySnapshot>();
            reads.addLast(next);
            return next;
        }

        @Override
        @SuppressWarnings("unchecked")
        public <T extends Session<T>> T startSession(SessionType<T> type) {
            assertSame(SessionTypes.WORLD_HYDRATION_SESSION_TYPE, type);
            return (T) new WorldHydrationSession() {
                public Executor executor() { return Runnable::run; }
                public CompletableFuture<WorldBrewerySnapshot> readWorld(UUID ignored) { return reads.removeFirst(); }
            };
        }
    }

    private static final class PublicationServer extends TBPServerMock {
        private final ConcurrentLinkedQueue<Runnable> publications = new ConcurrentLinkedQueue<>();

        void publish() {
            assertTrue(Bukkit.isPrimaryThread());
            Runnable next;
            while ((next = publications.poll()) != null) next.run();
        }

        @Override
        public org.mockbukkit.mockbukkit.inventory.InventoryMock createInventory(org.bukkit.inventory.InventoryHolder holder,
                                                                int size, net.kyori.adventure.text.Component title) {
            assertTrue(Bukkit.isPrimaryThread(), "Hydration must not construct inventories on the SQL worker");
            return super.createInventory(holder, size, title);
        }

        @Override
        public GlobalRegionScheduler getGlobalRegionScheduler() {
            GlobalRegionScheduler delegate = super.getGlobalRegionScheduler();
            return new GlobalRegionScheduler() {
                public void execute(Plugin plugin, Runnable runnable) { publications.add(runnable); }
                public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task) { return delegate.run(plugin, task); }
                public ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task, long delay) { return delegate.runDelayed(plugin, task, delay); }
                public ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task, long delay, long period) { return delegate.runAtFixedRate(plugin, task, delay, period); }
                public void cancelTasks(Plugin plugin) { publications.clear(); }
            };
        }
    }
}
