package dev.jsinco.brewery.api.persistence;

import java.util.Objects;

/** Durable native-brewing exclusion, not authority over the external contents or lawful loot. */
public record ExternalCauldronLease(ExternalCauldronLeaseRequest request, State state) {
    public enum State { ACTIVE, RELEASED, DESTROYED }
    public ExternalCauldronLease { Objects.requireNonNull(request); Objects.requireNonNull(state); }
}
