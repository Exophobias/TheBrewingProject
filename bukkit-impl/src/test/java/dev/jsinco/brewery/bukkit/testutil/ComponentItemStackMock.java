package dev.jsinco.brewery.bukkit.testutil;

import io.papermc.paper.datacomponent.DataComponentBuilder;
import io.papermc.paper.datacomponent.DataComponentType;
import org.bukkit.Material;
import org.bukkit.inventory.ItemType;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Item storage for renderer tests: MockBukkit 4.64 implements ItemMeta/PDC but not Paper's
 * component API. Values are immutable Paper component objects; a clone owns a separate map
 * and ItemMeta. No recipe or presentation decisions are implemented by this fixture.
 */
public final class ComponentItemStackMock extends ItemStackMockPDC {
    private final Map<DataComponentType, Object> components = new HashMap<>();

    public ComponentItemStackMock(Material material) {
        super(material);
    }

    @Override @SuppressWarnings("unchecked")
    public <T> T getData(DataComponentType.Valued<T> type) {
        return (T) components.get(type);
    }

    @Override public <T> void setData(DataComponentType.Valued<T> type, T value) {
        components.put(type, value);
    }

    @Override public <T> void setData(DataComponentType.Valued<T> type, DataComponentBuilder<T> value) {
        setData(type, value.build());
    }

    @Override public void setData(DataComponentType.NonValued type) {
        components.put(type, Boolean.TRUE);
    }

    @Override public void unsetData(DataComponentType type) {
        components.remove(type);
    }

    @Override public boolean hasData(DataComponentType type) {
        return components.containsKey(type);
    }

    @Override public Set<DataComponentType> getDataTypes() {
        return Set.copyOf(components.keySet());
    }

    @Override public ComponentItemStackMock clone() {
        ComponentItemStackMock copy = new ComponentItemStackMock(getType());
        copy.setAmount(getAmount());
        copy.setItemMeta(getItemMeta());
        copy.components.putAll(components);
        return copy;
    }

    /** Route production's ordinary new ItemStack(POTION) through the same test storage. */
    public static AutoCloseable installPotionFactory() throws ReflectiveOperationException {
        Field itemTypeField = Material.class.getDeclaredField("itemType");
        itemTypeField.setAccessible(true);
        Object originalSupplier = itemTypeField.get(Material.POTION);
        ItemType originalType = Material.POTION.asItemType();
        ItemType replacement = (ItemType) Proxy.newProxyInstance(ItemType.class.getClassLoader(),
                new Class<?>[]{ItemType.Typed.class}, (proxy, method, args) -> {
                    if (method.getName().equals("createItemStack") && args != null
                            && args.length == 1 && args[0] instanceof Integer amount) {
                        ComponentItemStackMock item = new ComponentItemStackMock(Material.POTION);
                        item.setAmount(amount);
                        return item;
                    }
                    try {
                        return method.invoke(originalType, args);
                    } catch (InvocationTargetException exception) {
                        throw exception.getCause();
                    }
                });
        itemTypeField.set(Material.POTION, (Supplier<ItemType>) () -> replacement);
        return () -> itemTypeField.set(Material.POTION, originalSupplier);
    }
}
