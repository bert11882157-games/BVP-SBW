package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class FixedWingThrustVectoringTest {
    private companion object { const val SETTLE = 45 }

    private val vectoring = FixedWingThrustVectoring(pitchDegrees = 20.0, armMetres = 7.5, gyrationRadiusMetres = 2.8)
    private val plain = ReferenceJetFixture.HANDLING
    private val vectored = plain.copy(thrustVectoring = vectoring)

    private class Surfaces(var elevator: Double) : FixedWingSurfaceInput {
        override val elevatorCommand get() = elevator
        override val aileronCommand = 0.0
        override val rudderCommand = 0.0
        override val groundRollAuthority = 1.0
        override val groundYaw = false
    }

    /**
     * Spools the engine to full throttle for 45 ticks with a neutral elevator, then flies [ticks] steps with [elevator]
     * with the world velocity kept along the nose (a path-following pull, so the angle of attack stays near zero and neither the
     * protection limit nor a stall interferes); returns the model.
     */
    private fun fly(
        handling: FixedWingHandlingProfile, speed: Double, elevator: Double, ticks: Int = 20, grounded: Boolean = false,
    ): FixedWingFlightModel {
        val model = FixedWingFlightModel(handling)
        model.reset(0.0, 0.0, 0.0)
        val surfaces = Surfaces(elevator)
        repeat(SETTLE + ticks) { tick ->
            surfaces.elevator = if (tick < SETTLE) 0.0 else elevator
            val q = model
            val fx = 2.0 * (q.quaternionX * q.quaternionZ + q.quaternionY * q.quaternionW)
            val fy = 2.0 * (q.quaternionY * q.quaternionZ - q.quaternionX * q.quaternionW)
            val fz = 1.0 - 2.0 * (q.quaternionX * q.quaternionX + q.quaternionY * q.quaternionY)
            assertTrue(model.step(
                serverTick = tick.toLong(), inputVelocityX = speed * fx, inputVelocityY = speed * fy, inputVelocityZ = speed * fz,
                grounded = grounded, controlsEnabled = true, throttleAxis = 1.0, surfaces = surfaces,
                gearDeployment = 0.0, wheelsDeployed = false,
            ))
        }
        return model
    }

    @Test
    fun absentOptionLeavesTheFlightModelUntouched() {
        val a = fly(plain, speed = 30.0, elevator = 1.0)
        val b = fly(plain.copy(thrustVectoring = null), speed = 30.0, elevator = 1.0)
        assertEquals(0.0, a.thrustVectorAngleDegrees, 0.0)
        assertEquals(0.0, a.thrustVectorPitchRateDegreesPerSecond, 0.0)
        assertEquals(a.quaternionX, b.quaternionX, 0.0)
        assertEquals(a.quaternionW, b.quaternionW, 0.0)
        assertEquals(a.velocityY, b.velocityY, 0.0)
        assertEquals(a.velocityZ, b.velocityZ, 0.0)
    }

    @Test
    fun nozzleAngleIsTheActuatedElevatorTimesTheRange() {
        assertEquals(20.0, fly(vectored, 30.0, 1.0).thrustVectorAngleDegrees, 1.0E-9)
        assertEquals(-20.0, fly(vectored, 30.0, -1.0).thrustVectorAngleDegrees, 1.0E-9)
        assertEquals(10.0, vectoring.nozzleAngleDegrees(0.5), 1.0E-12)
        // the published elevator is the visual rig's input, so rig angle = elevator * range = physics angle
        val m = fly(vectored, 30.0, 0.5, ticks = 20)
        assertEquals(m.elevator * 20.0, m.thrustVectorAngleDegrees, 1.0E-9)
    }

    @Test
    fun pitchMomentHasTheRightSignAndMagnitude() {
        val up = fly(vectored, 30.0, 1.0)
        val down = fly(vectored, 30.0, -1.0)
        assertTrue(up.thrustVectorPitchRateDegreesPerSecond > 0.0)
        assertTrue(down.thrustVectorPitchRateDegreesPerSecond < 0.0)
        // steady state: thrust x sin(angle) x arm / k^2 over the angular damping, thrust at full scale
        val full = up.thrustAccelerationMps2 / vectored.simulationLengthScale
        val expected = full * sin(20.0 * PI / 180) * 7.5 / (2.8 * 2.8) * 180 / PI / vectored.angularResponsePerSecond
        assertEquals(expected, up.thrustVectorPitchRateDegreesPerSecond, 0.05 * expected)
        // more thrust, more moment: dry power against full afterburner is not tested; zero throttle is
        val idle = FixedWingFlightModel(vectored).also { it.reset(0.0, 0.0, 0.0) }
        idle.step(0, 0.0, 0.0, 30.0, false, true, throttleAxis = 0.0, surfaces = Surfaces(1.0))
        assertEquals(0.0, idle.thrustVectorPitchRateDegreesPerSecond, 1.0E-9)
    }

    @Test
    fun theEffectIsStrongestAtLowSpeedWhereAirflowAuthorityFades() {
        // Surfaces fade with dynamic pressure, thrust does not: the vectored share of the pitch rate rises as speed falls.
        fun share(speed: Double): Double {
            val m = fly(vectored, speed, 1.0, ticks = 20)
            val vector = m.thrustVectorPitchRateDegreesPerSecond
            return vector / (vector + abs(m.pitchRateDegreesPerSecond))
        }
        val slow = share(5.0)   // below the 6 m/s minimum control speed: no aerodynamic authority at all
        val fast = share(60.0)
        assertTrue(slow > 0.95, "slow share $slow")
        assertTrue(fast < 0.5, "fast share $fast")
        assertTrue(slow > fast)
        val slowModel = fly(vectored, 5.0, 1.0, ticks = 20)
        assertTrue(slowModel.thrustVectorPitchRateDegreesPerSecond > 5.0)
    }

    @Test
    fun vectoredThrustLosesForwardPushAndPushesTheTailDown() {
        val (forward, up) = FixedWingThrustVectoring.thrustDirection(20.0).let { it[0] to it[1] }
        assertEquals(Math.cos(20.0 * PI / 180), forward, 1.0E-12)
        assertTrue(up < 0.0) // nose-up nozzle: down force at the tail
        assertEquals(1.0, forward * forward + up * up, 1.0E-12)
        assertEquals(1.0, FixedWingThrustVectoring.thrustDirection(0.0)[0], 0.0)
        assertEquals(-up, FixedWingThrustVectoring.thrustDirection(-20.0)[1], 1.0E-12)
    }

    @Test
    fun noEffectOnTheGroundAndPitchUpIsFadedAtVeryHighAlpha() {
        val ground = fly(vectored, 30.0, 1.0, ticks = 20, grounded = true)
        assertEquals(0.0, ground.thrustVectorPitchRateDegreesPerSecond, 0.0)
        assertEquals(1.0, FixedWingThrustVectoring.alphaAuthority(30.0, 20.0), 0.0)
        assertEquals(0.0, FixedWingThrustVectoring.alphaAuthority(80.0, 20.0), 1.0E-12)
        assertEquals(1.0, FixedWingThrustVectoring.alphaAuthority(80.0, -20.0), 0.0) // pitch-down is never limited
    }

    @Test
    fun rejectsOutOfRangeData() {
        assertThrows(IllegalArgumentException::class.java) { FixedWingThrustVectoring(0.0, 7.5, 2.8) }
        assertThrows(IllegalArgumentException::class.java) { FixedWingThrustVectoring(60.0, 7.5, 2.8) }
        assertThrows(IllegalArgumentException::class.java) { FixedWingThrustVectoring(20.0, -1.0, 2.8) }
        assertThrows(IllegalArgumentException::class.java) { FixedWingThrustVectoring(20.0, 7.5, 0.0) }
    }
}
