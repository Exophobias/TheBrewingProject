package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.api.persistence.CauldronPersistenceSnapshot;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.database.Session;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface CauldronInspectionSession extends Session<CauldronInspectionSession> {
    CompletableFuture<CauldronPersistenceSnapshot> inspect(List<BreweryLocation> keys,
                                                         CauldronPersistenceOrder.Observation observation);
}
