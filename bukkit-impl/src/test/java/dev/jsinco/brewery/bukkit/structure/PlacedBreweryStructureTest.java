package dev.jsinco.brewery.bukkit.structure;

import dev.jsinco.brewery.api.vector.BreweryLocation;
import org.bukkit.Location;
import org.bukkit.util.BlockVector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockbukkit.mockbukkit.MockBukkitExtension;
import org.mockbukkit.mockbukkit.MockBukkitInject;
import org.mockbukkit.mockbukkit.ServerMock;
import org.mockbukkit.mockbukkit.world.WorldMock;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(MockBukkitExtension.class)
class PlacedBreweryStructureTest {
    @MockBukkitInject
    ServerMock serverMock;
    private WorldMock worldMock;

    @BeforeEach
    void setUp() {
        this.worldMock = serverMock.addSimpleWorld("hello_world");
    }

    @ParameterizedTest
    @MethodSource("insideSmallBarrel")
    void findValid_Any_insideMatch(BlockVector blockVector) throws IOException, URISyntaxException {
        BreweryStructure breweryStructure = StructurePlacerUtils.matchingStructure();
        StructurePlacerUtils.constructSmallOakBarrel(worldMock);
        var found = PlacedBreweryStructure.findValid(breweryStructure,
                new Location(worldMock, blockVector.getBlockX(), blockVector.getBlockY(), blockVector.getBlockZ())
        ).orElseThrow().first();
        assertEquals(new BreweryLocation(-2, 2, 4, worldMock.getUID()), found.getUnique(),
                "The same footprint must have one key regardless of the block used to find it");
    }

    @ParameterizedTest
    @MethodSource("outsideSmallBarrel")
    void findValid_Any_outsideNoMatch(BlockVector pos) throws IOException, URISyntaxException {
        BreweryStructure breweryStructure = StructurePlacerUtils.matchingStructure();
        StructurePlacerUtils.constructSmallOakBarrel(worldMock);
        assertFalse(PlacedBreweryStructure.findValid(
                breweryStructure,
                new Location(worldMock, pos.getX(), pos.getY(), pos.getZ())
        ).isPresent());
    }

    @Test
    void positions() throws IOException, URISyntaxException {
        var placed = placedBarrel();
        assertEquals(getSmallBarrelBlocks(), placed.positions().stream()
                .map(position -> new BlockVector(position.x(), position.y(), position.z()))
                .collect(Collectors.toSet()));
    }

    @Test
    void existingDatabaseKeyRemainsExactEvenWhenItDiffersFromNewOrdering()
            throws IOException, URISyntaxException {
        var placed = placedBarrel();
        BreweryLocation legacyKey = new BreweryLocation(-3, 1, 1, worldMock.getUID());
        var restored = new PlacedBreweryStructure<>(placed.getStructure(),
                placed.getTransformation(), placed.getWorldOrigin(), legacyKey);
        assertEquals(legacyKey, restored.getUnique());
        assertEquals(placed.positions(), restored.positions());
    }

    @Test
    void databaseKeyOutsideFootprintIsRejected() throws IOException, URISyntaxException {
        var placed = placedBarrel();
        assertThrows(IllegalArgumentException.class, () -> new PlacedBreweryStructure<>(
                placed.getStructure(), placed.getTransformation(), placed.getWorldOrigin(),
                new BreweryLocation(100, 100, 100, worldMock.getUID())));
    }

    private PlacedBreweryStructure<?> placedBarrel() throws IOException, URISyntaxException {
        StructurePlacerUtils.constructSmallOakBarrel(worldMock);
        return PlacedBreweryStructure.findValid(StructurePlacerUtils.matchingStructure(),
                new Location(worldMock, -3, 1, 1)).orElseThrow().first();
    }

    static Stream<Arguments> getSchemFormatPaths() {
        return Stream.of("/structures/large_barrel.json", "/structures/small_barrel.json")
                .map(StructureReaderTest.class::getResource)
                .map(url -> {
                    try {
                        return url.toURI();
                    } catch (URISyntaxException uriSyntaxException) {
                        throw new RuntimeException();
                    }
                })
                .map(Paths::get)
                .map(Arguments::of);
    }

    private static Set<BlockVector> getSmallBarrelBlocks() {
        return Set.of(new BlockVector(-3, 1, 2),
                new BlockVector(-3, 1, 3),
                new BlockVector(-3, 1, 4),
                new BlockVector(-2, 1, 2),
                new BlockVector(-2, 1, 3),
                new BlockVector(-2, 1, 4),
                new BlockVector(-3, 2, 2),
                new BlockVector(-3, 2, 3),
                new BlockVector(-3, 2, 4),
                new BlockVector(-2, 2, 2),
                new BlockVector(-2, 2, 3),
                new BlockVector(-2, 2, 4),
                new BlockVector(-3, 1, 1));
    }

    public static Stream<Arguments> insideSmallBarrel() {
        return getSmallBarrelBlocks().stream().map(Arguments::of);
    }

    public static Stream<Arguments> outsideSmallBarrel() {
        List<BlockVector> blockVectorArrayList = new ArrayList<>();
        Set<BlockVector> banned = getSmallBarrelBlocks();
        for (int x = -7; x < 7; x++) {
            for (int y = 0; y < 10; y++) {
                for (int z = -4; z < 10; z++) {
                    BlockVector blockVector = new BlockVector(x, y, z);
                    if (!banned.contains(blockVector)) {
                        blockVectorArrayList.add(blockVector);
                    }
                }
            }
        }
        return blockVectorArrayList.stream().map(Arguments::of);
    }

}
