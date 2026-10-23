package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.brew.BrewQuality;
import dev.jsinco.brewery.api.brew.BrewingStep;
import dev.jsinco.brewery.api.breweries.InventoryAccessible;
import dev.jsinco.brewery.bukkit.Statistics;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.event.transaction.BarrelExtractEvent;
import dev.jsinco.brewery.bukkit.api.event.transaction.BarrelInsertEvent;
import dev.jsinco.brewery.bukkit.api.event.transaction.DistilleryExtractEvent;
import dev.jsinco.brewery.bukkit.api.event.transaction.DistilleryInsertEvent;
import dev.jsinco.brewery.bukkit.api.event.transaction.ItemTransactionEvent;
import dev.jsinco.brewery.bukkit.api.event.transaction.InventoryExtractionReceipt;
import dev.jsinco.brewery.bukkit.api.transaction.ItemSource;
import dev.jsinco.brewery.bukkit.api.transaction.ItemTransaction;
import dev.jsinco.brewery.bukkit.api.transaction.ItemTransactionSession;
import dev.jsinco.brewery.bukkit.brew.BrewAdapterAccess;
import dev.jsinco.brewery.bukkit.breweries.BreweryRegistry;
import dev.jsinco.brewery.bukkit.breweries.barrel.BukkitBarrel;
import dev.jsinco.brewery.bukkit.breweries.distillery.BukkitDistillery;
import dev.jsinco.brewery.bukkit.effect.named.PukeNamedExecutable;
import dev.jsinco.brewery.configuration.Config;
import dev.jsinco.brewery.database.sql.SqlDatabase;
import net.kyori.adventure.text.Component;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.concurrent.CompletableFuture;
import java.util.Optional;
import java.util.Set;

public class InventoryEventListener implements Listener {

    private final BreweryRegistry registry;
    private final SqlDatabase database;
    private static final Set<InventoryAction> CLICKED_INVENTORY_ITEM_MOVE = Set.of(InventoryAction.PLACE_SOME,
            InventoryAction.PLACE_ONE, InventoryAction.PLACE_ALL, InventoryAction.PICKUP_ALL, InventoryAction.PICKUP_HALF,
            InventoryAction.PICKUP_SOME, InventoryAction.PICKUP_ONE, InventoryAction.SWAP_WITH_CURSOR);
    private static final Set<InventoryAction> CURSOR = Set.of(
            InventoryAction.PICKUP_ONE, InventoryAction.PICKUP_SOME, InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_ALL,
            InventoryAction.PLACE_ONE, InventoryAction.PLACE_SOME, InventoryAction.PLACE_ALL, InventoryAction.SWAP_WITH_CURSOR
    );
    private static final Set<InventoryAction> ITEM_PICKUP_CURSOR = Set.of(
            InventoryAction.PICKUP_ONE, InventoryAction.PICKUP_SOME, InventoryAction.PICKUP_HALF, InventoryAction.PICKUP_ALL
    );
    private static final Set<InventoryAction> BANNED = Set.of(
            InventoryAction.PICKUP_FROM_BUNDLE, InventoryAction.PLACE_FROM_BUNDLE, InventoryAction.PICKUP_ALL_INTO_BUNDLE,
            InventoryAction.PLACE_ALL_INTO_BUNDLE, InventoryAction.PLACE_SOME_INTO_BUNDLE, InventoryAction.PICKUP_SOME_INTO_BUNDLE
    );

