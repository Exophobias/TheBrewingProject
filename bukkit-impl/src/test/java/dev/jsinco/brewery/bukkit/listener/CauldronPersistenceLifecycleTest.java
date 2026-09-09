package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.breweries.CauldronType;
import dev.jsinco.brewery.api.ingredient.ResolvedIngredientManager;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.MixStepImpl;
import dev.jsinco.brewery.api.moment.Interval;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.BukkitAdapter;
import dev.jsinco.brewery.bukkit.api.event.transaction.CauldronInsertEvent;
import dev.jsinco.brewery.bukkit.breweries.BukkitCauldron;
import dev.jsinco.brewery.bukkit.database.SessionTypes;
import dev.jsinco.brewery.bukkit.database.cauldron.SqLiteCauldronSession;
import dev.jsinco.brewery.bukkit.testutil.CauldronOwnerServerMock;
import org.bukkit.Material;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldUnloadEvent;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class CauldronPersistenceLifecycleTest {
    private CauldronOwnerServerMock server;
    private TheBrewingProject plugin;
    private WorldMock world;
    private WorldEventListener lifecycle;
    private BukkitCauldron owner;
    @BeforeEach void start() throws Exception {
        server = MockBukkit.mock(new CauldronOwnerServerMock()); world = server.addSimpleWorld("cauldron-order");
        plugin = MockBukkit.load(TheBrewingProject.class); plugin.getResolvedIngredientManager().join();
        plugin.getDatabase().flush().join(); server.publishGlobal();
        assertTrue(plugin.getAtomicMutationGate().reserve(() -> true));
        world.getBlockAt(1, 64, 3).setType(Material.WATER_CAULDRON);
        owner = newOwner();
        lifecycle = new WorldEventListener(plugin.getDatabase(), plugin.getPlacedStructureRegistry(), plugin.getBreweryRegistry());
    }
    @AfterEach void stop() { MockBukkit.unmock(); }
    private BukkitCauldron newOwner() {
        Brew brew = new BrewImpl(List.of(new MixStepImpl(new Interval(1, 2), Map.of(), CauldronType.WATER)));
        return new BukkitCauldron(brew, BukkitAdapter.toBreweryLocation(world.getBlockAt(1, 64, 3)), CauldronType.WATER);
    }
    private void persist() throws Exception {
        plugin.getDatabase().startSession(SessionTypes.CAULDRON_SESSION_TYPE).insertCauldron(owner).join();
        plugin.getBreweryRegistry().addActiveSinglePositionStructure(owner);
    }
    private void complete(CompletableFuture<?> result) throws Exception {
        long end = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!result.isDone() && System.nanoTime() < end) { server.publishGlobal(); Thread.sleep(1); }
        assertTrue(result.isDone()); result.join();
    }

    @Test void actualUnloadRefusesIngredientDelayedWriteEvenWhenDatabaseExecutorIsIdleThenReloadAdoptsNewOwner() throws Exception {
        var ingredients = new CompletableFuture<ResolvedIngredientManager<ItemStack>>();
        var session = new SqLiteCauldronSession(Runnable::run, plugin.getDatabase()::getConnection, ingredients,
                plugin.getCauldronPersistenceOrder());
        var insertion = session.insertCauldron(owner);
        plugin.getBreweryRegistry().addActiveSinglePositionStructure(owner);
        plugin.getDatabase().flush().join(); assertFalse(insertion.isDone());
        var blocked = new WorldUnloadEvent(world); lifecycle.guardWorldUnload(blocked); assertTrue(blocked.isCancelled());
        assertSame(owner, plugin.getBreweryRegistry().getActiveSinglePositionStructure(owner.position()).orElseThrow());
        ingredients.complete(plugin.getResolvedIngredientManager().join()); insertion.join();
        var unload = new WorldUnloadEvent(world); lifecycle.onWorldUnload(unload); assertFalse(unload.isCancelled());
        assertTrue(plugin.getBreweryRegistry().getActiveSinglePositionStructure(owner.position()).isEmpty());
        assertFalse(owner.persistenceAvailable());
        assertThrows(IllegalStateException.class, () -> session.updateCauldron(owner));
        var loading = lifecycle.loadWorld(world); complete(loading);
        var loaded = (BukkitCauldron) plugin.getBreweryRegistry().getActiveSinglePositionStructure(owner.position()).orElseThrow();
        assertNotSame(owner, loaded); assertTrue(loaded.persistenceAvailable());
        plugin.getDatabase().startSession(SessionTypes.CAULDRON_SESSION_TYPE).updateCauldron(loaded).join();
        assertThrows(IllegalStateException.class, () -> session.removeCauldron(owner));
        assertSame(loaded, plugin.getBreweryRegistry().getActiveSinglePositionStructure(owner.position()).orElseThrow());
    }

    @Test void actualHydrationReadWaitsCompleteAdmissionBeforeItsSnapshotAndOldUnpublishedCallbacksRefuse() throws Exception {
        var ingredients = new CompletableFuture<ResolvedIngredientManager<ItemStack>>();
        var session = new SqLiteCauldronSession(Runnable::run, plugin.getDatabase()::getConnection, ingredients,
                plugin.getCauldronPersistenceOrder());
        var insertion = session.insertCauldron(owner);
        var loading = lifecycle.loadWorld(world);
        plugin.getDatabase().flush().join(); server.publishGlobal();
        assertFalse(loading.isDone()); assertFalse(owner.persistenceAvailable());
        ingredients.complete(plugin.getResolvedIngredientManager().join()); insertion.join(); complete(loading);
        var loaded = (BukkitCauldron) plugin.getBreweryRegistry().getActiveSinglePositionStructure(owner.position()).orElseThrow();
        assertNotSame(owner, loaded); assertTrue(loaded.persistenceAvailable());
        assertThrows(IllegalStateException.class, () -> session.updateCauldron(owner));
    }

    @Test void replacementDuringActualIngredientEventRefusesOldSourceBeforeMaterializationOrMutation() throws Exception {
        persist(); var replacement = newOwner(); Brew before = owner.getBrew();
        server.getPluginManager().registerEvent(CauldronInsertEvent.class, new Listener() { }, EventPriority.NORMAL,
                (ignored, raw) -> {
                    plugin.getBreweryRegistry().removeActiveSinglePositionStructure(owner);
                    plugin.getBreweryRegistry().addActiveSinglePositionStructure(replacement);
                }, plugin);
        var player = server.addPlayer(); player.addAttachment(plugin, "brewery.cauldron.access", true);
        assertFalse(owner.withIngredient(new ItemStack(Material.WHEAT), player));
        assertSame(before, owner.getBrew());
        assertSame(replacement, plugin.getBreweryRegistry().getActiveSinglePositionStructure(owner.position()).orElseThrow());
        assertFalse(ListenerUtil.removeIfCurrent(owner));
    }

    @Test void terminalAdmissionFailureCannotUnregisterTheCurrentCauldron() throws Exception {
        // A holder that never had INSERT/hydration authority cannot authorize coordinate deletion.
        plugin.getBreweryRegistry().addActiveSinglePositionStructure(owner);
        assertFalse(ListenerUtil.removeIfCurrent(owner));
        assertSame(owner, plugin.getBreweryRegistry().getActiveSinglePositionStructure(owner.position()).orElseThrow());
    }

    @Test void failureBeforeHydrationPermitStillRevokesExistingOwner() throws Exception {
        persist(); plugin.getAtomicMutationGate().stop();
        assertThrows(IllegalStateException.class, () -> lifecycle.loadWorld(world));
        assertFalse(owner.persistenceAvailable()); assertTrue(owner.reserveExtraction().isEmpty());
    }

    @Test void actualIngredientHandlerCannotPublishAfterConsumptionCallbackReplacesHolder() throws Exception {
        owner = new BukkitCauldron(owner.getBrew(), owner.position(), CauldronType.WATER) {
            @Override public boolean withIngredient(ItemStack item, org.bukkit.entity.Player player) { return true; }
        };
        persist(); var replacement = newOwner();
        var player = server.addPlayer();
        var armed = new AtomicBoolean(); var replaced = new AtomicBoolean();
        var input = new ItemStack(Material.WHEAT, 2) {
            @Override public void setAmount(int amount) {
                super.setAmount(amount);
                if (armed.get()) {
                    replaced.set(true);
                    plugin.getBreweryRegistry().removeActiveSinglePositionStructure(owner);
                    plugin.getBreweryRegistry().addActiveSinglePositionStructure(replacement);
                }
            }
        };
        player.getInventory().setItemInMainHand(new ItemStack(Material.WHEAT, 2));
        var listener = new PlayerEventListener(null, plugin.getBreweryRegistry(), plugin.getDatabase(), null, null, null, null);
        var method = PlayerEventListener.class.getDeclaredMethod("handleIngredientAddition", ItemStack.class,
                org.bukkit.block.Block.class, BukkitCauldron.class, org.bukkit.entity.Player.class,
                org.bukkit.inventory.EquipmentSlot.class);
        method.setAccessible(true);
        armed.set(true);
        assertEquals(false, method.invoke(listener, input, world.getBlockAt(1, 64, 3), owner, player,
                org.bukkit.inventory.EquipmentSlot.HAND));
        assertTrue(replaced.get(), "Exercise the actual consumption boundary before its ownership recheck");
        assertSame(replacement, plugin.getBreweryRegistry().getActiveSinglePositionStructure(owner.position()).orElseThrow());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount(), "Detached callback input must not overwrite the current hand");
    }

    @Test void ownerActionExceptionSettlesRunLocally() {
        var failure = new IllegalStateException("action failed");
        var result = owner.runLocally(() -> { throw failure; }); assertFalse(result.isDone());
        server.publishRegion(); assertSame(failure, assertThrows(CompletionException.class, result::join).getCause());
    }
    @Test void schedulingExceptionSettlesRunLocally() {
        server.regionRejection = new IllegalStateException("scheduler refused");
        var result = owner.runLocally(() -> fail("Not admitted"));
        assertSame(server.regionRejection, assertThrows(CompletionException.class, result::join).getCause());
    }
    @Test void shutdownSettlesPendingOwnerActionAndDiscardsOnlyItsCallback() {
        var called = new AtomicBoolean(); var result = owner.runLocally(() -> called.set(true));
        plugin.getOwnerPublications().stop(); assertThrows(CompletionException.class, result::join);
        server.publishRegion(); assertFalse(called.get());
    }
}
