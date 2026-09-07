package dev.jsinco.brewery.structure;

import dev.jsinco.brewery.api.breweries.StructureHolder;
import dev.jsinco.brewery.api.structure.MultiblockStructure;
import dev.jsinco.brewery.api.structure.StructureType;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacedStructureRegistryImplTest {

    @Test
    void staleUnregisterCannotEraseReplacementAfterReload() {
        PlacedStructureRegistryImpl registry = new PlacedStructureRegistryImpl();
        BreweryLocation location = new BreweryLocation(1, 2, 3, UUID.randomUUID());
        TestStructure stale = new TestStructure(location);
        TestStructure current = new TestStructure(location);
        registry.registerStructure(stale);
        registry.clear();
        registry.registerStructure(current);
        registry.unregisterStructure(stale);
        assertSame(current, registry.getStructure(location).orElseThrow());
        assertEquals(1, registry.countStructureType(StructureType.DISTILLERY));
    }

    @Test
    void delayedLoadCannotReplaceAnExistingHolderOrPartiallyPublishBatch() {
        PlacedStructureRegistryImpl registry = new PlacedStructureRegistryImpl();
        UUID world = UUID.randomUUID();
        BreweryLocation occupied = new BreweryLocation(1, 2, 3, world);
        BreweryLocation vacant = new BreweryLocation(4, 5, 6, world);
        TestStructure current = new TestStructure(occupied);
        registry.registerStructure(current);
        assertThrows(IllegalStateException.class, () -> registry.registerStructure(new TestStructure(occupied)));
        assertThrows(IllegalStateException.class, () -> registry.registerStructures(
                List.of(new TestStructure(vacant), new TestStructure(occupied))));
        assertSame(current, registry.getStructure(occupied).orElseThrow());
        assertTrue(registry.getStructure(vacant).isEmpty());
        assertEquals(1, registry.countStructureType(StructureType.DISTILLERY));
    }

    @Test
    void overlappingBatchIsRejectedBeforeEitherHolderIsPublished() {
        PlacedStructureRegistryImpl registry = new PlacedStructureRegistryImpl();
        BreweryLocation location = new BreweryLocation(1, 2, 3, UUID.randomUUID());
        assertThrows(IllegalStateException.class, () -> registry.registerStructures(
                List.of(new TestStructure(location), new TestStructure(location))));
        assertTrue(registry.getStructure(location).isEmpty());
        assertEquals(0, registry.countStructureType(StructureType.DISTILLERY));
    }

    @Test
    void uninitializedHolderCannotPartiallyPublishItsBatch() {
        PlacedStructureRegistryImpl registry = new PlacedStructureRegistryImpl();
        BreweryLocation first = new BreweryLocation(1, 2, 3, UUID.randomUUID());
        TestStructure invalid = new TestStructure(first.add(1, 0, 0));
        invalid.setHolder(null);
        assertThrows(IllegalStateException.class, () -> registry.registerStructures(
                List.of(new TestStructure(first), invalid)));
        assertTrue(registry.getStructure(first).isEmpty());
        assertTrue(registry.getStructure(invalid.getUnique()).isEmpty());
        assertEquals(0, registry.countStructureType(StructureType.DISTILLERY));
    }

    @Test
    void publishedTypedViewCannotBeUsedToMutateRegistry() {
        PlacedStructureRegistryImpl registry = new PlacedStructureRegistryImpl();
        TestStructure structure = new TestStructure(new BreweryLocation(1, 2, 3, UUID.randomUUID()));
        registry.registerStructure(structure);
        var snapshot = registry.getStructures(StructureType.DISTILLERY);
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
        registry.clear();
        assertEquals(1, snapshot.size());
    }

    @Test
    void clearRemovesCoordinateAndTypedIndexes() {
        PlacedStructureRegistryImpl registry = new PlacedStructureRegistryImpl();
        BreweryLocation location = new BreweryLocation(1, 2, 3, UUID.randomUUID());
        TestStructure structure = new TestStructure(location);

        registry.registerStructure(structure);
        assertSame(structure, registry.getStructure(location).orElseThrow());
        assertTrue(registry.getStructures(StructureType.DISTILLERY).contains(structure));
        assertEquals(1, registry.countStructureType(StructureType.DISTILLERY));

        registry.clear();

        assertTrue(registry.getStructure(location).isEmpty());
        assertTrue(registry.getStructures(StructureType.DISTILLERY).isEmpty());
        assertEquals(0, registry.countStructureType(StructureType.DISTILLERY));
    }

    private static final class TestStructure implements MultiblockStructure<TestHolder> {

        private final BreweryLocation location;
        private TestHolder holder;

        private TestStructure(BreweryLocation location) {
            this.location = location;
            this.holder = new TestHolder(this);
        }

        @Override
        public List<BreweryLocation> positions() {
            return List.of(location);
        }

        @Override
        public TestHolder getHolder() {
            return holder;
        }

        @Override
        public void setHolder(TestHolder holder) {
            this.holder = holder;
        }

        @Override
        public BreweryLocation getUnique() {
            return location;
        }
    }

    private record TestHolder(TestStructure structure) implements StructureHolder<TestHolder> {

        @Override
        public MultiblockStructure<TestHolder> getStructure() {
            return structure;
        }

        @Override
        public void destroy(BreweryLocation breweryLocation) {
            // No-op test holder.
        }

        @Override
        public StructureType<?> getStructureType() {
            return StructureType.DISTILLERY;
        }
    }
}
