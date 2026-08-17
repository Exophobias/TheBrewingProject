package dev.jsinco.brewery.bukkit.listener;

import org.bukkit.event.Event;
import org.bukkit.event.block.Action;
import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PlayerEventListenerStructureGuardTest {

    @Test
    void itemUseDenyBlocksStructureInteraction() {
        assertFalse(PlayerEventListener.shouldHandleStructureInteraction(
                Action.RIGHT_CLICK_BLOCK, false, EquipmentSlot.HAND, Event.Result.DENY
        ));
    }

    @Test
    void itemUseAllowAndDefaultPermitStructureInteraction() {
        assertAll(
                () -> assertTrue(PlayerEventListener.shouldHandleStructureInteraction(
                        Action.RIGHT_CLICK_BLOCK, false, EquipmentSlot.HAND, Event.Result.ALLOW
                )),
                () -> assertTrue(PlayerEventListener.shouldHandleStructureInteraction(
                        Action.RIGHT_CLICK_BLOCK, false, EquipmentSlot.HAND, Event.Result.DEFAULT
                ))
        );
    }

    @Test
    void existingActionHandAndSneakGatesRemain() {
        assertAll(
                () -> assertFalse(PlayerEventListener.shouldHandleStructureInteraction(
                        Action.LEFT_CLICK_BLOCK, false, EquipmentSlot.HAND, Event.Result.ALLOW
                )),
                () -> assertFalse(PlayerEventListener.shouldHandleStructureInteraction(
                        Action.RIGHT_CLICK_AIR, false, EquipmentSlot.HAND, Event.Result.ALLOW
                )),
                () -> assertFalse(PlayerEventListener.shouldHandleStructureInteraction(
                        Action.RIGHT_CLICK_BLOCK, false, EquipmentSlot.OFF_HAND, Event.Result.ALLOW
                )),
                () -> assertFalse(PlayerEventListener.shouldHandleStructureInteraction(
                        Action.RIGHT_CLICK_BLOCK, true, EquipmentSlot.HAND, Event.Result.ALLOW
                ))
        );
    }
}
