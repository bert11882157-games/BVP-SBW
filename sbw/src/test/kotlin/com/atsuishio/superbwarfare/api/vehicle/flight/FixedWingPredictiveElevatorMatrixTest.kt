package com.atsuishio.superbwarfare.api.vehicle.flight

import kotlin.math.*
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

private object ElevatorExperiment {
    private val r = PI / 180.0

    // Exact constructor expressions extracted from BvpAircraftFlightProfiles
    // and generated flight_reference data. Scale is applied exactly once.
    private val profiles = listOf(
        "game" to FixedWingHandlingProfile.GAME_JET,

        "eurofighter_typhoon" to FixedWingReferenceHandling.scale(
            FixedWingHandlingProfile(
                9.80665, 53.34189761169287, 106.68379522338574,
                0.4 * 53.34189761169287, 596.9444444444445,
                25.0, 14.0, 1.2 * 53.34189761169287, 8,
                113757.14 / 15990, 1.4948275862068965, 0.8, 1.2,
                0.0019694533762057882 * 0.022,
                9.80665 * 0.16906855651187894 * 1.75,
                0.0012, 0.25, 6.0, 6.0,
                33.6, 155.0, 19.200000000000003, 9.5,
                1.2, 1.5, 20.0, 3.0, 0.01, 0.04, 16.0, 24.0,
                1, 2, 1.25, 0.0025, 0.0015, 10.0, 0.4, 21.0, 3.0,
                1.0, 446.38888888888886, -(-5.0),
                4.5 * PI / 180 / 1.75,
                0.0019694533762057882 * 0.65,
                24.0, 4.0, 0.0, 0.65,
                FixedWingAtmosphere.densityRatio(11000.0),
                0.8, 1.45, 0.82, 0.25,
                0.0019694533762057882 * 0.009577264155623388,
                null,
            ),
            0.25,
        ),

        "an_12b" to FixedWingReferenceHandling.scale(
            FixedWingHandlingProfile(
                9.80665, 64.7490762644019, 129.4981525288038,
                0.4 * 64.7490762644019, 186.11111111111111,
                17.0, 10.0, 1.2 * 64.7490762644019, 12,
                104604.26666666666 / 51000, 1.0, 0.45, 0.7,
                0.0014619534313725492 * 0.032127674599255356,
                9.80665 * 0.03268960492482275 * 1.6,
                0.0, 0.15, 2.5, 6.0,
                10.0, 22.0, 4.0, 3.0,
                1.2, 1.5, 20.0, 3.0, 0.01, 0.04, 16.0, 24.0,
                1, 2, 1.25, 0.0025, 0.0018, 2.5, 0.4, 15.5, 3.0,
                1.0, 166.66666666666666, -(-0.5),
                5.8 * PI / 180 / 1.6,
                0.0014619534313725492 * 0.6,
                12.0, 4.0, 90.0, 0.0,
                FixedWingAtmosphere.densityRatio(6000.0),
                1.0, 1.0, 0.82, 0.25,
                0.0014619534313725492 * 0.0,
                FixedWingTakeoffHandling(65.3845348748031, 2.0),
            ),
            0.25,
        ),
    )

    private fun forward(m: FixedWingFlightModel) = doubleArrayOf(
        2 * (m.quaternionX * m.quaternionZ + m.quaternionY * m.quaternionW),
        2 * (m.quaternionY * m.quaternionZ - m.quaternionX * m.quaternionW),
        1 - 2 * (m.quaternionX * m.quaternionX + m.quaternionY * m.quaternionY),
    )

    private fun up(m: FixedWingFlightModel) = doubleArrayOf(
        2 * (m.quaternionX * m.quaternionY - m.quaternionZ * m.quaternionW),
        1 - 2 * (m.quaternionX * m.quaternionX + m.quaternionZ * m.quaternionZ),
        2 * (m.quaternionY * m.quaternionZ + m.quaternionX * m.quaternionW),
    )

    private fun ray(
        x: Double, y: Double, z: Double, fp: Boolean,
        permission: Boolean = false, screen: Float = 0.15f,
    ): FixedWingPilotIntent {
        val length = sqrt(x * x + y * y + z * z)
        return FixedWingPilotIntent(
            x / length, y / length, z / length, 0, screen, fp, permission,
        )
    }

