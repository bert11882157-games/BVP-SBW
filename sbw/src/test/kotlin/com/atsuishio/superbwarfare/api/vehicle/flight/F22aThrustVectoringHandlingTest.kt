package com.atsuishio.superbwarfare.api.vehicle.flight

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The F-22A's thrust vectoring against a fixed nozzle on the same airframe, at the pack's quarter-distance scale. The profile is built
 * from the F-22A flight_reference exactly as BvpAircraftFlightProfiles.decode does (the bvp module has no offline compile here), and
 * the table of numbers it measures is written to build/tvc-handling.txt for the report.
 */
class F22aThrustVectoringHandlingTest {
    private companion object {
        const val SETTLE = 60
        const val G = 9.80665
        const val SCALE = 0.25
        const val KMH = 1.0 / 3.6

        /** engineering.thrustVectoring of bvp/.../flight_reference/f_22a.json */
        val VECTORING = FixedWingThrustVectoring(pitchDegrees = 20.0, armMetres = 6.54, gyrationRadiusMetres = 2.8)

        fun handling(vectoring: FixedWingThrustVectoring?): FixedWingHandlingProfile {
            val mass = 27865.0
            val area = 78.03999602308292
            val thrust = 231307.49222
            val abMultiplier = 1.3461540310682245
            val forcePerSpeedSquared = 0.5 * 1.225 * area / mass
            val maxCl = 1.8
            val liftSpeed = sqrt(G / (forcePerSpeedSquared * maxCl))
            val maxSpeed = 2414.0 * KMH
            val indicated = 1500.0 * KMH
            val response = 7.5
            val reference = ReferenceJetFixture.PROFILE.copy(
                id = "f_22a", massKg = mass, referenceWingAreaM2 = area, liftSlopePerRadian = 4.5, maxLiftCoefficient = maxCl,
                zeroLiftDragCoefficient = 0.022, inducedDragFactor = 0.1678802411182352, stallDragCoefficient = 0.65,
                stallAoADegrees = 28.0, stallRecoveryAoADegrees = 15.0, stallSpeedMps = liftSpeed,
                stallRecoverySpeedMps = 1.2 * liftSpeed, maximumAoADegrees = 56.0, dryThrustNewtons = thrust,
                afterburnerEnabled = true, afterburnerMultiplier = abMultiplier, maxStructuralSpeedMps = indicated,
                maxEngineSpeedMps = maxSpeed, groundSpeedLimitMps = 1.5 * liftSpeed,
                pitchAuthorityDegPerSecondSquared = 32.2 * response, pitchDampingPerSecond = response,
                rollAuthorityDegPerSecondSquared = 145.0 * response, rollDampingPerSecond = response,
                yawAuthorityDegPerSecondSquared = 18.4 * response, yawDampingPerSecond = response,
                controlEffectivenessDynamicPressurePa = 0.5 * 1.225 * 112.78322414755515 * 112.78322414755515,
                groundBrakingPerSecond = 6.0, stallRecoveryTicks = 8,
            )
            val controls = FixedWingHandlingProfile(
                gravityMps2 = G, liftReferenceSpeedMps = liftSpeed, trimSpeedMps = 112.78322414755515,
                minimumControlSpeedMps = 0.4 * liftSpeed, maximumSpeedMps = maxSpeed, stallAngleDegrees = 28.0,
                recoveryAngleDegrees = 15.0, recoverySpeedMps = 1.2 * liftSpeed, recoveryTicks = 8,
                dryAccelerationMps2 = thrust / mass, afterburnerMultiplier = abMultiplier, spoolUpPerSecond = 0.8,
                spoolDownPerSecond = 1.2, parasiteDragPerMetre = forcePerSpeedSquared * 0.022,
                inducedDragMps2 = G * 0.1678802411182352 * maxCl, airbrakeDragPerMetre = 0.0,
                rollingResistanceMps2 = 0.25, groundBrakingMps2 = 6.0, pitchRateDegreesPerSecond = 32.2,
                rollRateDegreesPerSecond = 145.0, rudderRateDegreesPerSecond = 18.4, angularResponsePerSecond = response,
                maximumLoadFactor = 9.0, pitchProtectionAngleDegrees = 24.0, maximumIndicatedSpeedMps = indicated,
                maximumNegativeLoadFactor = 3.0, normalizedLiftSlopePerDegree = 4.5 * PI / 180 / maxCl,
                stallDragPerMetre = forcePerSpeedSquared * 0.65, taxiFullSteeringSpeedMps = 24.0,
                minimumStallStateSpeedMps = 4.0, jetThrustDensityExponent = 0.65,
                jetThrustDensityKneeRatio = FixedWingAtmosphere.densityRatio(11000.0), jetDryMachFactor = 0.8,
                jetAfterburnerMachFactor = 1.45, waveDragPerMetre = forcePerSpeedSquared * 0.008443239851226154,
                overspeedDragPerMetre = 0.0025, sideDragPerMetre = 0.0015, firstSoftSpeedLimitMps = 460.0 * KMH,
                lowSpeedSurplusBoost = FixedWingHandlingProfile.JET_LOW_SPEED_BOOST, thrustVectoring = vectoring,
            )
            return FixedWingReferenceHandling.fromReference(reference, controls, SCALE)
        }
    }

