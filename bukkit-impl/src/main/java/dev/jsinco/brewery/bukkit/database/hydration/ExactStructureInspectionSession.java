package dev.jsinco.brewery.bukkit.database.hydration;

import dev.jsinco.brewery.api.persistence.BreweryPersistenceSnapshot;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.database.Session;
import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ExactStructureInspectionSession extends Session<ExactStructureInspectionSession> {
    CompletableFuture<BreweryPersistenceSnapshot> inspect(List<BreweryLocation> keys);
}
