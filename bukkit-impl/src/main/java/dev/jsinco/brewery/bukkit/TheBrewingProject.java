package dev.jsinco.brewery.bukkit;

import com.google.common.base.Preconditions;
import dev.faststats.bukkit.BukkitContext;
import dev.jsinco.brewery.api.brew.BrewManager;
import dev.jsinco.brewery.api.breweries.Tickable;
import dev.jsinco.brewery.api.config.Configuration;
import dev.jsinco.brewery.api.effect.modifier.ModifierManager;
import dev.jsinco.brewery.api.event.CustomEventRegistry;
import dev.jsinco.brewery.api.event.EventData;
import dev.jsinco.brewery.api.event.EventStepRegistry;
import dev.jsinco.brewery.api.ingredient.IngredientManager;
import dev.jsinco.brewery.api.ingredient.ResolvedIngredientManager;
import dev.jsinco.brewery.api.structure.MultiblockStructure;
import dev.jsinco.brewery.api.structure.StructureType;
import dev.jsinco.brewery.api.util.Logger;
import dev.jsinco.brewery.bukkit.api.TheBrewingProjectApi;
import dev.jsinco.brewery.bukkit.api.effect.DrunkEventManager;
import dev.jsinco.brewery.bukkit.api.event.AsyncRecipesLoadedEvent;
import dev.jsinco.brewery.bukkit.api.event.TBPReloadEvent;
import dev.jsinco.brewery.bukkit.api.integration.IntegrationTypes;
import dev.jsinco.brewery.bukkit.api.integration.ItemIntegration;
import dev.jsinco.brewery.bukkit.brew.BukkitBrewManager;
import dev.jsinco.brewery.bukkit.breweries.BreweryRegistry;
import dev.jsinco.brewery.bukkit.breweries.barrel.BukkitBarrel;
import dev.jsinco.brewery.bukkit.breweries.distillery.BukkitDistillery;
import dev.jsinco.brewery.bukkit.command.BreweryCommand;
import dev.jsinco.brewery.bukkit.configuration.serializer.BreweryLocationSerializer;
import dev.jsinco.brewery.bukkit.configuration.serializer.ColorSerializer;
import dev.jsinco.brewery.bukkit.configuration.serializer.IngredientInputSerializer;
import dev.jsinco.brewery.bukkit.configuration.serializer.IntegrationEventSerializer;
import dev.jsinco.brewery.bukkit.configuration.serializer.MaterialSerializer;
import dev.jsinco.brewery.bukkit.configuration.serializer.UncheckedIngredientSerializer;
import dev.jsinco.brewery.bukkit.database.SessionTypes;
import dev.jsinco.brewery.bukkit.effect.DrunkEventManagerImpl;
import dev.jsinco.brewery.bukkit.effect.event.ActiveEventsRegistry;
import dev.jsinco.brewery.bukkit.effect.event.DrunkEventExecutor;
import dev.jsinco.brewery.bukkit.ingredient.BukkitIngredientManager;
import dev.jsinco.brewery.bukkit.ingredient.ResolvedIngredientManagerImpl;
import dev.jsinco.brewery.bukkit.integration.IntegrationManagerImpl;
import dev.jsinco.brewery.bukkit.listener.BlockEventListener;
import dev.jsinco.brewery.bukkit.listener.BrewMigrationListener;
import dev.jsinco.brewery.bukkit.listener.EntityEventListener;
import dev.jsinco.brewery.bukkit.listener.InventoryEventListener;
import dev.jsinco.brewery.bukkit.listener.LegacyPlayerJoinListener;
import dev.jsinco.brewery.bukkit.listener.PlayerEventListener;
import dev.jsinco.brewery.bukkit.listener.PlayerJoinListener;
import dev.jsinco.brewery.bukkit.listener.PlayerWalkListener;
import dev.jsinco.brewery.bukkit.listener.WorldEventListener;
import dev.jsinco.brewery.bukkit.migration.Migrations;
import dev.jsinco.brewery.bukkit.migration.breweryx.BreweryXMigrationListener;
import dev.jsinco.brewery.bukkit.recipe.BukkitRecipeResultReader;
import dev.jsinco.brewery.bukkit.recipe.DefaultRecipeReader;
import dev.jsinco.brewery.bukkit.structure.BreweryStructureConfig;
import dev.jsinco.brewery.bukkit.structure.StructureMatcher;
import dev.jsinco.brewery.bukkit.structure.StructureRegistry;
import dev.jsinco.brewery.bukkit.structure.serializer.BreweryVectorListSerializer;
import dev.jsinco.brewery.bukkit.structure.serializer.BreweryVectorSerializer;
import dev.jsinco.brewery.bukkit.structure.serializer.MaterialHolderSerializer;
import dev.jsinco.brewery.bukkit.structure.serializer.MaterialTagSerializer;
import dev.jsinco.brewery.bukkit.structure.serializer.MaterialsSerializer;
import dev.jsinco.brewery.bukkit.structure.serializer.StructureMetaSerializer;
import dev.jsinco.brewery.bukkit.structure.serializer.StructureTypeSerializer;
import dev.jsinco.brewery.bukkit.structure.serializer.Vector3iSerializer;
import dev.jsinco.brewery.bukkit.util.BukkitIngredientUtil;
import dev.jsinco.brewery.bukkit.util.EventUtil;
import dev.jsinco.brewery.bukkit.util.color.ResourcePackColors;
import dev.jsinco.brewery.configuration.Config;
import dev.jsinco.brewery.configuration.DrunkenModifierSection;
import dev.jsinco.brewery.configuration.EventSection;
import dev.jsinco.brewery.configuration.IngredientsSection;
import dev.jsinco.brewery.configuration.OkaeriSerdesBuilder;
import dev.jsinco.brewery.configuration.locale.BreweryTranslator;
import dev.jsinco.brewery.configuration.serializers.BlockReplacementSerializer;
import dev.jsinco.brewery.configuration.serializers.ComponentSerializer;
import dev.jsinco.brewery.configuration.serializers.ConditionSerializer;
import dev.jsinco.brewery.configuration.serializers.ConsumableSerializer;
import dev.jsinco.brewery.configuration.serializers.CustomEventSerializer;
import dev.jsinco.brewery.configuration.serializers.DrunkenModifierSerializer;
import dev.jsinco.brewery.configuration.serializers.EventProbabilitySerializer;
import dev.jsinco.brewery.configuration.serializers.EventRegistrySerializer;
import dev.jsinco.brewery.configuration.serializers.EventStepSerializer;
import dev.jsinco.brewery.configuration.serializers.IntervalSerializer;
import dev.jsinco.brewery.configuration.serializers.LocaleSerializer;
import dev.jsinco.brewery.configuration.serializers.MinutesDurationSerializer;
import dev.jsinco.brewery.configuration.serializers.ModifierDisplaySerializer;
import dev.jsinco.brewery.configuration.serializers.ModifierExpressionSerializer;
import dev.jsinco.brewery.configuration.serializers.ModifierTooltipSerializer;
import dev.jsinco.brewery.configuration.serializers.NamedDrunkEventSerializer;
import dev.jsinco.brewery.configuration.serializers.ParticleDefinitionSerializer;
import dev.jsinco.brewery.configuration.serializers.RangeDSerializer;
import dev.jsinco.brewery.configuration.serializers.SecretKeySerializer;
import dev.jsinco.brewery.configuration.serializers.SoundDefinitionSerializer;
import dev.jsinco.brewery.configuration.serializers.TicksDurationSerializer;
import dev.jsinco.brewery.configuration.structure.StructureMatchers;
import dev.jsinco.brewery.database.PersistenceException;
import dev.jsinco.brewery.database.sql.DatabaseDriver;
import dev.jsinco.brewery.database.sql.SqlDatabase;
import dev.jsinco.brewery.effect.DrunksManagerImpl;
import dev.jsinco.brewery.effect.ModifierManagerImpl;
import dev.jsinco.brewery.effect.text.DrunkTextRegistry;
import dev.jsinco.brewery.format.TimeFormatRegistry;
import dev.jsinco.brewery.recipes.RecipeImpl;
import dev.jsinco.brewery.recipes.RecipeReader;
import dev.jsinco.brewery.recipes.RecipeRegistryImpl;
import dev.jsinco.brewery.structure.PlacedStructureRegistryImpl;
import dev.jsinco.brewery.util.ClassUtil;
import eu.okaeri.configs.ConfigManager;
import eu.okaeri.configs.json.gson.JsonGsonConfigurer;
import eu.okaeri.configs.serdes.OkaeriSerdes;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import net.kyori.adventure.translation.GlobalTranslator;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.ServerTickManager;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.Stream;

