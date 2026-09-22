package com.atsuishio.superbwarfare.entity.vehicle.base

import net.minecraft.util.Mth

/** Reconciles vanilla's byte-angle refresh with the existing full-precision rotation channel. */
internal object GroundRotationRefresh {
    fun angle(wireDegrees: Float, preciseDegrees: Float, stationaryRefresh: Boolean): Float {
        if (!stationaryRefresh || !wireDegrees.isFinite() || !preciseDegrees.isFinite()) {
            return wireDegrees
        }
        // ClientboundTeleportEntityPacket truncates toward zero before narrowing to a byte.
        // A different bucket means the precise channel may be stale: honor the packet then.
        val encoded = (preciseDegrees * 256F / 360F).toInt().toByte()
        val decoded = encoded.toInt() * 360F / 256F
        return if (Mth.wrapDegrees(decoded - wireDegrees) == 0F) preciseDegrees else wireDegrees
    }
}
