package dev.jsinco.brewery.bukkit.util.color;

import org.bukkit.Color;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ColorUtilCompatibilityTest {
    @Test
    void existingRecipeNamesKeepTheirOriginalRgb() {
        assertEquals(Color.RED, ColorUtil.parseColorString("RED"));
        assertEquals(Color.GREEN, ColorUtil.parseColorString("green"));
        assertEquals(Color.GRAY, ColorUtil.parseColorString("GRAY"));
        assertEquals(Color.fromRGB(128, 0, 0), ColorUtil.parseColorString("dark_red"));
        assertEquals(Color.FUCHSIA, ColorUtil.parseColorString("pink"));
    }

    @Test
    void additionalNamesAndArbitraryHexColorsRoundTrip() {
        assertEquals(Color.fromRGB(0xffaa00), ColorUtil.parseColorString("gold"));
        assertEquals(Color.fromRGB(0xff55ff), ColorUtil.parseColorString("light_purple"));
        var arbitrary = Color.fromRGB(123, 45, 67);
        assertEquals("#7b2d43", ColorUtil.colorToString(arbitrary));
        assertEquals(arbitrary, ColorUtil.parseColorString(ColorUtil.colorToString(arbitrary)));
        assertEquals(Color.fromRGB(0x123456), ColorUtil.parseColorString("#1234567"));
        assertThrows(IllegalArgumentException.class, () -> ColorUtil.parseColorString("#12"));
    }
}