    private fun error(m: FixedWingFlightModel, target: FixedWingPilotIntent): Double {
        val f = forward(m)
        return acos(
            (f[0] * target.directionX + f[1] * target.directionY +
                f[2] * target.directionZ).coerceIn(-1.0, 1.0),
        ) / r
    }

    fun pitchCeiling(name: String): Double =
        profiles.first { it.first == name }.second.pitchRateDegreesPerSecond

    fun run(): String {
        val out = StringBuilder()

        // Held-model demand isolates controller suppression from physical load limits.
        // Priming first enters ordinary horizontal guidance, exposing history-dependent
        // shared-yaw limiting and the low-negative-G blended-roll alignment issue.
        for ((name, h) in profiles)
            for (speed in listOf(h.trimSpeedMps, h.trimSpeedMps * 1.5))
                for (bank in listOf(0.0, 30.0, 60.0, 85.0, 180.0))
                    for (e in listOf(3.0, 5.0, 8.0, 10.0))
                        for (fp in listOf(false, true))
                            for (prime in listOf(false, true)) {
                                val m = FixedWingFlightModel(h)
                                m.reset(0.0, 0.0, bank)
                                val c = FixedWingMouseAimController(h)

                                if (prime) repeat(8) {
                                    check(c.update(
                                        m, ray(-sin(8 * r), 0.0, cos(8 * r), fp),
                                        true, false, 0.0, 0.0, speed, 1.0,
                                    ))
                                }

                                val f = forward(m)
                                val u = up(m)
                                val target = ray(
                                    f[0] * cos(e * r) + u[0] * sin(e * r),
                                    f[1] * cos(e * r) + u[1] * sin(e * r),
                                    f[2] * cos(e * r) + u[2] * sin(e * r),
                                    fp, bank > 90, 0f,
                                )
                                repeat(8) {
                                    check(c.update(m, target, true, false,
                                        0.0, 0.0, speed, 1.0))
                                }
                                out.append(
                                    "STATIC h=$name v=$speed bank=$bank error=$e " +
                                        "fp=$fp prime=$prime pitch=${c.elevatorCommand} " +
                                        "roll=${c.aileronCommand}\n",
                                )
                            }

        for ((name, h) in profiles)
            for (kind in listOf("pitch", "heading"))
                for (targetError in listOf(5.0, 8.0, 10.0))
                    for (speed in listOf(h.trimSpeedMps, h.trimSpeedMps * 1.5))
                        for (bank in listOf(0.0, 30.0, 60.0, 85.0, 180.0))
                            for (side in listOf(-1.0, 1.0))
                                for (fp in listOf(false, true)) {
                                    val m = FixedWingFlightModel(h)
                                    m.reset(0.0, -h.trimAngleDegrees, bank * side)
                                    val c = FixedWingMouseAimController(h)
                                    val f = forward(m)
                                    val u = up(m)
                                    val e = targetError * r

                                    val target = if (kind == "pitch") {
                                        ray(
                                            f[0] * cos(e) + u[0] * sin(e),
                                            f[1] * cos(e) + u[1] * sin(e),
                                            f[2] * cos(e) + u[2] * sin(e),
                                            fp, bank > 90, 0f,
                                        )
                                    } else {
                                        ray(
                                            -side * sin(e) * cos(h.trimAngleDegrees * r),
                                            sin(h.trimAngleDegrees * r),
                                            cos(e) * cos(h.trimAngleDegrees * r),
                                            fp, false, (side * 0.15).toFloat(),
                                        )
                                    }

                                    var vx = 0.0
                                    var vy = 0.0
                                    var vz = speed
                                    var first = -1
                                    var tail = 0.0
                                    var maximumError = 0.0
                                    var maxStep = 0.0
                                    var maxBank = 0.0
                                    var energy = 0.0
                                    var maxPitch = 0.0
                                    var requested = 0.0
                                    var initialSurface = 0.0
                                    var initialRate = 0.0

                                    // Central closure is intentionally about three times slower.
                                    repeat(1500) { tick ->
                                        val qx = m.quaternionX
                                        val qy = m.quaternionY
                                        val qz = m.quaternionZ
                                        val qw = m.quaternionW
                                        check(c.update(m, target, true, false,
                                            vx, vy, vz, 1.0))
                                        requested = max(requested, abs(c.elevatorCommand))
                                        check(m.step(
                                            tick.toLong(), vx, vy, vz, false, true,
                                            throttleAxis = if (tick < 14) 1.0 else 0.0,
                                            engineAvailability = 1.0,
                                            surfaces = c,
                                        ))
                                        vx = m.velocityX
                                        vy = m.velocityY
                                        vz = m.velocityZ

                                        if (tick < 20) {
                                            initialSurface = max(initialSurface, abs(m.elevator))
                                            initialRate = max(initialRate,
                                                abs(m.pitchRateDegreesPerSecond))
                                        }

                                        val currentError = error(m, target)
                                        maximumError = max(maximumError, currentError)
                                        if (first < 0 && currentError < 1) first = tick
                                        if (tick >= 1400) tail = max(tail, currentError)
                                        maxBank = max(maxBank, abs(m.rollDegrees))
                                        maxPitch = max(maxPitch,
                                            abs(m.pitchRateDegreesPerSecond))
                                        maxStep = max(maxStep, 2 * acos(abs(
                                            qx * m.quaternionX + qy * m.quaternionY +
                                                qz * m.quaternionZ + qw * m.quaternionW,
                                        ).coerceIn(-1.0, 1.0)) / r)

                                        val work = m.stepThrustWorkPerKg +
                                            m.stepGravityWorkPerKg + m.stepDragWorkPerKg +
                                            m.stepSideWorkPerKg + m.stepLiftWorkPerKg +
                                            m.stepGroundResistanceWorkPerKg
                                        energy = max(energy, abs(
                                            m.postStepKineticEnergyPerKg -
                                                m.preStepKineticEnergyPerKg - work,
                                        ))
                                    }

                                    out.append(
                                        "DYNAMIC h=$name kind=$kind e=$targetError v=$speed " +
                                            "bank=$bank side=$side fp=$fp first=$first tail=$tail " +
                                            "maxError=$maximumError maxBank=$maxBank maxStep=$maxStep " +
                                            "pitchRate=$maxPitch demand=$requested surface=$initialSurface " +
                                            "initialRate=$initialRate energy=$energy finalBank=${m.rollDegrees} finalSpeed=${sqrt(vx*vx+vy*vy+vz*vz)}\n",
                                    )
                                }
        return out.toString()
    }
}

