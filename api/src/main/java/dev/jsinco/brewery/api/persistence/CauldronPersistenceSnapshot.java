package dev.jsinco.brewery.api.persistence;

import dev.jsinco.brewery.api.vector.BreweryLocation;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Exact SQL cauldron rows. This is neither a reservation nor proof of restart ownership. */
public record CauldronPersistenceSnapshot(List<Entry> entries) {
    public CauldronPersistenceSnapshot {
        entries = List.copyOf(entries);
        validateKeys(entries.stream().map(Entry::location).toList());
    }
    public static List<BreweryLocation> validateKeys(List<BreweryLocation> keys) {
        return BreweryPersistenceSnapshot.validateKeys(keys);
    }
    public record Entry(BreweryLocation location, Optional<Row> row) {
        public Entry { Objects.requireNonNull(location); Objects.requireNonNull(row); }
    }
    /** Null cauldron_type is a supported legacy SQL value, represented explicitly. */
    public record Row(String serializedBrew, Optional<String> cauldronType) {
        public Row { Objects.requireNonNull(serializedBrew); Objects.requireNonNull(cauldronType); }
    }
}
