package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.api.persistence.CauldronFixtureRequest;
import dev.jsinco.brewery.api.persistence.CauldronFixtureSnapshot;
import dev.jsinco.brewery.database.Session;
import java.util.concurrent.CompletableFuture;

public interface CauldronFixtureSession extends Session<CauldronFixtureSession> {
    enum Operation { RESERVE, INSPECT, CLOSE }
    CompletableFuture<CauldronFixtureSnapshot> execute(CauldronFixtureRequest request, Operation operation);
}
