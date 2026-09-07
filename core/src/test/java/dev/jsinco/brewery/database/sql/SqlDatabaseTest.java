package dev.jsinco.brewery.database.sql;

import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.database.Session;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

class SqlDatabaseTest {
    @TempDir Path directory;

    @Test
    void closeDrainsCompleteSessionOperationAndClosesRealPool() throws Exception {
        SqlDatabase database = new SqlDatabase(DatabaseDriver.SQLITE);
        database.init(directory.toFile());
        CompletableFuture<Void> dependency = new CompletableFuture<>();
        AtomicBoolean written = new AtomicBoolean();
        DelayedSession session = database.startSession((executor, ignored) ->
                new DelayedSessionImpl(executor, dependency, written));
        var operation = session.write();
        var closed = database.close();
        try {
            assertFalse(closed.isDone());
            assertThrows(PersistenceException.class, () -> database.startSession((executor, ignored) ->
                    new DelayedSessionImpl(executor, dependency, written)));
            assertThrows(CompletionException.class, () -> session.write().join());
            dependency.complete(null);
            closed.get(5, TimeUnit.SECONDS);
            operation.join();
            assertTrue(written.get());
            assertSame(closed, database.close());
            assertThrows(PersistenceException.class, database::getConnection);
        } finally {
            dependency.complete(null);
            database.close().get(5, TimeUnit.SECONDS);
        }
    }

    public interface DelayedSession extends Session<DelayedSession> {
        CompletableFuture<Void> write();
    }

    public record DelayedSessionImpl(Executor executor, CompletableFuture<Void> dependency,
                                     AtomicBoolean written) implements DelayedSession {
        @Override
        public CompletableFuture<Void> write() {
            return dependency.thenCompose(ignored -> execute(() -> written.set(true)));
        }
    }
}
