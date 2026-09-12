package dev.jsinco.brewery.bukkit.listener;

import com.google.gson.JsonParser;
import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.brew.BrewingStep;
import dev.jsinco.brewery.api.breweries.CauldronType;
import dev.jsinco.brewery.api.breweries.InventoryAccessible;
import dev.jsinco.brewery.api.breweries.StructureHolder;
import dev.jsinco.brewery.api.ingredient.ResolvedIngredientManager;
import dev.jsinco.brewery.api.structure.MultiblockStructure;
import dev.jsinco.brewery.api.util.BreweryKey;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.breweries.BreweryRegistry;
import dev.jsinco.brewery.bukkit.breweries.BukkitCauldron;
import dev.jsinco.brewery.bukkit.breweries.barrel.BukkitBarrel;
import dev.jsinco.brewery.bukkit.breweries.distillery.BukkitDistillery;
import dev.jsinco.brewery.bukkit.database.hydration.WorldBrewerySnapshot;
import dev.jsinco.brewery.bukkit.database.cauldron.CauldronPersistenceOrder;
import dev.jsinco.brewery.bukkit.structure.PlacedBreweryStructure;
import dev.jsinco.brewery.structure.PlacedStructureRegistryImpl;
import dev.jsinco.brewery.util.DecoderEncoder;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** Called only by the world lifecycle's server-thread publication. */
final class WorldBreweryHydrator {
    static void publish(World world, WorldBrewerySnapshot rows,
                        ResolvedIngredientManager<ItemStack> ingredients,
                        PlacedStructureRegistryImpl structures, BreweryRegistry registry) {
        if (!rows.cauldrons().isEmpty()) throw new IllegalStateException("Cauldron hydration requires its exact drained lifecycle");
        publish(world, rows, ingredients, structures, registry, null);
    }

    static void publish(World world, WorldBrewerySnapshot rows,
                        ResolvedIngredientManager<ItemStack> ingredients,
                        PlacedStructureRegistryImpl structures, BreweryRegistry registry,
                        CauldronPersistenceOrder.Hydration cauldronHydration) {
        List<MultiblockStructure<?>> stagedStructures = new ArrayList<>();
        List<InventoryAccessible<ItemStack, Inventory>> stagedInventories = new ArrayList<>();
        List<BukkitCauldron> stagedCauldrons = new ArrayList<>();
        for (var row : rows.barrels()) {
            var type = dev.jsinco.brewery.api.util.BreweryRegistry.BARREL_TYPE.get(BreweryKey.parse(row.type()));
            if (type == null) throw new IllegalStateException("Unknown persisted barrel type: " + row.type());
            PlacedBreweryStructure<BukkitBarrel> structure = structure(world, row.structure());
            var barrel = new BukkitBarrel(location(world, row.structure().unique()), structure, row.size(), type);
            structure.setHolder(barrel);
            for (var brew : row.brews()) barrel.getInventory().set(deserialize(brew.serializedBrew(), ingredients), brew.position());
            stagedStructures.add(structure);
            stagedInventories.add(barrel);
        }
        for (var row : rows.distilleries()) {
            PlacedBreweryStructure<BukkitDistillery> structure = structure(world, row.structure());
            var distillery = new BukkitDistillery(structure, row.startTime());
            structure.setHolder(distillery);
            for (var brew : row.brews()) {
                var inventory = brew.distillate() ? distillery.getDistillate() : distillery.getMixture();
                inventory.set(deserialize(brew.serializedBrew(), ingredients), brew.position());
            }
            stagedStructures.add(structure);
            stagedInventories.add(distillery);
        }
        for (var row : rows.cauldrons()) {
            if (registry.getActiveSinglePositionStructure(row.location()).isPresent()) {
                throw new IllegalStateException("Hydration would replace a live cauldron at " + row.location());
            }
            Brew brew = deserialize(row.serializedBrew(), ingredients);
            CauldronType fallback = brew.lastStep() instanceof BrewingStep.CauldronStep<?> step
                    ? step.cauldronType() : CauldronType.WATER;
            CauldronType type = row.type() == null ? fallback : Arrays.stream(CauldronType.values())
                    .filter(candidate -> candidate.key().equals(BreweryKey.parse(row.type())))
                    .findFirst().orElse(fallback);
            stagedCauldrons.add(BukkitCauldron.hydrate(brew, row.location(), type,
                    java.util.Objects.requireNonNull(cauldronHydration, "Cauldron hydration permit"), row.birthUuid()));
        }
        // Construction/deserialization/overlap checks finish before any holder becomes visible.
        if (cauldronHydration != null) cauldronHydration.adopt(stagedCauldrons.stream()
                .map(cauldron -> cauldron.persistenceOwner(TheBrewingProject.getInstance().getCauldronPersistenceOrder())).toList());
        structures.registerStructures(stagedStructures);
        registry.registerInventories(stagedInventories);
        stagedCauldrons.forEach(registry::addActiveSinglePositionStructure);
        if (cauldronHydration != null) cauldronHydration.published();
    }

    private static Brew deserialize(String json, ResolvedIngredientManager<ItemStack> ingredients) {
        return BrewImpl.SERIALIZER.deserialize(JsonParser.parseString(json), ingredients);
    }

    private static <H extends StructureHolder<H>> PlacedBreweryStructure<H> structure(
            World world, WorldBrewerySnapshot.StructureRow row) {
        var format = TheBrewingProject.getInstance().getStructureRegistry().getStructure(row.format())
                .orElseThrow(() -> new IllegalStateException("Unknown persisted structure format: " + row.format()));
        return new PlacedBreweryStructure<>(format, DecoderEncoder.deserializeTransformation(row.transformation()),
                location(world, row.origin()), row.unique());
    }

    private static Location location(World world, BreweryLocation location) {
        if (!world.getUID().equals(location.worldUuid())) throw new IllegalArgumentException("Wrong snapshot world");
        return new Location(world, location.x(), location.y(), location.z());
    }
}
