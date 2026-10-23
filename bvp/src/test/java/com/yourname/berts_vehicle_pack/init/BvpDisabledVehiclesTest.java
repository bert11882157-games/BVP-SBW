package com.yourname.berts_vehicle_pack.init;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The disabled list (disabled_vehicles.json, 2026-10-23, bert) hides ids from lists and nothing else. */
class BvpDisabledVehiclesTest {
    @Test
    void visibleDropsOnlyDisabledIdsAndKeepsOrder() {
        List<String> disabled = List.copyOf(BvpDisabledVehicles.ids());
        List<String> all = new java.util.ArrayList<>(List.of("a_10", "f_15c", "t72b"));
        all.addAll(disabled);
        all.add("f_16c");
        List<String> visible = BvpDisabledVehicles.visible(all);
        assertEquals(List.of("a_10", "f_15c", "t72b", "f_16c"), visible);
        for (String id : disabled) {
            assertTrue(BvpDisabledVehicles.isDisabled(id));
            assertFalse(visible.contains(id));
            assertTrue(BvpDisabledVehicles.namespacedIds().contains("berts_vehicle_pack:" + id));
        }
        assertFalse(BvpDisabledVehicles.isDisabled("f_15c"));
    }
}
