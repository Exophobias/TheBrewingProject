package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.util.Logger;
import dev.jsinco.brewery.api.structure.StructureType;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.breweries.BreweryRegistry;
import dev.jsinco.brewery.bukkit.breweries.distillery.BukkitDistillery;
import dev.jsinco.brewery.bukkit.database.SessionTypes;
import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.database.sql.SqlDatabase;
import dev.jsinco.brewery.structure.PlacedStructureRegistryImpl;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.WorldLoadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

public class WorldEventListener implements Listener {

    private final SqlDatabase database;
    private final PlacedStructureRegistryImpl placedStructureRegistry;
    private final BreweryRegistry registry;
    private final WorldHydrationLifecycle hydration;

    public WorldEventListener(SqlDatabase database, PlacedStructureRegistryImpl placedStructureRegistry, BreweryRegistry registry) {
        this.database = database;
        this.placedStructureRegistry = placedStructureRegistry;
        this.registry = registry;
        this.hydration = new WorldHydrationLifecycle(TheBrewingProject.getInstance().getAtomicMutationGate());
    }

    public CompletableFuture<Void> init() {
        return CompletableFuture.allOf(Bukkit.getServer().getWorlds().stream()
                .map(this::loadWorld).toArray(CompletableFuture[]::new));
    }

    /** Called before reload clears any registry or begins draining the database. */
    public void invalidateAll() {
        hydration.invalidateAll();
    }

    public void stop() {
        hydration.stop();
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldLoad(WorldLoadEvent event) {
        loadWorld(event.getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void guardWorldUnload(WorldUnloadEvent event) {
        if (hasPendingAtomicDistilleryMutation(event.getWorld().getUID())) {
            event.setCancelled(true);
            Logger.logErr("World unload was refused because an atomic distillery mutation is pending in "
                    + event.getWorld().getName());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        var lifecycleGate = TheBrewingProject.getInstance().getAtomicMutationGate();
        var worldUuid = event.getWorld().getUID();
        if (!lifecycleGate.beginRefresh(() -> hasPendingAtomicDistilleryMutation(worldUuid))) {
            event.setCancelled(true);
            Logger.logErr("World unload was refused because an atomic distillery mutation is pending in "
                    + event.getWorld().getName());
            return;
        }
        try {
            hydration.invalidate(worldUuid);
            placedStructureRegistry.unloadWorld(worldUuid);
            registry.unloadWorld(worldUuid);
        } finally {
            lifecycleGate.endRefresh();
        }
    }

    private boolean hasPendingAtomicDistilleryMutation(java.util.UUID worldUuid) {
        return placedStructureRegistry.getStructures(StructureType.DISTILLERY).stream()
                .filter(structure -> structure.getUnique().worldUuid().equals(worldUuid))
                .map(structure -> structure.getHolder())
                .filter(BukkitDistillery.class::isInstance)
                .map(BukkitDistillery.class::cast)
                .anyMatch(BukkitDistillery::isAtomicMovePending);
    }

    public CompletableFuture<Void> loadWorld(World world) {
        UUID worldId = world.getUID();
        var load = hydration.begin(worldId);
        load.result().whenComplete((ignored, failure) -> {
            if (failure != null && !(failure instanceof CancellationException)) {
                Logger.logErr("Could not hydrate brewery holders for " + worldId
                        + "; atomic mutations stay unavailable until reload or world unload");
                Logger.logErr(failure);
            }
        });
        try {
            var plugin = TheBrewingProject.getInstance();
            database.startSession(SessionTypes.WORLD_HYDRATION_SESSION_TYPE).readWorld(worldId)
                    .thenCompose(snapshot -> plugin.getResolvedIngredientManager().thenApply(ingredients -> (Runnable) () -> {
                        if (Bukkit.getWorld(worldId) != world) {
                            hydration.invalidate(worldId);
                            return;
                        }
                        hydration.publish(load, () -> WorldBreweryHydrator.publish(world, snapshot, ingredients,
                                placedStructureRegistry, registry));
                    }))
                    .thenAccept(publication -> {
                        if (hydration.isCurrent(load)) {
                            Bukkit.getGlobalRegionScheduler().execute(plugin, () -> {
                                // Check before touching even the World: unload MONITOR still exposes it.
                                if (hydration.isCurrent(load)) publication.run();
                            });
                        }
                    })
                    .whenComplete((ignored, failure) -> {
                        if (failure != null) hydration.fail(load, failure);
                    });
        } catch (PersistenceException | RuntimeException failure) {
            hydration.fail(load, failure);
        }
        return load.result();
    }
}