public class TheBrewingProject extends JavaPlugin implements TheBrewingProjectApi {

    private static TheBrewingProject instance;
    private StructureRegistry structureRegistry;
    private PlacedStructureRegistryImpl placedStructureRegistry;
    private RecipeRegistryImpl<ItemStack> recipeRegistry;
    private BreweryRegistry breweryRegistry;
    private SqlDatabase database;
    private DrunkTextRegistry drunkTextRegistry;
    private TimeFormatRegistry timeFormatRegistry;
    private DrunksManagerImpl<Connection> drunksManager;
    private DrunkEventManager drunkEventManager;
    private CustomEventRegistry customDrunkEventRegistry;
    private WorldEventListener worldEventListener;
    private EventStepRegistry eventStepRegistry;
    private DrunkEventExecutor drunkEventExecutor;
    private ResourcePackColors resourcePackColors;
    private CompletableFuture<ResolvedIngredientManager<ItemStack>> ingredientManagerFuture = new CompletableFuture<>();
    private long time;
    private BrewManager<ItemStack> brewManager = new BukkitBrewManager();
    private final IntegrationManagerImpl integrationManager = new IntegrationManagerImpl();
    private final ActiveEventsRegistry activeEventsRegistry = new ActiveEventsRegistry();
    private final AtomicMutationGate atomicMutationGate = new AtomicMutationGate();
    private final OwnerPublicationQueue ownerPublications = new OwnerPublicationQueue();
    private PlayerWalkListener playerWalkListener;
    private ModifierManager modifierManager = new ModifierManagerImpl();
    private BreweryTranslator translator;
    private boolean successfulLoad = false;
    private final BukkitContext metrics = new BukkitContext.Factory(
            this,
            "2ee682246967303e517be0d593fe7a01"
    ).metrics(Statistics::register)
            .errorTrackerService(Logger.getTracker())
            .create();


