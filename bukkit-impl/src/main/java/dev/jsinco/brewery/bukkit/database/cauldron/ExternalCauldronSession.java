package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.api.persistence.ExternalCauldronLease;
import dev.jsinco.brewery.api.persistence.ExternalCauldronLeaseRequest;
import dev.jsinco.brewery.database.Session;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface ExternalCauldronSession extends Session<ExternalCauldronSession> {
    CompletableFuture<ExternalCauldronLease> acquire(ExternalCauldronLeaseRequest request);
    CompletableFuture<Optional<ExternalCauldronLease>> inspect(ExternalCauldronLeaseRequest request);
    CompletableFuture<ExternalCauldronLease> retire(ExternalCauldronLeaseRequest request, ExternalCauldronLease.State state);
    CompletableFuture<Optional<ExternalCauldronLease>> inspectAfter(ExternalCauldronLeaseRequest request, CompletableFuture<?> dependency);
    CompletableFuture<ExternalCauldronLease> retireAfter(ExternalCauldronLeaseRequest request, ExternalCauldronLease.State state, CompletableFuture<?> dependency);
}
