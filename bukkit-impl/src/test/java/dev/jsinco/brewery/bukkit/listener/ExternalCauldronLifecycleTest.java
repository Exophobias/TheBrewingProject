package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.persistence.*;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.BukkitAdapter;
import dev.jsinco.brewery.bukkit.api.event.structure.ExternalCauldronDestroyedEvent;
import dev.jsinco.brewery.bukkit.testutil.CauldronOwnerServerMock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.*;
import org.bukkit.block.BlockFace;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.*;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;
import static org.junit.jupiter.api.Assertions.*;

class ExternalCauldronLifecycleTest {
    private CauldronOwnerServerMock server;
    private TheBrewingProject plugin;
    private WorldMock world;
    private ExternalCauldronLeaseRequest request;
    @BeforeEach void start() throws Exception {
        server = MockBukkit.mock(new CauldronOwnerServerMock()); world = server.addSimpleWorld("production-oil"); world.getChunkAt(0,0).load();
        plugin = MockBukkit.load(TheBrewingProject.class); plugin.getResolvedIngredientManager().join(); plugin.getDatabase().flush().join(); server.publishGlobal();
        world.getBlockAt(1,64,3).setType(Material.CAULDRON);
        request = new ExternalCauldronLeaseRequest(UUID.randomUUID(), "patriamforging", BukkitAdapter.toBreweryLocation(world.getBlockAt(1,64,3)));
    }
    @AfterEach void stop() { MockBukkit.unmock(); }
    private <T> T finish(CompletableFuture<T> future) throws Exception {
        plugin.getDatabase().flush().get(5,TimeUnit.SECONDS); server.getScheduler().performOneTick(); server.publishGlobal();
        return future.get(5,TimeUnit.SECONDS);
    }
    @Test void nativeInputReturnsWithoutChangingProtectionProvenanceAndVanillaContentMutationIsBlocked() throws Exception {
        var lease = finish(plugin.acquireExternalCauldronLease(request)); assertTrue(plugin.isExternalCauldronLeaseCurrent(lease));
        var block = world.getBlockAt(1,64,3); block.setType(Material.WATER_CAULDRON);
        var player = server.addPlayer(); player.addAttachment(plugin, "brewery.cauldron.access", true);
        player.getInventory().setItemInMainHand(new ItemStack(Material.WHEAT,2));
        var interaction = new PlayerInteractEvent(player,Action.RIGHT_CLICK_BLOCK,player.getInventory().getItemInMainHand(),block,BlockFace.UP,EquipmentSlot.HAND);
        Event.Result originalBlock = interaction.useInteractedBlock(), originalItem = interaction.useItemInHand();
        server.getPluginManager().callEvent(interaction);
        assertEquals(originalBlock, interaction.useInteractedBlock()); assertEquals(originalItem, interaction.useItemInHand());
        assertEquals(2, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(plugin.getBreweryRegistry().getActiveSinglePositionStructure(request.location()).isEmpty());
        for (var reason : CauldronLevelChangeEvent.ChangeReason.values()) {
            var mutation = new CauldronLevelChangeEvent(block, player, reason, block.getState()); server.getPluginManager().callEvent(mutation);
            assertTrue(mutation.isCancelled(), "all vanilla reasons are excluded: " + reason);
        }
        assertEquals(Material.WATER_CAULDRON, block.getType()); assertTrue(plugin.isExternalCauldronLeaseCurrent(lease));
    }
    @Test void lawfulDestructionInvalidatesImmediatelyAndRetiresGenerationWithoutVetoingTheBreak() throws Exception {
        var lease = finish(plugin.acquireExternalCauldronLease(request)); var observed = new AtomicReference<ExternalCauldronDestroyedEvent>();
        server.getPluginManager().registerEvent(ExternalCauldronDestroyedEvent.class,new Listener(){},EventPriority.NORMAL,
                (ignored,event) -> observed.set((ExternalCauldronDestroyedEvent)event),plugin);
        var event = new BlockBreakEvent(world.getBlockAt(1,64,3),server.addPlayer()); server.getPluginManager().callEvent(event);
        assertFalse(event.isCancelled()); assertFalse(plugin.isExternalCauldronLeaseCurrent(lease)); assertNotNull(observed.get());
        assertEquals(lease, observed.get().getLease()); world.getBlockAt(1,64,3).setType(Material.AIR);
        assertEquals(ExternalCauldronLease.State.DESTROYED, finish(observed.get().getRetirement().toCompletableFuture()).state());
        world.getBlockAt(1,64,3).setType(Material.CAULDRON);
        assertThrows(ExecutionException.class, () -> finish(plugin.acquireExternalCauldronLease(request)));
        var replacement = new ExternalCauldronLeaseRequest(UUID.randomUUID(), request.owner(), request.location());
        assertTrue(plugin.isExternalCauldronLeaseCurrent(finish(plugin.acquireExternalCauldronLease(replacement))));
    }
    @Test void deniedBreakPreservesStockAndReleaseRequiresAnEmptyPhysicalCauldron() throws Exception {
        var lease = finish(plugin.acquireExternalCauldronLease(request)); var block = world.getBlockAt(1,64,3); block.setType(Material.WATER_CAULDRON);
        var event = new BlockBreakEvent(block,server.addPlayer()); event.setCancelled(true); server.getPluginManager().callEvent(event);
        assertTrue(plugin.isExternalCauldronLeaseCurrent(lease));
        assertThrows(ExecutionException.class, () -> finish(plugin.releaseExternalCauldronLease(request)));
        assertTrue(plugin.isExternalCauldronLeaseCurrent(lease)); block.setType(Material.CAULDRON);
        assertEquals(ExternalCauldronLease.State.RELEASED, finish(plugin.releaseExternalCauldronLease(request)).state());
        assertFalse(plugin.isExternalCauldronLeaseCurrent(lease)); assertFalse(plugin.getCauldronPersistenceOrder().nativeBlocked(request.location()));
    }
    @Test void nonemptyWorldCauldronCannotBeClaimedEvenWhenThereIsNoNativeRuntimeHolder() {
        world.getBlockAt(1,64,3).setType(Material.WATER_CAULDRON);
        assertThrows(CompletionException.class, () -> plugin.acquireExternalCauldronLease(request).join());
        assertFalse(plugin.getCauldronPersistenceOrder().nativeBlocked(request.location()));
    }
}
