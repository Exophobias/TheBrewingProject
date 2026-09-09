package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.bukkit.breweries.BukkitCauldron;
import dev.jsinco.brewery.database.Session;

import java.util.concurrent.CompletableFuture;

public interface CauldronSession extends Session<CauldronSession> {

    // Writes bind the holder's runtime capability. Cancellation cannot discard admitted SQL work.

    CompletableFuture<Void> insertCauldron(BukkitCauldron cauldron);

    CompletableFuture<Void> updateCauldron(BukkitCauldron newCauldron);

    CompletableFuture<Void> removeCauldron(BukkitCauldron cauldron);

}
