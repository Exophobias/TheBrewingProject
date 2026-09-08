package dev.jsinco.brewery.bukkit.breweries.distillery;

import dev.jsinco.brewery.api.breweries.DistilleryAccess;
import dev.jsinco.brewery.api.util.CancelState;
import dev.jsinco.brewery.bukkit.api.event.structure.DistilleryDestroyEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DistilleryDestroyCommitContractTest {

    private static final DistilleryAccess DISTILLERY = (DistilleryAccess) Proxy.newProxyInstance(
            DistilleryAccess.class.getClassLoader(),
            new Class<?>[]{DistilleryAccess.class},
            (proxy, method, args) -> null
    );

    @Test
    void legacyProposalCannotAuthorizeIrreversibleWorkWithoutAnOwnerReceipt() {
        DistilleryDestroyEvent event = new DistilleryDestroyEvent(
                new CancelState.Allowed(), DISTILLERY, null, null, List.of()
        );

        AtomicBoolean irreversibleWork = new AtomicBoolean();
        event.getCommitResult().thenAccept(committed -> {
            if (committed) irreversibleWork.set(true);
        });
        assertFalse(event.getCommitResult().toCompletableFuture().join());
        assertFalse(irreversibleWork.get());
    }

    @Test
    void listenerCannotForgeDestructionCommit() {
        CompletableFuture<Boolean> producerSignal = new CompletableFuture<>();
        DistilleryDestroyEvent event = event(producerSignal);
        AtomicBoolean irreversibleWork = new AtomicBoolean();
        event.getCommitResult().thenAccept(committed -> {
            if (committed) {
                irreversibleWork.set(true);
            }
        });

        event.getCommitResult().toCompletableFuture().complete(true);

        assertFalse(producerSignal.isDone());
        assertFalse(irreversibleWork.get());
        producerSignal.complete(false);
        assertFalse(event.getCommitResult().toCompletableFuture().join());
    }

    @Test
    void lifecycleFailureReachesCommitAwareListeners() {
        CompletableFuture<Boolean> producerSignal = new CompletableFuture<>();
        DistilleryDestroyEvent event = event(producerSignal);
        IllegalStateException failure = new IllegalStateException("injected lifecycle failure");

        producerSignal.completeExceptionally(failure);

        CompletionException completion = assertThrows(
                CompletionException.class,
                () -> event.getCommitResult().toCompletableFuture().join()
        );
        assertSame(failure, completion.getCause());
    }

    private static DistilleryDestroyEvent event(CompletableFuture<Boolean> commitSignal) {
        return new DistilleryDestroyEvent(
                new CancelState.Allowed(), DISTILLERY, null, null, List.of(), commitSignal
        );
    }
}
