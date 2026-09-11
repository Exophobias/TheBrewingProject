package dev.jsinco.brewery.api.persistence;

import dev.jsinco.brewery.api.vector.BreweryLocation;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletionStage;

/**
 * Owner-issued, runtime-only observation. Retain the actual returned object, not a caller-created
 * future or serialized copy. OBSERVED means the original operation and SQL observation succeeded;
 * it is not complete fixture cleanup. Check current() on the owning server thread at proof use.
 * No receipt authorizes deleting a holder recreated after restart, or proves item delivery.
 */
public interface CauldronPersistenceReceipt {
    enum Kind { INSPECTION, RETIREMENT }
    enum State { PENDING, OBSERVED, FAILED }
    Kind kind();
    List<BreweryLocation> keys();
    State state();
    Optional<CauldronPersistenceSnapshot> observation();
    /**
     * True only when this retirement's original SQL DELETE succeeded. Historical, not current
     * absence: later lane retirements can invalidate observations. Drain all exact original
     * acknowledgments, then obtain one fresh batch inspection; never replay because current=false.
     */
    boolean deletionAcknowledged();
    /** Caller completion/cancellation cannot alter the owner's operation or stored observation. */
    CompletionStage<CauldronPersistenceSnapshot> completion();
    /** False until observed, off the owning thread, or after any later admission/lifecycle drift. */
    boolean current();
}