    private class Surfaces(var elevator: Double) : FixedWingSurfaceInput {
        override val elevatorCommand get() = elevator
        override val aileronCommand = 0.0
        override val rudderCommand = 0.0
        override val groundRollAuthority = 1.0
        override val groundYaw = false
    }

    private data class Sample(val total: Double, val aero: Double, val vector: Double, val nozzle: Double, val alpha: Double, val thrust: Double)

    /**
     * Flies with full afterburner, a neutral stick for [SETTLE] ticks (engine spool) then [elevator] for 25 ticks, holding the world
     * velocity at [speed] and [alpha] degrees of angle of attack in the aircraft's own axes, so the rate it settles at is the
     * authority at that state. [speed] is in real km/h (the world speed is quarter-scale).
     */
    private fun fly(handling: FixedWingHandlingProfile, kmh: Double, alpha: Double, elevator: Double = 1.0): Sample {
        val model = FixedWingFlightModel(handling)
        model.reset(0.0, 0.0, 0.0)
        val surfaces = Surfaces(0.0)
        val world = kmh * KMH * SCALE
        val a = alpha * PI / 180
        repeat(SETTLE + 25) { tick ->
            surfaces.elevator = if (tick < SETTLE) 0.0 else elevator
            val x = model.quaternionX; val y = model.quaternionY; val z = model.quaternionZ; val w = model.quaternionW
            val fx = 2.0 * (x * z + y * w); val fy = 2.0 * (y * z - x * w); val fz = 1.0 - 2.0 * (x * x + y * y)
            val ux = 2.0 * (x * y - z * w); val uy = 1.0 - 2.0 * (x * x + z * z); val uz = 2.0 * (y * z + x * w)
            // positive angle of attack: the relative wind comes from below the nose
            val vx = world * (cos(a) * fx - sin(a) * ux)
            val vy = world * (cos(a) * fy - sin(a) * uy)
            val vz = world * (cos(a) * fz - sin(a) * uz)
            assertTrue(model.step(
                serverTick = tick.toLong(), inputVelocityX = vx, inputVelocityY = vy, inputVelocityZ = vz,
                grounded = false, controlsEnabled = true, throttleAxis = 1.0, afterburnerRequested = true,
                surfaces = surfaces, gearDeployment = 0.0, wheelsDeployed = false,
            ))
        }
        return Sample(
            model.pitchRateDegreesPerSecond + model.thrustVectorPitchRateDegreesPerSecond,
            model.pitchRateDegreesPerSecond, model.thrustVectorPitchRateDegreesPerSecond,
            model.thrustVectorAngleDegrees, model.angleOfAttackDegrees, model.thrustAccelerationMps2 / SCALE,
        )
    }

    private val fixed = handling(null)
    private val vectored = handling(VECTORING)

    @Test
    fun theF22aFixtureMatchesTheFlightReference() {
        assertEquals(20.0, vectored.thrustVectoring!!.pitchDegrees, 0.0)
        assertEquals(6.54, vectored.thrustVectoring!!.armMetres, 0.0)
        assertEquals(null, fixed.thrustVectoring)
    }

    @Test
    fun vectoringRaisesTheNosePitchRateAtEverySpeedAndMostAtLowSpeedAndHighAlpha() {
        val out = StringBuilder("F-22A pitch rate, full afterburner, full nose-up stick (deg/s), thrust vectoring OFF vs ON\n")
        out.append("state                         off total  on total  on aero  on vector  gain   nozzle  thrust m/s2\n")
        class Row(val label: String, val kmh: Double, val alpha: Double)
        val rows = listOf(
            Row("200 km/h  alpha  5", 200.0, 5.0), Row("200 km/h  alpha 30", 200.0, 30.0),
            Row("200 km/h  alpha 45", 200.0, 45.0), Row("200 km/h  alpha 60", 200.0, 60.0),
            Row("350 km/h  alpha 10", 350.0, 10.0), Row("350 km/h  alpha 30", 350.0, 30.0),
            Row("500 km/h  alpha  5", 500.0, 5.0), Row("500 km/h  alpha 20", 500.0, 20.0),
            Row("900 km/h  alpha  5", 900.0, 5.0), Row("1200 km/h alpha  3", 1200.0, 3.0),
        )
        val gain = HashMap<String, Double>()
        for (row in rows) {
            val off = fly(fixed, row.kmh, row.alpha)
            val on = fly(vectored, row.kmh, row.alpha)
            gain[row.label] = on.total / off.total
            out.append(String.format(Locale.ROOT, "%-29s %8.1f  %8.1f  %7.1f  %9.1f  x%-5.2f %5.1f  %8.2f%n",
                row.label, off.total, on.total, on.aero, on.vector, on.total / off.total, on.nozzle, on.thrust))
            assertEquals(0.0, off.vector, 0.0)
            assertTrue(on.total > off.total, "${row.label}: vectored ${on.total} vs fixed ${off.total}")
        }
        // pull-down too: the nozzle swings the other way
        val down = fly(vectored, 200.0, 5.0, elevator = -1.0)
        val downOff = fly(fixed, 200.0, 5.0, elevator = -1.0)
        out.append(String.format(Locale.ROOT, "nose-down, 200 km/h alpha 5: off %.1f  on %.1f (vector %.1f, nozzle %.1f)%n",
            downOff.total, down.total, down.vector, down.nozzle))
        assertTrue(down.total < downOff.total)
        File("build").mkdirs()
        File("build/tvc-handling.txt").writeText(out.toString())
        // the point of the feature: it matters most when the surfaces are weakest
        assertTrue(gain.getValue("200 km/h  alpha 30") > gain.getValue("500 km/h  alpha  5"))
        assertTrue(gain.getValue("200 km/h  alpha 30") >= 1.5, "low-speed gain ${gain["200 km/h  alpha 30"]}")
        assertTrue(abs(fly(vectored, 200.0, 5.0).nozzle) <= 20.0 + 1.0e-9)
    }

