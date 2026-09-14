package dev.jsinco.brewery.api.persistence;

import java.util.Optional;
import java.util.concurrent.CompletionStage;

/** Completion is owner-controlled. current() additionally verifies the original runtime/provider boundary. */
public interface CauldronFixtureReceipt {
    enum State { PENDING, FAILED, OBSERVED }
    State state();
    Optional<CauldronFixtureSnapshot> observation();
    CompletionStage<CauldronFixtureSnapshot> completion();
    boolean current();
}
