package dev.jsinco.brewery.bukkit.testutil;

import io.papermc.paper.datacomponent.item.ItemLore;
import io.papermc.paper.datacomponent.item.PotionContents;
import io.papermc.paper.datacomponent.item.TooltipDisplay;
import net.kyori.adventure.text.ComponentLike;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Scoped replacement for the Paper server's absent component-builder service in MockBukkit. */
public final class ItemComponentBridgeFixture {
    private ItemComponentBridgeFixture() { }

    public static AutoCloseable install() throws ReflectiveOperationException {
        Class<?> bridgeClass = Class.forName("io.papermc.paper.datacomponent.item.ItemComponentTypesBridge");
        Field bridgeField = bridgeClass.getDeclaredField("BRIDGE");
        Field unsafeField = Unsafe.class.getDeclaredField("theUnsafe");
        unsafeField.setAccessible(true);
        Unsafe unsafe = (Unsafe) unsafeField.get(null);
        Object base = unsafe.staticFieldBase(bridgeField);
        long offset = unsafe.staticFieldOffset(bridgeField);
        Object original = unsafe.getObjectVolatile(base, offset);
        Object bridge = Proxy.newProxyInstance(bridgeClass.getClassLoader(), new Class<?>[]{bridgeClass},
                (proxy, method, args) -> switch (method.getName()) {
                    case "lore" -> builder(ItemLore.Builder.class, ItemLore.class, Map.of("lines", List.of()));
                    case "potionContents" -> builder(PotionContents.Builder.class, PotionContents.class,
                            Map.of("customEffects", List.of(), "allEffects", List.of()));
                    case "tooltipDisplay" -> builder(TooltipDisplay.Builder.class, TooltipDisplay.class,
                            Map.of("hideTooltip", false, "hiddenComponents", Set.of()));
                    default -> throw new UnsupportedOperationException("Unimplemented test component builder: " + method.getName());
                });
        unsafe.putObjectVolatile(base, offset, Optional.of(bridge));
        return () -> unsafe.putObjectVolatile(base, offset, original);
    }

    private static Object builder(Class<?> builderType, Class<?> valueType, Map<String, Object> defaults) {
        Map<String, Object> values = new HashMap<>(defaults);
        return Proxy.newProxyInstance(builderType.getClassLoader(), new Class<?>[]{builderType},
                (proxy, method, args) -> {
                    if (method.getName().equals("build")) return value(valueType, new HashMap<>(values));
                    if (args == null || args.length != 1) throw new UnsupportedOperationException(method.toString());
                    Object value = args[0];
                    if (method.getName().equals("lines")) {
                        value = ((List<?>) value).stream().map(line -> ((ComponentLike) line).asComponent()).toList();
                    } else if (value instanceof Set<?> set) {
                        value = Set.copyOf(set);
                    }
                    values.put(method.getName(), value);
                    return proxy;
                });
    }

    private static Object value(Class<?> type, Map<String, Object> values) {
        return Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, (proxy, method, args) -> {
            return switch (method.getName()) {
                case "toString" -> type.getSimpleName() + values;
                case "hashCode" -> values.hashCode();
                case "equals" -> proxy == args[0];
                case "styledLines" -> values.get("lines");
                case "computeEffectiveColor" -> values.get("customColor");
                default -> values.get(method.getName());
            };
        });
    }
}
