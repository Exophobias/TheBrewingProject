package dev.jsinco.brewery.api.persistence;

import dev.jsinco.brewery.api.vector.BreweryLocation;
import java.util.Objects;
import java.util.UUID;

/** Persist this exact generation before admission. A retired generation can never be acquired again. */
public record ExternalCauldronLeaseRequest(UUID leaseId, String owner, BreweryLocation location) {
    public ExternalCauldronLeaseRequest {
        Objects.requireNonNull(leaseId); Objects.requireNonNull(owner); Objects.requireNonNull(location);
        Objects.requireNonNull(location.worldUuid());
        if (!owner.matches("[a-z][a-z0-9_-]{0,63}")) throw new IllegalArgumentException("Invalid external cauldron owner");
    }
}
