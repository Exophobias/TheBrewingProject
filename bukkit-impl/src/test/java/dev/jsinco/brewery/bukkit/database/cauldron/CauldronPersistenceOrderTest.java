package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.api.vector.BreweryLocation;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class CauldronPersistenceOrderTest {
    private final CauldronPersistenceOrder order = new CauldronPersistenceOrder();
    private final UUID world = UUID.randomUUID();
    private BreweryLocation key(int x) { return new BreweryLocation(x, 64, 2, world); }
    private CompletableFuture<Void> insert(CauldronPersistenceOrder.Owner owner) {
        return order.admit(owner, CauldronPersistenceOrder.Write.INSERT, previous -> previous);
    }

    @Test void replacementNeedsTerminalAdmissionAndOldCapabilityNeverReacquiresAnEmptyLane() {
        var a = order.newOwner(key(1)); var b = order.newOwner(key(1));
        insert(a).join();
        assertThrows(IllegalStateException.class, () -> insert(b));
        var delayed = new CompletableFuture<Void>();
        var deletion = order.admit(a, CauldronPersistenceOrder.Write.DELETE, previous -> previous.thenCompose(v -> delayed));
        var replacement = insert(b);
        assertFalse(replacement.isDone());
        assertThrows(IllegalStateException.class, () -> order.admit(a, CauldronPersistenceOrder.Write.UPDATE, p -> p));
        var repeat = order.admit(a, CauldronPersistenceOrder.Write.DELETE, p -> fail("Do not reissue old SQL"));
        repeat.cancel(true); assertFalse(deletion.isDone());
        delayed.complete(null); replacement.join(); deletion.join();
        assertTrue(b.writable()); assertFalse(a.writable());
        order.admit(b, CauldronPersistenceOrder.Write.DELETE, p -> p).join();
        assertEquals(0, order.status().coordinates());
        assertThrows(IllegalStateException.class, () -> insert(a));
        assertThrows(IllegalStateException.class, () -> insert(b));
    }

    @Test void failedPredecessorPoisonsReplacementAndRemainsExplicitlyUnresolved() {
        var a = order.newOwner(key(1)); var pending = new CompletableFuture<Void>();
        var insert = order.admit(a, CauldronPersistenceOrder.Write.INSERT, p -> pending);
        var deletion = order.admit(a, CauldronPersistenceOrder.Write.DELETE, p -> p);
        var b = order.newOwner(key(1)); var replacement = insert(b);
        pending.completeExceptionally(new IllegalStateException("SQL refused"));
        assertThrows(CompletionException.class, insert::join);
        assertThrows(CompletionException.class, deletion::join);
        assertThrows(CompletionException.class, replacement::join);
        assertTrue(a.failed()); assertTrue(b.failed()); assertTrue(order.unresolved(world));
        order.invalidate(world);
        var hydration = order.beginHydration(world, () -> true);
        assertThrows(CompletionException.class, () -> hydration.drained().join());
        assertThrows(IllegalStateException.class, () -> hydration.adopt(List.of(order.newOwner(key(1)))));
    }

    @Test void hydrationDrainsUnqueuedIngredientWorkAndRevokesEveryOldCapability() {
        var old = order.newOwner(key(1)); var detachedOld = order.newOwner(key(2));
        var ingredients = new CompletableFuture<Void>();
        order.admit(old, CauldronPersistenceOrder.Write.INSERT, p -> p.thenCompose(v -> ingredients));
        var current = new AtomicBoolean(true);
        var hydration = order.beginHydration(world, current::get);
        assertFalse(hydration.drained().isDone()); assertFalse(old.writable()); assertFalse(detachedOld.writable());
        var loaded = order.newOwner(key(1));
        assertThrows(IllegalStateException.class, () -> hydration.adopt(List.of(loaded)));
        ingredients.complete(null); hydration.drained().join();
        hydration.adopt(List.of(loaded)); assertFalse(loaded.writable());
        hydration.published(); assertTrue(loaded.writable());
        order.admit(loaded, CauldronPersistenceOrder.Write.UPDATE, p -> p).join();
        assertThrows(IllegalStateException.class, () -> order.admit(old, CauldronPersistenceOrder.Write.UPDATE, p -> p));
        assertThrows(IllegalStateException.class, () -> insert(detachedOld));
    }

    @Test void staleOrForeignHydrationCannotAdoptAndFailedPublicationStaysPaused() {
        var first = order.beginHydration(world, () -> true);
        var stale = order.newOwner(key(1));
        var second = order.beginHydration(world, () -> true);
        assertThrows(IllegalStateException.class, () -> first.adopt(List.of(stale)));
        assertThrows(IllegalStateException.class, () -> second.adopt(List.of(stale)));
        var foreign = new CauldronPersistenceOrder().newOwner(key(1));
        assertThrows(IllegalStateException.class, () -> second.adopt(List.of(foreign)));
        assertThrows(IllegalStateException.class, () -> insert(foreign));
        var loaded = order.newOwner(key(1)); second.adopt(List.of(loaded));
        assertFalse(loaded.writable(), "Registry publication has not been acknowledged");
        order.invalidateAll(); assertThrows(IllegalStateException.class, second::published);
    }

    @Test void invalidateAllAlsoRevokesNeverInsertedOwnersAndStopKeepsPendingReceipt() {
        var detached = order.newOwner(key(1)); order.invalidateAll();
        assertThrows(IllegalStateException.class, () -> insert(detached));
        var otherWorld = new BreweryLocation(1, 2, 3, UUID.randomUUID());
        var a = order.newOwner(otherWorld); var pending = new CompletableFuture<Void>();
        var observation = order.admit(a, CauldronPersistenceOrder.Write.INSERT, p -> pending);
        order.stop(); assertFalse(a.writable()); assertFalse(observation.isDone());
        assertThrows(IllegalStateException.class, () -> order.newOwner(otherWorld));
        pending.complete(null); observation.join();
    }

    @Test void boundedAdmissionRefusesOverflowWithoutLosingTheExistingTail() {
        var owner = order.newOwner(key(1)); var pending = new CompletableFuture<Void>();
        order.admit(owner, CauldronPersistenceOrder.Write.INSERT, p -> pending);
        for (int i = 1; i < CauldronPersistenceOrder.MAX_PENDING_PER_COORDINATE; i++)
            order.admit(owner, CauldronPersistenceOrder.Write.UPDATE, p -> p);
        assertThrows(IllegalStateException.class, () -> order.admit(owner, CauldronPersistenceOrder.Write.UPDATE, p -> p));
        assertEquals(256, order.status().pendingWrites());
        pending.complete(null); owner.barrier().join(); assertEquals(0, order.status().pendingWrites());
    }
}
