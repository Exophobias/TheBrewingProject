package dev.jsinco.brewery.api.persistence;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Owner SQL observation. CLOSED retires only an empty reservation; no brew was deleted or delivered. */
public record CauldronFixtureSnapshot(CauldronFixtureRequest request, Phase phase, List<Lane> lanes) {
    public enum Phase { RESERVED, CLOSED }
    public CauldronFixtureSnapshot {
        Objects.requireNonNull(request); Objects.requireNonNull(phase); lanes = List.copyOf(lanes);
        if (lanes.size() != request.lanes().size()) throw new IllegalArgumentException("Fixture lane count changed");
        if (lanes.stream().map(Lane::birthUuid).distinct().count() != lanes.size())
            throw new IllegalArgumentException("Duplicate fixture birth");
    }
    public record Lane(UUID birthUuid, boolean persistedCauldronPresent) {
        public Lane { Objects.requireNonNull(birthUuid); }
    }
}