    public static TheBrewingProject getInstance() {
        return TheBrewingProject.instance;
    }

    public void initialize() {
        this.translator = new BreweryTranslator(new File(this.getDataFolder(), "locale"));
        DrunkenModifierSection.load(this.getDataFolder(), serializers());
        translator.reload();
        GlobalTranslator.translator().addSource(translator);
        this.structureRegistry = new StructureRegistry();
        this.placedStructureRegistry = new PlacedStructureRegistryImpl();
        this.breweryRegistry = new BreweryRegistry();
        this.recipeRegistry = new RecipeRegistryImpl<>();
        this.drunkTextRegistry = new DrunkTextRegistry();
        this.timeFormatRegistry = new TimeFormatRegistry();
        this.eventStepRegistry = new EventStepRegistry();
    }

    @Override
    public void onLoad() {
        instance = this;
        saveResources();
        Migrations.migrateAllConfigFiles(this.getDataFolder());
        this.resourcePackColors = new ResourcePackColors();
        EventSection.migrateEvents(getDataFolder());
        Config.load(this.getDataFolder(), serializers());
        integrationManager.registerIntegrations(resourcePackColors);
        CompletableFuture.allOf(integrationManager.retrieve(IntegrationTypes.ITEM)
                .stream()
                .map(ItemIntegration::initialized)
                .toArray(CompletableFuture[]::new)
        ).thenAccept(ignored -> {
            Bukkit.getAsyncScheduler().runNow(this, ignored2 -> resourcePackColors.init());
        });
        initialize();
        integrationManager.loadIntegrations();
        Bukkit.getServicesManager().register(TheBrewingProjectApi.class, this, this, ServicePriority.Normal);
        this.successfulLoad = true;
    }

