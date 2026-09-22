package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.SeekWeaponInfo
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.tools.EntityFindUtil
import com.atsuishio.superbwarfare.tools.SeekTool
import com.atsuishio.superbwarfare.tools.angleTo
import net.minecraft.world.entity.Entity
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import java.util.UUID
import kotlin.math.max

internal data class VehicleWeaponTarget(
    val entityUuid: UUID? = null,
    val position: Vec3? = null,
)

/** Sanitizes packet-authored guidance against the weapon selected at execution time. */
internal object VehicleWeaponTargetValidator {
    private val NONE = VehicleWeaponTarget()
    private const val BLOCK_TARGET_TOLERANCE_SQR = 2.25

    fun sanitize(
        vehicle: VehicleEntity,
        selection: VehicleWeaponSelection,
        entityUuid: UUID?,
        position: Vec3?,
    ): VehicleWeaponTarget {
        val data = vehicle.getGunData(selection.seatIndex, selection.weaponIndex) ?: return NONE
        val seeker = data.get(GunProp.SEEK_WEAPON_INFO) ?: return NONE
        return when {
            seeker.onlyLockBlock -> validatePosition(vehicle, selection, seeker, position)
            seeker.onlyLockEntity -> validateEntity(vehicle, selection, seeker, entityUuid)
            else -> NONE
        }
    }

    private fun validateEntity(
        vehicle: VehicleEntity,
        selection: VehicleWeaponSelection,
        seeker: SeekWeaponInfo,
        uuid: UUID?,
    ): VehicleWeaponTarget {
        val target = uuid?.let { EntityFindUtil.findEntity(vehicle.level(), it.toString()) } ?: return NONE
        val controller = selection.controller
        val origin = seekOrigin(vehicle, controller)
        val direction = vehicle.getSeekVec(controller, 1f)
            ?.takeUnless { it.lengthSqr() <= 1.0E-12 }
            ?: return NONE
        val targetPoint = target.boundingBox.center

        if (!isAllowedEntity(vehicle, controller, target, seeker)) return NONE
        if (origin.distanceToSqr(targetPoint) > allowedRangeSqr(target, seeker)) return NONE
        if (direction.angleTo(origin.vectorTo(targetPoint)) > seeker.seekAngle) return NONE
        if (!hasLineOfSight(vehicle, controller, origin, target.eyePosition)) return NONE
        return VehicleWeaponTarget(entityUuid = target.uuid)
    }

    private fun validatePosition(
        vehicle: VehicleEntity,
        selection: VehicleWeaponSelection,
        seeker: SeekWeaponInfo,
        position: Vec3?,
    ): VehicleWeaponTarget {
        if (position == null || !position.isFinite()) return NONE
        val controller = selection.controller
        val origin = seekOrigin(vehicle, controller)
        val direction = vehicle.getSeekVec(controller, 1f)
            ?.takeUnless { it.lengthSqr() <= 1.0E-12 }
            ?: return NONE
        if (origin.distanceToSqr(position) > seeker.seekRange * seeker.seekRange) return NONE
        if (direction.angleTo(origin.vectorTo(position)) > seeker.seekAngle) return NONE

        val hit = vehicle.level().clip(
            ClipContext(origin, position, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, controller)
        )
        if (hit.type == HitResult.Type.BLOCK && hit.location.distanceToSqr(position) > BLOCK_TARGET_TOLERANCE_SQR) {
            return NONE
        }
        return VehicleWeaponTarget(position = position)
    }

    private fun isAllowedEntity(
        vehicle: VehicleEntity,
        controller: Entity,
        target: Entity,
        seeker: SeekWeaponInfo,
    ): Boolean {
        return target !== vehicle && target !== controller && target.vehicle == null
                && SeekTool.BASIC_FILTER.test(target)
                && SeekTool.NOT_IN_SMOKE.test(target)
                && !SeekTool.IS_FRIENDLY.test(controller, target)
                && target.boundingBox.size >= seeker.minTargetSize
                && SeekTool.IN_HEIGHT_RANGE.test(target, seeker.minTargetHeight, seeker.maxTargetHeight)
    }

    private fun allowedRangeSqr(target: Entity, seeker: SeekWeaponInfo): Double {
        val trackMultiplier = if (target is VehicleEntity && seeker.affectedByStealthTarget) {
            target.computed().trackDistanceMultiply
        } else {
            1.0
        }
        val directRange = seeker.seekRange * trackMultiplier
        val allowedRange = if (seeker.canGuidedByRadar) max(directRange, seeker.maxGuidedRange) else directRange
        return allowedRange * allowedRange
    }

    private fun seekOrigin(vehicle: VehicleEntity, controller: Entity): Vec3 {
        return vehicle.getSeekPos(controller, 1f)
            ?: vehicle.getViewPos(controller, 1f)
            ?: vehicle.getShootPosForHud(controller, 1f)
    }

    private fun hasLineOfSight(
        vehicle: VehicleEntity,
        controller: Entity,
        origin: Vec3,
        target: Vec3,
    ): Boolean {
        return vehicle.level().clip(
            ClipContext(origin, target, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, controller)
        ).type != HitResult.Type.BLOCK
    }

    private fun Vec3.isFinite() = x.isFinite() && y.isFinite() && z.isFinite()

}
