package dev.jsinco.brewery.database.sql;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.lang.reflect.*;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class CauldronFixtureMigrationTest {
    @TempDir Path directory;
    private Connection open() throws Exception { return DriverManager.getConnection("jdbc:sqlite:"+directory.resolve("migration.db")); }
    private void initialize(Connection connection) throws SQLException {
        var database=new SqlDatabase(DatabaseDriver.SQLITE);
        try { database.createTables(connection); } finally { database.close().join(); }
    }
    private void execute(Connection c,String sql) throws SQLException { try(var s=c.createStatement()) { s.execute(sql); } }
    private String scalar(Connection c,String sql) throws SQLException { try(var s=c.createStatement();var rows=s.executeQuery(sql)) { assertTrue(rows.next()); return rows.getString(1); } }
    private void schemaFour(Connection c) throws SQLException {
        initialize(c);
        for(var entry:new ArrayList<>(CauldronFixtureSchema.OBJECTS.entrySet()).reversed()) {
            String kind=entry.getValue().startsWith("CREATE TABLE")?"TABLE":entry.getValue().startsWith("CREATE UNIQUE INDEX")?"INDEX":"TRIGGER";
            execute(c,"DROP "+kind+" "+entry.getKey());
        }
        execute(c,"UPDATE version SET version=4");
        execute(c,"ALTER TABLE cauldrons ADD extension BLOB DEFAULT X'0199'");
        execute(c,"INSERT INTO cauldrons(cauldron_x,brew,birth_uuid) VALUES(7,'exact ordinary contents',X'000102030405060708090A0B0C0D0E0F')");
        execute(c,"CREATE TABLE extension_log(value TEXT)");
        execute(c,"CREATE TRIGGER extension_update AFTER UPDATE ON cauldrons BEGIN INSERT INTO extension_log VALUES('changed'); END");
    }
    private List<String> objects(Connection c) throws SQLException {
        List<String> result=new ArrayList<>();
        try(var s=c.createStatement();var r=s.executeQuery("SELECT name||':'||coalesce(sql,'') FROM sqlite_schema ORDER BY name")) { while(r.next()) result.add(r.getString(1)); }
        return result;
    }
    @Test void schemaFourUpgradesWithoutChangingBirthsRowsExtensionsOrFiringOrdinaryTriggers() throws Exception {
        try(var c=open()) {
            schemaFour(c); var before=objects(c);
            String row=scalar(c,"SELECT brew||hex(birth_uuid)||hex(extension) FROM cauldrons");
            initialize(c); assertEquals("5",scalar(c,"SELECT version FROM version"));
            assertEquals(row,scalar(c,"SELECT brew||hex(birth_uuid)||hex(extension) FROM cauldrons"));
            assertTrue(objects(c).containsAll(before)); assertEquals("0",scalar(c,"SELECT count(*) FROM extension_log"));
            var after=objects(c); initialize(c); assertEquals(after,objects(c));
        }
    }
    @ParameterizedTest @ValueSource(strings={"CREATE TABLE cauldron_fixture_lanes","CREATE TRIGGER cauldron_fixture_UPDATE","INSERT INTO version","commit"})
    void interruptedAdditiveMigrationLeavesExactSchemaFourAndCanRetry(String failurePoint) throws Exception {
        List<String> before;
        try(var c=open()) {
            schemaFour(c); before=objects(c); var fired=new AtomicBoolean();
            Connection proxy=(Connection)Proxy.newProxyInstance(Connection.class.getClassLoader(),new Class<?>[]{Connection.class},(self,method,args)-> {
                try {
                    if(method.getName().equals("commit") && failurePoint.equals("commit")) { fired.set(true); throw new SQLException("Interrupted commit"); }
                    Object value=method.invoke(c,args);
                    if(method.getName().equals("prepareStatement") && ((String)args[0]).toLowerCase(Locale.ROOT).startsWith(failurePoint.toLowerCase(Locale.ROOT))) {
                        var real=(PreparedStatement)value;
                        return Proxy.newProxyInstance(PreparedStatement.class.getClassLoader(),new Class<?>[]{PreparedStatement.class},(p,m,a)-> {
                            try {
                                Object result=m.invoke(real,a);
                                if(m.getName().startsWith("execute")) { fired.set(true); throw new SQLException("Interrupted DDL/publication"); }
                                return result;
                            } catch(InvocationTargetException failure) { throw failure.getCause(); }
                        });
                    }
                    return value;
                } catch(InvocationTargetException failure) { throw failure.getCause(); }
            });
            assertThrows(SQLException.class,()->initialize(proxy)); assertTrue(fired.get());
        }
        try(var c=open()) {
            assertEquals(before,objects(c)); assertEquals("4",scalar(c,"SELECT version FROM version"));
            assertEquals("exact ordinary contents",scalar(c,"SELECT brew FROM cauldrons"));
            initialize(c); assertEquals("5",scalar(c,"SELECT version FROM version"));
        }
    }
    @Test void partialOwnerObjectsAndFutureVersionRefuseWithoutRepair() throws Exception {
        try(var c=open()) {
            schemaFour(c); execute(c,"CREATE TABLE cauldron_fixture_runs(unrelated TEXT)"); var before=objects(c);
            assertThrows(SQLException.class,()->initialize(c)); assertEquals(before,objects(c));
            execute(c,"DROP TABLE cauldron_fixture_runs"); execute(c,"UPDATE version SET version=6"); before=objects(c);
            assertThrows(SQLException.class,()->initialize(c)); assertEquals(before,objects(c));
        }
    }
    @Test void missingCurrentWriteFenceIsRejectedWithoutRecreatingIt() throws Exception {
        try(var c=open()) {
            initialize(c); execute(c,"DROP TRIGGER cauldron_fixture_delete"); var before=objects(c);
            assertThrows(SQLException.class,()->initialize(c)); assertEquals(before,objects(c));
        }
    }
}
