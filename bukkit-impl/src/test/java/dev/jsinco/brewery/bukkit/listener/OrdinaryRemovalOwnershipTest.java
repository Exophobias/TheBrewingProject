package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.breweries.CauldronType;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.DistillStepImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.BukkitAdapter;
import dev.jsinco.brewery.bukkit.api.event.structure.DistilleryDestroyEvent;
import dev.jsinco.brewery.bukkit.breweries.BukkitCauldron;
import dev.jsinco.brewery.bukkit.breweries.distillery.BukkitDistillery;
import dev.jsinco.brewery.bukkit.database.SessionTypes;
import dev.jsinco.brewery.bukkit.database.distillery.DistillerySession;
import dev.jsinco.brewery.bukkit.structure.PlacedBreweryStructure;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import dev.jsinco.brewery.util.DecoderEncoder;
import dev.jsinco.brewery.database.Session;
import dev.jsinco.brewery.database.SessionType;
import dev.jsinco.brewery.database.sql.DatabaseDriver;
import dev.jsinco.brewery.database.sql.SqlDatabase;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.plugin.Plugin;
import org.joml.Matrix3d;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.IdentityHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/** Drives real owner callbacks and SQLite rows: a registry-only assertion misses stale DELETEs. */
class OrdinaryRemovalOwnershipTest {
    private OwnerServer server;
    private WorldMock world;
    private TheBrewingProject plugin;
    private BlockEventListener listener;

    @BeforeEach
    void setUp() {
        server = MockBukkit.mock(new OwnerServer());
        world = server.addSimpleWorld("removal-ownership");
        plugin = MockBukkit.load(TheBrewingProject.class);
        plugin.getResolvedIngredientManager().join();
        plugin.getDatabase().flush().join();
        server.publish();
        listener = new BlockEventListener(plugin.getStructureRegistry(),
                plugin.getPlacedStructureRegistry(), plugin.getDatabase(), plugin.getBreweryRegistry());
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void staleCauldronRemovalCannotDeleteReplacementRowOrDestroyEitherVisual() throws Exception {
        var old = cauldron(10, 1);
        var current = cauldron(10, 2);
        register(old);
        replace(old, current);
        String before = cauldronRow(current.position());

        ListenerUtil.removeActiveSinglePositionStructure(old);
        plugin.getDatabase().flush().join();

        assertFalse(old.destroyed);
        assertFalse(current.destroyed);
        assertSame(current, plugin.getBreweryRegistry().getActiveSinglePositionStructure(old.position()).orElseThrow());
        assertEquals(before, cauldronRow(current.position()));
    }

    @Test
    void queuedCauldronTickCannotDeleteReplacementAtInvalidGeometry() throws Exception {
        var old = cauldron(10, 1);
        register(old);
        world.getChunkAt(0, 0).load();
        old.tick();
        assertEquals(1, server.regions.size());
        var current = cauldron(10, 2);
        replace(old, current);
        String before = cauldronRow(current.position());
        assertEquals(Material.AIR, world.getBlockAt(10, 65, 10).getType());

        server.publishRegions();
        plugin.getDatabase().flush().join();

        assertFalse(old.destroyed);
        assertSame(current, plugin.getBreweryRegistry().getActiveSinglePositionStructure(old.position()).orElseThrow());
        assertEquals(before, cauldronRow(current.position()));
    }

    @Test
    void cauldronTeardownCallbackCannotDeleteReplacement() throws Exception {
        var old = cauldron(10, 1);
        var current = cauldron(10, 2);
        register(old);
        AtomicReference<String> replacementRow = new AtomicReference<>();
        old.onDestroy = () -> {
            replace(old, current);
            try { replacementRow.set(cauldronRow(current.position())); }
            catch (Exception failure) { throw new AssertionError(failure); }
        };

        ListenerUtil.removeActiveSinglePositionStructure(old);
        plugin.getDatabase().flush().join();

        assertTrue(old.destroyed);
        assertFalse(current.destroyed);
        assertSame(current, plugin.getBreweryRegistry().getActiveSinglePositionStructure(old.position()).orElseThrow());
        assertNotNull(replacementRow.get());
        assertEquals(replacementRow.get(), cauldronRow(current.position()));
    }

    @Test
    void currentCauldronRemovalPreservesSameCoordinateInAnotherWorld() throws Exception {
        var current = cauldron(10, 1);
        register(current);
        WorldMock otherWorld = server.addSimpleWorld("unrelated-world");
        var other = new TrackedCauldron(new BreweryLocation(10, 65, 10, otherWorld.getUID()), 3);
        register(other);
        String otherBefore = cauldronRow(other.position());

        ListenerUtil.removeActiveSinglePositionStructure(current);
        plugin.getDatabase().flush().join();

        assertTrue(current.destroyed);
        assertNull(cauldronRow(current.position()));
        assertTrue(plugin.getBreweryRegistry().getActiveSinglePositionStructure(current.position()).isEmpty());
        assertFalse(other.destroyed);
        assertEquals(otherBefore, cauldronRow(other.position()));
    }

    @Test
    void reentrantDestroyReplacementRejectsEveryProposalBeforeAnyDelete() throws Exception {
        var old = distillery(30, 101);
        var current = newDistillery(30, 202);
        var untouched = distillery(50, 303);
        List<CompletableFuture<Boolean>> receipts = new ArrayList<>();
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void destroying(DistilleryDestroyEvent event) {
                receipts.add(event.getCommitResult().toCompletableFuture());
                if (event.getDistillery() == untouched) replace(old, current);
            }
        }, plugin);
        var event = new BlockPistonExtendEvent(world.getBlockAt(29, 65, 30),
                List.of(block(old), block(untouched)), org.bukkit.block.BlockFace.EAST);

