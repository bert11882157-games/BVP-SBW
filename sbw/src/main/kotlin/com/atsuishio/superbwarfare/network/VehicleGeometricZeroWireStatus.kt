package com.atsuishio.superbwarfare.network

import kotlinx.serialization.Serializable

/**
 * Bounded ID67 result discriminator.  MANUAL is the legacy 50/100/200 selection;
 * the remaining values mirror the server-only HasFCS status without transporting a
 * point, direction, or any other authority.
 */
@Serializable
enum class VehicleGeometricZeroWireStatus {
    MANUAL,
    INACTIVE,
    UNSUPPORTED,
    NO_BLOCK_HIT,
    NO_SOLUTION,
    SOLUTION,
}
