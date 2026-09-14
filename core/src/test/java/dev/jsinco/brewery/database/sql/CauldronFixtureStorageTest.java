package dev.jsinco.brewery.database.sql;

import dev.jsinco.brewery.api.persistence.*;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.util.DecoderEncoder;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class CauldronFixtureStorageTest {
    @TempDir Path directory;
    private final UUID world = UUID.randomUUID();
    private Connection open() throws Exception { return DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("brewery.db")); }
    private void initialize(Connection connection) throws Exception {
        var database = new SqlDatabase(DatabaseDriver.SQLITE);
        try { database.createTables(connection); } finally { database.close().join(); }
    }
    private CauldronFixtureRequest request(int count) {
        List<CauldronFixtureRequest.Lane> lanes = new ArrayList<>();
        for (int i = 0; i < count; i++) lanes.add(new CauldronFixtureRequest.Lane(new BreweryLocation(i,64,2,world), UUID.randomUUID()));
        return new CauldronFixtureRequest(UUID.randomUUID(), UUID.randomUUID(), lanes);
    }
    private void sql(Connection connection, String sql) throws Exception { try (var statement = connection.createStatement()) { statement.execute(sql); } }
    private int count(Connection connection, String table) throws Exception {
        try (var statement = connection.createStatement(); var rows = statement.executeQuery("SELECT count(*) FROM " + table)) { assertTrue(rows.next()); return rows.getInt(1); }
    }
    private void insert(Connection connection, BreweryLocation key) throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO cauldrons(world_uuid,cauldron_x,cauldron_y,cauldron_z,brew,birth_uuid) VALUES(?,?,?,?,'control',?)")) {
            statement.setBytes(1, DecoderEncoder.asBytes(key.worldUuid())); statement.setInt(2,key.x());
            statement.setInt(3,key.y()); statement.setInt(4,key.z()); statement.setBytes(5,DecoderEncoder.asBytes(UUID.randomUUID()));
            assertEquals(1,statement.executeUpdate());
        }
    }
    @ParameterizedTest @ValueSource(ints={1,20})
    void durableReservationReopensWithSameBirthsAndExactCloseKeepsTombstones(int count) throws Exception {
        var request = request(count); CauldronFixtureSnapshot reserved;
        try (var connection = open()) {
            initialize(connection); reserved = CauldronFixtureStorage.reserve(connection,request);
            assertEquals(reserved,CauldronFixtureStorage.reserve(connection,request));
            assertEquals(count,reserved.lanes().stream().map(CauldronFixtureSnapshot.Lane::birthUuid).distinct().count());
            assertEquals(0,count(connection,"cauldrons"));
        }
        try (var connection = open()) {
            initialize(connection); assertEquals(reserved,CauldronFixtureStorage.inspect(connection,request));
            var closed=CauldronFixtureStorage.close(connection,request);
            assertEquals(CauldronFixtureSnapshot.Phase.CLOSED,closed.phase());
            assertEquals(reserved.lanes(),closed.lanes()); assertEquals(closed,CauldronFixtureStorage.close(connection,request));
            assertEquals(1,count(connection,"cauldron_fixture_runs")); assertEquals(count,count(connection,"cauldron_fixture_lanes"));
            assertThrows(CauldronFixtureStorage.Refusal.class,()->CauldronFixtureStorage.reserve(connection,request));
            var next=new CauldronFixtureRequest(UUID.randomUUID(),UUID.randomUUID(),request.lanes());
            var newBirths=CauldronFixtureStorage.reserve(connection,next);
            assertTrue(Collections.disjoint(reserved.lanes(),newBirths.lanes()));
        }
    }
    @Test void reservedKeyRejectsOrdinarySqlInsertAndCoordinateMoveWhileUnrelatedWritersContinue() throws Exception {
        var request=request(1); var control=new BreweryLocation(99,64,2,world);
        try(var connection=open()) {
            initialize(connection); insert(connection,control); CauldronFixtureStorage.reserve(connection,request);
            assertThrows(SQLException.class,()->insert(connection,request.keys().getFirst()));
            assertThrows(SQLException.class,()->sql(connection,"UPDATE cauldrons SET cauldron_x=0"));
            sql(connection,"UPDATE cauldrons SET brew='preserved control'");
            assertEquals(1,count(connection,"cauldrons"));
            CauldronFixtureStorage.close(connection,request); insert(connection,request.keys().getFirst());
            assertEquals(2,count(connection,"cauldrons"));
        }
    }
    @Test void coordinateActorCapabilityAndChangedLaneOrderRefuseWithoutPartialReservation() throws Exception {
        var request=request(2);
        try(var connection=open()) {
            initialize(connection); CauldronFixtureStorage.reserve(connection,request);
            assertThrows(CauldronFixtureStorage.Refusal.class,()->CauldronFixtureStorage.reserve(connection,new CauldronFixtureRequest(UUID.randomUUID(),UUID.randomUUID(),request.lanes())));
            var actorCollision=new CauldronFixtureRequest(UUID.randomUUID(),UUID.randomUUID(),List.of(new CauldronFixtureRequest.Lane(new BreweryLocation(44,64,2,world),request.lanes().getFirst().actorId())));
            assertThrows(CauldronFixtureStorage.Refusal.class,()->CauldronFixtureStorage.reserve(connection,actorCollision));
            var wrongToken=new CauldronFixtureRequest(request.runId(),UUID.randomUUID(),request.lanes());
            assertThrows(CauldronFixtureStorage.Refusal.class,()->CauldronFixtureStorage.close(connection,wrongToken));
            var reversed=new CauldronFixtureRequest(request.runId(),request.capability(),request.lanes().reversed());
            assertThrows(CauldronFixtureStorage.Refusal.class,()->CauldronFixtureStorage.reserve(connection,reversed));
            assertEquals(1,count(connection,"cauldron_fixture_runs")); assertEquals(2,count(connection,"cauldron_fixture_lanes"));
        }
    }
    @Test void secondLaneCollisionWithOrdinaryPersistedValueLeavesEverythingUntouched() throws Exception {
        var request=request(2);
        try(var connection=open()) {
            initialize(connection); insert(connection,request.keys().getLast());
            assertThrows(CauldronFixtureStorage.Refusal.class,()->CauldronFixtureStorage.reserve(connection,request));
            assertEquals(0,count(connection,"cauldron_fixture_runs")); assertEquals(0,count(connection,"cauldron_fixture_lanes"));
            assertEquals(1,count(connection,"cauldrons"));
        }
    }
    @Test void ignoredSecondLaneInsertRollsBackRunAndFirstLane() throws Exception {
        var request=request(2);
        try(var connection=open()) {
            initialize(connection);
            sql(connection,"CREATE TRIGGER ignored_fixture BEFORE INSERT ON cauldron_fixture_lanes WHEN NEW.lane=1 BEGIN SELECT RAISE(IGNORE); END");
            assertThrows(SQLException.class,()->CauldronFixtureStorage.reserve(connection,request));
            assertEquals(0,count(connection,"cauldron_fixture_runs")); assertEquals(0,count(connection,"cauldron_fixture_lanes"));
        }
    }
    @Test void failedCloseDoesNotReleaseAnyLaneAndCanRetryAfterReopen() throws Exception {
        var request=request(2);
        try(var connection=open()) {
            initialize(connection); CauldronFixtureStorage.reserve(connection,request);
            sql(connection,"CREATE TRIGGER ignored_close BEFORE UPDATE ON cauldron_fixture_runs BEGIN SELECT RAISE(IGNORE); END");
            assertThrows(SQLException.class,()->CauldronFixtureStorage.close(connection,request));
            assertEquals(CauldronFixtureSnapshot.Phase.RESERVED,CauldronFixtureStorage.inspect(connection,request).phase());
            assertThrows(SQLException.class,()->insert(connection,request.keys().getFirst()));
            sql(connection,"DROP TRIGGER ignored_close");
        }
        try(var connection=open()) { assertEquals(CauldronFixtureSnapshot.Phase.CLOSED,CauldronFixtureStorage.close(connection,request).phase()); }
    }
    @ParameterizedTest @ValueSource(booleans={false,true})
    void lostCommitReplyIsResolvedOnlyByExactDurableLookup(boolean closing) throws Exception {
        var request=request(1); CauldronFixtureSnapshot reserved=null;
        try(var connection=open()) {
            initialize(connection);
            if(closing) reserved=CauldronFixtureStorage.reserve(connection,request);
            var lost=afterCommitFailure(connection);
            assertThrows(SQLException.class,()-> { if(closing) CauldronFixtureStorage.close(lost,request); else CauldronFixtureStorage.reserve(lost,request); });
        }
        try(var connection=open()) {
            var observed=CauldronFixtureStorage.inspect(connection,request);
            assertEquals(closing?CauldronFixtureSnapshot.Phase.CLOSED:CauldronFixtureSnapshot.Phase.RESERVED,observed.phase());
            if(closing) assertEquals(reserved.lanes(),observed.lanes());
            assertEquals(CauldronFixtureSnapshot.Phase.CLOSED,CauldronFixtureStorage.close(connection,request).phase());
        }
    }
    @Test void foreignRowAtAReservedKeyRemainsQuarantinedAndCannotBecomeCleanupAuthority() throws Exception {
        var request=request(1);
        try(var connection=open()) {
            initialize(connection); CauldronFixtureStorage.reserve(connection,request);
            // Reproduce damaged/external state, then restore the genuine write fence before recovery.
            sql(connection,"DROP TRIGGER cauldron_fixture_insert"); insert(connection,request.keys().getFirst());
            sql(connection,CauldronFixtureSchema.OBJECTS.get("cauldron_fixture_insert")); initialize(connection);
            assertTrue(CauldronFixtureStorage.inspect(connection,request).lanes().getFirst().persistedCauldronPresent());
            assertThrows(CauldronFixtureStorage.Refusal.class,()->CauldronFixtureStorage.close(connection,request));
            assertThrows(SQLException.class,()->sql(connection,"DELETE FROM cauldrons"));
            assertThrows(SQLException.class,()->sql(connection,"UPDATE cauldrons SET brew='foreign rewrite'"));
            assertEquals(1,count(connection,"cauldrons"));
        }
    }
    @Test void closedTombstoneCannotRetireSameKeyReplacement() throws Exception {
        var request=request(1);
        try(var connection=open()) {
            initialize(connection); CauldronFixtureStorage.reserve(connection,request); CauldronFixtureStorage.close(connection,request);
            insert(connection,request.keys().getFirst());
            assertThrows(CauldronFixtureStorage.Refusal.class,()->CauldronFixtureStorage.close(connection,request));
            assertEquals(1,count(connection,"cauldrons"));
        }
    }
    @Test void unknownLookupNeverCreatesAReservation() throws Exception {
        try(var connection=open()) {
            initialize(connection); var request=request(1);
            assertThrows(CauldronFixtureStorage.Refusal.class,()->CauldronFixtureStorage.inspect(connection,request));
            assertThrows(CauldronFixtureStorage.Refusal.class,()->CauldronFixtureStorage.close(connection,request));
            assertEquals(0,count(connection,"cauldron_fixture_runs"));
        }
    }
    @Test void closedHistoryIsBoundedWithoutPruningOrReusingOldRunAuthority() throws Exception {
        try(var connection=open()) {
            initialize(connection); var lanes=request(1).lanes();
            for(int i=0;i<CauldronFixtureStorage.MAX_RUNS;i++) {
                var request=new CauldronFixtureRequest(UUID.randomUUID(),UUID.randomUUID(),lanes);
                CauldronFixtureStorage.reserve(connection,request); CauldronFixtureStorage.close(connection,request);
            }
            var next=new CauldronFixtureRequest(UUID.randomUUID(),UUID.randomUUID(),lanes);
            assertThrows(CauldronFixtureStorage.Refusal.class,()->CauldronFixtureStorage.reserve(connection,next));
            assertEquals(CauldronFixtureStorage.MAX_RUNS,count(connection,"cauldron_fixture_runs"));
        }
    }
    @Test void failedRollbackNeverCommitsOrTurnsARefusalIntoReleaseAuthority() throws Exception {
        var request=request(1); var autocommit=new AtomicBoolean();
        try(var connection=open()) {
            initialize(connection); insert(connection,request.keys().getFirst());
            var damaged=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)-> {
                if(method.getName().equals("rollback")) throw new SQLException("Lost rollback acknowledgement");
                if(method.getName().equals("setAutoCommit") && Boolean.TRUE.equals(args[0])) autocommit.set(true);
                try { return method.invoke(connection,args); } catch(InvocationTargetException failure) { throw failure.getCause(); }
            });
            var failure=assertThrows(SQLException.class,()->CauldronFixtureStorage.reserve(damaged,request));
            assertFalse(failure instanceof CauldronFixtureStorage.Refusal); assertFalse(autocommit.get()); assertTrue(connection.isClosed());
        }
        try(var connection=open()) { assertEquals(0,count(connection,"cauldron_fixture_runs")); assertEquals(1,count(connection,"cauldrons")); }
    }
    @Test void malformedDurableRequestBlocksStartupWithoutDeletingEvidence() throws Exception {
        try(var connection=open()) {
            initialize(connection); CauldronFixtureStorage.reserve(connection,request(1));
            sql(connection,"UPDATE cauldron_fixture_runs SET request=X'010203'");
            assertThrows(SQLException.class,()->initialize(connection));
            assertEquals(1,count(connection,"cauldron_fixture_runs")); assertEquals(1,count(connection,"cauldron_fixture_lanes"));
        }
    }
    @Test void invalidLaneBoundsAndDuplicateActorsAreRefusedBeforeAnyDatabaseCall() {
        assertThrows(IllegalArgumentException.class,()->request(0)); assertThrows(IllegalArgumentException.class,()->request(21));
        var request=request(2); var first=request.lanes().getFirst(); var second=request.lanes().getLast();
        assertThrows(IllegalArgumentException.class,()->new CauldronFixtureRequest(UUID.randomUUID(),UUID.randomUUID(),List.of(first,first)));
        assertThrows(IllegalArgumentException.class,()->new CauldronFixtureRequest(UUID.randomUUID(),UUID.randomUUID(),List.of(first,new CauldronFixtureRequest.Lane(second.location(),first.actorId()))));
    }
    private Connection afterCommitFailure(Connection connection) {
        var once=new AtomicBoolean();
        return (Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(proxy,method,args)-> {
            try {
                var result=method.invoke(connection,args);
                if(method.getName().equals("commit") && once.compareAndSet(false,true)) throw new SQLException("Lost commit reply");
                return result;
            } catch(InvocationTargetException failure) { throw failure.getCause(); }
        });
    }
}