        listener.onPistonExtend(event);
        plugin.getDatabase().flush().join();

        assertTrue(event.isCancelled());
        assertEquals(2, receipts.size());
        receipts.forEach(receipt -> assertFalse(receipt.join()));
        assertEquals(202L, distilleryRow(current));
        assertEquals(303L, distilleryRow(untouched));
        assertSame(current, plugin.getPlacedStructureRegistry().getHolder(current.getStructure().getUnique()).orElseThrow());
        assertSame(untouched, plugin.getPlacedStructureRegistry().getHolder(untouched.getStructure().getUnique()).orElseThrow());
        assertTrue(world.getEntitiesByClass(org.bukkit.entity.Item.class).isEmpty());
    }

    @Test
    void inventoryCloseReplacementCannotBeDeletedByOldHolder() throws Exception {
        var old = distillery(30, 101);
        var current = newDistillery(30, 202);
        var player = server.addPlayer();
        player.setOp(true);
        player.openInventory(old.getMixture().getInventory());
        List<CompletableFuture<Boolean>> receipts = new ArrayList<>();
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void destroying(DistilleryDestroyEvent event) {
                receipts.add(event.getCommitResult().toCompletableFuture());
            }
            @EventHandler public void closing(InventoryCloseEvent event) {
                if (event.getInventory() == old.getMixture().getInventory()) {
                    assertSame(old, plugin.getPlacedStructureRegistry().getHolder(old.getStructure().getUnique()).orElseThrow(),
                            "the old aliases must remain owned throughout viewer callbacks");
                    replace(old, current);
                }
            }
        }, plugin);
        var event = new BlockBreakEvent(block(old), player);

        listener.onBlockBreak(event);
        plugin.getDatabase().flush().join();

        assertTrue(event.isCancelled());
        assertEquals(1, receipts.size());
        assertFalse(receipts.getFirst().join());
        assertEquals(202L, distilleryRow(current));
        assertSame(current, plugin.getPlacedStructureRegistry().getHolder(current.getStructure().getUnique()).orElseThrow());
        assertTrue(world.getEntitiesByClass(org.bukkit.entity.Item.class).isEmpty());
    }

    @Test
    void currentDistilleryRemovalCommitsAndRetiresItsRegistrations() throws Exception {
        var current = distillery(30, 101);
        var player = server.addPlayer();
        player.setOp(true);
        List<CompletableFuture<Boolean>> receipts = new ArrayList<>();
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void destroying(DistilleryDestroyEvent event) {
                receipts.add(event.getCommitResult().toCompletableFuture());
            }
        }, plugin);
        var event = new BlockBreakEvent(block(current), player);

        listener.onBlockBreak(event);
        plugin.getDatabase().flush().join();

        assertFalse(event.isCancelled());
        assertEquals(1, receipts.size());
        assertTrue(receipts.getFirst().join());
        assertNull(distilleryRow(current));
        assertTrue(plugin.getPlacedStructureRegistry().getStructures(current.getStructure().positions()).isEmpty());
        assertNull(plugin.getBreweryRegistry().getFromInventory(current.getMixture().getInventory()));
    }

    @Test
    void laterHolderRejectionCannotCancelAnAlreadyAdmittedDeleteReceipt() throws Exception {
        var first = distillery(30, 101);
        var second = distillery(50, 202);
        var holders = List.of(first, second);
        for (var holder : holders) server.addPlayer().openInventory(holder.getMixture().getInventory());
        var receipts = new IdentityHashMap<BukkitDistillery, CompletableFuture<Boolean>>();
        var admitted = new AtomicReference<BukkitDistillery>();
        var replacement = new AtomicReference<BukkitDistillery>();
        Bukkit.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void destroying(DistilleryDestroyEvent event) {
                receipts.put((BukkitDistillery) event.getDistillery(), event.getCommitResult().toCompletableFuture());
            }
            @EventHandler public void closing(InventoryCloseEvent event) {
                if (admitted.get() != null) return;
                for (var holder : holders) {
                    if (event.getInventory() != holder.getMixture().getInventory()) continue;
                    admitted.set(holder);
                    var other = holder == first ? second : first;
                    var current = newDistillery(holder == first ? 50 : 30, 404);
                    replace(other, current);
                    replacement.set(current);
                }
            }
        }, plugin);
        var delayed = new DelayedRemovalDatabase(plugin.getDatabase());
        try {
            var controlledListener = new BlockEventListener(plugin.getStructureRegistry(),
                    plugin.getPlacedStructureRegistry(), delayed, plugin.getBreweryRegistry());
            var event = new BlockPistonExtendEvent(world.getBlockAt(29, 65, 30),
                    List.of(block(first), block(second)), org.bukkit.block.BlockFace.EAST);

            controlledListener.onPistonExtend(event);
            plugin.getDatabase().flush().join();

            assertTrue(event.isCancelled());
            assertNotNull(admitted.get());
            assertFalse(receipts.get(admitted.get()).isDone(),
                    "the unacknowledged SQL receipt must survive a later holder's rejection");
            assertNull(distilleryRow(admitted.get()));
            assertEquals(404L, distilleryRow(replacement.get()));
            var rejected = admitted.get() == first ? second : first;
            assertFalse(receipts.get(rejected).join());
            delayed.acknowledgment.complete(null);
            assertTrue(receipts.get(admitted.get()).join());
        } finally {
            delayed.acknowledgment.complete(null);
            delayed.close().join();
        }
    }

    private TrackedCauldron cauldron(int x, int value) {
        return new TrackedCauldron(new BreweryLocation(x, 65, x, world.getUID()), value);
    }

    private void register(TrackedCauldron cauldron) throws Exception {
        plugin.getDatabase().startSession(SessionTypes.CAULDRON_SESSION_TYPE).insertCauldron(cauldron).join();
        plugin.getBreweryRegistry().addActiveSinglePositionStructure(cauldron);
    }

    private void replace(TrackedCauldron old, TrackedCauldron current) {
        try {
            plugin.getBreweryRegistry().addActiveSinglePositionStructure(current);
            plugin.getDatabase().startSession(SessionTypes.CAULDRON_SESSION_TYPE).updateCauldron(current).join();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private BukkitDistillery newDistillery(int coordinate, long timer) {
        var format = plugin.getStructureRegistry().getStructure("bamboo_distillery").orElseThrow();
        var structure = new PlacedBreweryStructure<BukkitDistillery>(format, new Matrix3d(),
                new Location(world, coordinate, 65, coordinate));
        var still = new BukkitDistillery(structure, timer);
        structure.setHolder(still);
        return still;
    }

    private BukkitDistillery distillery(int coordinate, long timer) throws Exception {
        var still = newDistillery(coordinate, timer);
        plugin.getPlacedStructureRegistry().registerStructure(still.getStructure());
        plugin.getBreweryRegistry().registerInventory(still);
        plugin.getDatabase().startSession(SessionTypes.DISTILLERY_SESSION_TYPE).insertDistillery(still).join();
        return still;
    }

    private void replace(BukkitDistillery old, BukkitDistillery current) {
        try {
            plugin.getPlacedStructureRegistry().unregisterStructure(old.getStructure());
            plugin.getBreweryRegistry().unregisterInventory(old);
            plugin.getPlacedStructureRegistry().registerStructure(current.getStructure());
            plugin.getBreweryRegistry().registerInventory(current);
            plugin.getDatabase().startSession(SessionTypes.DISTILLERY_SESSION_TYPE).updateDistillery(current).join();
        } catch (Exception failure) {
            throw new AssertionError(failure);
        }
    }

    private org.bukkit.block.Block block(BukkitDistillery still) {
        return BukkitAdapter.toBlock(still.getStructure().getUnique()).orElseThrow();
    }

    private String cauldronRow(BreweryLocation position) throws Exception {
        return row("SELECT brew FROM cauldrons WHERE cauldron_x=? AND cauldron_y=? AND cauldron_z=? AND world_uuid=?", position);
    }

    private Long distilleryRow(BukkitDistillery still) throws Exception {
        String value = row("SELECT start_time FROM distilleries WHERE unique_x=? AND unique_y=? AND unique_z=? AND world_uuid=?",
                still.getStructure().getUnique());
        return value == null ? null : Long.valueOf(value);
    }

    private String row(String sql, BreweryLocation position) throws Exception {
        try (var connection = plugin.getDatabase().getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setInt(1, position.x());
            statement.setInt(2, position.y());
            statement.setInt(3, position.z());
            statement.setBytes(4, DecoderEncoder.asBytes(position.worldUuid()));
            try (var result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    private static final class TrackedCauldron extends BukkitCauldron {
        private boolean destroyed;
        private Runnable onDestroy = () -> { };
        private TrackedCauldron(BreweryLocation location, int value) {
            super(new BrewImpl(List.of(new DistillStepImpl(value))), location, CauldronType.WATER);
        }
        @Override public void destroy() { destroyed = true; onDestroy.run(); }
    }

    private static final class DelayedRemovalDatabase extends SqlDatabase {
        private final SqlDatabase delegate;
        private final CompletableFuture<Void> acknowledgment = new CompletableFuture<>();
        private DelayedRemovalDatabase(SqlDatabase delegate) { super(DatabaseDriver.SQLITE); this.delegate = delegate; }
        @Override @SuppressWarnings("unchecked")
        public <T extends Session<T>> T startSession(SessionType<T> type) throws dev.jsinco.brewery.database.PersistenceException {
            assertSame(SessionTypes.DISTILLERY_SESSION_TYPE, type);
            var real = delegate.startSession(SessionTypes.DISTILLERY_SESSION_TYPE);
            return (T) Proxy.newProxyInstance(DistillerySession.class.getClassLoader(),
                    new Class<?>[]{DistillerySession.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("removeDistillery")) {
                            return real.removeDistillery((BukkitDistillery) arguments[0])
                                    .thenCompose(ignored -> acknowledgment);
                        }
                        return method.invoke(real, arguments);
                    });
        }
    }

    private static final class OwnerServer extends TBPServerMock {
        private final ConcurrentLinkedQueue<Runnable> globals = new ConcurrentLinkedQueue<>();
        private final ConcurrentLinkedQueue<Runnable> regions = new ConcurrentLinkedQueue<>();
        void publish() { Runnable next; while ((next = globals.poll()) != null) next.run(); }
        void publishRegions() { Runnable next; while ((next = regions.poll()) != null) next.run(); }
        @Override public RegionScheduler getRegionScheduler() {
            return (RegionScheduler) Proxy.newProxyInstance(RegionScheduler.class.getClassLoader(),
                    new Class<?>[]{RegionScheduler.class}, (proxy, method, arguments) -> {
                        if (method.getName().equals("run")) {
                            @SuppressWarnings("unchecked") Consumer<ScheduledTask> action = (Consumer<ScheduledTask>) arguments[arguments.length - 1];
                            regions.add(() -> action.accept(null));
                        } else if (method.getName().equals("execute")) {
                            regions.add((Runnable) arguments[arguments.length - 1]);
                        }
                        return null;
                    });
        }
        @Override public GlobalRegionScheduler getGlobalRegionScheduler() {
            GlobalRegionScheduler delegate = super.getGlobalRegionScheduler();
            return new GlobalRegionScheduler() {
                public void execute(Plugin plugin, Runnable runnable) { globals.add(runnable); }
                public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task) { return delegate.run(plugin, task); }
                public ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task, long delay) { return delegate.runDelayed(plugin, task, delay); }
                public ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task, long delay, long period) { return delegate.runAtFixedRate(plugin, task, delay, period); }
                public void cancelTasks(Plugin plugin) { globals.clear(); }
            };
        }
    }
}
