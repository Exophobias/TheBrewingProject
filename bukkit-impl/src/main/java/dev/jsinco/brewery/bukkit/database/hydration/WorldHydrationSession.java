package dev.jsinco.brewery.bukkit.database.hydration;

import dev.jsinco.brewery.database.Session;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public interface WorldHydrationSession extends Session<WorldHydrationSession> {
    CompletableFuture<WorldBrewerySnapshot> readWorld(UUID worldId);
}
