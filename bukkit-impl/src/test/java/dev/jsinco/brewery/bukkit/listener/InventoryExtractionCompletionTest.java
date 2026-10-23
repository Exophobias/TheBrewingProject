package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.util.BreweryKey;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.DistillStepImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.BukkitAdapter;
import dev.jsinco.brewery.bukkit.api.event.transaction.BarrelExtractEvent;
import dev.jsinco.brewery.bukkit.api.event.transaction.InventoryExtractionReceipt;
import dev.jsinco.brewery.bukkit.api.event.transaction.ItemTransactionEvent;
import dev.jsinco.brewery.bukkit.brew.BrewAdapterAccess;
import dev.jsinco.brewery.bukkit.breweries.barrel.BukkitBarrel;
import dev.jsinco.brewery.bukkit.structure.PlacedBreweryStructure;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import dev.jsinco.brewery.bukkit.testutil.ComponentItemStackMock;
import dev.jsinco.brewery.bukkit.testutil.ItemComponentBridgeFixture;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.inventory.ItemStack;
import org.joml.Matrix3d;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class InventoryExtractionCompletionTest {
    private TheBrewingProject plugin;
    private InventoryEventListener listener;
    private PlayerMock player;
    private BukkitBarrel barrel;
    private ItemStack original;
    private AutoCloseable potionFactory;
    private AutoCloseable componentBridge;

    @BeforeEach void setUp() throws Exception {
        var server = MockBukkit.mock(new TBPServerMock());
        var world = server.addSimpleWorld("inventory-extraction");
        plugin = MockBukkit.load(TheBrewingProject.class);
        plugin.getResolvedIngredientManager().join();
        // The full production renderer needs Paper component/PDC storage absent from MockBukkit.
        // Scope the existing renderer fixtures to this test rather than depending on suite order.
        potionFactory = ComponentItemStackMock.installPotionFactory();
        componentBridge = ItemComponentBridgeFixture.install();
        var format = plugin.getStructureRegistry().getStructure("small_barrel").orElseThrow();
        var structure = new PlacedBreweryStructure<BukkitBarrel>(format, new Matrix3d(), new Location(world, 4, 65, 4));
        var type = dev.jsinco.brewery.api.util.BreweryRegistry.BARREL_TYPE.get(BreweryKey.parse("oak"));
        barrel = new BukkitBarrel(BukkitAdapter.toLocation(structure.getUnique()).orElseThrow(), structure, 9, type);
        structure.setHolder(barrel);
        plugin.getBreweryRegistry().registerInventory(barrel);
        player = server.addPlayer();
        player.addAttachment(plugin, "brewery.barrel.access", true);
        original = BrewAdapterAccess.toItem(new BrewImpl(List.of(new DistillStepImpl(10))), new Brew.State.Other());
        barrel.getInventory().getInventory().setItem(0, original.clone());
        player.openInventory(barrel.getInventory().getInventory());
        listener = new InventoryEventListener(plugin.getBreweryRegistry(), plugin.getDatabase());
    }

    @AfterEach void tearDown() throws Exception {
        try {
            if (componentBridge != null) componentBridge.close();
        } finally {
            try {
                if (potionFactory != null) potionFactory.close();
            } finally {
                MockBukkit.unmock();
            }
        }
    }

    @Test void deniedNumberKeyInsertionSettlesEarlierExtractionWithoutPublishingAReceipt() {
        player.getInventory().setItem(0, new ItemStack(Material.IRON_SWORD));
        var captured = new AtomicReference<BarrelExtractEvent>();
        plugin.getServer().getPluginManager().registerEvent(BarrelExtractEvent.class, new Listener() { }, EventPriority.MONITOR,
                (ignored, event) -> captured.set((BarrelExtractEvent) event), plugin);
        var click = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER, 0,
                ClickType.NUMBER_KEY, InventoryAction.HOTBAR_SWAP, 0);
        listener.onInventoryClick(click);
        assertTrue(click.isCancelled());
        var proposal = captured.get(); assertNotNull(proposal);
        assertTrue(proposal.getCompletionResult().toCompletableFuture().isDone());
        assertTrue(proposal.getCompletionResult().toCompletableFuture().join().isEmpty());
        assertEquals(original, barrel.getInventory().getInventory().getItem(0));
        assertEquals(Material.IRON_SWORD, player.getInventory().getItem(0).getType());
    }

    @Test void onlyAnObservedDestinationPublicationCompletesWithADetachedItem() throws Exception {
        var click = new InventoryClickEvent(player.getOpenInventory(), InventoryType.SlotType.CONTAINER, 0,
                ClickType.LEFT, InventoryAction.PICKUP_ALL);
        var completions = new HashMap<ItemTransactionEvent<?>, CompletableFuture<Optional<InventoryExtractionReceipt>>>();
        var compile = InventoryEventListener.class.getDeclaredMethod("compileTransactionsFromClick", InventoryClickEvent.class,
                boolean.class, dev.jsinco.brewery.api.breweries.InventoryAccessible.class, Map.class);
        compile.setAccessible(true);
        @SuppressWarnings("unchecked") var transactions = (List<ItemTransactionEvent<?>>) compile.invoke(listener, click, true, barrel, completions);
        var proposal = (BarrelExtractEvent) transactions.getFirst();
        assertFalse(proposal.getCompletionResult().toCompletableFuture().isDone());
        player.getOpenInventory().setCursor(original.clone()); // Models the native move after event acceptance.
        barrel.getInventory().getInventory().setItem(0, null);
        var display = InventoryEventListener.class.getDeclaredMethod("displayEventResult", org.bukkit.inventory.InventoryView.class, List.class, Map.class);
        display.setAccessible(true);
        display.invoke(listener, player.getOpenInventory(), transactions, completions);
        var receipt = proposal.getCompletionResult().toCompletableFuture().join().orElseThrow();
        assertEquals(10, ((dev.jsinco.brewery.api.brew.BrewingStep.Distill) receipt.sourceBrew().orElseThrow().lastCompletedStep()).runs());
        assertEquals(player.getOpenInventory().getCursor(), receipt.item());
        var copy = receipt.item(); copy.setAmount(9);
        assertEquals(1, receipt.item().getAmount());
    }
}