    private OkaeriSerdes serializers() {
        return new OkaeriSerdesBuilder()
                .add(new BreweryLocationSerializer())
                .add(new EventRegistrySerializer())
                .add(new EventStepSerializer())
                .add(new CustomEventSerializer())
                .add(new SoundDefinitionSerializer())
                .add(new IntervalSerializer())
                .add(new MaterialSerializer())
                .add(new LocaleSerializer())
                .add(new ConsumableSerializer())
                .add(new NamedDrunkEventSerializer())
                .add(new DrunkenModifierSerializer())
                .add(new ModifierExpressionSerializer())
                .add(new ModifierDisplaySerializer())
                .add(new ComponentSerializer())
                .add(new ModifierTooltipSerializer())
                .add(new EventProbabilitySerializer())
                .add(new RangeDSerializer())
                .add(new ConditionSerializer())
                .add(new SecretKeySerializer())
                .add(new MinutesDurationSerializer())
                .add(new TicksDurationSerializer())
                .add(new ColorSerializer())
                .add(new UncheckedIngredientSerializer())
                .add(new IngredientInputSerializer())
                .add(new ParticleDefinitionSerializer())
                .add(new IntegrationEventSerializer(integrationManager.getIntegrationRegistry()))
                .build();
    }

    public void reload() {
        tryReload();
    }

    /**
     * Reloads only when no atomic brewery mutation owns a live holder.
     *
     * @return true when the reload ran
     */
    public boolean tryReload() {
        if (!atomicMutationGate.beginRefresh(this::hasPendingAtomicDistilleryMutation)) {
            Logger.logErr("The Brewing Project reload was refused because an atomic distillery mutation is pending");
            return false;
        }
        try {
            worldEventListener.invalidateAll();
            reloadNow();
            return true;
        } finally {
            atomicMutationGate.endRefresh();
        }
    }

    private void reloadNow() {
        Migrations.migrateAllConfigFiles(this.getDataFolder());
        saveResources();
        flushDatabase();
        Config.config().load(true);
        DrunkenModifierSection.modifiers().load(true);
        EventSection.events().load(true);
        DrunkenModifierSection.postValidate();
        EventSection.postValidate();
        IngredientsSection.ingredients().load(true);
        IngredientsSection.validate(BukkitIngredientManager.INSTANCE, BukkitIngredientUtil::tagValuesFromString);
        translator.reload();
        this.structureRegistry.clear();
        this.placedStructureRegistry.clear();
        this.breweryRegistry.clear();
        loadStructures();
        this.drunkTextRegistry.clear();
        this.customDrunkEventRegistry.clear();
        EventSection.events().customEvents().events()
                .forEach(this.customDrunkEventRegistry::registerCustomEvent);
        this.drunkEventExecutor.clear();
        this.customDrunkEventRegistry = EventSection.events().customEvents();
        saveResources();
        // Registered listeners retain this database instance. Replacing it here would split
        // ordered writes and reads across the old listener executor and a new atomic executor.
        // flushDatabase() already flushes the shared instance before registries are rebuilt.
        this.drunksManager.reset(EventSection.events().enabledRandomEvents().stream().map(EventData::deserialize).collect(Collectors.toSet()));
        worldEventListener.init();
        loadRecipes();
        loadDrunkenReplacements();
        loadTimeFormats();
        new TBPReloadEvent().callEvent();
    }

    private boolean hasPendingAtomicDistilleryMutation() {
        return placedStructureRegistry.getStructures(StructureType.DISTILLERY).stream()
                .map(MultiblockStructure::getHolder)
                .filter(BukkitDistillery.class::isInstance)
                .map(BukkitDistillery.class::cast)
                .anyMatch(BukkitDistillery::isAtomicMovePending);
    }

