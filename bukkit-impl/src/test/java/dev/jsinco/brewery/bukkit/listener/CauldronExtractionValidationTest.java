package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.breweries.CauldronType;
import dev.jsinco.brewery.api.moment.Interval;
import dev.jsinco.brewery.api.util.CancelState;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.MixStepImpl;
import dev.jsinco.brewery.bukkit.Statistics;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.BukkitAdapter;
import dev.jsinco.brewery.bukkit.api.event.transaction.CauldronExtractEvent;
import dev.jsinco.brewery.bukkit.brew.BrewAdapterAccess;
import dev.jsinco.brewery.bukkit.breweries.BukkitCauldron;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import org.bukkit.Material;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Item;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.MockBukkit;
import org.mockbukkit.mockbukkit.entity.PlayerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class CauldronExtractionValidationTest {
    private TBPServerMock server;
    private TheBrewingProject plugin;
    private WorldMock world;
    private PlayerMock player;
    private Block block;
    private TrackingCauldron cauldron;
    private PlayerEventListener listener;
    private PlayerInteractEvent interaction;
    private Consumer<CauldronExtractEvent> callback;
    private Map<String, Integer> statisticsBefore;

    @BeforeEach
    void setUp() throws ReflectiveOperationException {
        server = MockBukkit.mock(new TBPServerMock());
        world = server.addSimpleWorld("extraction");
        plugin = MockBukkit.load(TheBrewingProject.class);
        plugin.getResolvedIngredientManager().join();
        plugin.getDatabase().flush().join();
        player = server.addPlayer();
        player.addAttachment(plugin, "brewery.cauldron.access", true);
        block = world.getBlockAt(4, 65, 4);
        setLevel(3);
        cauldron = new TrackingCauldron();
        plugin.getBreweryRegistry().addActiveSinglePositionStructure(cauldron);
        listener = new PlayerEventListener(null, plugin.getBreweryRegistry(), plugin.getDatabase(), null, null, null, null);
        player.getInventory().setItemInMainHand(new ItemStack(Material.GLASS_BOTTLE, 2));
        interaction = interaction(EquipmentSlot.HAND);
        callback = event -> event.setItemResult(output());
        server.getPluginManager().registerEvent(CauldronExtractEvent.class, new Listener() { },
                EventPriority.NORMAL, (ignored, event) -> {
                    assertDenied();
                    callback.accept((CauldronExtractEvent) event);
                }, plugin);
        statisticsBefore = statistics();
    }

    @AfterEach
    void tearDown() {
        MockBukkit.unmock();
    }

    @Test
    void successfulExtractionConsumesOneBottleAndOneLevel() throws ReflectiveOperationException {
        extract();
        assertDenied();
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(2, cauldron.getLevel());
        assertEquals(1, cauldron.effects);
        assertEquals(1, world.getEntitiesByClass(Item.class).size());
        assertEquals(statisticsBefore.values().stream().mapToInt(Integer::intValue).sum() + 1,
                statistics().values().stream().mapToInt(Integer::intValue).sum());
    }

    @Test
    void offhandExtractionChecksAndConsumesTheOffhandOnly() {
        player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND));
        player.getInventory().setItemInOffHand(new ItemStack(Material.GLASS_BOTTLE, 2));
        interaction = interaction(EquipmentSlot.OFF_HAND);
        extract();
        assertEquals(Material.DIAMOND, player.getInventory().getItemInMainHand().getType());
        assertEquals(1, player.getInventory().getItemInOffHand().getAmount());
        assertEquals(2, cauldron.getLevel());
    }

    @Test
    void ownerCancellationPreservesInputsAndHasNoExtractionEffects() throws ReflectiveOperationException {
        callback = event -> event.setCancelState(new CancelState.Cancelled());
        extract();
        assertUnchanged(3, new ItemStack(Material.GLASS_BOTTLE, 2));
    }

    @Test
    void ownerReplacementCannotBeExtractedOrHaveItsGeometryDecremented() throws ReflectiveOperationException {
        TrackingCauldron replacement = new TrackingCauldron();
        callback = event -> {
            plugin.getBreweryRegistry().addActiveSinglePositionStructure(replacement);
            event.setItemResult(output());
        };
        extract();
        assertSame(replacement, plugin.getBreweryRegistry().getActiveSinglePositionStructure(cauldron.position()).orElseThrow());
        assertUnchanged(3, new ItemStack(Material.GLASS_BOTTLE, 2));
    }

    @Test
    void changedHandAfterOwnerEventIsNotOverwritten() throws ReflectiveOperationException {
        callback = event -> {
            player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 4));
            event.setItemResult(output());
        };
        extract();
        assertUnchanged(3, new ItemStack(Material.DIAMOND, 4));
    }

    @Test
    void changedBottleAmountAfterOwnerEventIsNotConsumed() throws ReflectiveOperationException {
        callback = event -> {
            player.getInventory().setItemInMainHand(new ItemStack(Material.GLASS_BOTTLE, 1));
            event.setItemResult(output());
        };
        extract();
        assertUnchanged(3, new ItemStack(Material.GLASS_BOTTLE, 1));
    }

    @Test
    void detachedEventItemMutationCannotOverwriteTheValidatedHeldBottle() {
        callback = event -> {
            interaction.getItem().setType(Material.DIAMOND);
            interaction.getItem().setAmount(8);
            event.setItemResult(output());
        };
        extract();
        assertEquals(new ItemStack(Material.GLASS_BOTTLE, 1), player.getInventory().getItemInMainHand());
        assertEquals(2, cauldron.getLevel());
        assertEquals(1, world.getEntitiesByClass(Item.class).size());
    }

    @Test
    void ownerCallbackCannotSwitchToAnIdenticalBottleInAnotherHotbarSlot() throws ReflectiveOperationException {
        int originalSlot = player.getInventory().getHeldItemSlot();
        int otherSlot = (originalSlot + 1) % 9;
        player.getInventory().setItem(otherSlot, new ItemStack(Material.GLASS_BOTTLE, 2));
        callback = event -> {
            player.getInventory().setHeldItemSlot(otherSlot);
            event.setItemResult(output());
        };
        extract();
        assertUnchanged(3, new ItemStack(Material.GLASS_BOTTLE, 2));
        assertEquals(new ItemStack(Material.GLASS_BOTTLE, 2), player.getInventory().getItem(originalSlot));
    }

    @Test
    void materializationCannotSwitchToAnIdenticalBottleInAnotherHotbarSlot() throws ReflectiveOperationException {
        int originalSlot = player.getInventory().getHeldItemSlot();
        int otherSlot = (originalSlot + 1) % 9;
        player.getInventory().setItem(otherSlot, new ItemStack(Material.GLASS_BOTTLE, 2));
        callback = event -> event.setItemResult(new CallbackItem(() -> player.getInventory().setHeldItemSlot(otherSlot)));
        extract();
        assertUnchanged(3, new ItemStack(Material.GLASS_BOTTLE, 2));
        assertEquals(new ItemStack(Material.GLASS_BOTTLE, 2), player.getInventory().getItem(originalSlot));
        assertFalse(extractedFlag());
    }

    @Test
    void ownerCallbackMovingPlayerToAnotherWorldRefusesExtraction() throws ReflectiveOperationException {
        WorldMock other = server.addSimpleWorld("other");
        callback = event -> {
            player.teleport(new Location(other, 0, 65, 0));
            event.setItemResult(output());
        };
        extract();
        assertSame(other, player.getWorld());
        assertUnchanged(3, new ItemStack(Material.GLASS_BOTTLE, 2));
    }

    @Test
    void materializationMovingPlayerToAnotherWorldRefusesExtraction() throws ReflectiveOperationException {
        WorldMock other = server.addSimpleWorld("other");
        callback = event -> event.setItemResult(new CallbackItem(() -> player.teleport(new Location(other, 0, 65, 0))));
        extract();
        assertSame(other, player.getWorld());
        assertUnchanged(3, new ItemStack(Material.GLASS_BOTTLE, 2));
        assertFalse(extractedFlag());
    }

    @Test
    void ownerCallbackDisconnectingPlayerRefusesExtraction() throws ReflectiveOperationException {
        callback = event -> {
            player.disconnect();
            event.setItemResult(output());
        };
        extract();
        assertFalse(player.isOnline());
        assertUnchanged(3, new ItemStack(Material.GLASS_BOTTLE, 2));
    }

    @Test
    void materializationDisconnectingPlayerRefusesExtraction() throws ReflectiveOperationException {
        callback = event -> event.setItemResult(new CallbackItem(player::disconnect));
        extract();
        assertFalse(player.isOnline());
        assertUnchanged(3, new ItemStack(Material.GLASS_BOTTLE, 2));
        assertFalse(extractedFlag());
    }

    @Test
    void sameHolderBrewChangeInvalidatesTheProposedOutput() throws ReflectiveOperationException {
        callback = event -> {
            cauldron.current = brew(7);
            event.setItemResult(output());
        };
        extract();
        assertUnchanged(3, new ItemStack(Material.GLASS_BOTTLE, 2));
    }

    @Test
    void changedLevelAfterOwnerEventIsNotConsumedAgain() throws ReflectiveOperationException {
        callback = event -> {
            setLevel(1);
            event.setItemResult(output());
        };
        extract();
        assertUnchanged(1, new ItemStack(Material.GLASS_BOTTLE, 2));
    }

    @Test
    void emptyCauldronCannotProduceABrew() throws ReflectiveOperationException {
        block.setType(Material.CAULDRON);
        callback = event -> fail("Empty cauldron must be refused before owner event");
        extract();
        assertUnchanged(0, new ItemStack(Material.GLASS_BOTTLE, 2));
    }

    @Test
    void differentCauldronMaterialCannotUseTheOldRecipe() throws ReflectiveOperationException {
        block.setType(Material.LAVA_CAULDRON);
        callback = event -> fail("Wrong material must be refused before owner event");
        extract();
        assertUnchanged(1, new ItemStack(Material.GLASS_BOTTLE, 2));
        assertEquals(Material.LAVA_CAULDRON, block.getType());
    }

    @Test
    void materializationFailureKeepsNativeInteractionDeniedAndInputsUntouched() throws ReflectiveOperationException {
        callback = event -> event.setItemResult(new CallbackItem(() -> {
            throw new IllegalStateException("Injected lazy item rendering failure");
        }));
        assertThrows(IllegalStateException.class, this::extract);
        assertUnchanged(3, new ItemStack(Material.GLASS_BOTTLE, 2));
        assertFalse(extractedFlag());
    }

    @Test
    void materializationReplacementIsRecheckedBeforeExtractionEffects() throws ReflectiveOperationException {
        TrackingCauldron replacement = new TrackingCauldron();
        callback = event -> event.setItemResult(new CallbackItem(() ->
                plugin.getBreweryRegistry().addActiveSinglePositionStructure(replacement)));
        extract();
        assertUnchanged(3, new ItemStack(Material.GLASS_BOTTLE, 2));
        assertFalse(extractedFlag());
    }

    @Test
    void materializationHandChangeIsNotOverwritten() throws ReflectiveOperationException {
        callback = event -> event.setItemResult(new CallbackItem(() ->
                player.getInventory().setItemInMainHand(new ItemStack(Material.EMERALD))));
        extract();
        assertUnchanged(3, new ItemStack(Material.EMERALD));
        assertFalse(extractedFlag());
    }

    @Test
    void materializationSameHolderBrewChangeIsRechecked() throws ReflectiveOperationException {
        callback = event -> event.setItemResult(new CallbackItem(() -> cauldron.current = brew(9)));
        extract();
        assertUnchanged(3, new ItemStack(Material.GLASS_BOTTLE, 2));
        assertFalse(extractedFlag());
    }

    @Test
    void materializationLevelChangeIsRecheckedBeforeExtractionEffects() throws ReflectiveOperationException {
        callback = event -> event.setItemResult(new CallbackItem(() -> setLevel(1)));
        extract();
        assertUnchanged(1, new ItemStack(Material.GLASS_BOTTLE, 2));
        assertFalse(extractedFlag());
    }

    @Test
    void emptyOwnerOutputIsRefusedWithoutConsumingInputs() throws ReflectiveOperationException {
        callback = event -> event.setItemResult(new ItemStack(Material.AIR));
        extract();
        assertUnchanged(3, new ItemStack(Material.GLASS_BOTTLE, 2));
        assertFalse(extractedFlag());
    }

    private void extract() {
        listener.handleCauldronExtract(interaction, block, cauldron);
    }

    private PlayerInteractEvent interaction(EquipmentSlot hand) {
        return new PlayerInteractEvent(player, Action.RIGHT_CLICK_BLOCK, player.getInventory().getItem(hand).clone(),
                block, BlockFace.UP, hand);
    }

    private void assertDenied() {
        assertEquals(Event.Result.DENY, interaction.useInteractedBlock());
        assertEquals(Event.Result.DENY, interaction.useItemInHand());
    }

    private void assertUnchanged(int level, ItemStack held) throws ReflectiveOperationException {
        assertDenied();
        assertEquals(level, cauldron.getLevel());
        assertEquals(held, player.getInventory().getItemInMainHand());
        assertEquals(0, cauldron.effects);
        assertTrue(world.getEntitiesByClass(Item.class).isEmpty());
        assertEquals(statisticsBefore, statistics());
    }

    @SuppressWarnings("unchecked")
    private Map<String, Integer> statistics() throws ReflectiveOperationException {
        Field field = Statistics.class.getDeclaredField("brewsMade");
        field.setAccessible(true);
        return Map.copyOf((Map<String, Integer>) field.get(null));
    }

    private boolean extractedFlag() throws ReflectiveOperationException {
        Field field = BukkitCauldron.class.getDeclaredField("brewExtracted");
        field.setAccessible(true);
        return field.getBoolean(cauldron);
    }

    private void setLevel(int level) {
        block.setType(Material.WATER_CAULDRON);
        Levelled state = (Levelled) block.getBlockData();
        state.setLevel(level);
        block.setBlockData(state);
    }

    private static Brew brew(long end) {
        return new BrewImpl(List.of(new MixStepImpl(new Interval(0, end), Map.of(), CauldronType.WATER)));
    }

    private static ItemStack output() {
        ItemStack output = new ItemStack(Material.POTION);
        var meta = output.getItemMeta();
        meta.getPersistentDataContainer().set(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE, 1.0);
        output.setItemMeta(meta);
        return output;
    }

    private final class TrackingCauldron extends BukkitCauldron {
        private Brew current = brew(1);
        private int effects;

        private TrackingCauldron() {
            super(brew(1), BukkitAdapter.toBreweryLocation(block), CauldronType.WATER);
        }

        @Override public Brew getBrew() { return current; }
        @Override public Brew getUpdatedBrew() { return current; }
        @Override public void playBrewExtractedEffects() { effects++; }
    }

    private static final class CallbackItem extends ItemStack {
        private final Runnable callback;
        private CallbackItem(Runnable callback) {
            super(Material.POTION);
            this.callback = callback;
        }
        @Override public ItemStack clone() {
            callback.run();
            return output();
        }
    }
}
