package dev.jsinco.brewery.bukkit.breweries.distillery;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.breweries.DistilleryAccess;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.DistillStepImpl;
import dev.jsinco.brewery.bukkit.api.event.process.BrewDistillEvent;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrewDistillCommitContractTest {

    private static final DistilleryAccess DISTILLERY = (DistilleryAccess) Proxy.newProxyInstance(
            DistilleryAccess.class.getClassLoader(),
            new Class<?>[]{DistilleryAccess.class},
            (proxy, method, args) -> null
    );

    @Test
    void acceptedEventRunsCommitWorkOnlyAfterSuccessfulPublication() {
        CompletableFuture<Boolean> commitSignal = new CompletableFuture<>();
        BrewDistillEvent event = event(commitSignal);
        AtomicBoolean commitWork = new AtomicBoolean();
        event.getCommitResult().thenAccept(committed -> {
            if (committed) {
                commitWork.set(true);
            }
        });

        assertFalse(commitWork.get());
        BukkitDistillery.completeCommitSignals(List.of(commitSignal), true, null);

        assertTrue(commitWork.get());
        assertTrue(event.getCommitResult().toCompletableFuture().join());
    }

    @Test
    void listenerCannotCompleteProducerCommitReceipt() {
        CompletableFuture<Boolean> producerSignal = new CompletableFuture<>();
        BrewDistillEvent event = event(producerSignal);
        AtomicBoolean irreversibleWork = new AtomicBoolean();
        event.getCommitResult().thenAccept(committed -> {
            if (committed) {
                irreversibleWork.set(true);
            }
        });

        CompletableFuture<Boolean> listenerView = event.getCommitResult().toCompletableFuture();
        assertTrue(listenerView.complete(true), "a listener may mutate only its detached view");
        assertFalse(producerSignal.isDone());
        assertFalse(irreversibleWork.get(), "forged listener completion must not reach other listeners");

        producerSignal.complete(false);
        assertFalse(event.getCommitResult().toCompletableFuture().join());
        assertFalse(irreversibleWork.get());
    }

    @Test
    void cancelledEventCompletesFalseWithoutRunningCommitWork() {
        CompletableFuture<Boolean> commitSignal = new CompletableFuture<>();
        BrewDistillEvent event = event(commitSignal);
        AtomicBoolean commitWork = new AtomicBoolean();
        event.getCommitResult().thenAccept(committed -> {
            if (committed) {
                commitWork.set(true);
            }
        });
        event.setCancelled(true);

        BukkitDistillery.completeCommitSignals(List.of(commitSignal), false, null);

        assertTrue(event.isCancelled());
        assertFalse(event.getCommitResult().toCompletableFuture().join());
        assertFalse(commitWork.get());
    }

    @Test
    void rolledBackEventCompletesExceptionallyWithoutRunningCommitWork() {
        CompletableFuture<Boolean> commitSignal = new CompletableFuture<>();
        BrewDistillEvent event = event(commitSignal);
        AtomicBoolean commitWork = new AtomicBoolean();
        event.getCommitResult().thenAccept(committed -> {
            if (committed) {
                commitWork.set(true);
            }
        });
        IllegalStateException injectedFailure = new IllegalStateException("injected rollback");

        BukkitDistillery.completeCommitSignals(
                List.of(commitSignal), null, new CompletionException(injectedFailure)
        );

        CompletionException completion = assertThrows(
                CompletionException.class,
                () -> event.getCommitResult().toCompletableFuture().join()
        );
        assertSame(injectedFailure, completion.getCause());
        assertFalse(commitWork.get());
    }

    @Test
    void transferPlanPreservesCancellationDestinationAndMutatedResult() {
        Brew first = brew(1);
        Brew second = brew(2);
        Brew third = brew(3);
        Brew occupied = brew(20);
        Brew listenerResult = brew(42);
        AtomicInteger eventNumber = new AtomicInteger();

        List<DistilleryAccess.AtomicBrewMove> moves = BukkitDistillery.planTransfers(
                new Brew[]{first, second, third},
                new Brew[]{occupied, null, null, null},
                2,
                (source, proposed) -> eventNumber.getAndIncrement() == 0
                        ? Optional.empty()
                        : Optional.of(listenerResult)
        );

        assertEquals(2, eventNumber.get());
        assertEquals(1, moves.size());
        DistilleryAccess.AtomicBrewMove move = moves.getFirst();
        assertEquals(1, move.mixturePosition());
        // Destination 1 belonged to the cancelled first event, matching the legacy loop's continue.
        assertEquals(2, move.distillatePosition());
        assertSame(second, move.expectedMixture());
        assertSame(listenerResult, move.distillate());
    }

    @Test
    void laterDeferredEventDiscardsEarlierAcceptedMoveAndStopsBatch() {
        Brew first = brew(1);
        Brew second = brew(2);
        Brew third = brew(3);
        AtomicInteger eventNumber = new AtomicInteger();
        AtomicBoolean batchDeferred = new AtomicBoolean();
        List<CompletableFuture<Boolean>> commitSignals = new ArrayList<>();

        BukkitDistillery.TransferPlan plan = BukkitDistillery.planTransferBatch(
                new Brew[]{first, second, third},
                new Brew[]{null, null, null},
                3,
                (source, proposed) -> {
                    int currentEvent = eventNumber.getAndIncrement();
                    CompletableFuture<Boolean> commitSignal = new CompletableFuture<>();
                    commitSignals.add(commitSignal);
                    BrewDistillEvent event = new BrewDistillEvent(
                            DISTILLERY, source, proposed, commitSignal
                    );
                    if (currentEvent == 1) {
                        event.deferBatch();
                    }
                    batchDeferred.set(event.isBatchDeferred());
                    return Optional.of(event.getResult());
                },
                batchDeferred::get
        );

        assertTrue(plan.deferred());
        assertTrue(plan.moves().isEmpty());
        assertEquals(2, eventNumber.get(), "no event after the deferring event should fire");
        assertTrue(BukkitDistillery.completeDeferredBatch(plan, commitSignals));
        assertEquals(2, commitSignals.size());
        assertTrue(commitSignals.stream().allMatch(CompletableFuture::isDone));
        assertTrue(commitSignals.stream().noneMatch(CompletableFuture::join));
    }

    private static BrewDistillEvent event(CompletableFuture<Boolean> commitSignal) {
        return new BrewDistillEvent(DISTILLERY, brew(1), brew(2), commitSignal);
    }

    private static Brew brew(int runs) {
        return new BrewImpl(List.of(new DistillStepImpl(runs)));
    }
}