    public InventoryEventListener(BreweryRegistry registry, SqlDatabase database) {
        this.registry = registry;
        this.database = database;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        InventoryAccessible<ItemStack, Inventory> inventoryAccessible = registry.getFromInventory(event.getInventory());
        if (inventoryAccessible == null) {
            return;
        }
        if (atomicMovePending(inventoryAccessible)) {
            event.setCancelled(true);
            return;
        }
        InventoryAction action = event.getAction();
        if (action == InventoryAction.NOTHING) {
            return;
        }
        if (BANNED.contains(action)) {
            event.setCancelled(true);
            return;
        }
        boolean upperInventoryIsClicked = event.getClickedInventory() == event.getInventory();
        if (!upperInventoryIsClicked && CLICKED_INVENTORY_ITEM_MOVE.contains(action)) {
            return;
        }
        var completions = new HashMap<ItemTransactionEvent<?>, CompletableFuture<Optional<InventoryExtractionReceipt>>>();
        List<? extends ItemTransactionEvent<?>> transactions = compileTransactionsFromClick(event, upperInventoryIsClicked, inventoryAccessible, completions);
        if (transactions.isEmpty()) {
            return;
        }
        Optional<Runnable> deferredRelease = reserveDeferredPublication(inventoryAccessible);
        if (deferredRelease.isEmpty()) {
            event.setResult(Event.Result.DENY);
            settleExtractions(completions);
            return;
        }
        Runnable release = deferredRelease.get();
        try {
            for (ItemTransactionEvent<?> transactionEvent : transactions) {
                if (!transactionEvent.callEvent()) {
                    if (transactionEvent.getCancelState() instanceof dev.jsinco.brewery.api.util.CancelState.PermissionDenied(
                            Component denyMessage
                    )) {
                        event.getWhoClicked().sendMessage(denyMessage);
                    }
                    event.setResult(Event.Result.DENY);
                    settleExtractions(completions);
                    release.run();
                    return;
                }
            }
            scheduleDeferredPublication(
                    event.getWhoClicked(),
                    () -> {
                        try {
                            if (!event.isCancelled()) displayEventResult(event.getView(), transactions, completions);
                        } catch (RuntimeException | Error failure) {
                            completions.values().forEach(completion -> completion.completeExceptionally(failure));
                            throw failure;
                        } finally {
                            settleExtractions(completions);
                        }
                    },
                    () -> { settleExtractions(completions); release.run(); }
            );
        } catch (RuntimeException | Error failure) {
            completions.values().forEach(completion -> completion.completeExceptionally(failure));
            release.run();
            throw failure;
        }
    }

    private void displayEventResult(@NonNull InventoryView view, List<? extends ItemTransactionEvent<?>> transactions,
                                    Map<ItemTransactionEvent<?>, CompletableFuture<Optional<InventoryExtractionReceipt>>> completions) {
        for (ItemTransactionEvent<?> transactionEvent : transactions) {
            ItemTransactionSession<?> session = transactionEvent.getTransactionSession();
            ItemTransaction transaction = session.getTransaction();
            ItemStack itemStack = session.getResult() == null ? null : session.getResult().get();
            boolean published = false;
            if (transaction.to() instanceof ItemTransaction.Cursor
                    && transaction.itemStack().equals(view.getCursor())
            ) {
                view.setCursor(itemStack);
                published = true;
            }
            if (transaction.to() instanceof ItemTransaction.RawPosition(int pos)
                    && transaction.itemStack().equals(view.getItem(pos))
            ) {
                view.setItem(pos, itemStack);
                published = true;
            }
            if (transaction.to() instanceof ItemTransaction.UpperInventoryPosition(int pos)
                    && transaction.itemStack().equals(view.getTopInventory().getItem(pos))
            ) {
                view.getTopInventory().setItem(pos, itemStack);
                published = true;
            }
            if (transaction.to() instanceof ItemTransaction.LowerInventoryPosition(int pos)
                    && transaction.itemStack().equals(view.getBottomInventory().getItem(pos))
            ) {
                view.getBottomInventory().setItem(pos, itemStack);
                published = true;
            }
            if (published && itemStack != null && !itemStack.isEmpty() && completions.containsKey(transactionEvent)) {
                var receipt = new InventoryExtractionReceipt(BrewAdapterAccess.fromItem(transaction.itemStack()), itemStack);
                completions.get(transactionEvent).complete(Optional.of(receipt));
                Optional.ofNullable(itemStack.getPersistentDataContainer().get(BrewAdapterAccess.BREWERY_SCORE, PersistentDataType.DOUBLE))
                        .ifPresent(score -> Statistics.registerBrewMade(BrewQuality.quality(score).orElse(null)));
            }
        }
    }

