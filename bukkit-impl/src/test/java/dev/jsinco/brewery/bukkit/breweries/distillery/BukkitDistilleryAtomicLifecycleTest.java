package dev.jsinco.brewery.bukkit.breweries.distillery;

import dev.jsinco.brewery.api.util.CancelState;
import dev.jsinco.brewery.api.util.Holder;
import org.junit.jupiter.api.Test;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BukkitDistilleryAtomicLifecycleTest {

    @Test
    void runLocallyExecutesInlineWhenAlreadyOnOwningRegion() {
        AtomicBoolean actionRan = new AtomicBoolean();
        AtomicInteger schedules = new AtomicInteger();

        CompletableFuture<Void> result = BukkitDistillery.runLocally(
                true,
                () -> actionRan.set(true),
                ignored -> schedules.incrementAndGet()
        );

        assertTrue(actionRan.get());
        assertTrue(result.isDone());
        assertDoesNotThrow(result::join);
        assertEquals(0, schedules.get());
    }

    @Test
    void runLocallySchedulesWhenCalledOffRegion() {
        AtomicBoolean actionRan = new AtomicBoolean();
        AtomicReference<Runnable> scheduled = new AtomicReference<>();

        CompletableFuture<Void> result = BukkitDistillery.runLocally(
                false,
                () -> actionRan.set(true),
                scheduled::set
        );

        assertFalse(actionRan.get());
        assertFalse(result.isDone());
        scheduled.get().run();
        assertTrue(actionRan.get());
        assertDoesNotThrow(result::join);
    }

    @Test
    void committedPublicationFailureKeepsReservationAndAllGatesClosed() throws ReflectiveOperationException {
        BukkitDistillery distillery = allocatePendingDistillery();
        IllegalStateException injectedFailure = new IllegalStateException("injected publication failure");

        CompletableFuture<Boolean> result = BukkitDistillery.publishAtomicMutation(
                CompletableFuture.completedFuture(true),
                () -> {
                    throw injectedFailure;
                },
                action -> BukkitDistillery.runLocally(true, action, ignored -> {
                    throw new AssertionError("inline publication must not schedule");
                }),
                () -> setPending(distillery, false)
        );

        CompletionException completion = assertThrows(CompletionException.class, result::join);
        assertSame(injectedFailure, completion.getCause());
        assertTrue(distillery.isAtomicMovePending());
        assertInstanceOf(CancelState.Cancelled.class, distillery.open(null, (Holder.Player) null));
        assertTrue(distillery.access(null).isEmpty());
        assertDoesNotThrow(distillery::tick);
        assertDoesNotThrow(distillery::tickInventory);
        assertDoesNotThrow(() -> distillery.destroy(null));
    }

    @Test
    void committedSchedulingFailureKeepsReservation() {
        AtomicBoolean released = new AtomicBoolean();
        IllegalStateException schedulingFailure = new IllegalStateException("injected scheduling failure");

        CompletableFuture<Boolean> result = BukkitDistillery.publishAtomicMutation(
                CompletableFuture.completedFuture(true),
                () -> { },
                ignored -> {
                    throw schedulingFailure;
                },
                () -> released.set(true)
        );

        CompletionException completion = assertThrows(CompletionException.class, result::join);
        assertSame(schedulingFailure, completion.getCause());
        assertFalse(released.get(), "a committed outcome must remain quarantined if publication cannot run");
    }

    @Test
    void pendingCloseDoesNotTouchReservedInventories() throws ReflectiveOperationException {
        BukkitDistillery distillery = allocatePendingDistillery();

        assertDoesNotThrow(() -> distillery.close(true));
        assertTrue(distillery.isAtomicMovePending());
    }

    @Test
    void deferredInventoryPublicationParticipatesInTheMutationGate()
            throws ReflectiveOperationException {
        BukkitDistillery distillery = allocatePendingDistillery();
        setPending(distillery, false);
        setDeferredPublications(distillery, 1);

        assertTrue(distillery.isAtomicMovePending());
        assertDoesNotThrow(() -> distillery.close(true));
    }

    @Test
    void reservationRequiresTheExactCurrentHolder() {
        Object expected = new Object();

        assertTrue(BukkitDistillery.reservationOwnsHolder(true, false, expected, expected));
        assertFalse(BukkitDistillery.reservationOwnsHolder(true, false, expected, new Object()));
        assertFalse(BukkitDistillery.reservationOwnsHolder(false, false, expected, expected));
        assertFalse(BukkitDistillery.reservationOwnsHolder(true, true, expected, expected));
    }

    @Test
    void knownFalseSchedulingFailureReleasesReservation() {
        AtomicBoolean released = new AtomicBoolean();
        IllegalStateException schedulingFailure = new IllegalStateException("injected scheduling failure");

        CompletableFuture<Boolean> result = BukkitDistillery.publishAtomicMutation(
                CompletableFuture.completedFuture(false),
                () -> { },
                ignored -> {
                    throw schedulingFailure;
                },
                () -> released.set(true)
        );

        assertThrows(CompletionException.class, result::join);
        assertTrue(released.get(), "known unchanged durable state does not require quarantine");
    }

    @Test
    void confirmedRollbackSchedulingFailureReleasesReservation() {
        AtomicBoolean released = new AtomicBoolean();
        CompletableFuture<Boolean> persistence = CompletableFuture.failedFuture(
                new dev.jsinco.brewery.bukkit.database.distillery.DistillerySession
                        .AtomicMovePersistenceException(
                        new IllegalStateException("injected precommit failure"), true
                )
        );

        CompletableFuture<Boolean> result = BukkitDistillery.publishAtomicMutation(
                persistence,
                () -> { },
                ignored -> {
                    throw new IllegalStateException("injected scheduling failure");
                },
                () -> released.set(true)
        );

        assertThrows(CompletionException.class, result::join);
        assertTrue(released.get(), "confirmed rollback must not strand the live inventory");
    }

    private static BukkitDistillery allocatePendingDistillery() throws ReflectiveOperationException {
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        BukkitDistillery distillery = (BukkitDistillery) unsafe.allocateInstance(BukkitDistillery.class);
        setPending(distillery, true);
        return distillery;
    }

    private static void setPending(BukkitDistillery distillery, boolean pending) {
        try {
            Field pendingField = BukkitDistillery.class.getDeclaredField("atomicMovePending");
            pendingField.setAccessible(true);
            pendingField.setBoolean(distillery, pending);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }

    private static void setDeferredPublications(BukkitDistillery distillery, int pending) {
        try {
            Field pendingField = BukkitDistillery.class.getDeclaredField("deferredInventoryPublications");
            pendingField.setAccessible(true);
            pendingField.setInt(distillery, pending);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError(failure);
        }
    }
}
