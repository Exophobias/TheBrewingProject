package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.breweries.CauldronType;
import dev.jsinco.brewery.api.meta.MetaDataType;
import dev.jsinco.brewery.api.moment.PassedMoment;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.MixStepImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.BukkitAdapter;
import dev.jsinco.brewery.bukkit.api.event.process.BrewCauldronProcessEvent;
import dev.jsinco.brewery.bukkit.api.integration.IntegrationTypes;
import dev.jsinco.brewery.bukkit.api.integration.ItemIntegration;
import dev.jsinco.brewery.bukkit.breweries.BukkitCauldron;
import dev.jsinco.brewery.bukkit.database.SessionTypes;
import dev.jsinco.brewery.bukkit.testutil.CauldronOwnerServerMock;
import dev.jsinco.brewery.bukkit.testutil.ComponentItemStackMock;
import dev.jsinco.brewery.bukkit.testutil.ItemComponentBridgeFixture;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Player;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import static org.junit.jupiter.api.Assertions.*;

class CauldronServingPublicationTest {
    private static final Key PROOF = Key.key("test", "exclusive-proof");
    private CauldronOwnerServerMock server;
    private WorldMock world;
    private TheBrewingProject plugin;
    private RuntimeException metadataFailure;
    private AutoCloseable potionFactory;
    private AutoCloseable componentBridge;

