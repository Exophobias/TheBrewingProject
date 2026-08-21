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
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlacedStructureRegistryImplTest {

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