    private void loadDrunkenReplacements() {
        File file = new File(getDataFolder(), "/locale/" + Config.config().language().toLanguageTag() + ".drunk_text.json");
        if (!file.exists()) {
            Logger.log("Could not find drunken text replacements for your language, using en-US");
            file = new File(getDataFolder(), "/locale/en-US.drunk_text.json");
        }
        try (InputStream inputStream = new FileInputStream(file)) {
            drunkTextRegistry.load(inputStream);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void loadTimeFormats() {
        String fileName = Config.config().language().toLanguageTag() + ".time.properties";
        File file = new File(getDataFolder(), "/locale/" + fileName);
        if (!file.exists() && TimeFormatRegistry.class.getResource("/locale/" + fileName) == null) {
            Logger.log("Could not find time formats for your language, using en-US");
            file = new File(getDataFolder(), "/locale/en-US.time.properties");
        }
        try {
            timeFormatRegistry.sync(file);
            timeFormatRegistry.load(file);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private void loadStructures() {
        File structureRoot = new File(getDataFolder(), "structures");
        if (!structureRoot.exists() && !structureRoot.mkdirs()) {
            throw new RuntimeException("Could not create structure root: " + structureRoot);
        }
        Stream.of("small_barrel", "large_barrel", "bamboo_distillery")
                .map(string -> "structures/" + string)
                .flatMap(name -> Stream.of(name + ".schem", name + ".json"))
                .forEach(this::saveResourceIfNotExists);
        OkaeriSerdes pack = new OkaeriSerdesBuilder()
                .add(new BreweryVectorSerializer())
                .add(new BreweryVectorListSerializer())
                .add(new MaterialHolderSerializer())
                .add(new MaterialTagSerializer())
                .add(new StructureMetaSerializer())
                .add(new Vector3iSerializer())
                .add(new MaterialsSerializer())
                .add(new StructureTypeSerializer())
                .add(new BlockReplacementSerializer())
                .build();
        List<StructureMatcher> matchers = StructureMatchers.matchers(this.getDataFolder(), pack)
                .stream()
                .map(StructureMatcher::getMatchers)
                .flatMap(Collection::stream)
                .toList();
        Stream.of(structureRoot.listFiles())
                .filter(file -> file.getName().endsWith(".json"))
                .map(File::toPath)
                .map(path -> ConfigManager.create(BreweryStructureConfig.class, it -> {
                    it.withConfigurer(new JsonGsonConfigurer(), pack);
                    it.withBindFile(path);
                    it.withRemoveOrphans(true);
                    it.saveDefaults();
                    it.load(true);
                }).toStructure(path, matchers))
                .forEach(structureRegistry::addStructure);
    }

    @Override
    public void onEnable() {
        Preconditions.checkState(successfulLoad, "Plugin loading failed, see above exception in load stage");
        loadStructures();
        integrationManager.enableIntegrations();
        this.database = new SqlDatabase(DatabaseDriver.SQLITE);
        try {
            database.init(this.getDataFolder());
            database.startSession(SessionTypes.MISC_SESSION_TYPE).getTime()
                    .thenAccept(time -> this.time = time)
                    .join();

        } catch (IOException | PersistenceException | SQLException e) {
            throw new RuntimeException(e); // Hard exit if any issues here
        }
        EventSection.load(getDataFolder(), serializers());
        DrunkenModifierSection.postValidate();
        EventSection.postValidate();
        this.customDrunkEventRegistry = EventSection.events().customEvents();
        this.drunksManager = new DrunksManagerImpl<>(customDrunkEventRegistry, EventSection.events().enabledRandomEvents().stream().map(EventData::deserialize).collect(Collectors.toSet()),
                EventUtil::fromData, () -> this.time, () -> database.startSession(SessionTypes.DRUNK_STATE_SESSION_TYPE));
        this.drunkEventExecutor = new DrunkEventExecutor();
        this.drunkEventManager = new DrunkEventManagerImpl(customDrunkEventRegistry, drunkEventExecutor);
        PluginManager pluginManager = Bukkit.getPluginManager();
        pluginManager.registerEvents(new BrewMigrationListener(), this);
        pluginManager.registerEvents(new BlockEventListener(this.structureRegistry, placedStructureRegistry, this.database, this.breweryRegistry), this);
        pluginManager.registerEvents(new PlayerEventListener(this.placedStructureRegistry, this.breweryRegistry, this.database, this.drunksManager, this.drunkTextRegistry, recipeRegistry, drunkEventExecutor), this);
        pluginManager.registerEvents(new InventoryEventListener(breweryRegistry, database), this);
        this.worldEventListener = new WorldEventListener(this.database, this.placedStructureRegistry, this.breweryRegistry);
        worldEventListener.init();
        this.playerWalkListener = new PlayerWalkListener();
        pluginManager.registerEvents(worldEventListener, this);
        pluginManager.registerEvents(playerWalkListener, this);
        pluginManager.registerEvents(new EntityEventListener(), this);
        if (ClassUtil.exists("io.papermc.paper.event.connection.PlayerConnectionValidateLoginEvent")) {
            pluginManager.registerEvents(new PlayerJoinListener(), this);
        } else {
            pluginManager.registerEvents(new LegacyPlayerJoinListener(), this);
        }
        pluginManager.registerEvents(new BreweryXMigrationListener(), this);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, this::updateStructures, 1, 1);
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(this, this::otherTicking, 1, 1);
        IngredientsSection.load(this.getDataFolder(), serializers());
        IngredientsSection.validate(BukkitIngredientManager.INSTANCE, BukkitIngredientUtil::tagValuesFromString);
        loadRecipes();
        CompletableFuture.allOf(integrationManager.retrieve(IntegrationTypes.ITEM).stream().map(ItemIntegration::initialized)
                        .toArray(CompletableFuture<?>[]::new))
                .thenAccept(ignored -> ingredientManagerFuture.complete(new ResolvedIngredientManagerImpl()));
        this.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, BreweryCommand::register);
        loadDrunkenReplacements();
        loadTimeFormats();
        this.metrics.ready();
    }

    @Override
    public void onDisable() {
        if (worldEventListener != null) {
            worldEventListener.stop();
        } else {
            atomicMutationGate.stop();
        }
        if (recipeRegistry != null) {
            recipeRegistry.invalidatePendingLoads();
        }
        ownerPublications.stop();
        ingredientManagerFuture.completeExceptionally(new java.util.concurrent.CancellationException("Plugin is stopping"));
        try {
            flushDatabase();
        } finally {
            if (database != null) database.close().join();
            this.metrics.shutdown();
        }
    }

    private void flushDatabase() {
        if (database == null) return;
        try {
            breweryRegistry.iterate(StructureType.BARREL, inventoryAccessible -> inventoryAccessible.close(true));
            breweryRegistry.iterate(StructureType.DISTILLERY, inventoryAccessible -> inventoryAccessible.close(true));
        } catch (Throwable e) {
            Logger.logAndTrackErr(e);
        }
        try {
            database.startSession(SessionTypes.MISC_SESSION_TYPE)
                    .setTime(time)
                    .exceptionally(Logger::logAndTrackErr);
            database.flush().join();
        } catch (PersistenceException e) {
            Logger.logErr(e);
        }
    }

    private void saveResources() {
        Stream.of("recipes.yml", "incomplete-recipes.yml", "locale/en-US.drunk_text.json", "locale/ru.drunk_text.json", "locale/lol-US.drunk_text.json")
                .forEach(this::saveResourceIfNotExists);
    }

    private void saveResourceIfNotExists(String resource) {
        if (new File(getDataFolder(), resource).exists()) {
            return;
        }
        super.saveResource(resource, false);
    }

    private void updateStructures(ScheduledTask ignored) {
        if (noTicking()) {
            return; // Don't tick if the server is frozen, debug purposes
        }
        breweryRegistry.getActiveSinglePositionStructure().stream()
                .filter(Tickable.class::isInstance)
                .map(Tickable.class::cast)
                .forEach(Tickable::tick);
        placedStructureRegistry.getStructures(StructureType.DISTILLERY).stream()
                .map(MultiblockStructure::getHolder)
                .map(BukkitDistillery.class::cast)
                .forEach(distillery -> distillery.runLocally(distillery::tick));
        breweryRegistry.iterate(StructureType.BARREL, barrel ->
                barrel.runLocally(((BukkitBarrel) barrel)::tickInventory)
        );
        breweryRegistry.iterate(StructureType.DISTILLERY, distillery ->
                distillery.runLocally(((BukkitDistillery) distillery)::tickInventory)
        );
    }

    private void otherTicking(ScheduledTask ignored) {
        if (noTicking()) {
            return; // Don't tick if the server is frozen, debug purposes
        }
        drunksManager.tick(drunkEventExecutor::doDrunkEvent, uuid -> Bukkit.getPlayer(uuid) != null);
        if (++time % 200 == 0) {
            try {
                database.startSession(SessionTypes.MISC_SESSION_TYPE)
                        .setTime(time)
                        .exceptionally(Logger::logAndTrackErr);
            } catch (PersistenceException e) {
                Logger.logAndTrackErr(e);
            }
        }
    }

    /**
     * Defaults to the brewery namespace
     *
     * @param key Key string with optional namespace
     * @return Namespaced key
     */
    public static NamespacedKey key(String key) {
        if (key.contains(":")) {
            String[] split = key.split(":", 2);
            return new NamespacedKey(split[0], split[1]);
        }
        return new NamespacedKey("brewery", key);
    }

    public Configuration getConfiguration() {
        return Config.config();
    }


    private boolean noTicking() {
        ServerTickManager serverTickManager = Bukkit.getServerTickManager();
        return serverTickManager.isFrozen() && !serverTickManager.isSprinting()
                && !serverTickManager.isStepping();
    }

    @Override
    public IngredientManager<ItemStack> getIngredientManager() {
        return BukkitIngredientManager.INSTANCE;
    }

    @Override
    public CompletableFuture<ResolvedIngredientManager<ItemStack>> getResolvedIngredientManager() {
        return ingredientManagerFuture;
    }

    @Override
    public DrunkEventManager getDrunkenEventManager() {
        return this.drunkEventManager;
    }

    public StructureRegistry getStructureRegistry() {
        return this.structureRegistry;
    }

    public PlacedStructureRegistryImpl getPlacedStructureRegistry() {
        return this.placedStructureRegistry;
    }

    public RecipeRegistryImpl<ItemStack> getRecipeRegistry() {
        return this.recipeRegistry;
    }

    public BreweryRegistry getBreweryRegistry() {
        return this.breweryRegistry;
    }

    public SqlDatabase getDatabase() {
        return this.database;
    }

    public DrunkTextRegistry getDrunkTextRegistry() {
        return this.drunkTextRegistry;
    }

    public TimeFormatRegistry getTimeFormatRegistry() {
        return this.timeFormatRegistry;
    }

    public DrunksManagerImpl<Connection> getDrunksManager() {
        return this.drunksManager;
    }

    public CustomEventRegistry getCustomDrunkEventRegistry() {
        return this.customDrunkEventRegistry;
    }

    public EventStepRegistry getEventStepRegistry() {
        return this.eventStepRegistry;
    }

    public DrunkEventExecutor getDrunkEventExecutor() {
        return this.drunkEventExecutor;
    }

    private void loadRecipes() {
        long generation = recipeRegistry.beginLoad();
        RecipeReader<ItemStack> reader = new RecipeReader<>(getDataFolder(),
                new BukkitRecipeResultReader(), BukkitIngredientManager.INSTANCE);
        var recipes = reader.readRecipes();
        var defaults = DefaultRecipeReader.readDefaultRecipes(getDataFolder());
        var pending = new java.util.ArrayList<CompletableFuture<?>>();
        pending.addAll(recipes);
        pending.addAll(defaults.values());
        CompletableFuture.allOf(pending.toArray(CompletableFuture<?>[]::new))
                .thenRunAsync(() -> {
                    var loadedDefaults = new java.util.LinkedHashMap<String,
                            dev.jsinco.brewery.api.recipe.DefaultRecipe<ItemStack>>();
                    defaults.forEach((name, future) -> loadedDefaults.put(name, future.join()));
                    var loaded = recipes.stream().map(CompletableFuture::join)
                            .filter(Objects::nonNull).toList();
                    if (recipeRegistry.publishLoad(generation, loaded, loadedDefaults)) {
                        new AsyncRecipesLoadedEvent(recipeRegistry).callEvent();
                    }
                }).exceptionally(Logger::logAndTrackErr);
    }

    public AtomicMutationGate getAtomicMutationGate() {
        return atomicMutationGate;
    }

    public OwnerPublicationQueue getOwnerPublications() {
        return ownerPublications;
    }

    public long getTime() {
        return this.time;
    }

    public BrewManager<ItemStack> getBrewManager() {
        return this.brewManager;
    }

    public IntegrationManagerImpl getIntegrationManager() {
        return this.integrationManager;
    }

    public ActiveEventsRegistry getActiveEventsRegistry() {
        return this.activeEventsRegistry;
    }

    public PlayerWalkListener getPlayerWalkListener() {
        return this.playerWalkListener;
    }

    public ModifierManager getModifierManager() {
        return this.modifierManager;
    }
}

