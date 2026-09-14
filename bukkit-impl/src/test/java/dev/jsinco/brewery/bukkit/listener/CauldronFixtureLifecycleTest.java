package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.breweries.CauldronType;
import dev.jsinco.brewery.api.persistence.*;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.BukkitAdapter;
import dev.jsinco.brewery.bukkit.api.event.structure.CauldronCreateEvent;
import dev.jsinco.brewery.bukkit.api.event.transaction.CauldronInsertEvent;
import dev.jsinco.brewery.bukkit.breweries.BukkitCauldron;
import dev.jsinco.brewery.bukkit.database.BrewPersistenceSnapshot;
import dev.jsinco.brewery.bukkit.database.SessionTypes;
import dev.jsinco.brewery.bukkit.testutil.CauldronOwnerServerMock;
import dev.jsinco.brewery.util.DecoderEncoder;
import org.bukkit.Material;
import org.bukkit.block.BlockFace;
import org.bukkit.event.*;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.world.WorldMock;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class CauldronFixtureLifecycleTest {
    private CauldronOwnerServerMock server;
    private TheBrewingProject plugin;
    private WorldMock world;
    private CauldronFixtureRequest request;
    @BeforeEach void start() throws Exception {
        server=MockBukkit.mock(new CauldronOwnerServerMock()); world=server.addSimpleWorld("fixture-quarantine");
        world.getChunkAt(0,0).load(); plugin=MockBukkit.load(TheBrewingProject.class);
        plugin.getResolvedIngredientManager().join(); plugin.getDatabase().flush().join(); server.publishGlobal();
        world.getBlockAt(1,64,3).setType(Material.WATER_CAULDRON);
        request=new CauldronFixtureRequest(UUID.randomUUID(),UUID.randomUUID(),List.of(new CauldronFixtureRequest.Lane(
                BukkitAdapter.toBreweryLocation(world.getBlockAt(1,64,3)),UUID.randomUUID())));
    }
    @AfterEach void stop() { MockBukkit.unmock(); }
    private void finish(CauldronFixtureReceipt receipt) throws Exception {
        plugin.getDatabase().flush().get(5,TimeUnit.SECONDS); server.getScheduler().performOneTick();
        receipt.completion().toCompletableFuture().get(5,TimeUnit.SECONDS); assertTrue(receipt.current());
    }
    @Test void registeredNativeInputIsRefusedBeforeCreateInsertOrHandConsumption() throws Exception {
        var stale=new BukkitCauldron(new BrewImpl(List.of()),request.keys().getFirst(),CauldronType.WATER);
        finish(plugin.reserveCauldronFixtures(request));
        var events=new AtomicInteger(); var listener=new Listener(){};
        server.getPluginManager().registerEvent(CauldronCreateEvent.class,listener,EventPriority.NORMAL,(ignored,event)->events.incrementAndGet(),plugin);
        server.getPluginManager().registerEvent(CauldronInsertEvent.class,listener,EventPriority.NORMAL,(ignored,event)->events.incrementAndGet(),plugin);
        var player=server.addPlayer(); player.addAttachment(plugin,"brewery.cauldron.access",true);
        player.getInventory().setItemInMainHand(new ItemStack(Material.WHEAT,2));
        var event=new PlayerInteractEvent(player,Action.RIGHT_CLICK_BLOCK,player.getInventory().getItemInMainHand(),world.getBlockAt(1,64,3),BlockFace.UP,EquipmentSlot.HAND);
        server.getPluginManager().callEvent(event);
        assertEquals(Event.Result.DENY,event.useItemInHand()); assertEquals(Event.Result.DENY,event.useInteractedBlock());
        assertEquals(2,player.getInventory().getItemInMainHand().getAmount()); assertEquals(0,events.get());
        assertFalse(stale.withIngredient(new ItemStack(Material.WHEAT),player)); assertTrue(stale.reserveExtraction().isEmpty());
        assertTrue(plugin.getBreweryRegistry().getActiveSinglePositionStructure(request.keys().getFirst()).isEmpty());
        finish(plugin.closeCauldronFixtures(request));
        assertTrue(new BukkitCauldron(new BrewImpl(List.of()),request.keys().getFirst(),CauldronType.WATER).persistenceAvailable());
    }
    @Test void hydrationSkipsUnexpectedReservedRowButPublishesUnrelatedOrdinaryBirth() throws Exception {
        finish(plugin.reserveCauldronFixtures(request));
        var key=request.keys().getFirst(); var control=BukkitAdapter.toBreweryLocation(world.getBlockAt(5,64,3));
        UUID reservedRowBirth=UUID.randomUUID(), controlBirth=UUID.randomUUID();
        try(var connection=plugin.getDatabase().getConnection()) {
            String guard;
            try(var statement=connection.createStatement();var rows=statement.executeQuery("SELECT sql FROM sqlite_schema WHERE name='cauldron_fixture_insert'")) { assertTrue(rows.next()); guard=rows.getString(1); }
            try(var statement=connection.createStatement()) { statement.execute("DROP TRIGGER cauldron_fixture_insert"); }
            try(var statement=connection.prepareStatement("INSERT INTO cauldrons(world_uuid,cauldron_x,cauldron_y,cauldron_z,brew,birth_uuid) VALUES(?,?,?,?,?,?)")) {
                for(var item:Map.of(key,reservedRowBirth,control,controlBirth).entrySet()) {
                    statement.setBytes(1,DecoderEncoder.asBytes(world.getUID())); statement.setInt(2,item.getKey().x());
                    statement.setInt(3,64); statement.setInt(4,3); statement.setString(5,BrewPersistenceSnapshot.captureNow(new BrewImpl(List.of(
                            new dev.jsinco.brewery.brew.MixStepImpl(new dev.jsinco.brewery.api.moment.Interval(1,2),Map.of(),CauldronType.WATER)))));
                    statement.setBytes(6,DecoderEncoder.asBytes(item.getValue())); assertEquals(1,statement.executeUpdate());
                }
            }
            try(var statement=connection.createStatement()) { statement.execute(guard); }
        }
        var rows=plugin.getDatabase().startSession(SessionTypes.WORLD_HYDRATION_SESSION_TYPE).readWorld(world.getUID()).get(5,TimeUnit.SECONDS);
        var hydration=plugin.getCauldronPersistenceOrder().beginHydration(world.getUID(),()->true);
        WorldBreweryHydrator.publish(world,rows,plugin.getResolvedIngredientManager().join(),plugin.getPlacedStructureRegistry(),plugin.getBreweryRegistry(),hydration);
        assertTrue(plugin.getBreweryRegistry().getActiveSinglePositionStructure(key).isEmpty());
        var ordinary=(BukkitCauldron)plugin.getBreweryRegistry().getActiveSinglePositionStructure(control).orElseThrow();
        assertEquals(Optional.of(controlBirth),ordinary.birthUuid()); assertTrue(ordinary.persistenceAvailable());
        var close=plugin.closeCauldronFixtures(request); plugin.getDatabase().flush().join(); server.getScheduler().performOneTick();
        assertEquals(CauldronFixtureReceipt.State.FAILED,close.state()); assertTrue(plugin.getCauldronPersistenceOrder().fixtureBlocked(key));
        try(var connection=plugin.getDatabase().getConnection();var statement=connection.createStatement();var stored=statement.executeQuery("SELECT count(*) FROM cauldrons")) {
            assertTrue(stored.next()); assertEquals(2,stored.getInt(1));
        }
    }
}
