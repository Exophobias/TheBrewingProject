package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.util.BreweryKey;
import dev.jsinco.brewery.api.util.CancelState;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.DistillStepImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.BukkitAdapter;
import dev.jsinco.brewery.bukkit.api.event.structure.BarrelDestroyEvent;
import dev.jsinco.brewery.bukkit.breweries.barrel.BukkitBarrel;
import dev.jsinco.brewery.bukkit.database.SessionTypes;
import dev.jsinco.brewery.bukkit.database.barrel.BarrelSession;
import dev.jsinco.brewery.bukkit.structure.PlacedBreweryStructure;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import dev.jsinco.brewery.database.Session;
import dev.jsinco.brewery.database.SessionType;
import dev.jsinco.brewery.database.sql.DatabaseDriver;
import dev.jsinco.brewery.database.sql.SqlDatabase;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.ExplosionResult;
import org.bukkit.Location;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockExplodeEvent;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Real explosion listener, owner event and SQLite deletion; no native world physics is simulated. */
class BarrelDestroyAcknowledgmentTest {
    private OwnerServer server;
    private WorldMock world;
    private TheBrewingProject plugin;
    private final List<BarrelDestroyEvent> events = new ArrayList<>();

    @BeforeEach void start() {
        server = MockBukkit.mock(new OwnerServer());
        world = server.addSimpleWorld("barrel-acknowledgment");
        plugin = MockBukkit.load(TheBrewingProject.class);
        plugin.getResolvedIngredientManager().join();
        plugin.getDatabase().flush().join();
        server.publish();
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void observe(BarrelDestroyEvent event) {
                events.add(event);
                assertFalse(event.getCommitResult().toCompletableFuture().isDone(),
                        "authorization is not a durable deletion receipt");
                event.setDrops(List.of());
            }
        }, plugin);
    }

    @AfterEach void stop() { MockBukkit.unmock(); }

    @Test void oneLogicalBarrelProducesOneReceiptAndDeletesParentAndEveryBrew() throws Exception {
        var barrel = barrel(20);
        var event = explosion(barrel);
        listener(plugin.getDatabase()).onBlockExplode(event);
        assertFalse(event.isCancelled());
        assertEquals(1, events.size());
        assertTrue(events.getFirst().getCommitResult().toCompletableFuture().join());
        assertAbsent(barrel);
    }

    @Test void cancellationAcknowledgesFalseAndPreservesDurableHolderAndContents() throws Exception {
        var barrel = barrel(20);
        server.getPluginManager().registerEvents(new Listener() {
            @EventHandler public void refuse(BarrelDestroyEvent event) {
                event.setCancelled(true);
            }
        }, plugin);
        var event = explosion(barrel);
        listener(plugin.getDatabase()).onBlockExplode(event);
        assertTrue(event.isCancelled());
        assertFalse(events.getFirst().getCommitResult().toCompletableFuture().join());
        assertPresent(barrel);
    }

    @Test void externallyCancelledExplosionNeverProposesOwnerDeletion() throws Exception {
        var barrel = barrel(20);
        var event = explosion(barrel);
        event.setCancelled(true);
        // Dispatch through Bukkit: ignoreCancelled is a registration property, not a method guard.
        event.callEvent();
        assertTrue(events.isEmpty());
        assertPresent(barrel);
    }

    @Test void acknowledgedSuccessWaitsForDeletionFutureAfterLiveUnregistration() throws Exception {
        var barrel = barrel(20);
        var database = new ControlledDatabase(plugin.getDatabase(), false);
        try {
            listener(database).onBlockExplode(explosion(barrel));
            plugin.getDatabase().flush().join();
            assertEquals(1, events.size());
            assertFalse(events.getFirst().getCommitResult().toCompletableFuture().isDone());
            assertAbsent(barrel);
            database.acknowledgment.complete(null);
            assertTrue(events.getFirst().getCommitResult().toCompletableFuture().join());
        } finally { database.acknowledgment.complete(null); database.close().join(); }
    }

    @Test void asynchronousDeleteFailureIsExceptionalEvenAfterLiveAliasesRetire() throws Exception {
        var barrel = barrel(20);
        var database = new ControlledDatabase(plugin.getDatabase(), true);
        try {
            listener(database).onBlockExplode(explosion(barrel));
            assertEquals(1, events.size());
            assertFalse(events.getFirst().getCommitResult().toCompletableFuture().isDone());
            database.acknowledgment.completeExceptionally(new IllegalStateException("delete failed"));
            assertThrows(CompletionException.class, () -> events.getFirst().getCommitResult().toCompletableFuture().join());
            assertTrue(plugin.getPlacedStructureRegistry().getStructures(barrel.getStructure().positions()).isEmpty());
            assertEquals(2, plugin.getDatabase().startSession(SessionTypes.BARREL_SESSION_TYPE)
                    .findBrews(barrel.getStructure().getUnique()).join().size());
        } finally { database.acknowledgment.complete(null); database.close().join(); }
    }

    @Test void observersCannotCompleteTheOwnersPendingReceipt() throws Exception {
        var barrel = barrel(20);
        var database = new ControlledDatabase(plugin.getDatabase(), false);
        try {
            listener(database).onBlockExplode(explosion(barrel));
            var event = events.getFirst();
            assertTrue(event.getCommitResult().toCompletableFuture().complete(true));
            assertFalse(event.getCommitResult().toCompletableFuture().isDone());
            database.acknowledgment.complete(null);
            assertTrue(event.getCommitResult().toCompletableFuture().join());
        } finally { database.acknowledgment.complete(null); database.close().join(); }
    }

    @Test void legacyThirdPartyProposalDoesNotFabricateAnOwnerDeletionAcknowledgment() throws Exception {
        var barrel = barrel(20);
        var proposal = new BarrelDestroyEvent(new CancelState.Allowed(), barrel, null,
                new Location(world, 20, 65, 20), List.of());
        assertFalse(proposal.getCommitResult().toCompletableFuture().join());
        assertPresent(barrel);
    }

    private BukkitBarrel barrel(int x) throws Exception {
        var format = plugin.getStructureRegistry().getStructure("small_barrel").orElseThrow();
        var structure = new PlacedBreweryStructure<BukkitBarrel>(format, new Matrix3d(), new Location(world, x, 65, x));
        var type = dev.jsinco.brewery.api.util.BreweryRegistry.BARREL_TYPE.get(BreweryKey.parse("oak"));
        assertNotNull(type);
        var barrel = new BukkitBarrel(BukkitAdapter.toLocation(structure.getUnique()).orElseThrow(), structure, 9, type);
        structure.setHolder(barrel);
        barrel.getInventory().set(new BrewImpl(List.of(new DistillStepImpl(1))), 1);
        barrel.getInventory().set(new BrewImpl(List.of(new DistillStepImpl(2))), 7);
        plugin.getPlacedStructureRegistry().registerStructure(structure);
        plugin.getBreweryRegistry().registerInventory(barrel);
        plugin.getDatabase().startSession(SessionTypes.BARREL_SESSION_TYPE).insertBarrel(barrel).join();
        return barrel;
    }

    private BlockExplodeEvent explosion(BukkitBarrel barrel) {
        var blocks = barrel.getStructure().positions().stream().map(BukkitAdapter::toBlock)
                .map(java.util.Optional::orElseThrow).toList();
        return new BlockExplodeEvent(world.getBlockAt(0, 65, 0), world.getBlockAt(0, 65, 0).getState(),
                new ArrayList<>(blocks), 1, ExplosionResult.DESTROY);
    }
    private BlockEventListener listener(SqlDatabase database) {
        return new BlockEventListener(plugin.getStructureRegistry(), plugin.getPlacedStructureRegistry(),
                database, plugin.getBreweryRegistry());
    }
    private void assertAbsent(BukkitBarrel barrel) throws Exception {
        assertTrue(plugin.getPlacedStructureRegistry().getStructures(barrel.getStructure().positions()).isEmpty());
        assertNull(plugin.getBreweryRegistry().getFromInventory(barrel.getInventory().getInventory()));
        assertTrue(plugin.getDatabase().startSession(SessionTypes.BARREL_SESSION_TYPE)
                .findBrews(barrel.getStructure().getUnique()).join().isEmpty());
        assertTrue(plugin.getDatabase().startSession(SessionTypes.WORLD_HYDRATION_SESSION_TYPE)
                .readWorld(world.getUID()).join().barrels().isEmpty());
    }
    private void assertPresent(BukkitBarrel barrel) throws Exception {
        assertSame(barrel, plugin.getPlacedStructureRegistry().getHolder(barrel.getStructure().getUnique()).orElseThrow());
        assertEquals(2, plugin.getDatabase().startSession(SessionTypes.BARREL_SESSION_TYPE)
                .findBrews(barrel.getStructure().getUnique()).join().size());
        assertEquals(1, plugin.getDatabase().startSession(SessionTypes.WORLD_HYDRATION_SESSION_TYPE)
                .readWorld(world.getUID()).join().barrels().size());
    }

    private static final class ControlledDatabase extends SqlDatabase {
        final CompletableFuture<Void> acknowledgment = new CompletableFuture<>();
        private final SqlDatabase delegate;
        private final boolean refuse;
        ControlledDatabase(SqlDatabase delegate, boolean refuse) {
            super(DatabaseDriver.SQLITE); this.delegate = delegate; this.refuse = refuse;
        }
        @Override @SuppressWarnings("unchecked")
        public <T extends Session<T>> T startSession(SessionType<T> type) throws dev.jsinco.brewery.database.PersistenceException {
            assertSame(SessionTypes.BARREL_SESSION_TYPE, type);
            var real = delegate.startSession(SessionTypes.BARREL_SESSION_TYPE);
            return (T) Proxy.newProxyInstance(BarrelSession.class.getClassLoader(), new Class<?>[]{BarrelSession.class},
                    (proxy, method, args) -> method.getName().equals("removeBarrel")
                            ? refuse ? acknowledgment : real.removeBarrel((BukkitBarrel) args[0]).thenCompose(ignored -> acknowledgment)
                            : method.invoke(real, args));
        }
    }
    private static final class OwnerServer extends TBPServerMock {
        private final ConcurrentLinkedQueue<Runnable> queued = new ConcurrentLinkedQueue<>();
        void publish() { Runnable action; while ((action = queued.poll()) != null) action.run(); }
        @Override public GlobalRegionScheduler getGlobalRegionScheduler() {
            var delegate = super.getGlobalRegionScheduler();
            return new GlobalRegionScheduler() {
                public void execute(Plugin plugin, Runnable action) { queued.add(action); }
                public ScheduledTask run(Plugin plugin, Consumer<ScheduledTask> task) { return delegate.run(plugin, task); }
                public ScheduledTask runDelayed(Plugin plugin, Consumer<ScheduledTask> task, long delay) { return delegate.runDelayed(plugin, task, delay); }
                public ScheduledTask runAtFixedRate(Plugin plugin, Consumer<ScheduledTask> task, long delay, long period) { return delegate.runAtFixedRate(plugin, task, delay, period); }
                public void cancelTasks(Plugin plugin) { queued.clear(); }
            };
        }
    }
}
