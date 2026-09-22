package com.atsuishio.superbwarfare.entity.vehicle.utils

import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModDamageTypes
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.tools.VectorTool.calculateY
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import org.joml.Math
import kotlin.math.min

object VehicleEngineUtils {
    @JvmStatic
    fun VehicleEntity.trackEngine(engineInfo: EngineInfo.Track) {
        applyTrackGroundMotion(engineInfo)
    }

    @JvmStatic
    fun VehicleEntity.wheelEngine(engineInfo: EngineInfo.Wheel) {
        applyWheelGroundMotion(engineInfo)
    }

    @JvmStatic
    fun VehicleEntity.shipEngine(engineInfo: EngineInfo.Ship) {
        val buoyancy = engineInfo.buoyancy
        val energyCost = (engineInfo.energyCostRate * Mth.abs(power)).toInt()
        val maxForwardSpeedRate = engineInfo.maxForwardSpeedRate
        val maxBackwardSpeedRate = engineInfo.maxBackwardSpeedRate
        val powerAdd = engineInfo.increment
        val powerReduce = engineInfo.decrement
        val steeringSpeed = engineInfo.steeringSpeed
        val bodyPitchRate = engineInfo.bodyPitchRate

        if (buoyancy != 0.0) {
            val fluidFloat = buoyancy * VehicleVecUtils.getSubmergedHeight(this)
            deltaMovement = deltaMovement.add(0.0, fluidFloat, 0.0)
        }

        if (onGround()) {
            deltaMovement = deltaMovement.multiply(0.2, 0.99, 0.2)
        } else if (isInFluidType) {
            val f = (0.835f - 0.04f * min(VehicleVecUtils.getSubmergedHeight(this), bbHeight.toDouble()) + 0.005f * Mth.abs(deltaMovement.normalize().dot(getViewVector(1f)).toFloat()))
            deltaMovement = deltaMovement.add(
                getViewVector(1f).normalize()
                    .scale(0.04 * deltaMovement.dot(getViewVector(1f)))
            )
            deltaMovement = deltaMovement.multiply(f, 0.85, f)
        } else {
            deltaMovement = deltaMovement.multiply(0.99, 0.99, 0.99)
        }

        if (level().isClientSide && isInFluidType && deltaMovement.horizontalDistanceSqr() > 0.3162) {
            val y = y + VehicleVecUtils.getSubmergedHeight(this) - 0.2
            addRandomParticle(ParticleTypes.CLOUD, position().add(
                0.0,
                y,
                0.0),
                1.2f, level(), 0f, (2 + 4 * deltaMovement.length()).toInt())

            addRandomParticle(ParticleTypes.BUBBLE_COLUMN_UP, position().add(
                0.0,
                y,
                0.0), 1.2f, level(), 0f, (2 + 10 * deltaMovement.length()).toInt())

            addRandomParticle(ParticleTypes.BUBBLE_COLUMN_UP, position().add(
                -4.5 * lookAngle.x,
                -0.25,
                -4.5 * lookAngle.z), 0.3f, level(), 0f, (40 * Mth.abs(power)).toInt())

        }

        val passenger0 = getFirstPassenger()

        if (!hasOperationalPower(energyCost)) {
            forwardInputDown = false
            backInputDown = false
            power *= 0.95f
        }

        if (passenger0 == null) {
            leftInputDown = false
            rightInputDown = false
            forwardInputDown = false
            backInputDown = false
        }

        val maxPower = if (sprintInputDown) 1.3f else (if (power > 1) power - 0.002f else 1f)

        if (forwardInputDown && !backInputDown) {
            power = Math.min(power + (if (power < 0) powerAdd * 2f else powerAdd) * (maxPower - (Mth.abs(power) / 1.02f)), maxPower)
        }

        if (backInputDown) {
            power = Math.max(
                    power - (if (power > 0) powerReduce * 4f else powerReduce) * (maxPower - (Mth.abs(power) / 1.02f)), -1f
            )
        }

        targetSpeed = if (power > 0) {
            maxForwardSpeedRate.toDouble()
        } else {
            maxBackwardSpeedRate.toDouble()
        }

        if (!forwardInputDown && !backInputDown) {
            power *= 0.97f
        }

        if (rightInputDown || leftInputDown) {
            power *= 0.98f
        }

        if (mainEngineDamaged) {
            power *= 0.875f
        }

        if (level() is ServerLevel) {
            consumeOperationalPower(energyCost)
        }

        if (rightInputDown) {
            holdTick++
            deltaRot -= steeringSpeed * 0.03f * Math.min(holdTick, 40)
        } else if (leftInputDown) {
            holdTick++
            deltaRot += steeringSpeed * 0.03f * Math.min(holdTick, 40)
        } else {
            holdTick = 0
        }

        deltaRot *= Math.max(0.78f - 0.25f * deltaMovement.horizontalDistance(), 0.1).toFloat()

        propellerRot += 2 * power
        rudderRot = Mth.clamp(
            rudderRot - deltaRot,
            -0.8f,
            0.8f
        ) * 0.75f

        if (isInFluidType || isUnderWater) {
            xRot *= 0.85f
            val direct = (90 - VehicleVecUtils.calculateAngle(deltaMovement, getViewVector(1f)).toFloat()) / 90
            xRot =
                (xRot - direct * (if (onGround()) 0 else 1) * bodyPitchRate * deltaMovement.horizontalDistance()).toFloat()
            yRot = (yRot - 20 * deltaMovement.horizontalDistance() * deltaRot * (if (power > 0) 1 else -1)).toFloat()
            deltaMovement = deltaMovement.add(
                getViewVector(1f).scale(0.11 * targetSpeed * power * (if (Mth.abs(power) <= 1) Mth.abs(power) else 1f))
            )

            deltaMovement = deltaMovement.add(
                getUpVec(1f).scale(deltaMovement.length() * 0.005 * VehicleVecUtils.getSubmergedHeight(this) * Mth.abs(xRot))
            )
        } else {
            xRot *= 0.99f
        }

        setZRot(roll * 0.95f)
    }

