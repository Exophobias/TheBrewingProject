package dev.jsinco.brewery.bukkit.breweries;

import dev.jsinco.brewery.api.breweries.BarrelAccess;
import dev.jsinco.brewery.api.breweries.InventoryAccessible;
import dev.jsinco.brewery.api.structure.SinglePositionStructure;
import dev.jsinco.brewery.api.structure.StructureType;
import dev.jsinco.brewery.api.vector.BreweryLocation;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class BreweryRegistryLifecycleTest {
    @Test
    void staleCauldronRemovalCannotEraseItsReplacement() {
        BreweryRegistry registry = new BreweryRegistry();
        var location = new BreweryLocation(1, 2, 3, UUID.randomUUID());
        var old = new Single(location);
        var current = new Single(location);
        registry.addActiveSinglePositionStructure(old);
        var snapshot = registry.getActiveSinglePositionStructure();
        registry.addActiveSinglePositionStructure(current);
        registry.removeActiveSinglePositionStructure(old);
        assertSame(current, registry.getActiveSinglePositionStructure(location).orElseThrow());
        assertSame(old, snapshot.iterator().next());
        assertThrows(UnsupportedOperationException.class, snapshot::clear);
    }

    @Test
    void staleInventoryRemovalCannotEraseReplacementOwner() {
        BreweryRegistry registry = new BreweryRegistry();
        Inventory inventory = (Inventory) Proxy.newProxyInstance(Inventory.class.getClassLoader(),
                new Class<?>[]{Inventory.class}, BreweryRegistryLifecycleTest::identity);
        var old = holder(inventory);
        var current = holder(inventory);
        registry.registerInventory(old);
        registry.registerInventory(current);
        registry.unregisterInventory(old);
        assertSame(current, registry.getFromInventory(inventory));
    }

    @Test
    void closeHandlersMayUnregisterOpenedInventoriesWithoutSkippingTheRest() {
        BreweryRegistry registry = new BreweryRegistry();
        assertEquals(0, registry.countOpened(StructureType.BARREL));
        var first = holder(null);
        var second = holder(null);
        registry.registerOpened(first);
        registry.registerOpened(second);
        Set<Object> closed = new HashSet<>();
        registry.iterate(StructureType.BARREL, holder -> {
            closed.add(holder);
            registry.unregisterOpened((InventoryAccessible<ItemStack, Inventory>) holder);
        });
        assertEquals(Set.of(first, second), closed);
        assertEquals(0, registry.countOpened(StructureType.BARREL));
    }

    @SuppressWarnings("unchecked")
    private static InventoryAccessible<ItemStack, Inventory> holder(Inventory inventory) {
        return (InventoryAccessible<ItemStack, Inventory>) Proxy.newProxyInstance(
                InventoryAccessible.class.getClassLoader(), new Class<?>[]{InventoryAccessible.class, BarrelAccess.class},
                (proxy, method, arguments) -> method.getName().equals("getInventories")
                        ? inventory == null ? Set.of() : Set.of(inventory) : identity(proxy, method, arguments));
    }

    private static Object identity(Object proxy, java.lang.reflect.Method method, Object[] arguments) {
        return switch (method.getName()) {
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == arguments[0];
            case "toString" -> "fixture-" + System.identityHashCode(proxy);
            default -> null;
        };
    }

    private record Single(BreweryLocation position) implements SinglePositionStructure {
        public void destroy() { }
    }
}
