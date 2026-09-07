package dev.jsinco.brewery.bukkit.database.hydration;

import dev.jsinco.brewery.api.vector.BreweryLocation;

import java.util.List;

/** Immutable database values. Reading a snapshot never resolves a Bukkit world or creates an inventory. */
public record WorldBrewerySnapshot(List<BarrelRow> barrels, List<DistilleryRow> distilleries,
                                   List<CauldronRow> cauldrons) {
    public WorldBrewerySnapshot {
        barrels = List.copyOf(barrels);
        distilleries = List.copyOf(distilleries);
        cauldrons = List.copyOf(cauldrons);
    }

    public record BrewRow(int position, boolean distillate, String serializedBrew) { }

    public record StructureRow(BreweryLocation origin, BreweryLocation unique,
                               String transformation, String format) { }

    public record BarrelRow(StructureRow structure, String type, int size, List<BrewRow> brews) {
        public BarrelRow { brews = List.copyOf(brews); }
    }

    public record DistilleryRow(StructureRow structure, long startTime, List<BrewRow> brews) {
        public DistilleryRow { brews = List.copyOf(brews); }
    }

    public record CauldronRow(BreweryLocation location, String type, String serializedBrew) { }
}