    @JvmStatic
    fun VehicleEntity.helicopterEngine(engineInfo: EngineInfo.Helicopter) {
        runHelicopterEngine(engineInfo, true)
    }

    /** Native engine, attitude, operational power, and rotor state without legacy lift/drag/motion writes. */
    @JvmStatic
    fun VehicleEntity.helicopterControlLifecycle(engineInfo: EngineInfo.Helicopter) {
        runHelicopterEngine(engineInfo, false)
    }

    private fun VehicleEntity.runHelicopterEngine(engineInfo: EngineInfo.Helicopter, applyMotion: Boolean) {
        val energyCost = engineInfo.energyCostRate.toInt()
        val powerAdd = engineInfo.increment
        val powerReduce = engineInfo.decrement
        var pitchSpeed = engineInfo.pitchSpeed
        var yawSpeed = engineInfo.yawSpeed
        var rollSpeed = engineInfo.rollSpeed
        val lift = engineInfo.liftSpeed
        val speed = engineInfo.speed

        if (onGround()) {
            if (applyMotion) {
                deltaMovement = deltaMovement.multiply(0.8, 1.0, 0.8)
            }
        } else {
            if (!sympatheticDetonated) {
                setZRot(roll * (if (backInputDown) 0.9f else 0.99f))
            }
            if (applyMotion) {
                val f = Mth.clamp(
                    0.93499f - 0.01 * deltaMovement.lengthSqr() + (0.07 * speed) + 0.001f * Mth.abs(deltaMovement.normalize().dot(getViewVector(1f)).toFloat()), 0.01, 0.99
                ).toFloat()
                deltaMovement = deltaMovement.add(
                    getViewVector(1f).scale(
                        (if (xRot < 0) -0.001 else (if (xRot > 0) 0.001 else 0.0)) * deltaMovement.length()
                    )
                )
                deltaMovement = deltaMovement.multiply(f.toDouble(), 0.95, f.toDouble())
            }
        }

        if (isInFluidType && tickCount % 4 == 0 && VehicleVecUtils.getSubmergedHeight(this) > 0.5 * bbHeight) {
            if (applyMotion) {
                deltaMovement = deltaMovement.multiply(0.6, 0.6, 0.6)
            }
            hurt(
                ModDamageTypes.causeVehicleStrikeDamage(
                    level().registryAccess(),
                    vehicle,
                    if (getFirstPassenger() == null) vehicle else getFirstPassenger()
                ), 6 + (20 * ((lastTickSpeed - 0.4) * (lastTickSpeed - 0.4))).toFloat()
            )
            crash = true
        }

        val pilot = getFirstPassenger()
        val hasPassenger = getPassengers().isNotEmpty()

        val diffX: Float
        val diffZ: Float

        if (health > 0.1f * getMaxHealth()) {
            if (pilot == null) {
                leftInputDown = false
                rightInputDown = false
                forwardInputDown = false
                backInputDown = false
                upInputDown = false
                downInputDown = false

                if (!isWreck) {
                    roll *= 0.99f
                    xRot -= 0.5f * deltaMovement.dot(getViewVector(1f)).toFloat()
                    roll -= 0.5f * deltaMovement.dot(getRightVec(1f)).toFloat()
                }

                if (!hasPassenger && engineStartOver) {
                    power = Math.max(power * 0.99f, if (onGround()) 0f else 0.048f)
                }
            } else {
                if (hoverMode) {
                    roll *= 0.97f
                    roll -= 0.5f * deltaMovement.dot(getRightVec(1f)).toFloat()

                    xRot *= 0.97f
                    xRot -= 0.5f * deltaMovement.dot(getViewVector(1f)).toFloat()

                    rollSpeed *= 0.05f
                    yawSpeed *= 0.5f
                    pitchSpeed *= 0.2f

                    if (applyMotion) {
                        deltaMovement = deltaMovement.multiply(0.95, 1.0, 0.95)
                    }
                }

                if (rightInputDown) {
                    holdTick++
                    deltaRot -= 1f * Math.min(holdTick, 7) * power
                } else if (leftInputDown) {
                    holdTick++
                    deltaRot += 1f * Math.min(holdTick, 7) * power
                } else {
                    holdTick = 0
                }
                xRot += (if (onGround()) 0f else 1.5f) * pitchSpeed * mouseMoveSpeedY * synchedPropellerRot
                setZRot(roll - rollSpeed * (deltaRot + (if (onGround()) 0f else 0.25f) * mouseMoveSpeedX * synchedPropellerRot))

                yRot += yawSpeed * Mth.clamp(
                    (if (onGround()) 0.1f else 2f) * mouseMoveSpeedX * synchedPropellerRot + (if (subEngineDamaged) 25 else 0) * synchedPropellerRot,
                    -10f,
                    10f
                )

                if (onGround()) {
                    hoverMode = false
                    setZRot(roll * 0.98f)
                    xRot *= 0.98f
                }
            }

            if (!hasOperationalPower(energyCost)) {
                power *= 0.995f
                forwardInputDown = false
                backInputDown = false
                engineStart = false
                engineStartOver = false
            } else {
                val up = forwardInputDown
                val down = downInputDown

                if (upInputDown) {
                    upInputDown = false
                    hoverMode = !hoverMode
                }

                if (!engineStart && up) {
                    engineStart = true
                    level().playSound(null, this, engineInfo.engineStartSound, soundSource, 3f, 1f)
                }

                if (up && engineStartOver) {
                    holdPowerTick++
                    power = Math.min(power + 0.0007f * powerAdd * Math.min(holdPowerTick, 10), 0.12f)
                }

                if (engineStartOver) {
                    if (down) {
                        holdPowerTick++
                        power = Math.max(
                            power - 0.001f * powerReduce * Math.min(holdPowerTick, 5),
                            if (onGround()) 0f else 0.035f / lift
                        )
                    } else if (backInputDown) {
                        holdPowerTick++
                        power = Math.max(
                            power - 0.001f * powerReduce * Math.min(holdPowerTick, 5),
                            if (onGround()) 0f else 0.058f / lift
                        )
                    }
                }

                if (engineStart && !engineStartOver) {
                    power = Math.min(power + 0.0012f * powerAdd, 0.045f)
                }

                if (!(up || down || backInputDown) && engineStartOver) {
                    val force = (if (hoverMode) 0.01f else 0.002f) * deltaMovement.y().toFloat()
                    power = if (deltaMovement.y() < 0) {
                        Math.min(power - force, 0.12f)
                    } else {
                        Math.max(power - (if (onGround()) 0.25f * force else force), 0f)
                    }
                    holdPowerTick = 0
                }
            }
        } else if (engineStartOver) {
            power = Math.max(power - (if (isWreck) 0.0006f else 0.0003f), if (onGround()) 0f else 0.04f)

            if (!onGround()) {
                destroyRot += if (isWreck) 0.2f else 0.06f
            }

            destroyRot *= if (onGround()) 0.99f else 0.994f

            diffX = -15 - xRot
            diffZ = -35 - roll

            xRot += diffX * 0.2f * synchedPropellerRot
            yRot += destroyRot
            setZRot(roll + diffZ * 0.75f * synchedPropellerRot)
            if (applyMotion) {
                deltaMovement = deltaMovement.add(getViewVector(1f).scale(-0.006f * destroyRot.toDouble()))
            }
        }

        if (mainEngineDamaged) {
            power *= 0.98f
        }

        deltaRot *= 0.9f
        synchedPropellerRot = Mth.lerp(0.18f, synchedPropellerRot, power)
        propellerRot += 30 * synchedPropellerRot
        synchedPropellerRot *= 0.9995f

        if (engineStart) {
            consumeOperationalPower(
                (energyCost * 8.3333f * Mth.abs(power)).toInt()
            )
        }

        if (applyMotion) {
            deltaMovement = deltaMovement.add(
                getUpVec(1f).scale((synchedPropellerRot * lift * 0.66f).toDouble())
            )
        }

        if (power > 0.04f) {
            engineStart = true
            engineStartOver = true
        }

        if (power < 0.0004f) {
            engineStart = false
            engineStartOver = false
        }
    }

