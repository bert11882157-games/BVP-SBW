package com.atsuishio.superbwarfare.client.sound;

import java.util.UUID;

/** Origin retained on the actual sound instance, never inferred from the listener's selection. */
public interface AttributedVehicleSound {
    UUID eliteSourceEntity();
    default String eliteWeapon() { return null; }
    default String eliteChannel() { return getClass().getSimpleName(); }
    default UUID eliteCycle() { return null; }
    default int eliteReloadRevision() { return 0; }
}
