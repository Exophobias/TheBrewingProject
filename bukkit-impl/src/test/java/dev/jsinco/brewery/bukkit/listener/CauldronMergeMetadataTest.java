package dev.jsinco.brewery.bukkit.listener;

import dev.jsinco.brewery.api.brew.Brew;
import dev.jsinco.brewery.api.meta.MetaDataType;
import dev.jsinco.brewery.brew.BrewImpl;
import dev.jsinco.brewery.brew.DistillStepImpl;
import dev.jsinco.brewery.bukkit.TheBrewingProject;
import dev.jsinco.brewery.bukkit.api.integration.IntegrationTypes;
import dev.jsinco.brewery.bukkit.api.integration.ItemIntegration;
import dev.jsinco.brewery.bukkit.breweries.BukkitCauldron;
import dev.jsinco.brewery.bukkit.testutil.TBPServerMock;
import net.kyori.adventure.key.Key;
import org.junit.jupiter.api.*;
import org.mockbukkit.mockbukkit.MockBukkit;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class CauldronMergeMetadataTest {
    private TheBrewingProject plugin;
    private dev.jsinco.brewery.bukkit.testutil.CauldronOwnerServerMock server;
    private org.mockbukkit.mockbukkit.world.WorldMock world;
    @BeforeEach void setUp() {
        server = MockBukkit.mock(new dev.jsinco.brewery.bukkit.testutil.CauldronOwnerServerMock());
        world = server.addSimpleWorld("metadata-merge");
        plugin = MockBukkit.load(TheBrewingProject.class);
        plugin.getResolvedIngredientManager().join(); plugin.getDatabase().flush().join(); server.publishGlobal();
    }
    @AfterEach void tearDown() { MockBukkit.unmock(); }

    @Test void registeredMetadataPolicyRunsOnlyWhileItsOwnerIsEnabled() throws Exception {
        var enabled = new AtomicBoolean(true);
        var calls = new AtomicInteger();
        Key proof = Key.key("test", "proof"), unrelated = Key.key("another", "data");
        var integration = (ItemIntegration) Proxy.newProxyInstance(ItemIntegration.class.getClassLoader(),
                new Class<?>[]{ItemIntegration.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getId" -> "merge-policy-test";
                    case "isEnabled" -> enabled.get();
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "mergeBrewMetadata" -> { calls.incrementAndGet(); yield ((Brew) args[2]).withoutMeta(proof); }
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        plugin.getIntegrationManager().register(IntegrationTypes.ITEM, integration);
        assertTrue(plugin.getIntegrationManager().isRegistered(IntegrationTypes.ITEM, integration));
        assertFalse(plugin.getIntegrationManager().isRegistered(IntegrationTypes.RECIPE_SCORE, integration));
        Brew first = new BrewImpl(List.of(new DistillStepImpl(10)))
                .withMeta(proof, MetaDataType.STRING, "first-bottle-only")
                .withMeta(unrelated, MetaDataType.INTEGER, 23);
        Brew second = new BrewImpl(first.getSteps());
        var merge = BukkitCauldron.class.getDeclaredMethod("mergeMetadata", Brew.class, Brew.class, Brew.class);
        merge.setAccessible(true);
        Brew merged = (Brew) merge.invoke(null, first, second, first);
        assertFalse(merged.hasMeta(proof, MetaDataType.STRING));
        assertEquals(23, merged.meta(unrelated, MetaDataType.INTEGER));
        assertEquals(1, calls.get());
        enabled.set(false);
        assertSame(first, merge.invoke(null, first, second, first));
        assertEquals(1, calls.get());
    }
    @Test void addingPlainWaterCannotCarryForwardAnExclusiveCertificate() throws Exception {
        Key proof = Key.key("test", "exclusive-proof");
        var integration = new ItemIntegration() {
            @Override public String getId() { return "water-dilution-test"; }
            @Override public boolean isEnabled() { return true; }
            @Override public Optional<org.bukkit.inventory.ItemStack> createItem(String id) { return Optional.empty(); }
            @Override public boolean isIngredient(String id) { return false; }
            @Override public net.kyori.adventure.text.Component displayName(String id) { return null; }
            @Override public String getItemId(org.bukkit.inventory.ItemStack item) { return null; }
            @Override public java.util.concurrent.CompletableFuture<Void> initialized() { return java.util.concurrent.CompletableFuture.completedFuture(null); }
            @Override public Brew mergeBrewMetadata(Brew existing, Brew added, Brew merged) {
                assertTrue(added.getSteps().isEmpty());
                assertTrue(added.metaKeys().isEmpty());
                return merged.withoutMeta(proof);
            }
        };
        plugin.getIntegrationManager().register(IntegrationTypes.ITEM, integration);
        var block = world.getBlockAt(5, 65, 5);
        block.setType(org.bukkit.Material.WATER_CAULDRON);
        var data = (org.bukkit.block.data.Levelled) block.getBlockData(); data.setLevel(1); block.setBlockData(data);
        Brew brew = new BrewImpl(List.of(new dev.jsinco.brewery.brew.MixStepImpl(
                new dev.jsinco.brewery.api.moment.PassedMoment(1), Map.of(), dev.jsinco.brewery.api.breweries.CauldronType.BREW)))
                .withMeta(proof, MetaDataType.STRING, "only-the-original-serving");
        var cauldron = new BukkitCauldron(brew, dev.jsinco.brewery.bukkit.api.BukkitAdapter.toBreweryLocation(block),
                dev.jsinco.brewery.api.breweries.CauldronType.BREW);
        plugin.getDatabase().startSession(dev.jsinco.brewery.bukkit.database.SessionTypes.CAULDRON_SESSION_TYPE).insertCauldron(cauldron).join();
        plugin.getBreweryRegistry().addActiveSinglePositionStructure(cauldron);
        var player = server.addPlayer(); player.addAttachment(plugin, "brewery.cauldron.access", true);
        assertTrue(cauldron.withIngredient(new org.bukkit.inventory.ItemStack(org.bukkit.Material.POTION), player));
        assertEquals(2, cauldron.getLevel());
        assertFalse(cauldron.getBrew().hasMeta(proof, MetaDataType.STRING));
    }

}
