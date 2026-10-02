package dev.jsinco.brewery.bukkit.database.cauldron;

import dev.jsinco.brewery.api.persistence.*;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.bukkit.api.event.structure.ExternalCauldronDestroyedEvent;
import dev.jsinco.brewery.database.sql.*;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class ExternalCauldronCoordinatorTest {
    @TempDir Path directory;
    private SqlDatabase database;
    private final CauldronPersistenceOrder order = new CauldronPersistenceOrder();
    private final Queue<Runnable> publications = new ConcurrentLinkedQueue<>();
    private final List<ExternalCauldronDestroyedEvent> events = new ArrayList<>();
    private final AtomicBoolean loaded = new AtomicBoolean(true), empty = new AtomicBoolean(true), cauldron = new AtomicBoolean(true);
    private final ExternalCauldronLeaseRequest request = new ExternalCauldronLeaseRequest(UUID.randomUUID(), "patriamforging", new BreweryLocation(1,64,3,UUID.randomUUID()));
    private ExternalCauldronCoordinator owner;
    @BeforeEach void start() throws Exception {
        database = new SqlDatabase(DatabaseDriver.SQLITE); database.init(directory.toFile());
        owner = new ExternalCauldronCoordinator(database, order, () -> true, key -> loaded.get(),
                key -> loaded.get() && empty.get(), key -> loaded.get() && cauldron.get(), key -> loaded.get(), publications::add, events::add);
        owner.initialize();
    }
    @AfterEach void stop() { database.close().join(); }
    private void publish() { Runnable work; while ((work = publications.poll()) != null) work.run(); }
    private <T> T finish(CompletableFuture<T> future) throws Exception { database.flush().get(5, TimeUnit.SECONDS); publish(); return future.get(5,TimeUnit.SECONDS); }
    @Test void destructionDuringPendingAcquisitionNeverPublishesAuthorityAndDrainsItsTerminalSql() throws Exception {
        var acquisition = owner.acquire(request); assertTrue(order.nativeBlocked(request.location()));
        owner.destroy(request.location()); assertEquals(1,events.size());
        database.flush().get(5,TimeUnit.SECONDS); publish();
        assertThrows(ExecutionException.class, () -> acquisition.get(5,TimeUnit.SECONDS));
        assertEquals(ExternalCauldronLease.State.DESTROYED, events.getFirst().getRetirement().toCompletableFuture().get(5,TimeUnit.SECONDS).state());
        assertFalse(order.nativeBlocked(request.location()));
        assertEquals(ExternalCauldronLease.State.DESTROYED,finish(owner.inspect(request)).orElseThrow().state());
        assertThrows(ExecutionException.class, () -> finish(owner.acquire(request)));
    }
    @Test void callerCancellationDoesNotCancelOriginalAcquisitionOrDurableOwnership() throws Exception {
        var acquisition = owner.acquire(request); assertTrue(acquisition.cancel(true));
        database.flush().get(5,TimeUnit.SECONDS); publish();
        var lease = finish(owner.inspect(request)).orElseThrow();
        assertTrue(owner.isCurrent(lease)); assertTrue(order.externalOwned(request));
        assertEquals(ExternalCauldronLease.State.RELEASED, finish(owner.release(request)).state());
    }
    @Test void worldUnloadSuspendsAuthorityWithoutDestroyingOrRefundingDurableStock() throws Exception {
        var acquisition = owner.acquire(request); loaded.set(false);
        assertThrows(ExecutionException.class, () -> finish(acquisition));
        assertTrue(events.isEmpty()); assertTrue(order.externalOwned(request));
        var lease = finish(owner.inspect(request)).orElseThrow(); assertEquals(ExternalCauldronLease.State.ACTIVE,lease.state()); assertFalse(owner.isCurrent(lease));
        loaded.set(true); assertTrue(owner.isCurrent(lease));
    }
    @Test void lostPhysicalCauldronReconciliationRetiresWithoutGrantingAReplacementGeneration() throws Exception {
        var lease = finish(owner.acquire(request)); cauldron.set(false); empty.set(false);
        owner.reconcileLoaded(request.location().worldUuid(),0,0); assertFalse(owner.isCurrent(lease));
        assertEquals(ExternalCauldronLease.State.DESTROYED,finish(events.getFirst().getRetirement().toCompletableFuture()).state());
        assertTrue(finish(owner.inspect(request)).isPresent());
    }
}
