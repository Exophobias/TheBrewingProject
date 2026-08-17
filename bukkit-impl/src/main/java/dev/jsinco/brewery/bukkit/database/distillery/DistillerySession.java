package dev.jsinco.brewery.bukkit.database.distillery;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.breweries.DistilleryAccess;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.bukkit.breweries.distillery.BukkitDistillery;
import dev.jsinco.brewery.database.Session;
import dev.jsinco.brewery.database.UncheckedPersistenceException;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface DistillerySession extends Session<DistillerySession> {

    CompletableFuture<Void> insertBrew(BreweryLocation distilleryLocation, int inventoryPos, boolean distillateInventoryType, Brew brew);

    CompletableFuture<Void> removeBrew(BreweryLocation distilleryLocation, int inventoryPos, boolean distillateInventoryType);

    CompletableFuture<List<BrewLookupResult>> findBrews(BreweryLocation distilleryLocation);

    CompletableFuture<Void> updateBrew(BreweryLocation distilleryLocation, int inventoryPos, boolean distillateInventoryType, Brew newBrew);

    CompletableFuture<Boolean> moveBrewsAtomically(BreweryLocation distilleryLocation,
                                                   List<DistilleryAccess.AtomicBrewMove> moves);

    CompletableFuture<Boolean> removeMixtureBrewsAtomically(BreweryLocation distilleryLocation,
                                                             List<DistilleryAccess.AtomicBrewRemoval> removals);

    CompletableFuture<Boolean> consumeDistilleryAtomically(BreweryLocation distilleryLocation);

    CompletableFuture<Void> insertDistillery(BukkitDistillery distillery);

    CompletableFuture<Void> removeDistillery(BukkitDistillery distillery);

    CompletableFuture<List<BukkitDistillery>> findDistilleries(UUID worldUuid);

    CompletableFuture<Void> updateDistillery(BukkitDistillery newDistillery);

    record BrewLookupResult(Brew brew, int position, boolean distillateInventoryType) {
    }

    final class AtomicMovePersistenceException extends UncheckedPersistenceException {
        private final boolean rollbackConfirmed;

        public AtomicMovePersistenceException(Throwable cause, boolean rollbackConfirmed) {
            super(cause);
            this.rollbackConfirmed = rollbackConfirmed;
        }

        public boolean rollbackConfirmed() {
            return rollbackConfirmed;
        }
    }
}