    private List<? extends ItemTransactionEvent<?>> compileTransactionsFromClick(InventoryClickEvent event, boolean upperInventoryIsClicked,
                                                                                 InventoryAccessible<ItemStack, Inventory> inventoryAccessible,
                                                                                 Map<ItemTransactionEvent<?>, CompletableFuture<Optional<InventoryExtractionReceipt>>> completions) {
        Player player = event.getWhoClicked() instanceof Player temp ? temp : null;
        if (event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            if (upperInventoryIsClicked) {
                return findEmptyPositions(event.getView(), event.getCurrentItem(), false)
                        .stream()
                        .map(inventoryPosition -> eventFromStructure(
                                inventoryAccessible,
                                completions,
                                new ItemTransaction.RawPosition(event.getRawSlot()),
                                inventoryPosition,
                                event.getCurrentItem(),
                                false,
                                player
                        ))
                        .toList();
            } else {
                return findEmptyPositions(event.getView(), event.getCurrentItem(), true)
                        .stream()
                        .map(inventoryPosition -> eventFromStructure(
                                inventoryAccessible,
                                completions,
                                new ItemTransaction.RawPosition(event.getRawSlot()),
                                inventoryPosition,
                                event.getCurrentItem(),
                                true,
                                player
                        ))
                        .toList();
            }
        }
        if (event.getAction() == InventoryAction.HOTBAR_SWAP) {
            if (!upperInventoryIsClicked) {
                return List.of();
            }
            int hotbar = event.getHotbarButton() == -1 ?
                    (event.getClick() == ClickType.SWAP_OFFHAND ? 40 : -1)
                    : event.getHotbarButton();
            if (hotbar == -1) {
                return List.of();
            }
            ItemStack currentItem = event.getCurrentItem();
            List<ItemTransactionEvent<?>> output = new ArrayList<>();
            if (currentItem != null && !currentItem.isEmpty()) {
                output.add(eventFromStructure(
                        inventoryAccessible,
                        completions,
                        new ItemTransaction.RawPosition(event.getRawSlot()),
                        new ItemTransaction.LowerInventoryPosition(hotbar),
                        currentItem,
                        false,
                        player
                ));
            }
            ItemStack hotbarItem = event.getView().getBottomInventory().getItem(hotbar);
            if (hotbarItem != null && !hotbarItem.isEmpty()) {
                output.add(eventFromStructure(
                        inventoryAccessible,
                        completions,
                        new ItemTransaction.LowerInventoryPosition(hotbar),
                        new ItemTransaction.RawPosition(event.getRawSlot()),
                        hotbarItem,
                        true,
                        player
                ));
            }
            return output;
        }
        if (CURSOR.contains(event.getAction())) {
            if (InventoryAction.SWAP_WITH_CURSOR == event.getAction()) {
                return List.of(
                        eventFromStructure(
                                inventoryAccessible,
                                completions,
                                new ItemTransaction.Cursor(),
                                new ItemTransaction.RawPosition(event.getRawSlot()),
                                event.getCursor(),
                                true,
                                player
                        ),
                        eventFromStructure(
                                inventoryAccessible,
                                completions,
                                new ItemTransaction.RawPosition(event.getRawSlot()),
                                new ItemTransaction.Cursor(),
                                event.getCurrentItem(),
                                false,
                                player
                        )
                );
            }
            if (ITEM_PICKUP_CURSOR.contains(event.getAction())) {
                return List.of(eventFromStructure(
                        inventoryAccessible,
                        completions,
                        new ItemTransaction.RawPosition(event.getRawSlot()),
                        new ItemTransaction.Cursor(),
                        event.getCurrentItem(),
                        false,
                        player
                ));
            }
            return List.of(eventFromStructure(
                    inventoryAccessible,
                    completions,
                    new ItemTransaction.Cursor(),
                    new ItemTransaction.RawPosition(event.getRawSlot()),
                    event.getCursor(),
                    true,
                    player
            ));
        }
        return List.of();
    }

