package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.api.persistence.*;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.database.sql.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class CauldronFixtureCoordinatorTest {
    @TempDir Path directory;
    private SqlDatabase database;
    private CauldronPersistenceOrder order;
    private CauldronFixtureCoordinator coordinator;
    private final Queue<Runnable> main=new ConcurrentLinkedQueue<>();
    private final Set<BreweryLocation> occupied=new HashSet<>();
    private boolean provider=true;
    private final Thread ownerThread=Thread.currentThread();
    private final CauldronFixtureRequest request=new CauldronFixtureRequest(UUID.randomUUID(),UUID.randomUUID(),
            List.of(new CauldronFixtureRequest.Lane(new BreweryLocation(1,64,3,UUID.randomUUID()),UUID.randomUUID())));
    @BeforeEach void start() throws Exception {
        database=new SqlDatabase(DatabaseDriver.SQLITE); database.init(directory.toFile());
        order=new CauldronPersistenceOrder(); coordinator=new CauldronFixtureCoordinator(database,order,
                ()->provider && Thread.currentThread()==ownerThread,key->!occupied.contains(key),main::add); coordinator.initialize();
    }
    @AfterEach void stop() { database.close().join(); }
    private CauldronFixtureReceipt execute(CauldronFixtureSession.Operation operation) { return coordinator.execute(request,operation); }
    private void pump() throws Exception {
        database.flush().get(5,TimeUnit.SECONDS);
        Runnable task; while((task=main.poll())!=null) task.run();
    }
    private CauldronFixtureSnapshot finish(CauldronFixtureReceipt receipt) throws Exception { pump(); return receipt.completion().toCompletableFuture().get(5,TimeUnit.SECONDS); }
    @Test void reservationFencesBeforeSqlCompletionAndCannotBeForgedByCallerFuture() throws Exception {
        var old=order.newOwner(request.keys().getFirst());
        var receipt=execute(CauldronFixtureSession.Operation.RESERVE);
        assertTrue(order.fixtureBlocked(request.keys().getFirst())); assertFalse(old.writable());
        assertThrows(IllegalStateException.class,()->order.newOwner(request.keys().getFirst()));
        var forged=receipt.completion().toCompletableFuture(); forged.complete(null);
        assertEquals(CauldronFixtureReceipt.State.PENDING,receipt.state()); assertFalse(receipt.current());
        var snapshot=finish(receipt); assertEquals(CauldronFixtureSnapshot.Phase.RESERVED,snapshot.phase()); assertTrue(receipt.current());
        assertThrows(IllegalStateException.class,()->order.admit(old,CauldronPersistenceOrder.Write.INSERT,ignored->CompletableFuture.completedFuture(null)));
        var close=execute(CauldronFixtureSession.Operation.CLOSE); assertFalse(receipt.current());
        close.completion().toCompletableFuture().cancel(true); finish(close); assertTrue(close.current());
        assertFalse(order.fixtureBlocked(request.keys().getFirst())); assertTrue(order.newOwner(request.keys().getFirst()).writable());
    }
    @Test void pendingOriginalOperationPreventsConflictingRequestAndClose() throws Exception {
        var first=execute(CauldronFixtureSession.Operation.RESERVE);
        assertSame(first,execute(CauldronFixtureSession.Operation.RESERVE));
        assertThrows(IllegalStateException.class,()->execute(CauldronFixtureSession.Operation.CLOSE));
        assertThrows(IllegalStateException.class,()->coordinator.execute(new CauldronFixtureRequest(request.runId(),UUID.randomUUID(),request.lanes()),CauldronFixtureSession.Operation.RESERVE));
        finish(first);
    }
    @Test void admittedOriginalSqlAndRuntimeOwnersCannotBeTakenByFixtureReservation() {
        var key=request.keys().getFirst(); var holder=order.newOwner(key); var original=new CompletableFuture<Void>();
        order.admit(holder,CauldronPersistenceOrder.Write.INSERT,ignored->original);
        assertThrows(IllegalStateException.class,()->execute(CauldronFixtureSession.Operation.RESERVE));
        assertFalse(order.fixtureBlocked(key)); original.complete(null);
        assertThrows(IllegalStateException.class,()->execute(CauldronFixtureSession.Operation.RESERVE));
    }
    @Test void coldReservationReinstatesQuarantineAndExactCleanupWithoutRecreatingAnyCauldron() throws Exception {
        var original=finish(execute(CauldronFixtureSession.Operation.RESERVE));
        order.stop(); database.close().join(); start();
        assertTrue(order.fixtureBlocked(request.keys().getFirst()));
        var restored=finish(execute(CauldronFixtureSession.Operation.INSPECT)); assertEquals(original,restored);
        var hydration=order.beginHydration(request.keys().getFirst().worldUuid(),()->true);
        assertThrows(IllegalStateException.class,()->hydration.restoreOwner(request.keys().getFirst(),original.lanes().getFirst().birthUuid()));
        hydration.adopt(List.of()); hydration.published();
        assertEquals(CauldronFixtureSnapshot.Phase.CLOSED,finish(execute(CauldronFixtureSession.Operation.CLOSE)).phase());
        order.stop(); database.close().join(); start();
        assertFalse(order.fixtureBlocked(request.keys().getFirst()));
        assertEquals(CauldronFixtureSnapshot.Phase.CLOSED,finish(execute(CauldronFixtureSession.Operation.CLOSE)).phase());
    }
    @Test void providerLossAfterCommittedAcquireRetainsFenceAndDurableRequest() throws Exception {
        var receipt=execute(CauldronFixtureSession.Operation.RESERVE); database.flush().join(); provider=false; pump();
        assertEquals(CauldronFixtureReceipt.State.FAILED,receipt.state()); assertFalse(receipt.current());
        assertTrue(order.fixtureBlocked(request.keys().getFirst()));
        try(var c=database.getConnection()) { assertEquals(CauldronFixtureSnapshot.Phase.RESERVED,CauldronFixtureStorage.inspect(c,request).phase()); }
    }
    @Test void runtimeReplacementBeforeCloseRefusesWithoutChangingDurableReservation() throws Exception {
        finish(execute(CauldronFixtureSession.Operation.RESERVE)); occupied.add(request.keys().getFirst());
        assertThrows(IllegalStateException.class,()->execute(CauldronFixtureSession.Operation.CLOSE));
        try(var c=database.getConnection()) { assertEquals(CauldronFixtureSnapshot.Phase.RESERVED,CauldronFixtureStorage.inspect(c,request).phase()); }
    }
    @Test void confirmedUnknownCloseRefusalReleasesOnlyItsNewEmptyRuntimeFence() throws Exception {
        var receipt=execute(CauldronFixtureSession.Operation.CLOSE); pump();
        assertEquals(CauldronFixtureReceipt.State.FAILED,receipt.state()); assertFalse(order.fixtureBlocked(request.keys().getFirst()));
        try(var c=database.getConnection()) { assertTrue(CauldronFixtureStorage.readAll(c).isEmpty()); }
    }
    @Test void staleObservedReceiptCannotAuthorizeAfterAnotherLaneReservation() throws Exception {
        var first=execute(CauldronFixtureSession.Operation.RESERVE); finish(first); assertTrue(first.current());
        var other=new CauldronFixtureRequest(UUID.randomUUID(),UUID.randomUUID(),List.of(new CauldronFixtureRequest.Lane(
                new BreweryLocation(8,64,3,request.keys().getFirst().worldUuid()),UUID.randomUUID())));
        finish(coordinator.execute(other,CauldronFixtureSession.Operation.RESERVE)); assertFalse(first.current());
        var observed=execute(CauldronFixtureSession.Operation.INSPECT); finish(observed); assertTrue(observed.current());
    }
}
