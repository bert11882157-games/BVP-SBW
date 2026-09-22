package com.yourname.berts_vehicle_pack.entity;

/** No-world acceptance for the optional, bounded rotor-power visual field. */
public final class BvpRotorSpoolTest {
    public static void main(String[] args) {
        int checks = 0;
        for (String input : new String[] { null, "", "NaN", "Infinity", "-Infinity", "-0.001",
                "1.001", "1e309", "broken", "0".repeat(33) }) {
            if (!Double.isNaN(BvpFarVehicleVisuals.parseRotorSpool(input))) {
                throw new AssertionError("Invalid spool accepted: " + input);
            }
            checks++;
        }
        for (int tick = 0; tick <= 1000; tick++) {
            double accepted = tick / 1000.0;
            double restored = BvpFarVehicleVisuals.parseRotorSpool(Double.toString(accepted));
            if (restored != accepted) throw new AssertionError("Rotor power changed during snapshot roundtrip");
            checks++;
        }
        System.out.println("PASS rotor visual snapshot: " + checks + " assertions");
    }
}
