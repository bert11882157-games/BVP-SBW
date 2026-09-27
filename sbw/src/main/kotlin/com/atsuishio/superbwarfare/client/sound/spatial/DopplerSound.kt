package com.atsuishio.superbwarfare.client.sound.spatial

import net.minecraft.world.phys.Vec3

/**
 * A playing sound whose source moves. [dopplerVelocity] is the source's velocity in blocks per tick (world frame);
 * [SpatialDoppler] subtracts the listener's own motion and hands the result to OpenAL every tick, so pitch shifts
 * the way it does in the real world instead of through per-sound pitch formulas. Return null for no shift.
 */
interface DopplerSound {
    fun dopplerVelocity(): Vec3?
}
