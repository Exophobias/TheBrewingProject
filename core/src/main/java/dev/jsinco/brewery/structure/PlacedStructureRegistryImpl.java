package dev.jsinco.brewery.structure;

import dev.jsinco.brewery.api.breweries.StructureHolder;
import dev.jsinco.brewery.api.structure.MultiblockStructure;
import dev.jsinco.brewery.api.structure.PlacedStructureRegistry;
import dev.jsinco.brewery.api.structure.StructureType;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.api.vector.BreweryVector;

import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlacedStructureRegistryImpl implements PlacedStructureRegistry {

    private final Map<UUID, Map<BreweryVector, MultiblockStructure<? extends StructureHolder<?>>>> structures = new ConcurrentHashMap<>();
    private final Map<StructureType<?>, Set<MultiblockStructure<?>>> typedMultiBlockStructureMap = new ConcurrentHashMap<>();

    public synchronized void registerStructures(Collection<? extends MultiblockStructure<?>> multiblockStructures) {
        multiblockStructures.forEach(this::registerStructure);
    }

    @Override
    public synchronized void registerStructure(MultiblockStructure<?> multiblockStructure) {
        for (BreweryLocation location : multiblockStructure.positions()) {
            UUID worldUuid = location.worldUuid();
            structures.computeIfAbsent(worldUuid, ignored -> new ConcurrentHashMap<>()).put(location.toVector(), multiblockStructure);
        }
        typedMultiBlockStructureMap.computeIfAbsent(multiblockStructure.getHolder().getStructureType(), ignored -> ConcurrentHashMap.newKeySet()).add(multiblockStructure);
    }

    @Override
    public synchronized void unregisterStructure(MultiblockStructure<?> structure) {
        for (BreweryLocation location : structure.positions()) {
            UUID worldUuid = location.worldUuid();
            structures.computeIfAbsent(worldUuid, ignored -> new ConcurrentHashMap<>()).remove(location.toVector());
        }
        typedMultiBlockStructureMap.computeIfAbsent(structure.getHolder().getStructureType(), ignored -> ConcurrentHashMap.newKeySet()).remove(structure);
    }

    @Override
    public Optional<MultiblockStructure<?>> getStructure(BreweryLocation location) {
        UUID worldUuid = location.worldUuid();
        Map<BreweryVector, MultiblockStructure<?>> placedBreweryStructureMap = structures.get(worldUuid);
        if (placedBreweryStructureMap == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(placedBreweryStructureMap.get(location.toVector()));
    }

    @Override
    public Set<MultiblockStructure<?>> getStructures(Collection<BreweryLocation> locations) {
        Set<MultiblockStructure<?>> breweryStructures = new HashSet<>();
        for (BreweryLocation location : locations) {
            getStructure(location).ifPresent(breweryStructures::add);
        }
        return breweryStructures;
    }

    @Override
    public synchronized int countStructureType(StructureType<?> structureType) {
        if (!typedMultiBlockStructureMap.containsKey(structureType)) {
            return 0;
        }
        return typedMultiBlockStructureMap.get(structureType).size();
    }

    public Set<MultiblockStructure<?>> getStructures(StructureType<?> structureType) {
        return typedMultiBlockStructureMap.getOrDefault(structureType, Set.of());
    }

    @Override
    public Optional<StructureHolder<?>> getHolder(BreweryLocation location) {
        UUID worldUuid = location.worldUuid();
        Map<BreweryVector, MultiblockStructure<? extends StructureHolder<?>>> placedBreweryStructureMap = structures.get(worldUuid);
        if (placedBreweryStructureMap == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(placedBreweryStructureMap.get(location.toVector()))
                .map(MultiblockStructure::getHolder);
    }

    @Override
    public synchronized void unloadWorld(UUID worldUuid) {
        Map<BreweryVector, MultiblockStructure<? extends StructureHolder<?>>> removed = structures.remove(worldUuid);
        if (removed == null) {
            return;
        }
        removed.forEach((ignored1, structure) -> {
            typedMultiBlockStructureMap.computeIfAbsent(structure.getHolder().getStructureType(), ignored2 -> ConcurrentHashMap.newKeySet()).remove(structure);
        });
    }

    @Override
    public synchronized void clear() {
        structures.clear();
    }
}