    @JvmStatic
    fun VehicleEntity.aircraftEngine(engineInfo: EngineInfo.Aircraft) {
        var powerAdd = engineInfo.increment
        val powerReduce = engineInfo.decrement
        val pitchSpeed = engineInfo.pitchSpeed
        val yawSpeed = engineInfo.yawSpeed
        val rollSpeed = engineInfo.rollSpeed
        val lift = engineInfo.liftSpeed
        val speedRate = engineInfo.speedRate
        val resistance = engineInfo.resistance * if (downInputDown) 1.5 else 1.0
        val gearRotateAngle = engineInfo.gearRotateAngle
        val energyCost = (engineInfo.energyCostRate * Mth.abs(power)).toInt()

        val speedSqr = deltaMovement.lengthSqr()
        val speed = deltaMovement.length()
        val dotViewVector = deltaMovement.dot(getViewVector(1f))
        val normalizeDotViewVector = deltaMovement.normalize().dot(getViewVector(1f))

        var f = Mth.clamp(0.96 - 0.0017 * resistance * speedSqr - 0.00001 * (1 - Mth.abs(normalizeDotViewVector.toFloat())), 0.01, 0.99).toFloat()

        if (onGround()) {
            if (isWreck) {
                deltaMovement = deltaMovement.multiply(0.9, 1.0, 0.9)
            }
            f = 0.497f + 0.45f * Mth.abs(normalizeDotViewVector.toFloat())
            deltaMovement = deltaMovement.add(
                getViewVector(1f).normalize()
                    .scale(0.05 * dotViewVector)
            )
        } else {
            val forward = dotViewVector > 0
            deltaMovement = deltaMovement.add(
                getViewVector(1f)
                    .scale((if (forward) 0.04 else -0.04) * dotViewVector)
            )
        }

        deltaMovement = deltaMovement.multiply(f.toDouble(), f.toDouble(), f.toDouble())

        if (isInFluidType && tickCount % 4 == 0) {
            deltaMovement = deltaMovement.multiply(0.6, 0.6, 0.6)
            if (lastTickSpeed > 0.4) {
                hurt(
                    ModDamageTypes.causeVehicleStrikeDamage(
                        level().registryAccess(),
                        this,
                        if (getFirstPassenger() == null) this else getFirstPassenger()
                    ), (20 * ((lastTickSpeed - 0.4) * (lastTickSpeed - 0.4))).toFloat()
                )
            }
            crash = true
        }

        if (!hasOperationalPower(energyCost)) {
            forwardInputDown = false
            backInputDown = false
            engineStart = false
            engineStartOver = false
            power *= 0.996f
            deltaMovement = deltaMovement.multiply(0.998, 1.0, 0.998)
        } else {
            consumeOperationalPower(energyCost)
        }

        if (health > 0.1f * getMaxHealth()) {
            if (getPassengers().isEmpty() || isInFluidType) {
                leftInputDown = false
                rightInputDown = false
                forwardInputDown = false
                backInputDown = false
                if (onGround()) {
                    power *= 0.95f
                    deltaMovement = deltaMovement.multiply(0.94, 1.0, 0.94)
                } else {
                    power *= 0.995f
                    xRot = Mth.clamp(xRot + 0.1f, -89f, 89f)
                }
            }

            if (firstPassenger != null) {
                if (!engineStart && forwardInputDown && power > 0.01f) {
                    engineStart = true
                    level().playSound(null, this, engineInfo.engineStartSound, soundSource, 3f, 1f)
                }

                if (sprintInputDown || onGround()) {
                    powerAdd *= 1.6f
                }

                if (hasOperationalPower(energyCost)) {
                    val maxPower = if (sprintInputDown || onGround()) 3 else (if (power > 1) power - 0.012 else 1)

                    if (forwardInputDown) {
                        power = Mth.clamp((power + 0.006f * powerAdd).toDouble(), -0.1, maxPower.toDouble()).toFloat() }

                    if (backInputDown) {
                        power = Math.max(
                                power - 0.006f * powerReduce, if (onGround()) -0.2f else 0.025f
                        )
                    }
                }

                if (!onGround()) {
                    if (rightInputDown) {
                        holdTick++
                        deltaRot -= 0.05f * Math.min(holdTick, 12)
                    } else if (leftInputDown) {
                        holdTick++
                        deltaRot += 0.05f * Math.min(holdTick, 12)
                    } else {
                        holdTick = 0
                    }
                } else {
                    holdTick = 0
                }

                // 刹车
                if (downInputDown) {
                    if (onGround()) {
                        power *= 0.92f
                        deltaMovement = deltaMovement.multiply(0.97, 1.0, 0.97)
                    } else {
                        power = Math.max(power * 0.97f, if (onGround()) -0.3f else 0.025f)
                    }

                    planeBreak = Math.min(planeBreak + 10, 60f)
                }
            }

            val rotSpeed = 0.3f + 3.2f * Mth.abs(calculateY(roll))

            var addY = Mth.clamp(Math.max(0.24f * speed.toFloat(), 0f) * mouseMoveSpeedX, -rotSpeed, rotSpeed)
            if (onGround()) {
                addY = Mth.clamp(Mth.clamp(0.6f * speed.toFloat(), 0f, 0.75f) * mouseMoveSpeedX, -1f, 1f)
            }

            val addX = Mth.clamp(Mth.clamp(dotViewVector - 0.24, 0.1, 0.2).toFloat() * mouseMoveSpeedY, -3.5f, 3.5f)
            val addZ = deltaRot - (if (onGround() || Mth.abs(roll) > 60) 0f else 0.02f * (60 - Mth.abs(roll)) / 60) * mouseMoveSpeedX * dotViewVector.toFloat()

            yRot += yawSpeed * addY
            if (!onGround()) {
                xRot += pitchSpeed * addX

                if (tickCount > 5) {
                    updateRotation(this)
                }

                if ((roll > 0 && addY < 0) || (roll < 0 && addY > 0)) {
                    addY *= Mth.clamp(1f - Mth.abs(roll) / 45, 0f, 1f)
                }

                setZRot(roll - rollSpeed * addZ)
            }

            // 自动回正
            if (!onGround()) {
                val xSpeed = 1 - Mth.clamp((Mth.abs(xRot) - 60) / 90, 0f, 1f)
                val zSpeed = Mth.clamp(Mth.abs(roll) / 90, 0f, 1f)

                if (roll > 0) {
                    setZRot(roll - Math.min(zSpeed, roll))
                } else if (roll < 0) {
                    setZRot(roll + Math.min(zSpeed, -roll))
                }

                roll *= xSpeed
            }

            propellerRot += 30 * power

            // 起落架
            if (engineInfo.hasGear) {
                if (upInputDown) {
                    upInputDown = false
                    if (synchedGearRot == 0f && !onGround()) {
                        gearUp = true
                    } else if (synchedGearRot == 1f) {
                        gearUp = false
                    }
                }

                if (onGround()) {
                    gearUp = false
                }

                synchedGearRot = if (gearUp) {
                    Math.min(synchedGearRot + 0.05f, 1f)
                } else {
                    Math.max(synchedGearRot - 0.05f, 0f)
                }

                gearRot = synchedGearRot * gearRotateAngle
            }

            val flapX =
                (1 - (Mth.abs(roll)) / 90) * Mth.clamp(mouseMoveSpeedY * 3, -15f, 15f) - calculateY(
                    roll
                ) * Mth.clamp(mouseMoveSpeedX * 3, -15f, 15f)

            flap1LRot = Mth.clamp(
                -flapX - 15 * addZ - planeBreak,
                -15f,
                15f
            )
            flap1RRot = Mth.clamp(
                -flapX + 15 * addZ - planeBreak,
                -15f,
                15f
            )
            flap1L2Rot = Mth.clamp(
                -flapX - 15 * addZ + planeBreak,
                -15f,
                15f
            )
            flap1R2Rot = Mth.clamp(
                -flapX + 15 * addZ + planeBreak,
                -15f,
                15f
            )

            flap2LRot = Mth.clamp(flapX - 15 * addZ, -15f, 15f)
            flap2RRot = Mth.clamp(flapX + 15 * addZ, -15f, 15f)

            val flapY =
                (1 - (Mth.abs(roll)) / 90) * Mth.clamp(mouseMoveSpeedX * 3, -15f, 15f) + calculateY(
                    roll
                ) * Mth.clamp(mouseMoveSpeedY * 3, -15f, 15f)
            flap3Rot = flapY * 5
        } else {
            power = Math.max(power - if (onGround()) 0.03f else 0.0003f, if (onGround()) 0f else 0.02f)
            if (onGround()) {
                destroyRot *= 0.95f
            } else {
                destroyRot += 0.1f
            }

            val diffX: Float = 90 - xRot
            xRot += diffX * 0.001f * destroyRot
            setZRot(roll - destroyRot)
            deltaMovement = deltaMovement.add(0.0, -0.03, 0.0)
            deltaMovement = deltaMovement.add(0.0, -destroyRot * 0.005, 0.0)
        }

        deltaRot *= 0.9f
        planeBreak *= 0.8f
        if (onGround()) {
            power *= 0.995f
        }

        if (mainEngineDamaged) {
            power *= 0.96f
        }

        if (subEngineDamaged) {
            power *= 0.96f
        }

        val flapAngle = ((flap1LRot + flap1RRot + flap1L2Rot + flap1R2Rot) / 50).toDouble()

        deltaMovement = deltaMovement.add(
            getUpVec(1f).scale(
                (1 - Mth.abs(deltaMovement.normalize().dot(getUpVec(1f)).toFloat())) * speed * 0.008 * lift * (flapAngle + Mth.clamp(4 - 0.25 * Mth.abs(dotViewVector.toFloat()), 1.0, 4.0))
            )
        )

        val force = 0.047 * power

        deltaMovement = deltaMovement.add(getViewVector(1f).scale(force * speedRate))
        val vd = 0.05 * deltaMovement.dot(getUpVec(1f).scale(-1.0))
        if (!onGround() && tickCount > 10 && vd > 0.05 && Mth.abs(xRot) < 25) {
            deltaMovement = deltaMovement.add(getViewVector(1f).scale(vd))
        }

        if (power > 0.2f) {
            engineStart = true
            engineStartOver = true
        }

        if (power < 0.0004f) {
            engineStart = false
            engineStartOver = false
        }
    }