    @BeforeEach void setUp() throws Exception {
        server = MockBukkit.mock(new CauldronOwnerServerMock());
        world = server.addSimpleWorld("serving-publication");
        plugin = MockBukkit.load(TheBrewingProject.class);
        potionFactory = ComponentItemStackMock.installPotionFactory();
        componentBridge = ItemComponentBridgeFixture.install();
        plugin.getResolvedIngredientManager().join();
        plugin.getDatabase().flush().join();
        server.publishGlobal();
        plugin.getIntegrationManager().register(IntegrationTypes.ITEM, new ItemIntegration() {
            @Override public String getId() { return "serving-publication-test"; }
            @Override public boolean isEnabled() { return true; }
            @Override public Optional<ItemStack> createItem(String id) { return Optional.empty(); }
            @Override public boolean isIngredient(String id) { return false; }
            @Override public Component displayName(String id) { return null; }
            @Override public String getItemId(ItemStack item) { return null; }
            @Override public CompletableFuture<Void> initialized() { return CompletableFuture.completedFuture(null); }
            @Override public Brew mergeBrewMetadata(Brew existing, Brew added, Brew merged) {
                if (metadataFailure != null) throw metadataFailure;
                assertTrue(added.getSteps().isEmpty(), "Plain water carries no certified process history");
                return merged.withoutMeta(PROOF);
            }
        });
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

    @Test void actualHandlerProcessVetoLeavesLevelProofAndHeldInputUntouched() throws Exception {
        var fixture = fixture();
        var proposals = new AtomicInteger();
        server.getPluginManager().registerEvent(BrewCauldronProcessEvent.class, new Listener() { }, EventPriority.NORMAL,
                (ignored, event) -> {
                    proposals.incrementAndGet();
                    ((BrewCauldronProcessEvent) event).setCancelled(true);
                }, plugin);
        ItemStack held = fixture.player().getInventory().getItemInMainHand().clone();
        assertFalse(addThroughHandler(fixture));
        assertEquals(1, proposals.get());
        assertUnchanged(fixture, held);
    }

    @Test void actualHandlerMetadataFailureCannotPublishAFreeServing() throws Exception {
        var fixture = fixture();
        ItemStack held = fixture.player().getInventory().getItemInMainHand().clone();
        metadataFailure = new IllegalStateException("injected metadata failure");
        assertSame(metadataFailure, assertThrows(IllegalStateException.class, () -> addThroughHandler(fixture)));
        assertUnchanged(fixture, held);
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void acceptedCosmeticFailureStillConsumesExactlyOneInput(boolean failLevelDisplay) throws Exception {
        var fixture = fixture();
        fixture.cauldron().failEffects = !failLevelDisplay;
        fixture.cauldron().failLevelDisplay = failLevelDisplay;
        var warnings = new AtomicInteger();
        Handler handler = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getMessage().contains("serving accepted, but its feedback failed")) warnings.incrementAndGet();
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        plugin.getLogger().addHandler(handler);
        try {
            assertTrue(addThroughHandler(fixture));
        } finally {
            plugin.getLogger().removeHandler(handler);
        }
        assertEquals(2, fixture.cauldron().getLevel());
        assertFalse(fixture.cauldron().getBrew().hasMeta(PROOF, MetaDataType.STRING));
        assertEquals(Material.POTION, fixture.player().getInventory().getItemInMainHand().getType());
        assertEquals(1, fixture.player().getInventory().getItemInMainHand().getAmount());
        assertTrue(warnings.get() > 0, "The accepted serving's cosmetic failure remains visible to operators");
    }

    private void assertUnchanged(Fixture fixture, ItemStack held) {
        assertEquals(1, fixture.cauldron().getLevel());
        assertSame(fixture.original(), fixture.cauldron().getBrew());
        assertTrue(fixture.cauldron().getBrew().hasMeta(PROOF, MetaDataType.STRING));
        assertEquals(held, fixture.player().getInventory().getItemInMainHand());
    }

    private Fixture fixture() throws Exception {
        Block block = world.getBlockAt(5, 65, 5);
        block.setType(Material.WATER_CAULDRON);
        Levelled data = (Levelled) block.getBlockData(); data.setLevel(1); block.setBlockData(data);
        Brew original = new BrewImpl(List.of(new MixStepImpl(new PassedMoment(1), Map.of(), CauldronType.BREW)))
                .withMeta(PROOF, MetaDataType.STRING, "only-the-original-serving");
        var cauldron = new FeedbackCauldron(original, BukkitAdapter.toBreweryLocation(block));
        plugin.getDatabase().startSession(SessionTypes.CAULDRON_SESSION_TYPE).insertCauldron(cauldron).join();
        plugin.getBreweryRegistry().addActiveSinglePositionStructure(cauldron);
        var player = server.addPlayer(); player.addAttachment(plugin, "brewery.cauldron.access", true);
        player.getInventory().setItemInMainHand(new ItemStack(Material.POTION, 2));
        return new Fixture(original, block, cauldron, player);
    }

    private boolean addThroughHandler(Fixture fixture) throws Exception {
        var listener = new PlayerEventListener(null, plugin.getBreweryRegistry(), plugin.getDatabase(), null, null, null, null);
        var method = PlayerEventListener.class.getDeclaredMethod("handleIngredientAddition", ItemStack.class,
                Block.class, BukkitCauldron.class, Player.class, EquipmentSlot.class);
        method.setAccessible(true);
        try {
            return (boolean) method.invoke(listener, fixture.player().getInventory().getItemInMainHand(), fixture.block(),
                    fixture.cauldron(), fixture.player(), EquipmentSlot.HAND);
        } catch (InvocationTargetException failure) {
            if (failure.getCause() instanceof RuntimeException cause) throw cause;
            throw failure;
        }
    }

    private record Fixture(Brew original, Block block, FeedbackCauldron cauldron, PlayerMock player) { }

    private static final class FeedbackCauldron extends BukkitCauldron {
        private boolean failEffects;
        private boolean failLevelDisplay;
        private FeedbackCauldron(Brew brew, BreweryLocation location) { super(brew, location, CauldronType.BREW); }
        @Override public void playIngredientAddedEffects(ItemStack item, Player player) {
            if (failEffects) throw new IllegalStateException("injected effect failure");
            super.playIngredientAddedEffects(item, player);
        }
        @Override public void updateLevel(BlockData data) {
            if (failLevelDisplay) throw new IllegalStateException("injected level-display failure");
            super.updateLevel(data);
        }
    }
}
