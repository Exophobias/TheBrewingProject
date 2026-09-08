package dev.jsinco.brewery.api.persistence;

import dev.jsinco.brewery.api.vector.BreweryLocation;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Immutable, read-only owner evidence for exact keys, including brews with no parent row. */
public record BreweryPersistenceSnapshot(List<Entry> entries) {
    public static final int MAX_KEYS = 32;
    public BreweryPersistenceSnapshot { entries = List.copyOf(entries); }

    /** Validate before any persistence work is queued; return an immutable caller-independent list. */
    public static List<BreweryLocation> validateKeys(List<BreweryLocation> keys) {
        Objects.requireNonNull(keys, "keys");
        if (keys.isEmpty() || keys.size() > MAX_KEYS) throw new IllegalArgumentException("Expected 1..32 exact keys");
        List<BreweryLocation> copy = List.copyOf(keys);
        var world = Objects.requireNonNull(copy.getFirst().worldUuid(), "world UUID");
        var seen = new HashSet<BreweryLocation>();
        for (var key : copy) {
            if (!world.equals(key.worldUuid()) || !seen.add(key))
                throw new IllegalArgumentException("Exact keys must be distinct and share a world UUID");
        }
        return copy;
    }

    public record Entry(BreweryLocation location, Optional<DistilleryRow> distillery,
                        Optional<BarrelRow> barrel, List<BrewRow> distilleryBrews, List<BrewRow> barrelBrews) {
        public Entry {
            Objects.requireNonNull(location); Objects.requireNonNull(distillery); Objects.requireNonNull(barrel);
            distilleryBrews = List.copyOf(distilleryBrews); barrelBrews = List.copyOf(barrelBrews);
        }
        public boolean absent() {
            return distillery.isEmpty() && barrel.isEmpty() && distilleryBrews.isEmpty() && barrelBrews.isEmpty();
        }
    }
    public record StructureRow(BreweryLocation origin, BreweryLocation unique, String transformation, String format) {
        public StructureRow {
            Objects.requireNonNull(origin); Objects.requireNonNull(unique);
            Objects.requireNonNull(transformation); Objects.requireNonNull(format);
        }
    }
    public record DistilleryRow(StructureRow structure, long startTime) {
        public DistilleryRow { Objects.requireNonNull(structure); }
    }
    public record BarrelRow(StructureRow structure, String type, int size) {
        public BarrelRow { Objects.requireNonNull(structure); Objects.requireNonNull(type); }
    }
    /** Raw owner serialization; callers do not need implementation classes or a particular JSON library. */
    public record BrewRow(int position, boolean distillate, String serializedBrew) {
        public BrewRow { Objects.requireNonNull(serializedBrew); }
    }
}
