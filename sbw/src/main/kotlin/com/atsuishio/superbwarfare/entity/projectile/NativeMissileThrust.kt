package com.atsuishio.superbwarfare.entity.projectile

import net.minecraft.world.phys.Vec3
import kotlin.math.min

/** Server-side motor ramp for legacy native missiles that previously jumped to cruise speed. */
internal object NativeMissileThrust {
    fun step(current: Vec3, facing: Vec3, ageTicks: Int, cruiseSpeed: Double, acceleration: Double): Vec3 {
        if (facing.lengthSqr() < 1.0e-8 || !current.lengthSqr().isFinite()) return current
        val direction = current.scale(0.05).add(facing.normalize().scale(cruiseSpeed)).normalize()
        val speed = min(cruiseSpeed, min(current.length() + acceleration,
            0.5 + ageTicks.coerceAtLeast(0) * acceleration))
        return direction.scale(speed.coerceAtLeast(0.0))
    }
}