    private fun updateRotation(entity: VehicleEntity) {
        val d0 = entity.deltaMovement.add(0.0, -0.36, 0.0)
        if (Mth.abs(entity.xRot) < 60) {
            val diffY = Mth.wrapDegrees(-VehicleVecUtils.getYRotFromVector(d0) - entity.yRot).toFloat()
            entity.yRot += 0.01f * diffY
        }
        if (Mth.abs(entity.xRot) < 90) {
            val diffX = Mth.wrapDegrees(-VehicleVecUtils.getXRotFromVector(d0) - entity.xRot).toFloat()
            entity.xRot += 0.01f * diffX
        }
    }

    @JvmStatic
    fun VehicleEntity.tomEngine(engineInfo: EngineInfo.Tom6) {
        val powerAdd = engineInfo.increment
        val powerReduce = engineInfo.decrement
        val pitchSpeed = engineInfo.pitchSpeed
        val yawSpeed = engineInfo.yawSpeed
        val rollSpeed = engineInfo.rollSpeed
        val lift = engineInfo.liftSpeed
        val speedRate = engineInfo.speedRate
        val energyCost = (engineInfo.energyCostRate * Mth.abs(power)).toInt()

        val f = Mth.clamp(
            Math.max(
                (if (onGround()) 0.96f else 1f) - 0.015 * deltaMovement.lengthSqr(), 0.5
            ) + 0.0001f * Mth.abs(deltaMovement.normalize().dot(getViewVector(1f)).toFloat()), 0.01, 0.99
        ).toFloat()

        val v0 = deltaMovement.normalize().vectorTo(getViewVector(1f))
        deltaMovement = deltaMovement.add(v0.normalize().scale(deltaMovement.length() * 0.05))

        deltaMovement = deltaMovement.multiply(f.toDouble(), f.toDouble(), f.toDouble())

        if (isInFluidType && tickCount % 4 == 0) {
            deltaMovement = deltaMovement.multiply(0.6, 0.6, 0.6)
            if (lastTickSpeed > 0.4) {
                hurt(
                    ModDamageTypes.causeVehicleStrikeDamage(
                        level().registryAccess(),
                        this,
                        if (getFirstPassenger() == null) this else getFirstPassenger()
                    ), (20 * ((lastTickSpeed - 0.4) * (lastTickSpeed - 0.4))).toFloat()
                )
            }
        }

        val passenger = getFirstPassenger()

        if (!hasOperationalPower(energyCost)) {
            forwardInputDown = false
            backInputDown = false
            engineStart = false
            engineStartOver = false
            power *= 0.996f
        } else {
            consumeOperationalPower(energyCost)
        }

        if (passenger == null || isInFluidType) {
            leftInputDown = false
            rightInputDown = false
            forwardInputDown = false
            backInputDown = false
            power *= 0.95f
            if (onGround()) {
                deltaMovement = deltaMovement.multiply(0.94, 1.0, 0.94)
            } else {
                xRot = Mth.clamp(xRot + 0.1f, -89f, 89f)
            }
        } else if (passenger is Player) {

            val maxPower = if (sprintInputDown || onGround()) 2.2f else (if (power > 1) power - 0.012f else 1f)

            if (forwardInputDown) {
                power = Mth.clamp(power + 0.02f * powerAdd, -0.1f, maxPower)
            }

            if (backInputDown) {
                if (onGround()) {
                    deltaMovement = deltaMovement.scale(0.97)
                }
                power = Math.max(power - 0.01f * powerReduce, if (onGround()) -0.1f else 0.05f)
            }

            val diffY = Math.clamp(-90f, 90f, Mth.wrapDegrees(passenger.getYHeadRot() - yRot))
            val diffX = Math.clamp(-60f, 60f, Mth.wrapDegrees(passenger.xRot - xRot))

            val deltaRoll = Mth.abs(Mth.clamp(roll / 60, -1.5f, 1.5f))

            val addY = Mth.clamp(
                Math.min(
                    (if (onGround()) 1.5f else 0.9f) * Math.max(
                        deltaMovement.length() - 0.06,
                        0.1
                    ).toFloat(), 0.9f
                ) * diffY - 0.5f * deltaRot, -3 * (deltaRoll + 1), 3 * (deltaRoll + 1)
            )
            val addX = Mth.clamp(
                Math.min(Math.max(deltaMovement.length() - 0.1, 0.01).toFloat(), 0.9f) * diffX,
                -4f,
                4f
            )
            val addZ = deltaRot - (if (onGround()) 0f else 0.01f) * diffY * deltaMovement
                .length().toFloat()

            val i = xRot / 90

            val yRotSync = addY * (1 - Mth.abs(i)) + addZ * i

            yRot += yRotSync * yawSpeed
            xRot = Mth.clamp(
                xRot + addX * pitchSpeed,
                (if (onGround()) -12 else -120).toFloat(),
                (if (onGround()) 3 else 120).toFloat()
            )
            setZRot(roll - addZ * (1 - Mth.abs(i)) * rollSpeed)

            if (!forwardInputDown && !backInputDown) {
                power *= 0.995f
            }

            if (!onGround()) {
                if (rightInputDown) {
                    holdTick++
                    deltaRot -= 0.04f * Math.min(holdTick, 20)
                } else if (leftInputDown) {
                    deltaRot += 0.04f * Math.min(holdTick, 20)
                }
            }
        }

        // 自动回正
        if (!onGround()) {
            val xSpeed = 1 + 20 * Mth.abs(xRot / 180)
            val speed = Mth.clamp(Mth.abs(roll) / (90 / xSpeed), 0f, 1f)

            if (roll > 0) {
                setZRot(roll - Math.min(speed, roll))
            } else if (roll < 0) {
                setZRot(roll + Math.min(speed, -roll))
            }
        }

        deltaRot *= 0.85f
        if (onGround()) {
            power *= 0.995f
        }

        deltaMovement = deltaMovement.add(
            getUpVec(1f).scale(
                deltaMovement
                    .dot(getViewVector(1f)) * 0.022 * lift * (1 + Math.sin((if (onGround()) 25 else 30) * Mth.DEG_TO_RAD))
            )
        )
        deltaMovement = deltaMovement.add(
            getViewVector(1f).scale(
                0.061 * speedRate * power
            )
        )
    }