    private List<ItemTransaction.InventoryPosition> findEmptyPositions(InventoryView view, @Nullable ItemStack currentItem, boolean topInventory) {
        if (currentItem == null) {
            return List.of();
        }
        Inventory inventory = topInventory ? view.getTopInventory() : view.getBottomInventory();
        int amount = currentItem.getAmount();
        int size = view.getTopInventory().getSize() + 9 * 4;
        List<ItemTransaction.InventoryPosition> positions = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            int rawPos;
            if (topInventory) {
                rawPos = i;
            } else {
                rawPos = size - 1 - i;
            }
            if (inventory != view.getInventory(rawPos)) {
                continue;
            }
            ItemStack item = view.getItem(rawPos);
            if (item == null || item.isEmpty()) {
                positions.add(new ItemTransaction.RawPosition(rawPos));
                return positions;
            }
            if (!item.isSimilar(currentItem) || item.getMaxStackSize() <= item.getAmount()) {
                continue;
            }
            positions.add(new ItemTransaction.RawPosition(rawPos));
            amount -= item.getMaxStackSize() - item.getAmount();
            if (amount <= 0) {
                break;
            }
        }
        return positions;
    }

    private static ItemTransactionEvent<?> eventFromStructure(InventoryAccessible<ItemStack, Inventory> inventoryAccessible,
                                                              Map<ItemTransactionEvent<?>, CompletableFuture<Optional<InventoryExtractionReceipt>>> completions,
                                                              ItemTransaction.InventoryPosition from, ItemTransaction.InventoryPosition to,
                                                              ItemStack item, boolean insertion, @Nullable Player player) {
        ItemTransaction transaction = new ItemTransaction(from, to, item, insertion);
        Optional<Brew> brewOptional = BrewAdapterAccess.fromItem(item)
                .map(inventoryAccessible::initializeBrew);
        if (player != null) {
            brewOptional = brewOptional
                    .map(brew -> brew.witModifiedLastStep(step ->
                            step instanceof BrewingStep.AuthoredStep<?> authoredStep && !authoredStep.isCompleted()
                                    ? authoredStep.withBrewersReplaced(new LinkedList<>()) : step
                    ))
                    .map(brew -> brew.witModifiedLastStep(step ->
                            step instanceof BrewingStep.AuthoredStep<?> authoredStep
                                    ? authoredStep.withBrewer(player.getUniqueId()) : step
                    ));
        }
        var completion = new CompletableFuture<Optional<InventoryExtractionReceipt>>();
        ItemTransactionEvent<?> event;
        if (inventoryAccessible instanceof BukkitDistillery distillery) {
            dev.jsinco.brewery.api.util.CancelState cancelState = brewOptional.isEmpty() ? new dev.jsinco.brewery.api.util.CancelState.Cancelled() :
                    player == null || player.hasPermission("brewery.distillery.access") ? new dev.jsinco.brewery.api.util.CancelState.Allowed() :
                    new dev.jsinco.brewery.api.util.CancelState.PermissionDenied(Component.translatable("tbp.distillery.access-denied"));
            event = insertion ? new DistilleryInsertEvent(
                    distillery,
                    new ItemTransactionSession<>(transaction, brewOptional
                                                              .map(brew -> new ItemSource.BrewBasedSource(brew, new Brew.State.Brewing()))
                                                              .orElse(null)
                    ),
                    cancelState,
                    player
            ) : new DistilleryExtractEvent(
                    distillery,
                    new ItemTransactionSession<>(transaction, brewOptional
                                                              .map(brew -> BrewAdapterAccess.toItem(brew, new Brew.State.Other()))
                                                              .map(ItemSource.ItemBasedSource::new)
                                                              .orElse(null)
                    ),
                    cancelState,
                    player, completion
            );
            if (!insertion) completions.put(event, completion);
            return event;
        }
        if (inventoryAccessible instanceof BukkitBarrel barrel) {
            dev.jsinco.brewery.api.util.CancelState cancelState = brewOptional.isEmpty() ? new dev.jsinco.brewery.api.util.CancelState.Cancelled() :
                    player == null || player.hasPermission("brewery.barrel.access") ? new dev.jsinco.brewery.api.util.CancelState.Allowed() :
                    new dev.jsinco.brewery.api.util.CancelState.PermissionDenied(Component.translatable("tbp.barrel.access-denied"));
            event = insertion ? new BarrelInsertEvent(
                    barrel,
                    new ItemTransactionSession<>(transaction, brewOptional
                                                              .map(brew -> new ItemSource.BrewBasedSource(brew, new Brew.State.Brewing()))
                                                              .orElse(null)
                    ),
                    cancelState,
                    player
            ) : new BarrelExtractEvent(
                    barrel,
                    new ItemTransactionSession<>(transaction, brewOptional
                                                              .map(brew -> BrewAdapterAccess.toItem(brew, new Brew.State.Other()))
                                                              .map(ItemSource.ItemBasedSource::new)
                                                              .orElse(null)),
                    cancelState,
                    player, completion
            );
            if (!insertion) completions.put(event, completion);
            return event;
        }
        throw new IllegalStateException("Unknown structure: " + inventoryAccessible);
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent dragEvent) {
        InventoryAccessible<ItemStack, Inventory> inventoryAccessible = registry.getFromInventory(dragEvent.getInventory());
        if (inventoryAccessible == null) {
            return;
        }
        if (atomicMovePending(inventoryAccessible)) {
            dragEvent.setCancelled(true);
            return;
        }
        InventoryView inventoryView = dragEvent.getView();
        var completions = new HashMap<ItemTransactionEvent<?>, CompletableFuture<Optional<InventoryExtractionReceipt>>>();
        List<? extends ItemTransactionEvent<?>> transactionEvents = dragEvent.getNewItems()
                .entrySet()
                .stream()
                .filter(entry -> dragEvent.getInventory() == inventoryView.getInventory(entry.getKey()))
                .map(entry -> eventFromStructure(
                        inventoryAccessible,
                        completions,
                        new ItemTransaction.Cursor(),
                        new ItemTransaction.RawPosition(entry.getKey()),
                        entry.getValue(),
                        true,
                        dragEvent.getWhoClicked() instanceof Player player ? player : null
                )).toList();
        if (transactionEvents.isEmpty()) {
            return;
        }
        Optional<Runnable> deferredRelease = reserveDeferredPublication(inventoryAccessible);
        if (deferredRelease.isEmpty()) {
            dragEvent.setCancelled(true);
            return;
        }
        Runnable release = deferredRelease.get();
        try {
            List<dev.jsinco.brewery.api.util.CancelState> cancelled = transactionEvents.stream()
                    .filter(transactionEvent -> !transactionEvent.callEvent())
                    .map(ItemTransactionEvent::getCancelState)
                    .toList();
            if (!cancelled.isEmpty()) {
                cancelled.stream()
                        .filter(dev.jsinco.brewery.api.util.CancelState.PermissionDenied.class::isInstance)
                        .map(dev.jsinco.brewery.api.util.CancelState.PermissionDenied.class::cast)
                        .map(dev.jsinco.brewery.api.util.CancelState.PermissionDenied::message)
                        .forEach(dragEvent.getWhoClicked()::sendMessage);
                dragEvent.setCancelled(true);
                release.run();
                return;
            }

            scheduleDeferredPublication(
                    dragEvent.getWhoClicked(),
                    () -> displayEventResult(inventoryView, transactionEvents, completions),
                    release
            );
        } catch (RuntimeException | Error failure) {
            release.run();
            throw failure;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryMoveItem(InventoryMoveItemEvent event) {
        Optional<InventoryAccessible<ItemStack, Inventory>> source = Optional.ofNullable(registry.getFromInventory(event.getSource()));
        Optional<InventoryAccessible<ItemStack, Inventory>> destination = Optional.ofNullable(registry.getFromInventory(event.getDestination()));
        if (source.filter(InventoryEventListener::atomicMovePending).isPresent()
                || destination.filter(InventoryEventListener::atomicMovePending).isPresent()) {
            event.setCancelled(true);
            return;
        }
        Optional<InventoryAccessible<ItemStack, Inventory>> both = destination.or(() -> source);
        if (!Config.config().automation()) {
            both.ifPresent(ignored -> event.setCancelled(true));
            return;
        }
        if (both.isEmpty()) {
            return;
        }
        InventoryAccessible<ItemStack, Inventory> inventoryAccessible = both.get();
        var completions = new HashMap<ItemTransactionEvent<?>, CompletableFuture<Optional<InventoryExtractionReceipt>>>();
        ItemTransactionEvent<?> transactionEvent = eventFromStructure(
                inventoryAccessible,
                completions,
                new ItemTransaction.FirstInventoryPosition(source.isPresent()),
                new ItemTransaction.FirstInventoryPosition(destination.isPresent()),
                event.getItem(),
                destination.isPresent(),
                null
        );
        if (!transactionEvent.callEvent()) {
            settleExtractions(completions);
            event.setCancelled(true);
            return;
        }
        ItemSource result = transactionEvent.getTransactionSession().getResult();
        if (result == null) {
            settleExtractions(completions);
            event.setCancelled(true);
            return;
        }
        ItemStack output = result.get();
        ItemStack input = event.getItem().clone();
        int sourceBefore = countSimilar(event.getSource(), input);
        int destinationBefore = countSimilar(event.getDestination(), output);
        event.setItem(output);
        if (completions.isEmpty()) return;
        var location = event.getDestination().getLocation();
        if (location == null) { settleExtractions(completions); return; }
        try {
            TheBrewingProject.getInstance().getServer().getRegionScheduler().run(
                    TheBrewingProject.getInstance(), location, ignored -> {
                        try {
                            if (!event.isCancelled()
                                    && countSimilar(event.getSource(), input) <= sourceBefore - input.getAmount()
                                    && countSimilar(event.getDestination(), output) >= destinationBefore + output.getAmount()) {
                                completions.get(transactionEvent).complete(Optional.of(new InventoryExtractionReceipt(
                                        BrewAdapterAccess.fromItem(input), output)));
                            }
                        } catch (RuntimeException | Error failure) {
                            completions.values().forEach(completion -> completion.completeExceptionally(failure));
                            throw failure;
                        } finally { settleExtractions(completions); }
                    });
        } catch (RuntimeException | Error failure) {
            completions.values().forEach(completion -> completion.completeExceptionally(failure));
            throw failure;
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryPickupItem(InventoryPickupItemEvent event) {
        InventoryAccessible<ItemStack, Inventory> inventoryAccessible = registry.getFromInventory(event.getInventory());
        if (inventoryAccessible != null && atomicMovePending(inventoryAccessible)) {
            event.setCancelled(true);
            return;
        }
        if (event.getItem().getPersistentDataContainer().has(PukeNamedExecutable.PUKE_ITEM)) {
            event.setCancelled(true);
        }
    }

    private static int countSimilar(Inventory inventory, ItemStack sample) {
        int amount = 0;
        for (ItemStack item : inventory.getContents()) {
            if (item != null && item.isSimilar(sample)) amount += item.getAmount();
        }
        return amount;
    }

    private static void settleExtractions(Map<ItemTransactionEvent<?>, CompletableFuture<Optional<InventoryExtractionReceipt>>> completions) {
        completions.values().forEach(completion -> completion.complete(Optional.empty()));
    }

    private static boolean atomicMovePending(InventoryAccessible<ItemStack, Inventory> inventoryAccessible) {
        return inventoryAccessible instanceof BukkitDistillery distillery && distillery.isAtomicMovePending();
    }

    private static Optional<Runnable> reserveDeferredPublication(
            InventoryAccessible<ItemStack, Inventory> inventoryAccessible) {
        if (inventoryAccessible instanceof BukkitDistillery distillery) {
            return distillery.reserveDeferredInventoryPublication();
        }
        return Optional.of(() -> { });
    }

    private static void scheduleDeferredPublication(
            org.bukkit.entity.HumanEntity viewer, Runnable publication, Runnable release) {
        var scheduled = viewer.getScheduler().run(
                TheBrewingProject.getInstance(),
                ignored -> {
                    try {
                        publication.run();
                    } finally {
                        release.run();
                    }
                },
                release
        );
        if (scheduled == null) release.run();
    }
}
