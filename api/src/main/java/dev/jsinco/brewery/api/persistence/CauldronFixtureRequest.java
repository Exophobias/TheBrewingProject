package dev.jsinco.brewery.api.persistence;

import dev.jsinco.brewery.api.vector.BreweryLocation;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Persist this exact request before admission. It reserves empty keys, never player value or native input. */
public record CauldronFixtureRequest(UUID runId, UUID capability, List<Lane> lanes) {
    public static final int MAX_LANES = 20;
    public CauldronFixtureRequest {
        Objects.requireNonNull(runId); Objects.requireNonNull(capability);
        lanes = List.copyOf(lanes);
        if (lanes.isEmpty() || lanes.size() > MAX_LANES) throw new IllegalArgumentException("Expected 1..20 fixture lanes");
        CauldronPersistenceSnapshot.validateKeys(lanes.stream().map(Lane::location).toList());
        var actors = new HashSet<UUID>();
        for (Lane lane : lanes) if (!actors.add(lane.actorId())) throw new IllegalArgumentException("Duplicate fixture actor");
    }
    public record Lane(BreweryLocation location, UUID actorId) {
        public Lane { Objects.requireNonNull(location); Objects.requireNonNull(actorId); }
    }
    public List<BreweryLocation> keys() { return lanes.stream().map(Lane::location).toList(); }
}
