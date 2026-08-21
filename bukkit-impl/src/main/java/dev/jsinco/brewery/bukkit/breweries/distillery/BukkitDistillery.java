package dev.jsinco.brewery.bukkit.breweries.distillery;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.brew.BrewingStep;
import dev.jsinco.brewery.api.breweries.Distillery;
import dev.jsinco.brewery.api.breweries.DistilleryAccess;
import dev.jsinco.brewery.api.breweries.DistilleryProgress;
import dev.jsinco.brewery.api.moment.Moment;
import dev.jsinco.brewery.api.structure.MaterialTag;
import dev.jsinco.brewery.api.structure.StructureMeta;
import dev.jsinco.brewery.api.util.CancelState;
import dev.jsinco.brewery.api.util.Holder;
import dev.jsinco.brewery.api.util.HolderProviderHolder;
import dev.jsinco.brewery.api.util.Logger;
import dev.jsinco.brewery.api.util.Pair;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import dev.jsinco.brewery.brew.DistillStepImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.BukkitAdapter;
import dev.jsinco.brewery.bukkit.api.event.process.BrewDistillEvent;
import dev.jsinco.brewery.bukkit.api.event.structure.DistilleryDestroyEvent;
import dev.jsinco.brewery.bukkit.brew.BrewAdapterAccess;
import dev.jsinco.brewery.bukkit.breweries.BrewInventoryImpl;
import dev.jsinco.brewery.bukkit.database.SessionTypes;
import dev.jsinco.brewery.bukkit.database.distillery.DistillerySession;
import dev.jsinco.brewery.bukkit.structure.BreweryStructure;
import dev.jsinco.brewery.bukkit.structure.PlacedBreweryStructure;
import dev.jsinco.brewery.bukkit.util.BlockUtil;
import dev.jsinco.brewery.bukkit.util.LocationUtil;
import dev.jsinco.brewery.bukkit.util.SoundPlayer;
import dev.jsinco.brewery.bukkit.util.VectorUtil;
import dev.jsinco.brewery.configuration.Config;
import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.util.MessageUtil;
import net.kyori.adventure.sound.Sound;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.joml.Vector3i;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class BukkitDistillery implements Distillery<BukkitDistillery, ItemStack, Inventory>, DistilleryAccess, DistilleryProgress {

    private final PlacedBreweryStructure<BukkitDistillery> structure;
    private long startTime;
    private final BrewInventoryImpl mixture;
    private final BrewInventoryImpl distillate;
    private boolean dirty = true;
    private final Set<BreweryLocation> mixtureContainerLocations = new HashSet<>();
    private final Set<BreweryLocation> distillateContainerLocations = new HashSet<>();
    private long recentlyAccessed = -1L;
    private volatile boolean atomicMovePending;
    private volatile int deferredInventoryPublications;
    private volatile boolean durablyConsumed;

    public BukkitDistillery(@NonNull PlacedBreweryStructure<BukkitDistillery> structure) {
        this(structure, TheBrewingProject.getInstance().getTime());
    }

    public BukkitDistillery(@NonNull PlacedBreweryStructure<BukkitDistillery> structure, long startTime) {
        this.structure = structure;
        this.startTime = startTime;
        BreweryLocation unique = structure.getUnique();
        this.mixture = new BrewInventoryImpl(Component.translatable("tbp.distillery.gui-title.mixture"), structure.getStructure().getMeta(StructureMeta.INVENTORY_SIZE), new DistilleryBrewPersistenceHandler(unique, false));
        this.distillate = new BrewInventoryImpl(Component.translatable("tbp.distillery.gui-title.distillate"), structure.getStructure().getMeta(StructureMeta.INVENTORY_SIZE), new DistilleryBrewPersistenceHandler(unique, true));
    }

    @Override
    public CancelState open(@NonNull BreweryLocation location, Holder.@NonNull Player playerHolder) {
        if (isAtomicMovePending()) {
            return new CancelState.Cancelled();
        }
        checkDirty();
        Player player = BukkitAdapter.toPlayer(playerHolder).orElse(null);
        if (player == null) {
            return new CancelState.Cancelled();
        }
        if (mixtureContainerLocations.contains(location)) {
            playInteractionEffects(location, player);
            return openInventory(mixture, player);
        }
        if (distillateContainerLocations.contains(location)) {
            playInteractionEffects(location, player);
            return openInventory(distillate, player);
        }
        return new CancelState.Cancelled();
    }

    @Override
    public boolean open(@NonNull BreweryLocation breweryLocation, @NonNull UUID playerUuid) {
        Optional<Holder.Player> playerHolder = HolderProviderHolder.instance().player(playerUuid);
        CancelState cancelState = playerHolder
                .map(player -> open(breweryLocation, player))
                .orElseGet(CancelState.Cancelled::new);
        return switch (cancelState) {
            case CancelState.Cancelled ignored -> false;
            case CancelState.Allowed ignored -> true;
            case CancelState.PermissionDenied(Component message) -> {
                playerHolder.flatMap(BukkitAdapter::toPlayer)
                        .ifPresent(player -> player.sendMessage(message));
                yield false;
            }
        };
    }

    @Override
    public void close(boolean silent) {
        if (isAtomicMovePending()) {
            return;
        }
        Stream.of(mixture, distillate).forEach(inventory -> {
                    inventory.updateBrewsFromInventory();
                    inventory.getInventory().clear();
                }
        );
    }

    private void playInteractionEffects(BreweryLocation location, Player player) {
        BukkitAdapter.toWorld(location)
                .ifPresent(world -> SoundPlayer.playSoundEffect(
                        Config.config().sounds().distilleryAccess(),
                        Sound.Source.BLOCK,
                        world, location.x() + 0.5, location.y() + 0.5, location.z() + 0.5
                ));
        BlockUtil.playWobbleEffect(location, player);
    }

    private CancelState openInventory(BrewInventoryImpl inventory, Player player) {
        if (!player.hasPermission("brewery.distillery.access")) {
            return new CancelState.PermissionDenied(Component.translatable("tbp.distillery.access-denied"));
        }
        if (inventoryUnpopulated()) {
            mixture.updateInventoryFromBrews();
            distillate.updateInventoryFromBrews();
        }
        this.recentlyAccessed = TheBrewingProject.getInstance().getTime();
        TheBrewingProject.getInstance().getBreweryRegistry().registerOpened(this);
        player.openInventory(inventory.getInventory());
        return new CancelState.Allowed();
    }

    @Override
    public boolean inventoryAllows(@NonNull UUID playerUuid, @NonNull ItemStack item) {
        Player player = Bukkit.getPlayer(playerUuid);
        if (player == null) {
            return false;
        }
        if (!player.hasPermission("brewery.distillery.access")) {
            MessageUtil.message(player, "tbp.distillery.access-denied");
            return false;
        }
        return inventoryAllows(item);
    }

    @Override
    public boolean inventoryAllows(@NonNull ItemStack item) {
        return BrewAdapterAccess.fromItem(item).isPresent();
    }

    @Override
    public Set<Inventory> getInventories() {
        return Set.of(mixture.getInventory(), distillate.getInventory());
    }

    /**
     * Made to avoid chunk access on startup
     */
    private void checkDirty() {
        if (!dirty) {
            return;
        }
        dirty = false;
        BreweryStructure breweryStructure = structure.getStructure();

        // Locate key structure parts from reading from the world, try to prioritize large features first
        if (breweryStructure.hasMeta(StructureMeta.DISTILLATE_MATERIAL_TAG)) {
            MaterialTag distillateMaterialTag = breweryStructure.getMeta(StructureMeta.DISTILLATE_MATERIAL_TAG);
            if (breweryStructure.hasMeta(StructureMeta.MIXTURE_MATERIAL_TAG)) {
                MaterialTag mixtureMaterialTag = breweryStructure.getMeta(StructureMeta.MIXTURE_MATERIAL_TAG);
                if (mixtureMaterialTag.volume() > distillateMaterialTag.volume()) {
                    List<BreweryLocation> mixturePositions = findMaterialRegion(mixtureMaterialTag, List.of());
                    mixtureContainerLocations.addAll(mixturePositions);
                    distillateContainerLocations.addAll(findMaterialRegion(distillateMaterialTag, mixturePositions));
                } else {
                    List<BreweryLocation> distillatePositions = findMaterialRegion(distillateMaterialTag, List.of());
                    distillateContainerLocations.addAll(distillatePositions);
                    mixtureContainerLocations.addAll(findMaterialRegion(mixtureMaterialTag, distillatePositions));
                }
                return;
            }
            distillateContainerLocations.addAll(findMaterialRegion(distillateMaterialTag, List.of()));
        }
        if (breweryStructure.hasMeta(StructureMeta.MIXTURE_MATERIAL_TAG)) {
            MaterialTag mixtureMaterialTag = breweryStructure.getMeta(StructureMeta.MIXTURE_MATERIAL_TAG);
            mixtureContainerLocations.addAll(findMaterialRegion(mixtureMaterialTag, List.of()));
        }
        BreweryLocation worldOrigin = BukkitAdapter.toBreweryLocation(structure.getWorldOrigin());
        if (breweryStructure.hasMeta(StructureMeta.DISTILLATE_ACCESS_POINTS)) {
            breweryStructure.getMeta(StructureMeta.DISTILLATE_ACCESS_POINTS)
                    .elements()
                    .stream()
                    .map(VectorUtil::toJoml)
                    .map(vector -> VectorUtil.transform(vector, structure.getTransformation()))
                    .map(VectorUtil::toBreweryVector)
                    .map(worldOrigin::add)
                    .forEach(distillateContainerLocations::add);
        }
        if (breweryStructure.hasMeta(StructureMeta.MIXTURE_ACCESS_POINTS)) {
            breweryStructure.getMeta(StructureMeta.MIXTURE_ACCESS_POINTS)
                    .elements()
                    .stream()
                    .map(VectorUtil::toJoml)
                    .map(vector -> VectorUtil.transform(vector, structure.getTransformation()))
                    .map(VectorUtil::toBreweryVector)
                    .map(worldOrigin::add)
                    .forEach(mixtureContainerLocations::add);
        }
    }

    private List<BreweryLocation> findMaterialRegion(MaterialTag tag, List<BreweryLocation> blackList) {
        Set<Material> materials = tag.materials().stream()
                .map(BukkitAdapter::toMaterial)
                .collect(Collectors.toSet());
        List<BreweryLocation> matchingPositions = structure.positions().stream()
                .map(BukkitAdapter::toBlock)
                .flatMap(Optional::stream)
                .filter(block -> materials.contains(block.getType()))
                .map(BukkitAdapter::toBreweryLocation)
                .toList();
        List<BreweryLocation> output = new ArrayList<>();
        Vector3i region = new Vector3i(tag.xRegion(), tag.yRegion(), tag.zRegion());
        Vector3i transformedRegion = VectorUtil.transform(region, structure.getTransformation());
        for (BreweryLocation matchingPosition : matchingPositions) {
            List<BreweryLocation> found = findInSelection(matchingPosition, transformedRegion, matchingPositions);
            if (blackList.stream().anyMatch(found::contains)) {
                continue;
            }
            output.addAll(found);
        }
        return output;
    }

    private List<BreweryLocation> findInSelection(BreweryLocation startingPoint, Vector3i region, List<BreweryLocation> matchingPositions) {
        List<BreweryLocation> output = new ArrayList<>();
        for (int dx = 0; dx < Math.abs(region.x()); dx++) {
            for (int dy = 0; dy < Math.abs(region.y()); dy++) {
                for (int dz = 0; dz < Math.abs(region.z()); dz++) {
                    BreweryLocation relative = startingPoint.add(dx, dy, dz);
                    if (!matchingPositions.contains(relative)) {
                        return List.of();
                    }
                    output.add(relative);
                }
            }
        }
        return output;
    }

    private boolean shouldUnpopulateInventory() {
        return recentlyAccessed == -1L || recentlyAccessed + Moment.SECOND <= TheBrewingProject.getInstance().getTime();
    }

    private boolean inventoryUnpopulated() {
        return recentlyAccessed == -1L;
    }

    public void tick() {
        if (isAtomicMovePending() || !ownsLiveLocation(structure.getUnique())) {
            return;
        }
        BreweryLocation unique = getStructure().getUnique();
        long timeProcessed = getTimeProcessed();
        if (timeProcessed < 0) {
            resetStartTime();
            return;
        }
        long processTime = getProcessTime();
        int processedBrews = (int) ((timeProcessed / processTime) * getStructure().getStructure().getMeta(StructureMeta.PROCESS_AMOUNT));
        int brewAmount = mixture.brewAmount();
        if (brewAmount < processedBrews
                || distillate.isFull()
                || !BlockUtil.isChunkLoaded(unique)) {
            return;
        }
        boolean playSound = timeProcessed % processTime == 0 && timeProcessed > 0;
        long particleEffectInterval = Math.max(processTime / 4L, 10L);
        boolean playParticles = timeProcessed % particleEffectInterval < 5 && brewAmount > processedBrews;
        // Still dispatch while dirty, checkDirty() has to run its initial structure scan once
        if (!dirty && !playSound && !playParticles) {
            return;
        }
        BukkitAdapter.scheduleIfLoaded(unique, TheBrewingProject.getInstance(), location -> {
            if (isAtomicMovePending() || !ownsLiveLocation(unique)) {
                return;
            }
            checkDirty();
            if (playSound) {
                SoundPlayer.playSoundEffect(
                        Config.config().sounds().distilleryProcess(),
                        Sound.Source.BLOCK,
                        location.getWorld(), unique.x() + 0.5, unique.y() + 0.5, unique.z() + 0.5
                );
            }
            if (playParticles) {
                distillateContainerLocations.stream()
                        .map(BukkitAdapter::toLocation)
                        .flatMap(Optional::stream)
                        .map(containerLocation -> containerLocation.add(0.5, 1.3, 0.5))
                        .forEach(containerLocation -> containerLocation.getWorld().spawnParticle(Particle.ENTITY_EFFECT, containerLocation, 2, Color.WHITE));
            }
        });
    }

    public void tickInventory() {
        if (isAtomicMovePending() || !ownsLiveLocation(structure.getUnique())) {
            return;
        }
        checkDirty();
        if (recentlyAccessed == -1L) {
            return;
        }
        if (shouldUnpopulateInventory()) {
            close(false);
            Bukkit.getAsyncScheduler().runNow(TheBrewingProject.getInstance(), ignored ->
                    TheBrewingProject.getInstance().getBreweryRegistry().unregisterOpened(this)
            );

            // Distilling results can be computed later on
            this.recentlyAccessed = -1L;
            return;
        }
        if (!mixture.getInventory().getViewers().isEmpty() || !distillate.getInventory().getViewers().isEmpty()) {
            this.recentlyAccessed = TheBrewingProject.getInstance().getTime();
        }
        long timeProcessed = getTimeProcessed();
        long processTime = getProcessTime();
        // Process has changed one meta tick, to avoid running a sound if the mixture inventory changed
        if (timeProcessed < processTime - 1 || mixture.getInventory().isEmpty()) {
            return;
        }
        boolean hasChanged = mixture.updateBrewsFromInventory();
        distillate.updateBrewsFromInventory();
        if (hasChanged) {
            resetStartTime();
            return;
        }
        if (timeProcessed < processTime) {
            return;
        }
        if (transferItems((int) (getStructure().getStructure().getMeta(StructureMeta.PROCESS_AMOUNT)
                * (timeProcessed / processTime)))) {
            // Once a batch is deferred or starts its atomic receipt, no GUI or timer publication
            // may cross that reservation. A true durable publication performs both exactly once.
            return;
        }
        distillate.updateInventoryFromBrews();
        mixture.updateInventoryFromBrews();
        resetStartTime();
    }

    @Override
    public Optional<Inventory> access(@NonNull BreweryLocation breweryLocation) {
        if (isAtomicMovePending()) {
            return Optional.empty();
        }
        checkDirty();
        if (!mixtureContainerLocations.contains(breweryLocation) && !distillateContainerLocations.contains(breweryLocation)) {
            return Optional.empty();
        }
        if (inventoryUnpopulated()) {
            mixture.updateInventoryFromBrews();
            distillate.updateInventoryFromBrews();
            TheBrewingProject.getInstance().getBreweryRegistry().registerOpened(this);
        }
        this.recentlyAccessed = TheBrewingProject.getInstance().getTime();
        if (mixtureContainerLocations.contains(breweryLocation)) {
            return Optional.of(mixture.getInventory());
        }
        return Optional.of(distillate.getInventory());
    }

    @Override
    public Brew initializeBrew(Brew brew) {
        if (brew.lastStep() instanceof BrewingStep.Distill) {
            return brew;
        }
        return brew.withStep(new DistillStepImpl(0));
    }

    @Override
    public long getTimeProcessed() {
        return TheBrewingProject.getInstance().getTime() - startTime;
    }

    private void resetStartTime() {
        startTime = TheBrewingProject.getInstance().getTime();
        try {
            TheBrewingProject.getInstance().getDatabase().startSession(SessionTypes.DISTILLERY_SESSION_TYPE)
                    .updateDistillery(this)
                    .exceptionally(Logger::logAndTrackErr);
        } catch (PersistenceException e) {
            Logger.logErr(e);
        }
    }

    @Override
    public boolean isProcessing() {
        return !mixture.isEmpty();
    }

    @Override
    public long getProcessTime() {
        return getStructure().getStructure().getMeta(StructureMeta.PROCESS_TIME);
    }

    @Override
    public int getProcessAmount() {
        return getStructure().getStructure().getMeta(StructureMeta.PROCESS_AMOUNT);
    }

    private boolean transferItems(int amount) {
        List<CompletableFuture<Boolean>> allCommitSignals = new ArrayList<>();
        List<CompletableFuture<Boolean>> acceptedCommitSignals = new ArrayList<>();
        boolean[] batchDeferred = {false};
        TransferPlan plan;
        try {
            plan = planTransferBatch(
                    mixture.getBrews(),
                    distillate.getBrews(),
                    amount,
                    (mixtureBrew, distillateBrew) -> {
                        CompletableFuture<Boolean> commitSignal = new CompletableFuture<>();
                        allCommitSignals.add(commitSignal);
                        BrewDistillEvent event = new BrewDistillEvent(
                                this, mixtureBrew, distillateBrew, commitSignal
                        );
                        boolean accepted = event.callEvent();
                        if (event.isBatchDeferred()) {
                            batchDeferred[0] = true;
                            return Optional.empty();
                        }
                        if (!accepted) {
                            commitSignal.complete(false);
                            return Optional.empty();
                        }
                        acceptedCommitSignals.add(commitSignal);
                        return Optional.of(event.getResult());
                    },
                    () -> batchDeferred[0]
            );
        } catch (RuntimeException | Error eventFailure) {
            completeCommitSignals(allCommitSignals, null, eventFailure);
            throw eventFailure;
        }
        if (completeDeferredBatch(plan, allCommitSignals)) {
            return true;
        }
        List<AtomicBrewMove> moves = plan.moves();
        if (moves.isEmpty()) {
            return false;
        }
        CompletableFuture<Boolean> batchCommit;
        try {
            batchCommit = moveBrewsAtomically(moves);
        } catch (RuntimeException | Error startFailure) {
            completeCommitSignals(acceptedCommitSignals, null, startFailure);
            Logger.logAndTrackErr(startFailure);
            return true;
        }
        batchCommit.whenComplete((committed, failure) -> {
            completeCommitSignals(acceptedCommitSignals, committed, failure);
            if (failure != null) {
                Logger.logAndTrackErr(unwrapCompletionFailure(failure));
            } else if (!committed) {
                Logger.logErr("Atomic distillery transfer was rejected because its inventory state changed");
            }
        });
        return true;
    }

    static List<AtomicBrewMove> planTransfers(Brew[] mixtureBrews, Brew[] distillateBrews, int amount,
                                               BiFunction<Brew, Brew, Optional<Brew>> processEvent) {
        return planTransferBatch(
                mixtureBrews, distillateBrews, amount, processEvent, () -> false
        ).moves();
    }

    static TransferPlan planTransferBatch(Brew[] mixtureBrews, Brew[] distillateBrews, int amount,
                                           BiFunction<Brew, Brew, Optional<Brew>> processEvent,
                                           BooleanSupplier batchDeferred) {
        Queue<Pair<Brew, Integer>> brewsToTransfer = new LinkedList<>();
        List<AtomicBrewMove> moves = new ArrayList<>();
        for (int i = 0; i < mixtureBrews.length; i++) {
            if (mixtureBrews[i] == null) {
                continue;
            }
            if (amount-- <= 0) {
                break;
            }
            brewsToTransfer.add(new Pair<>(mixtureBrews[i], i));
        }
        for (int i = 0; i < distillateBrews.length; i++) {
            if (distillateBrews[i] != null) {
                continue;
            }
            if (brewsToTransfer.isEmpty()) {
                break;
            }
            Pair<Brew, Integer> nextBrewToTransfer = brewsToTransfer.poll();
            Brew mixtureBrew = nextBrewToTransfer.first();
            Brew distillateBrew = mixtureBrew.withLastStep(
                    BrewingStep.Distill.class,
                    BrewingStep.Distill::incrementRuns,
                    () -> new DistillStepImpl(1));
            int destination = i;
            Optional<Brew> eventResult = processEvent.apply(mixtureBrew, distillateBrew);
            if (batchDeferred.getAsBoolean()) {
                return new TransferPlan(List.of(), true);
            }
            eventResult.ifPresent(result ->
                    moves.add(new AtomicBrewMove(
                            nextBrewToTransfer.second(),
                            destination,
                            mixtureBrew,
                            result
                    ))
            );
        }
        return new TransferPlan(List.copyOf(moves), false);
    }

    static boolean completeDeferredBatch(TransferPlan plan,
                                         List<? extends CompletableFuture<Boolean>> commitSignals) {
        if (!plan.deferred()) {
            return false;
        }
        completeCommitSignals(commitSignals, false, null);
        return true;
    }

    record TransferPlan(List<AtomicBrewMove> moves, boolean deferred) {
    }

    static void completeCommitSignals(List<? extends CompletableFuture<Boolean>> signals,
                                      Boolean committed, Throwable failure) {
        if (failure != null) {
            Throwable cause = unwrapCompletionFailure(failure);
            signals.forEach(signal -> signal.completeExceptionally(cause));
            return;
        }
        boolean didCommit = Boolean.TRUE.equals(committed);
        signals.forEach(signal -> signal.complete(didCommit));
    }

    @Override
    public CompletableFuture<Boolean> moveBrewsAtomically(@NonNull List<AtomicBrewMove> requestedMoves) {
        List<AtomicBrewMove> moves = List.copyOf(requestedMoves);
        if (moves.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        // BrewInventory is the durable model. The backing Bukkit inventories are deliberately not
        // read here: TBP clears them when a distillery is unpopulated, and treating that empty GUI
        // as authoritative would erase dormant brews. The caller's expected source plus the
        // database precondition protect this hand-off instead.
        if (!reserveAtomicMove(moves)) {
            return CompletableFuture.completedFuture(false);
        }

        long committedStartTime = TheBrewingProject.getInstance().getTime();
        CompletableFuture<Boolean> persistence;
        try {
            persistence = TheBrewingProject.getInstance().getDatabase()
                    .startSession(SessionTypes.DISTILLERY_SESSION_TYPE)
                    .moveBrewsAtomically(structure.getUnique(), moves, committedStartTime);
        } catch (RuntimeException | PersistenceException failure) {
            releaseAtomicMove();
            return CompletableFuture.failedFuture(failure);
        }

        return publishAtomicMutation(persistence, () -> applyCommittedMoves(moves, committedStartTime));
    }

    @Override
    public CompletableFuture<Boolean> removeMixtureBrewsAtomically(
            @NonNull List<AtomicBrewRemoval> requestedRemovals) {
        List<AtomicBrewRemoval> removals = List.copyOf(requestedRemovals);
        if (removals.isEmpty()) {
            return CompletableFuture.completedFuture(false);
        }

        if (!reserveAtomicRemoval(removals)) {
            return CompletableFuture.completedFuture(false);
        }

        CompletableFuture<Boolean> persistence;
        try {
            persistence = TheBrewingProject.getInstance().getDatabase()
                    .startSession(SessionTypes.DISTILLERY_SESSION_TYPE)
                    .removeMixtureBrewsAtomically(structure.getUnique(), removals);
        } catch (RuntimeException | PersistenceException failure) {
            releaseAtomicMove();
            return CompletableFuture.failedFuture(failure);
        }

        return publishAtomicMutation(persistence, () -> applyCommittedRemovals(removals));
    }

    @Override
    public boolean atomicConsumptionPreservesWorldGeometry() {
        return true;
    }

    @Override
    public Optional<AtomicConsumption> prepareAtomicConsumption(
            @NonNull BreweryLocation requestedLocation,
            Holder.@Nullable Player actor) {
        Objects.requireNonNull(requestedLocation, "requestedLocation");
        if (!Bukkit.isOwnedByCurrentRegion(structure.getWorldOrigin())) {
            throw new IllegalStateException(
                    "Atomic distillery consumption must start on the owning region"
            );
        }
        if (isAtomicMovePending() || durablyConsumed || !ownsLiveLocation(requestedLocation)) {
            return Optional.empty();
        }

        CompletableFuture<Boolean> destroyCommitSignal = new CompletableFuture<>();
        try {
            Player player = actor == null ? null : BukkitAdapter.toPlayer(actor).orElse(null);
            CancelState initialState = actor == null
                    ? new CancelState.Allowed()
                    : player != null && player.hasPermission("brewery.distillery.access")
                    ? new CancelState.Allowed()
                    : new CancelState.PermissionDenied(
                    Component.translatable("tbp.distillery.access-denied"));
            DistilleryDestroyEvent event = new DistilleryDestroyEvent(
                    initialState,
                    this,
                    player,
                    BukkitAdapter.toLocation(requestedLocation).orElse(structure.getWorldOrigin()),
                    calculateDestroyDrops(),
                    destroyCommitSignal
            );
            event.callEvent();
            if (event.isCancelled() || !reserveAtomicConsumption(requestedLocation)) {
                if (event.isCancelled()) {
                    event.getCancelState().sendMessage(player);
                }
                destroyCommitSignal.complete(false);
                return Optional.empty();
            }
        } catch (RuntimeException failure) {
            destroyCommitSignal.completeExceptionally(failure);
            throw failure;
        }

        return Optional.of(new PreparedAtomicConsumption(
                requestedLocation, destroyCommitSignal));
    }

    @Override
    public CompletableFuture<Boolean> consumeWithoutDropsAtomically(
            @NonNull BreweryLocation requestedLocation) {
        return prepareAtomicConsumption(requestedLocation, null)
                .map(AtomicConsumption::commit)
                .orElseGet(() -> CompletableFuture.completedFuture(false));
    }

    private CompletableFuture<Boolean> commitReservedConsumption(
            BreweryLocation requestedLocation,
            CompletableFuture<Boolean> destroyCommitSignal) {

        CompletableFuture<Boolean> persistence;
        try {
            persistence = TheBrewingProject.getInstance().getDatabase()
                    .startSession(SessionTypes.DISTILLERY_SESSION_TYPE)
                    .consumeDistilleryAtomically(structure.getUnique());
        } catch (RuntimeException | PersistenceException failure) {
            releaseAtomicMove();
            destroyCommitSignal.completeExceptionally(failure);
            return CompletableFuture.failedFuture(failure);
        }

        // A successfully consumed holder remains permanently reserved. Stale references must not
        // be able to write its old arrays back after its durable row and registries are gone.
        CompletableFuture<Boolean> result = publishAtomicMutation(
                persistence,
                () -> applyCommittedConsumption(requestedLocation),
                this::runLocally,
                () -> {
                    if (!durablyConsumed) {
                        releaseAtomicMove();
                    }
                }
        );
        result.whenComplete((committed, failure) -> {
            Throwable cause = unwrapCompletionFailure(failure);
            if (cause != null) {
                destroyCommitSignal.completeExceptionally(cause);
            } else {
                destroyCommitSignal.complete(Boolean.TRUE.equals(committed));
            }
        });
        return result;
    }

    private final class PreparedAtomicConsumption implements AtomicConsumption {

        private final BreweryLocation requestedLocation;
        private final CompletableFuture<Boolean> destroyCommitSignal;
        private boolean settled;

        private PreparedAtomicConsumption(
                BreweryLocation requestedLocation,
                CompletableFuture<Boolean> destroyCommitSignal) {
            this.requestedLocation = requestedLocation;
            this.destroyCommitSignal = destroyCommitSignal;
        }

        @Override
        public synchronized boolean isValid() {
            return !settled && ownsReservedHolder();
        }

        @Override
        public synchronized CompletableFuture<Boolean> commit() {
            if (settled) {
                return CompletableFuture.completedFuture(false);
            }
            final boolean valid;
            try {
                valid = ownsReservedHolder();
            } catch (RuntimeException | LinkageError failure) {
                // An ownership lookup failure cannot authorize a durable delete. Keep the holder
                // quarantined so a stale reference cannot become writable again.
                settled = true;
                destroyCommitSignal.completeExceptionally(failure);
                return CompletableFuture.failedFuture(failure);
            }
            if (!valid) {
                settled = true;
                if (atomicMovePending && !durablyConsumed) {
                    releaseAtomicMove();
                }
                destroyCommitSignal.complete(false);
                return CompletableFuture.completedFuture(false);
            }
            settled = true;
            return commitReservedConsumption(requestedLocation, destroyCommitSignal);
        }

        @Override
        public synchronized void abort() {
            if (settled) {
                return;
            }
            settled = true;
            releaseAtomicMove();
            destroyCommitSignal.complete(false);
        }

        private boolean ownsReservedHolder() {
            Object currentHolder = TheBrewingProject.getInstance().getPlacedStructureRegistry()
                    .getHolder(requestedLocation)
                    .orElse(null);
            return reservationOwnsHolder(
                    atomicMovePending, durablyConsumed, BukkitDistillery.this, currentHolder);
        }
    }

    static boolean reservationOwnsHolder(boolean pending, boolean consumed,
                                         Object expectedHolder, Object currentHolder) {
        return pending && !consumed && currentHolder == expectedHolder;
    }

    /**
     * Reserves the delayed publication that finalizes an accepted inventory event.
     * Atomic mutations and registry refreshes cannot begin until the returned idempotent release
     * action is run.
     */
    public Optional<Runnable> reserveDeferredInventoryPublication() {
        boolean reserved = TheBrewingProject.getInstance().getAtomicMutationGate()
                .reserve(this::beginDeferredInventoryPublication);
        if (!reserved) {
            return Optional.empty();
        }
        AtomicBoolean released = new AtomicBoolean();
        return Optional.of(() -> {
            if (released.compareAndSet(false, true)) {
                endDeferredInventoryPublication();
            }
        });
    }

    private synchronized boolean beginDeferredInventoryPublication() {
        if (atomicMovePending || deferredInventoryPublications > 0 || durablyConsumed
                || !ownsLiveLocation(structure.getUnique())) {
            return false;
        }
        deferredInventoryPublications++;
        return true;
    }

    private synchronized void endDeferredInventoryPublication() {
        if (deferredInventoryPublications <= 0) {
            throw new IllegalStateException("No deferred distillery inventory publication is pending");
        }
        deferredInventoryPublications--;
    }

    private CompletableFuture<Boolean> publishAtomicMutation(CompletableFuture<Boolean> persistence,
                                                              Runnable applyCommitted) {
        return publishAtomicMutation(
                persistence, applyCommitted, this::runLocally, this::releaseAtomicMove
        );
    }

    static CompletableFuture<Boolean> publishAtomicMutation(
            CompletableFuture<Boolean> persistence,
            Runnable applyCommitted,
            Function<Runnable, CompletableFuture<Void>> localRunner,
            Runnable releaseReservation) {
        CompletableFuture<Boolean> result = new CompletableFuture<>();
        persistence.whenComplete((committed, persistenceFailure) -> {
            Throwable cause = unwrapCompletionFailure(persistenceFailure);
            if (cause != null && !rollbackConfirmed(cause)) {
                // An ambiguous durable outcome must stay quarantined. Releasing the old in-memory
                // side could duplicate a move that actually committed. Restart reloads the
                // authoritative database state.
                result.completeExceptionally(cause);
                return;
            }

            CompletableFuture<Void> publication;
            try {
                publication = localRunner.apply(() -> {
                    if (cause == null && Boolean.TRUE.equals(committed)) {
                        // A committed database transaction is authoritative. If publication
                        // throws after changing only part of the live state, retaining the
                        // reservation is the only safe response; restart reloads the whole state.
                        applyCommitted.run();
                    }
                    releaseReservation.run();
                });
            } catch (RuntimeException schedulingFailure) {
                if (cause != null || !Boolean.TRUE.equals(committed)) {
                    // The durable state is known to be unchanged, so releasing the reservation
                    // does not need region-owned inventory access.
                    releaseReservation.run();
                }
                result.completeExceptionally(schedulingFailure);
                return;
            }

            publication.whenComplete((ignored, publicationFailure) -> {
                if (publicationFailure != null) {
                    result.completeExceptionally(unwrapCompletionFailure(publicationFailure));
                } else if (cause != null) {
                    result.completeExceptionally(cause);
                } else {
                    result.complete(Boolean.TRUE.equals(committed));
                }
            });
        });
        return result;
    }

    private boolean reserveAtomicMove(List<AtomicBrewMove> moves) {
        return TheBrewingProject.getInstance().getAtomicMutationGate()
                .reserve(() -> reserveAtomicMoveUnderLifecycleGate(moves));
    }

    private synchronized boolean reserveAtomicMoveUnderLifecycleGate(List<AtomicBrewMove> moves) {
        if (atomicMovePending || deferredInventoryPublications > 0
                || !ownsLiveLocation(structure.getUnique())
                || !matchesLiveState(moves)) {
            return false;
        }
        return suspendForAtomicMutation();
    }

    private boolean reserveAtomicRemoval(List<AtomicBrewRemoval> removals) {
        return TheBrewingProject.getInstance().getAtomicMutationGate()
                .reserve(() -> reserveAtomicRemovalUnderLifecycleGate(removals));
    }

    private synchronized boolean reserveAtomicRemovalUnderLifecycleGate(List<AtomicBrewRemoval> removals) {
        if (atomicMovePending || deferredInventoryPublications > 0
                || !ownsLiveLocation(structure.getUnique())
                || !matchesLiveRemovalState(removals)) {
            return false;
        }
        return suspendForAtomicMutation();
    }

    private boolean reserveAtomicConsumption(BreweryLocation requestedLocation) {
        return TheBrewingProject.getInstance().getAtomicMutationGate()
                .reserve(() -> reserveAtomicConsumptionUnderLifecycleGate(requestedLocation));
    }

    private synchronized boolean reserveAtomicConsumptionUnderLifecycleGate(
            BreweryLocation requestedLocation) {
        if (atomicMovePending || deferredInventoryPublications > 0 || durablyConsumed
                || !ownsLiveLocation(requestedLocation)) {
            return false;
        }
        return suspendForAtomicMutation();
    }

    private boolean ownsLiveLocation(BreweryLocation requestedLocation) {
        return TheBrewingProject.getInstance().getPlacedStructureRegistry()
                .getHolder(requestedLocation)
                .filter(holder -> holder == this)
                .isPresent();
    }

    private boolean suspendForAtomicMutation() {
        atomicMovePending = true;
        try {
            mixture.suspendPersistenceWrites();
            distillate.suspendPersistenceWrites();
            return true;
        } catch (RuntimeException failure) {
            if (mixture.persistenceWritesSuspended()) {
                mixture.resumePersistenceWrites();
            }
            if (distillate.persistenceWritesSuspended()) {
                distillate.resumePersistenceWrites();
            }
            atomicMovePending = false;
            throw failure;
        }
    }

    private boolean matchesLiveState(List<AtomicBrewMove> moves) {
        Brew[] mixtureBrews = mixture.getBrews();
        Brew[] distillateBrews = distillate.getBrews();
        Set<Integer> mixturePositions = new HashSet<>();
        Set<Integer> distillatePositions = new HashSet<>();
        for (AtomicBrewMove move : moves) {
            if (move.mixturePosition() >= mixtureBrews.length
                    || move.distillatePosition() >= distillateBrews.length
                    || !mixturePositions.add(move.mixturePosition())
                    || !distillatePositions.add(move.distillatePosition())
                    || !sameBrew(mixtureBrews[move.mixturePosition()], move.expectedMixture())
                    || distillateBrews[move.distillatePosition()] != null) {
                return false;
            }
        }
        return true;
    }

    private boolean matchesLiveRemovalState(List<AtomicBrewRemoval> removals) {
        Brew[] mixtureBrews = mixture.getBrews();
        Set<Integer> mixturePositions = new HashSet<>();
        for (AtomicBrewRemoval removal : removals) {
            if (removal.mixturePosition() >= mixtureBrews.length
                    || !mixturePositions.add(removal.mixturePosition())
                    || !sameBrew(mixtureBrews[removal.mixturePosition()], removal.expectedMixture())) {
                return false;
            }
        }
        return true;
    }

    private static boolean sameBrew(Brew first, Brew second) {
        return first != null && second != null
                && Objects.equals(first.getSteps(), second.getSteps())
                && Objects.equals(first.meta(), second.meta());
    }

    private void applyCommittedMoves(List<AtomicBrewMove> moves, long committedStartTime) {
        for (AtomicBrewMove move : moves) {
            mixture.applyCommitted(null, move.mixturePosition());
            distillate.applyCommitted(move.distillate(), move.distillatePosition());
        }
        mixture.updateInventoryFromBrews();
        distillate.updateInventoryFromBrews();
        startTime = committedStartTime;
    }

    private void applyCommittedRemovals(List<AtomicBrewRemoval> removals) {
        for (AtomicBrewRemoval removal : removals) {
            mixture.applyCommitted(null, removal.mixturePosition());
        }
        mixture.updateInventoryFromBrews();
    }

    private void applyCommittedConsumption(BreweryLocation requestedLocation) {
        Object currentHolder = TheBrewingProject.getInstance().getPlacedStructureRegistry()
                .getHolder(requestedLocation)
                .orElse(null);
        if (currentHolder != null && currentHolder != this) {
            // A reload may replace the live holder while database work is queued. Never let an
            // old completion unregister or clear the replacement; keep this operation
            // quarantined until authoritative reload instead.
            throw new IllegalStateException(
                    "Distillery holder changed before committed consumption publication"
            );
        }

        destroyWithoutDrops();
        TheBrewingProject.getInstance().getBreweryRegistry().unregisterOpened(this);
        TheBrewingProject.getInstance().getBreweryRegistry().unregisterInventory(this);
        TheBrewingProject.getInstance().getPlacedStructureRegistry().unregisterStructure(structure);
        // World geometry belongs to the initiating event. In particular, an explosion may cover
        // only part of the schematic and protection plugins may authorize an even smaller set.
        // Removing structure.positions() here would bypass both decisions. The acknowledged
        // caller removes its exact approved positions only after this durable publication.
        durablyConsumed = true;
    }

    private synchronized void releaseAtomicMove() {
        if (mixture.persistenceWritesSuspended()) {
            mixture.resumePersistenceWrites();
        }
        if (distillate.persistenceWritesSuspended()) {
            distillate.resumePersistenceWrites();
        }
        atomicMovePending = false;
    }

    private static boolean rollbackConfirmed(Throwable failure) {
        for (Throwable current = failure; current != null; current = current.getCause()) {
            if (current instanceof DistillerySession.AtomicMovePersistenceException atomicFailure) {
                return atomicFailure.rollbackConfirmed();
            }
        }
        return false;
    }

    private static Throwable unwrapCompletionFailure(Throwable failure) {
        Throwable current = failure;
        while (current instanceof CompletionException && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    @Override
    public boolean isAtomicMovePending() {
        return atomicMovePending || deferredInventoryPublications > 0;
    }

    /**
     * Ensures that the distillery's inventory is up-to-date before the distillery is destroyed.
     *
     * @return A snapshot of the brews that should drop from the distillery
     */
    public List<Brew> calculateDestroyDrops() {
        List<Brew> drops = new ArrayList<>();
        boolean inventoryUnpopulated = inventoryUnpopulated();
        for (BrewInventoryImpl distilleryInventory : List.of(distillate, mixture)) {
            if (!inventoryUnpopulated) {
                distilleryInventory.updateBrewsFromInventory();
            }
            drops.addAll(distilleryInventory.getBrewSnapshot());
        }
        return drops;
    }

    public void destroyWithoutDrops() {
        distillate.destroy();
        mixture.destroy();
    }

    @Override
    public void destroy(BreweryLocation breweryLocation) {
        if (isAtomicMovePending()) {
            return;
        }
        calculateDestroyDrops();
        List<Brew> drops = new ArrayList<>();
        drops.addAll(distillate.destroy());
        drops.addAll(mixture.destroy());
        LocationUtil.dropBrews(breweryLocation, drops);
    }

    public PlacedBreweryStructure<BukkitDistillery> getStructure() {
        return this.structure;
    }

    public long getStartTime() {
        return this.startTime;
    }

    public BrewInventoryImpl getMixture() {
        return this.mixture;
    }

    public BrewInventoryImpl getDistillate() {
        return this.distillate;
    }

    @Override
    public CompletableFuture<Void> runLocally(Runnable action) {
        return runLocally(
                Bukkit.isOwnedByCurrentRegion(structure.getWorldOrigin()),
                action,
                scheduledAction -> Bukkit.getRegionScheduler().run(
                        TheBrewingProject.getInstance(),
                        structure.getWorldOrigin(),
                        ignored -> scheduledAction.run()
                )
        );
    }

    static CompletableFuture<Void> runLocally(boolean ownedByCurrentRegion, Runnable action,
                                              Consumer<Runnable> scheduler) {
        CompletableFuture<Void> completableFuture = new CompletableFuture<>();
        Runnable completedAction = () -> {
            try {
                action.run();
                completableFuture.complete(null);
            } catch (Throwable failure) {
                completableFuture.completeExceptionally(failure);
            }
        };
        if (ownedByCurrentRegion) {
            completedAction.run();
            return completableFuture;
        }
        scheduler.accept(completedAction);
        return completableFuture;
    }
}