    @JvmStatic
    fun VehicleEntity.wheelChairEngine(engineInfo: EngineInfo.WheelChair) {
        val buoyancy = engineInfo.buoyancy
        val energyCost = (engineInfo.energyCostRate * Mth.abs(power)).toInt()
        val wheelRotSpeed = engineInfo.wheelRotSpeed
        val wheelDifferential = engineInfo.wheelDifferential.toFloat()
        val maxForwardSpeedRate = engineInfo.maxForwardSpeedRate
        val maxBackwardSpeedRate = engineInfo.maxBackwardSpeedRate
        val powerAdd = engineInfo.increment
        val powerReduce = engineInfo.decrement
        val steeringSpeed = engineInfo.steeringSpeed
        val jumpEnergyCost = engineInfo.jumpEnergyCost

        if (buoyancy != 0.0) {
            val fluidFloat = buoyancy * VehicleVecUtils.getSubmergedHeight(this)
            deltaMovement = deltaMovement.add(0.0, fluidFloat, 0.0)
        }

        if (onGround()) {
            val f0 = 0.63f + 0.25f * Mth.abs(deltaMovement.normalize().dot(getViewVector(1f)).toFloat())
            deltaMovement = deltaMovement.add(
                getViewVector(1f).normalize()
                    .scale(0.05 * deltaMovement.dot(getViewVector(1f)))
            )
            deltaMovement = deltaMovement.multiply(f0.toDouble(), 0.99, f0.toDouble())
        } else if (isInFluidType) {
            val f1 = 0.74f + 0.09f * Mth.abs(deltaMovement.normalize().dot(getViewVector(1f)).toFloat())
            deltaMovement = deltaMovement.add(
                getViewVector(1f).normalize()
                    .scale(0.04 * deltaMovement.dot(getViewVector(1f)))
            )
            deltaMovement = deltaMovement.multiply(f1.toDouble(), 0.85, f1.toDouble())
        } else {
            deltaMovement = deltaMovement.multiply(0.99, 0.99, 0.99)
        }
        isSprinting = deltaMovement.horizontalDistance() > 0.15

        val passenger0 = getFirstPassenger()
        var diffY = 0f

        if (passenger0 == null) {
            leftInputDown = false
            rightInputDown = false
            forwardInputDown = false
            backInputDown = false
            power = 0f
        } else {
            diffY = Math.clamp(-90f, 90f, Mth.wrapDegrees(passenger0.yHeadRot - yRot))
            yRot += Mth.clamp(0.4f * diffY, -5f * steeringSpeed, 5f * steeringSpeed)
        }

        if (forwardInputDown) {
            if (!hasOperationalPower(energyCost) && passenger0 is Player) {
                moveWithOutPower(passenger0, true)
            } else {
                power = Math.min(
                    power + (if (power < 0) powerAdd * 2f else powerAdd) * (1 - (power / 1.02f)), (if (sprintInputDown) 2f else 1f)
                )
            }
        }

        if (backInputDown) {
            if (!hasOperationalPower(energyCost) && passenger0 is Player) {
                moveWithOutPower(passenger0, false)
            } else {
                power = Math.max(
                    power - (if (power > 0) powerReduce * 2f else powerReduce) * (1 - (power / 1.02f)), -1f
                )
            }
        }

        targetSpeed = if (power > 0) {
            (maxForwardSpeedRate * (1 + xRot / 60)).toDouble()
        } else {
            (maxBackwardSpeedRate * (1 - xRot / 60)).toDouble()
        }

        power *= if (power > 0) {
            1 + xRot / 514
        } else {
            1 - xRot / 514
        }

        if (!forwardInputDown && !backInputDown) {
            power *= 0.96f
        }

        if (upInputDown && onGround() && hasOperationalPower(jumpEnergyCost) && jumpCoolDown == 0 && engineInfo.canJump) {
            if (passenger0 is ServerPlayer) {
                passenger0.level().playSound(
                    null,
                    passenger0.onPos,
                    ModSounds.WHEEL_CHAIR_JUMP.get(),
                    SoundSource.PLAYERS,
                    1f,
                    1f
                )
            }
            consumeOperationalPower(jumpEnergyCost)
            deltaMovement = deltaMovement.add(getUpVec(1f).scale(engineInfo.jumpForce))
            jumpCoolDown = engineInfo.jumpCoolDown
        }

        if (level() is ServerLevel) {
            consumeOperationalPower(energyCost)
        }

        val s0 = deltaMovement.dot(getViewVector(1f))
        leftWheelRot =
            (leftWheelRot - 1.25 * wheelRotSpeed * s0).toFloat() - 0.015f * wheelDifferential * Mth.clamp(
                0.4f * diffY,
                -5f,
                5f
            )
        rightWheelRot =
            (rightWheelRot - 1.25 * wheelRotSpeed * s0).toFloat() + 0.015f * wheelDifferential * Mth.clamp(
                0.4f * diffY,
                -5f,
                5f
            )

        if (isInFluidType || onGround()) {
            val water =
                (if (!isInFluidType && !onGround()) 0.05f else (if (isInFluidType && !onGround()) 0.3f else 1f)).toDouble()
            deltaMovement = deltaMovement.add(
                getViewVector(1f).scale(0.15 * water * targetSpeed * power)
            )
        }
    }

    fun VehicleEntity.moveWithOutPower(player: Player, forward: Boolean) {
        deltaMovement = deltaMovement.add(getViewVector(1f).scale((if (forward) 0.1f else -0.1f).toDouble()))
        if (player is ServerPlayer) {
            player.level().playSound(null, player.onPos, SoundEvents.BOAT_PADDLE_LAND, SoundSource.PLAYERS, 1f, 1f)
        }
        player.causeFoodExhaustion(0.03f)

        forwardInputDown = false
        backInputDown = false
    }

    // 角度线性插值方法
    @JvmStatic
    fun lerpAngle(current: Float, target: Float, factor: Float): Float {
        // 处理角度环绕
        var diff = target - current
        while (diff < -180) diff += 360f
        while (diff > 180) diff -= 360f

        return current + diff * factor
    }
}
