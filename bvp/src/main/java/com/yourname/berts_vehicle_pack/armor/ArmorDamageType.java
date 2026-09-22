package com.yourname.berts_vehicle_pack.armor;

enum ArmorDamageType {
    KINETIC("Kinetic"),
    CHEMICAL("Chemical");

    final String displayName;

    ArmorDamageType(String displayName) {
        this.displayName = displayName;
    }
}
