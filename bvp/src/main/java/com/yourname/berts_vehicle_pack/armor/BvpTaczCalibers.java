package com.yourname.berts_vehicle_pack.armor;

import net.minecraft.resources.ResourceLocation;

import java.util.HashMap;
import java.util.Map;

/** Exact fired TacZ cartridge IDs with a known nominal projectile diameter. */
public final class BvpTaczCalibers {
    private static final Map<String, Double> CALIBERS = knownCalibers();

    private BvpTaczCalibers() { }

    public static Double forAmmo(ResourceLocation ammoId) {
        return ammoId == null ? null : CALIBERS.get(ammoId.toString());
    }

    private static Map<String, Double> knownCalibers() {
        var values = new HashMap<String, Double>();
        add(values, 5.6, "tacz:22wmr");
        add(values, 7.62, "tacz:308", "tacz:30_06", "tacz:762x25", "tacz:762x39", "tacz:762x54");
        add(values, 8.59, "tacz:338");
        add(values, 9.07, "tacz:357mag");
        add(values, 11.63, "tacz:45_70");
        add(values, 11.43, "tacz:45acp");
        add(values, 4.6, "tacz:46x30");
        add(values, 12.7, "tacz:500mag", "tacz:50ae", "tacz:50bmg");
        add(values, 5.45, "tacz:545x39");
        add(values, 5.56, "tacz:556x45");
        add(values, 5.7, "tacz:57x28");
        add(values, 5.8, "tacz:58x42");
        add(values, 6.8, "tacz:68x51fury");
        add(values, 7.92, "tacz:792x57");
        add(values, 9.0, "tacz:9mm");

        // Exact canonical sbw_flans v47 ammunition identities; rockets and shotguns are
        // deliberately absent because the cartridge/launcher bore is not a pellet or warhead.
        add(values, 9.0, "sbw_flans:9x39", "sbw_flans:9x19_tracer", "sbw_flans:9x19_fmj",
                "sbw_flans:9x18_makarov");
        add(values, 8.0, "sbw_flans:8x50r_lebel");
        add(values, 7.92, "sbw_flans:792x57_mg42", "sbw_flans:792x57_k98",
                "sbw_flans:792x33_kurz");
        add(values, 7.7, "sbw_flans:77x58_arisaka", "sbw_flans:303_british");
        add(values, 7.62, "sbw_flans:762x54r_tracer", "sbw_flans:762x54r_lps",
                "sbw_flans:762x51_m80", "sbw_flans:762x51_m62_tracer",
                "sbw_flans:762x39_tracer", "sbw_flans:762x39_ps",
                "sbw_flans:762x25_tokarev", "sbw_flans:30_carbine",
                "sbw_flans:3006", "sbw_flans:3006_tracer");
        add(values, 7.5, "sbw_flans:75x54_french");
        add(values, 6.5, "sbw_flans:65x50sr_arisaka");
        add(values, 5.8, "sbw_flans:58x42_dbp87");
        add(values, 5.56, "sbw_flans:556x45_m856a1", "sbw_flans:556x45_m855");
        add(values, 5.45, "sbw_flans:545x39_tracer", "sbw_flans:545x39_ps");
        add(values, 12.7, "sbw_flans:50_action_express");
        add(values, 11.43, "sbw_flans:45_acp_tracer", "sbw_flans:45_acp_fmj");
        add(values, 9.0, "sbw_flans:380_acp_fmj");
        add(values, 11.15, "sbw_flans:1115x60r");
        return Map.copyOf(values);
    }

    private static void add(Map<String, Double> values, double caliber, String... ids) {
        for (String id : ids) {
            if (values.putIfAbsent(id, caliber) != null) throw new IllegalStateException("Duplicate TacZ ammo " + id);
        }
    }
}
