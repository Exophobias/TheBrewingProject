package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.util.CancelState;
import dev.jsinco.brewery.bukkit.api.event.structure.DistilleryDestroyEvent;
import dev.jsinco.brewery.bukkit.breweries.distillery.BukkitDistillery;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlockEventListenerAtomicGuardTest {

    @Test
    void laterListenerCannotUncancelReservedDistilleryRemoval() throws ReflectiveOperationException {
        BukkitDistillery distillery = allocatePendingDistillery();
        DistilleryDestroyEvent event = new DistilleryDestroyEvent(
                new CancelState.Cancelled(), distillery, null, null, List.of()
        );

        event.setCancelled(false);

        assertFalse(event.isCancelled(), "models a later listener overriding initial cancellation");
        assertTrue(BlockEventListener.containsPendingAtomicDistillery(List.of(distillery)),
                "the authoritative pre-mutation guard must still abort removal");
    }

    @Test
    void hopperLookupRecognizesPendingDistillery() throws ReflectiveOperationException {
        BukkitDistillery distillery = allocatePendingDistillery();

        assertTrue(BlockEventListener.isPendingAtomicDistillery(distillery));
    }

    @Test
    void aggregateCancellationRejectsEveryDestroyProposal() {
        CompletableFuture<Boolean> first = new CompletableFuture<>();
        CompletableFuture<Boolean> second = new CompletableFuture<>();

        assertFalse(BlockEventListener.rejectDestroyProposals(List.of(first, second)));
        assertFalse(first.join());
        assertFalse(second.join());
    }

    @Test
    void destroyReceiptCompletesOnlyAfterPersistenceOutcome() {
        CompletableFuture<Boolean> successfulReceipt = new CompletableFuture<>();
        CompletableFuture<Void> successfulPersistence = new CompletableFuture<>();
        BlockEventListener.linkDestroyReceipt(successfulReceipt, successfulPersistence);

        assertFalse(successfulReceipt.isDone());
        successfulPersistence.complete(null);
        assertTrue(successfulReceipt.join());

        CompletableFuture<Boolean> failedReceipt = new CompletableFuture<>();
        CompletableFuture<Void> failedPersistence = new CompletableFuture<>();
        IllegalStateException persistenceFailure = new IllegalStateException("injected persistence failure");
        BlockEventListener.linkDestroyReceipt(failedReceipt, failedPersistence);

        failedPersistence.completeExceptionally(persistenceFailure);

        CompletionException completion = assertThrows(CompletionException.class, failedReceipt::join);
        assertSame(persistenceFailure, completion.getCause());
    }

    private static BukkitDistillery allocatePendingDistillery() throws ReflectiveOperationException {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        BukkitDistillery distillery = (BukkitDistillery) unsafe.allocateInstance(BukkitDistillery.class);
        Field pendingField = BukkitDistillery.class.getDeclaredField("atomicMovePending");
        pendingField.setAccessible(true);
        pendingField.setBoolean(distillery, true);
        return distillery;
    }
}