    /**
     * Free flight (the model's own velocity fed back, gravity and drag on): level flight, then full nose-up stick for 4 s. Reports the
     * nose attitude and flight-path angle reached and the peak angle of attack, vectoring off against on. The flight path lags the nose
     * with vectoring: the exhaust-up nozzle pushes the tail down, which is a real cost of the push.
     */
    @Test
    fun aFullPullInFreeFlightKeepsAngleOfAttackOutOfTheDeepStallWhereTheSurfacesWork() {
        class Pull(val turn: Double, val peakG: Double, val peakAlpha: Double, val endAlpha: Double, val endKmh: Double, val nose: Double)
        fun pull(handling: FixedWingHandlingProfile, kmh: Double): Pull {
            val model = FixedWingFlightModel(handling)
            model.reset(0.0, 0.0, 0.0)
            val surfaces = Surfaces(0.0)
            var vx = 0.0; var vy = 0.0; var vz = kmh * KMH * SCALE
            var peakG = 0.0; var peakAlpha = 0.0; var turn = 0.0
            val pullStart = 40
            repeat(pullStart + 80) { tick ->
                surfaces.elevator = if (tick < pullStart) 0.0 else 1.0
                assertTrue(model.step(
                    serverTick = tick.toLong(), inputVelocityX = vx, inputVelocityY = vy, inputVelocityZ = vz,
                    grounded = false, controlsEnabled = true, throttleAxis = 1.0, afterburnerRequested = true,
                    surfaces = surfaces, gearDeployment = 0.0, wheelsDeployed = false,
                ))
                val nx = model.velocityX; val ny = model.velocityY; val nz = model.velocityZ
                if (tick >= pullStart) {
                    // load factor from the change of the velocity direction (centripetal) plus gravity on the vertical
                    val speed = sqrt(nx * nx + ny * ny + nz * nz)
                    val a = sqrt((nx - vx) * (nx - vx) + (ny - vy + 0.0) * (ny - vy) + (nz - vz) * (nz - vz)) * 20.0
                    peakG = maxOf(peakG, a / SCALE / G)
                    peakAlpha = maxOf(peakAlpha, abs(model.angleOfAttackDegrees))
                    turn = Math.toDegrees(Math.atan2(ny, sqrt(nx * nx + nz * nz)))
                    if (speed <= 0.0) return Pull(turn, peakG, peakAlpha, 0.0, 0.0, 0.0)
                }
                vx = nx; vy = ny; vz = nz
            }
            val speed = sqrt(vx * vx + vy * vy + vz * vz)
            val nose = Math.toDegrees(Math.asin((2.0 * (model.quaternionY * model.quaternionZ - model.quaternionX * model.quaternionW)).coerceIn(-1.0, 1.0)))
            return Pull(turn, peakG, peakAlpha, model.angleOfAttackDegrees, speed / SCALE / KMH, nose)
        }
        val out = StringBuilder("F-22A free-flight full pull, 4 s, full afterburner (OFF / ON)\n")
        out.append("start speed   nose attitude after 4 s   flight-path climb angle after 4 s   peak AoA   end AoA   end km/h\n")
        val paths = ArrayList<Triple<Double, Double, Double>>()
        for (kmh in listOf(200.0, 350.0, 500.0, 900.0)) {
            val off = pull(fixed, kmh)
            val on = pull(vectored, kmh)
            out.append(String.format(Locale.ROOT, "%5.0f km/h   %6.1f / %6.1f deg   %6.1f / %6.1f deg   %5.1f / %5.1f   %5.1f / %5.1f   %5.0f / %5.0f%n",
                kmh, off.nose, on.nose, off.turn, on.turn, off.peakAlpha, on.peakAlpha, off.endAlpha, on.endAlpha, off.endKmh, on.endKmh))
            paths += Triple(kmh, on.peakAlpha, on.nose)
        }
        File("build").mkdirs()
        File("build/tvc-handling-free.txt").writeText(out.toString())
        for ((kmh, alpha, _) in paths) if (kmh >= 350.0) assertTrue(alpha < 40.0, "$kmh km/h: peak angle of attack $alpha")
    }
}