class FixedWingPredictiveElevatorMatrixTest {
    @Test
    fun actualProfilesRetainStrongAlignedPitchAndBoundedIntegratedCapture() {
        val fields = Regex("""(\w+)=([^ ]+)""")
        val rows = ElevatorExperiment.run().lineSequence()
            .filter { it.isNotBlank() }
            .map { line ->
                fields.findAll(line).associate {
                    it.groupValues[1] to it.groupValues[2]
                }
            }.toList()

        val static = rows.filter { "prime" in it }
        val dynamic = rows.filter { "kind" in it }
        assertEquals(480, static.size)
        assertEquals(720, dynamic.size)

        // Fine central travel now intentionally commands partial elevator. It must still
        // respond with either history, including the An-12B's low negative-load limit.
        for (row in static) {
            val demand = row.getValue("pitch").toDouble()
            assertTrue(demand.isFinite() && abs(demand) <= 1.0 + 1e-12, "$row")
            if (row["fp"] == "true" && row.getValue("error").toDouble() >= 5.0) {
                assertTrue(demand >= 0.25, "Aligned central cockpit pitch suppressed: $row")
            }
        }

        for (row in dynamic) {
            val tail = row.getValue("tail").toDouble()
            val step = row.getValue("maxStep").toDouble()
            val energy = row.getValue("energy").toDouble()
            val rate = row.getValue("pitchRate").toDouble()
            assertTrue(tail.isFinite() && tail < 2.0, "Did not settle: $row")
            assertTrue(step.isFinite() && step < 8.0, "Quaternion discontinuity: $row")
            assertTrue(energy.isFinite() && energy < 1e-8, "Energy mismatch: $row")
            assertTrue(
                rate.isFinite() &&
                    rate <= ElevatorExperiment.pitchCeiling(row.getValue("h")) + 1e-9,
                "Physical pitch ceiling exceeded: $row",
            )
        }
    }
}
