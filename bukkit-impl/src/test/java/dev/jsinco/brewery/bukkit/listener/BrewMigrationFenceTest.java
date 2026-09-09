package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.structure.StructureType;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.breweries.distillery.BukkitDistillery;
import dev.jsinco.brewery.bukkit.structure.PlacedBreweryStructure;
import dev.jsinco.brewery.bukkit.testutil.CauldronOwnerServerMock;
import org.bukkit.*;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix3d;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class BrewMigrationFenceTest {
    private CauldronOwnerServerMock server;private TheBrewingProject plugin;private BukkitDistillery owner;
    private InventoryOpenEvent event;
    @BeforeEach void setup() {
        server=MockBukkit.mock(new CauldronOwnerServerMock());var world=server.addSimpleWorld("migration-fence");
        plugin=MockBukkit.load(TheBrewingProject.class);plugin.getResolvedIngredientManager().join();plugin.getDatabase().flush().join();server.publishGlobal();
        var format=plugin.getStructureRegistry().getStructures(StructureType.DISTILLERY).iterator().next();
        var structure=new PlacedBreweryStructure<BukkitDistillery>(format,new Matrix3d(),new Location(world,20,65,20));
        owner=new BukkitDistillery(structure);structure.setHolder(owner);
        plugin.getPlacedStructureRegistry().registerStructure(structure);plugin.getBreweryRegistry().registerInventory(owner);
        var view=server.addPlayer().openInventory(owner.getMixture().getInventory());event=new InventoryOpenEvent(view);
        owner.getMixture().getInventory().setItem(0,new ItemStack(Material.STONE,2));
    }
    @AfterEach void close(){MockBukkit.unmock();}
    @Test void reservedOwnerIsRejectedBeforeAnyMigrationRender() {
        Runnable release=owner.reserveDeferredInventoryPublication().orElseThrow();
        try {
            var renders=new AtomicInteger();var listener=new BrewMigrationListener(item->{renders.incrementAndGet();return Optional.of(new ItemStack(Material.GOLD_INGOT));},()->true);
            listener.onPlayerOpenInventory(event);assertTrue(event.isCancelled());assertEquals(0,renders.get());assertOriginal();
        } finally { release.run(); }
    }
    @Test void replacementVirtualAliasDuringRenderingCannotPublishIntoTheOldInventory() {
        var listener=new BrewMigrationListener(item->{plugin.getBreweryRegistry().unregisterInventory(owner);return Optional.of(new ItemStack(Material.GOLD_INGOT));},()->true);
        listener.onPlayerOpenInventory(event);assertTrue(event.isCancelled());assertOriginal();
    }
    @Test void rawSlotMutationDuringRenderingIsPreservedAndCancelsOpening() {
        var listener=new BrewMigrationListener(item->{owner.getMixture().getInventory().setItem(0,new ItemStack(Material.DIAMOND));return Optional.of(new ItemStack(Material.GOLD_INGOT));},()->true);
        listener.onPlayerOpenInventory(event);assertTrue(event.isCancelled());assertEquals(Material.DIAMOND,owner.getMixture().getInventory().getItem(0).getType());
    }
    @Test void rendererReceivesDetachedInputAndCurrentReplacementStillPublishes() {
        var listener=new BrewMigrationListener(item->{item.setType(Material.GOLD_INGOT);item.setAmount(3);assertOriginal();return Optional.of(item);},()->true);
        listener.onPlayerOpenInventory(event);assertFalse(event.isCancelled());
        assertEquals(Material.GOLD_INGOT,owner.getMixture().getInventory().getItem(0).getType());
        assertEquals(2,owner.getMixture().getInventory().getItem(0).getAmount());
    }
    @Test void openingPreservesTheEntireStackWhenRenderingReturnsOneRecipeItem() {
        owner.getMixture().getInventory().setItem(0,new ItemStack(Material.STONE,37));
        var result=new ItemStack(Material.GOLD_INGOT);
        result.editMeta(meta->meta.displayName(net.kyori.adventure.text.Component.text("Migrated brew")));
        new BrewMigrationListener(item->Optional.of(result),()->true).onPlayerOpenInventory(event);
        assertFalse(event.isCancelled());
        ItemStack actual=owner.getMixture().getInventory().getItem(0);
        assertEquals(37,actual.getAmount());assertEquals(result.getItemMeta(),actual.getItemMeta());
        assertEquals(1,result.getAmount(),"Publication does not mutate the renderer's returned object");
    }
    @Test void joiningPreservesAllStackedItemsThroughTheSameMigrationBoundary() {
        var player=server.addPlayer();player.getInventory().setItem(5,new ItemStack(Material.STONE,29));
        new BrewMigrationListener(item->Optional.of(new ItemStack(Material.GOLD_INGOT)),()->true)
                .onPlayerJoin(new org.bukkit.event.player.PlayerJoinEvent(player,(net.kyori.adventure.text.Component)null));
        assertEquals(new ItemStack(Material.GOLD_INGOT,29),player.getInventory().getItem(5));
    }
    @Test void changedRecipeCapacityCannotClampOrDiscardTheOriginalStack() {
        new BrewMigrationListener(item->Optional.of(new ItemStack(Material.DIAMOND_SWORD)),()->true)
                .onPlayerOpenInventory(event);
        assertTrue(event.isCancelled());assertOriginal();
    }
    @Test void emptyRenderCannotEraseAnExistingItem() {
        new BrewMigrationListener(item->Optional.of(new ItemStack(Material.AIR)),()->true).onPlayerOpenInventory(event);
        assertTrue(event.isCancelled());assertOriginal();
    }
    @Test void disabledMigrationNeitherRendersNorChangesTheNativeEvent() {
        new BrewMigrationListener(item->{throw new AssertionError("disabled render");},()->false).onPlayerOpenInventory(event);
        assertFalse(event.isCancelled());assertOriginal();
    }
    private void assertOriginal(){assertEquals(new ItemStack(Material.STONE,2),owner.getMixture().getInventory().getItem(0));}
}
