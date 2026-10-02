package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.bukkit.api.BukkitAdapter;
import dev.jsinco.brewery.bukkit.database.cauldron.CauldronPersistenceOrder;
import dev.jsinco.brewery.bukkit.database.cauldron.ExternalCauldronCoordinator;
import org.bukkit.block.Block;
import org.bukkit.event.*;
import org.bukkit.event.block.*;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.WorldLoadEvent;

/** Blocks vanilla content changes; lawful block destruction remains governed by protection plugins. */
public final class ExternalCauldronListener implements Listener {
    private final CauldronPersistenceOrder order;
    private final ExternalCauldronCoordinator owner;
    public ExternalCauldronListener(CauldronPersistenceOrder order, ExternalCauldronCoordinator owner) {
        this.order = order; this.owner = owner;
    }
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void level(CauldronLevelChangeEvent event) {
        if (order.nativeBlocked(BukkitAdapter.toBreweryLocation(event.getBlock()))
                && !order.fixtureBlocked(BukkitAdapter.toBreweryLocation(event.getBlock()))) event.setCancelled(true);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void broken(BlockBreakEvent event) { destroy(event.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void burned(BlockBurnEvent event) { destroy(event.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityChange(EntityChangeBlockEvent event) { destroy(event.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void placed(BlockPlaceEvent event) { destroy(event.getBlock()); }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void exploded(BlockExplodeEvent event) {
        if (event.getExplosionResult() != org.bukkit.ExplosionResult.TRIGGER_BLOCK) event.blockList().forEach(this::destroy);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void entityExploded(EntityExplodeEvent event) {
        if (event.getExplosionResult() != org.bukkit.ExplosionResult.TRIGGER_BLOCK) event.blockList().forEach(this::destroy);
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void extended(BlockPistonExtendEvent event) {
        for (var block : event.getBlocks()) { destroy(block); destroy(block.getRelative(event.getDirection())); }
    }
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void retracted(BlockPistonRetractEvent event) {
        for (var block : event.getBlocks()) { destroy(block); destroy(block.getRelative(event.getDirection())); }
    }
    @EventHandler(priority = EventPriority.MONITOR)
    public void chunkLoaded(ChunkLoadEvent event) { owner.reconcileLoaded(event.getWorld().getUID(), event.getChunk().getX(), event.getChunk().getZ()); }
    @EventHandler(priority = EventPriority.MONITOR)
    public void worldLoaded(WorldLoadEvent event) { owner.reconcileLoaded(event.getWorld().getUID(), Integer.MIN_VALUE, Integer.MIN_VALUE); }
    private void destroy(Block block) { owner.destroy(BukkitAdapter.toBreweryLocation(block)); }
}
