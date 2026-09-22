package com.atsuishio.superbwarfare.entity.vehicle.base

/** A complete, validated selection transaction; callers publish both lists before side effects. */
internal data class VehicleWeaponSlots(val primary: List<Int>, val secondary: List<Int>) {
    fun select(valid: List<List<Int>>, seat: Int, primaryIndex: Int, secondaryIndex: Int?): VehicleWeaponSlots? {
        if (seat !in valid.indices || primary.size != valid.size || secondary.size != valid.size) return null
        val ordered = valid[seat]
        if (primaryIndex !in ordered || secondaryIndex == primaryIndex ||
            (secondaryIndex != null && secondaryIndex !in ordered)) return null
        val p = primary.toMutableList().also { it[seat] = primaryIndex }
        val s = secondary.toMutableList().also { it[seat] = secondaryIndex ?: -1 }
        return VehicleWeaponSlots(p.toList(), s.toList())
    }

    companion object {
        fun normalize(valid: List<List<Int>>, primary: List<Int>, secondary: List<Int>): VehicleWeaponSlots {
            val p = ArrayList<Int>(valid.size)
            val s = ArrayList<Int>(valid.size)
            for ((seat, ordered) in valid.withIndex()) {
                val selected = primary.getOrNull(seat)?.takeIf { it in ordered } ?: ordered.firstOrNull() ?: -1
                p.add(selected)
                s.add(secondary.getOrNull(seat)?.takeIf { it in ordered && it != selected }
                    ?: ordered.firstOrNull { it != selected } ?: -1)
            }
            return VehicleWeaponSlots(p.toList(), s.toList())
        }
    }
}
