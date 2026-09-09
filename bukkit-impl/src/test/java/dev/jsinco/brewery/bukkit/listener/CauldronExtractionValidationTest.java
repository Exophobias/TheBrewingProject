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
import dev.jsinco.brewery.bukkit.api.event.transaction.CauldronExtractionReceipt;
import dev.jsinco.brewery.bukkit.api.transaction.ItemSource;
import dev.jsinco.brewery.bukkit.brew.BrewAdapterAccess;
import dev.jsinco.brewery.bukkit.breweries.BukkitCauldron;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import dev.jsinco.brewery.bukkit.testutil.CauldronOwnerServerMock;
import dev.jsinco.brewery.bukkit.database.SessionTypes;
import org.bukkit.Material;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.data.Levelled;
import org.bukkit.entity.Item;
import org.bukkit.event.Event;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
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
import java.util.ArrayList;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class CauldronExtractionValidationTest {
    private CauldronOwnerServerMock server;
    private TheBrewingProject plugin;
    private WorldMock world;
    private PlayerMock player;
    private Block block;
    private TrackingCauldron cauldron;
    private PlayerEventListener listener;
    private PlayerInteractEvent interaction;
    private Consumer<CauldronExtractEvent> callback;
    private Map<String, Integer> statisticsBefore;
    private final List<CauldronExtractEvent> proposals = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        server = MockBukkit.mock(new CauldronOwnerServerMock());
        world = server.addSimpleWorld("extraction");
        plugin = MockBukkit.load(TheBrewingProject.class);
        plugin.getResolvedIngredientManager().join();
        plugin.getDatabase().flush().join();
        server.publishGlobal();
        assertTrue(plugin.getAtomicMutationGate().reserve(() -> true), "Actual cauldron world hydration must publish");
        player = server.addPlayer();
        player.addAttachment(plugin, "brewery.cauldron.access", true);
        block = world.getBlockAt(4, 65, 4);
        setLevel(3);
        cauldron = new TrackingCauldron();
        plugin.getDatabase().startSession(SessionTypes.CAULDRON_SESSION_TYPE).insertCauldron(cauldron).join();
        plugin.getBreweryRegistry().addActiveSinglePositionStructure(cauldron);
        listener = new PlayerEventListener(null, plugin.getBreweryRegistry(), plugin.getDatabase(), null, null, null, null);
        player.getInventory().setItemInMainHand(new ItemStack(Material.GLASS_BOTTLE, 2));
        interaction = interaction(EquipmentSlot.HAND);
        callback = event -> event.setItemResult(output());
        server.getPluginManager().registerEvent(CauldronExtractEvent.class, new Listener() { },
                EventPriority.NORMAL, (ignored, event) -> {
                    assertDenied();
                    var proposal = (CauldronExtractEvent) event;
                    assertFalse(proposal.getCompletionResult().toCompletableFuture().isDone());
                    proposals.add(proposal);
                    callback.accept(proposal);
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
        var receipt = observed();
        assertEquals(player.getUniqueId(), receipt.playerId());
        assertEquals(cauldron.position(), receipt.cauldron());
        assertEquals(3, receipt.levelBefore()); assertEquals(2, receipt.levelAfter());
        assertTrue(receipt.inputConsumed());
        assertEquals(world.getEntitiesByClass(Item.class).iterator().next().getUniqueId(), receipt.itemEntityId());
        assertEquals(output(), receipt.item());
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
        assertThrows(CompletionException.class, () -> proposals.getFirst().getCompletionResult().toCompletableFuture().join());
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

    @Test void laterMonitorCancellationSettlesWithoutACompletionReceipt() throws ReflectiveOperationException {
        server.getPluginManager().registerEvent(CauldronExtractEvent.class, new Listener() { }, EventPriority.MONITOR,
                (ignored, event) -> ((CauldronExtractEvent) event).setCancelState(new CancelState.Cancelled()), plugin);
        extract(); assertUnchanged(3, new ItemStack(Material.GLASS_BOTTLE, 2));
        assertTrue(proposals.getFirst().getCompletionResult().toCompletableFuture().join().isEmpty());
    }

    @Test void nestedOwnerAndSpawnCallbacksCannotExtractTheSameServing() {
        callback = event -> {
            listener.handleCauldronExtract(interaction(EquipmentSlot.HAND), block, cauldron);
            event.setItemResult(output());
        };
        onSpawn(event -> listener.handleCauldronExtract(interaction(EquipmentSlot.HAND), block, cauldron));
        extract();
        assertEquals(1, proposals.size()); assertEquals(2, cauldron.getLevel());
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        assertEquals(1, world.getEntitiesByClass(Item.class).size()); assertNotNull(observed());
    }

    @Test void reservedSourceRefusesOrdinaryRemovalIngredientAndDirectLevelMutation() {
        callback = event -> {
            assertTrue(cauldron.isExtractionPending());
            assertFalse(ListenerUtil.removeIfCurrent(cauldron));
            assertFalse(cauldron.decrementLevel());
            assertFalse(cauldron.withIngredient(new ItemStack(Material.WHEAT), player));
            assertTrue(cauldron.tryExtractBrew(new ItemSource.ItemBasedSource(output()), () -> true).isEmpty());
            assertEquals(3, cauldron.getLevel());
            event.setItemResult(output());
        };
        extract(); assertEquals(2, cauldron.getLevel()); assertNotNull(observed());
    }

    @Test void cancelledNativeSpawnCannotProduceASuccessReceiptOrCounter() throws ReflectiveOperationException {
        onSpawn(event -> event.setCancelled(true));
        extract();
        assertEquals(2, cauldron.getLevel()); assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(proposals.getFirst().getCompletionResult().toCompletableFuture().join().isEmpty());
        assertEquals(statisticsBefore, statistics());
    }

    @Test void nativeSpawnReplacementRefusesToDecrementNewOwnerAndReportsNoCompletion() throws ReflectiveOperationException {
        TrackingCauldron replacement = new TrackingCauldron();
        onSpawn(event -> plugin.getBreweryRegistry().addActiveSinglePositionStructure(replacement));
        extract();
        assertSame(replacement, plugin.getBreweryRegistry().getActiveSinglePositionStructure(cauldron.position()).orElseThrow());
        assertEquals(3, replacement.getLevel()); assertEquals(1, world.getEntitiesByClass(Item.class).size());
        assertEquals(1, player.getInventory().getItemInMainHand().getAmount());
        assertTrue(proposals.getFirst().getCompletionResult().toCompletableFuture().join().isEmpty());
        assertEquals(statisticsBefore, statistics(), "No rollback or durable delivery is implied by the absent receipt");
    }

    @Test void nativeSpawnHandMutationIsNotOverwrittenOrReportedAsCompletion() {
        onSpawn(event -> player.getInventory().setItemInMainHand(new ItemStack(Material.DIAMOND, 4)));
        extract();
        assertEquals(new ItemStack(Material.DIAMOND, 4), player.getInventory().getItemInMainHand());
        assertEquals(3, cauldron.getLevel());
        assertTrue(proposals.getFirst().getCompletionResult().toCompletableFuture().join().isEmpty());
    }

    @Test void alteredNativeOutputDoesNotCountAsTheApprovedHandoff() throws ReflectiveOperationException {
        onSpawn(event -> event.getEntity().setItemStack(new ItemStack(Material.DIRT)));
        extract(); assertEquals(2, cauldron.getLevel());
        assertTrue(proposals.getFirst().getCompletionResult().toCompletableFuture().join().isEmpty());
        assertEquals(statisticsBefore, statistics());
    }

    @Test void lastBottleAndLastServingHaveAnObservedRuntimeReceiptOnlyAfterUnregistration() {
        setLevel(1); player.getInventory().setItemInMainHand(new ItemStack(Material.GLASS_BOTTLE));
        interaction = interaction(EquipmentSlot.HAND);
        AtomicInteger observed = new AtomicInteger();
        callback = event -> {
            event.setItemResult(output());
            event.getCompletionResult().thenAccept(result -> {
                assertTrue(result.isPresent()); assertEquals(0, cauldron.getLevel());
                assertTrue(plugin.getBreweryRegistry().getActiveSinglePositionStructure(cauldron.position()).isEmpty());
                assertFalse(cauldron.isExtractionPending()); observed.incrementAndGet();
            });
        };
        extract(); assertEquals(1, observed.get()); assertEquals(0, observed().levelAfter());
        assertTrue(player.getInventory().getItemInMainHand().isEmpty());
    }

    @Test void finalServingTeardownReplacementCannotBeUnregisteredOrReported() {
        setLevel(1); TrackingCauldron replacement = new TrackingCauldron();
        cauldron.onDestroy = () -> {
            setLevel(3); plugin.getBreweryRegistry().addActiveSinglePositionStructure(replacement);
        };
        extract();
        assertSame(replacement, plugin.getBreweryRegistry().getActiveSinglePositionStructure(cauldron.position()).orElseThrow());
        assertEquals(3, replacement.getLevel());
        assertTrue(proposals.getFirst().getCompletionResult().toCompletableFuture().join().isEmpty());
    }

    @Test void observerCannotForgeCompletionOrMutateTheStoredOutput() {
        callback = event -> {
            assertTrue(event.getCompletionResult().toCompletableFuture().complete(Optional.empty()));
            assertFalse(event.getCompletionResult().toCompletableFuture().isDone());
            event.setItemResult(output());
        };
        extract(); var receipt = observed();
        receipt.item().setType(Material.DIRT); assertEquals(output(), receipt.item());
    }

    @Test void legacyProposalIsUnacknowledgedAndStaleReservationCannotReleaseANewerOne() {
        var legacy = new CauldronExtractEvent(cauldron, new ItemSource.BrewBasedSource(brew(1), new Brew.State.Other()),
                new CancelState.Allowed(), player);
        assertTrue(legacy.getCompletionResult().toCompletableFuture().join().isEmpty());
        var old = cauldron.reserveExtraction().orElseThrow(); old.close();
        var current = cauldron.reserveExtraction().orElseThrow(); old.close();
        assertTrue(cauldron.ownsExtraction(current)); assertFalse(cauldron.ownsExtraction(old));
        assertFalse(ListenerUtil.removeIfCurrent(cauldron, old));
        current.close(); assertFalse(cauldron.isExtractionPending());
        assertFalse(cauldron.decrementLevel(old));
        assertTrue(cauldron.tryExtractBrew(new ItemSource.ItemBasedSource(output()), () -> true, old).isEmpty());
        assertEquals(3, cauldron.getLevel()); assertEquals(0, cauldron.effects);
    }

    @Test void creativeHandoffReportsTheRetainedBottleWithoutPretendingInputConsumption() {
        assertFalse(dev.jsinco.brewery.configuration.Config.config().consumeItemsInCreative());
        player.setGameMode(GameMode.CREATIVE);
        extract();
        assertEquals(new ItemStack(Material.GLASS_BOTTLE, 2), player.getInventory().getItemInMainHand());
        assertEquals(2, cauldron.getLevel()); assertFalse(observed().inputConsumed());
        assertEquals(1, world.getEntitiesByClass(Item.class).size());
    }

    @Test void nativeLevelHandlerCancelsOnlyTheCurrentReservedCauldron() {
        var handler = new BlockEventListener(null, null, plugin.getDatabase(), plugin.getBreweryRegistry());
        try (var reservation = cauldron.reserveExtraction().orElseThrow()) {
            var reserved = new CauldronLevelChangeEvent(block, player,
                    CauldronLevelChangeEvent.ChangeReason.BOTTLE_FILL, block.getState());
            handler.guardReservedCauldronLevelChange(reserved); assertTrue(reserved.isCancelled());
            Block foreignBlock = world.getBlockAt(8, 65, 8); foreignBlock.setType(Material.WATER_CAULDRON);
            var foreign = new CauldronLevelChangeEvent(foreignBlock, player,
                    CauldronLevelChangeEvent.ChangeReason.BOTTLE_FILL, foreignBlock.getState());
            handler.guardReservedCauldronLevelChange(foreign); assertFalse(foreign.isCancelled());
            var replacement = new TrackingCauldron();
            plugin.getBreweryRegistry().addActiveSinglePositionStructure(replacement);
            var replaced = new CauldronLevelChangeEvent(block, player,
                    CauldronLevelChangeEvent.ChangeReason.BOTTLE_FILL, block.getState());
            handler.guardReservedCauldronLevelChange(replaced); assertFalse(replaced.isCancelled());
            plugin.getBreweryRegistry().addActiveSinglePositionStructure(cauldron);
        }
        var available = new CauldronLevelChangeEvent(block, player,
                CauldronLevelChangeEvent.ChangeReason.BOTTLE_FILL, block.getState());
        handler.guardReservedCauldronLevelChange(available); assertFalse(available.isCancelled());
        assertEquals(3, cauldron.getLevel(), "An event guard must not rewrite world data");
    }

    private void onSpawn(Consumer<ItemSpawnEvent> action) {
        server.getPluginManager().registerEvent(ItemSpawnEvent.class, new Listener() { }, EventPriority.NORMAL,
                (ignored, event) -> action.accept((ItemSpawnEvent) event), plugin);
    }

    private CauldronExtractionReceipt observed() {
        return proposals.getFirst().getCompletionResult().toCompletableFuture().join().orElseThrow();
    }

    private void extract() {
        try { listener.handleCauldronExtract(interaction, block, cauldron); }
        finally { assertFalse(cauldron.isExtractionPending(), "Owner must release every synchronous exit"); }
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
        for (var proposal : proposals) {
            var completed = proposal.getCompletionResult().toCompletableFuture();
            assertTrue(completed.isDone(), "Refused owner proposal left observers pending");
            if (!completed.isCompletedExceptionally()) assertTrue(completed.join().isEmpty());
        }
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
        private Runnable onDestroy = () -> { };

        private TrackingCauldron() {
            super(brew(1), BukkitAdapter.toBreweryLocation(block), CauldronType.WATER);
        }

        @Override public Brew getBrew() { return current; }
        @Override public Brew getUpdatedBrew() { return current; }
        @Override public void playBrewExtractedEffects() { effects++; }
        @Override public void destroyForExtraction(ExtractionReservation reservation) {
            super.destroyForExtraction(reservation); onDestroy.run();
        }
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
