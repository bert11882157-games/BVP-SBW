package com.atsuishio.superbwarfare.api.weapon

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles
import com.atsuishio.superbwarfare.data.gun.ShootParameters
import com.atsuishio.superbwarfare.network.message.receive.FiredVisualMessage
import com.atsuishio.superbwarfare.tools.sendPacketTo
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.network.PacketDistributor
import net.minecraftforge.network.PacketDistributor.TargetPoint
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** Server-side publisher for accepted logical-shot presentation records. */
object FiredVisualRecords {
    private const val FALLBACK_TRACKING_RADIUS = 128.0
    private val serverSessionId = UUID.randomUUID()
    private val nextSequence = AtomicLong()

    @JvmStatic
    fun publish(parameters: ShootParameters, result: ShotResult) {
        if (!result.isAccepted()) return

        val shooter = parameters.shooter
        val source = parameters.ammoSupplier ?: shooter
        val profileId = result.projectileProfileId
        val weaponId = ProjectileProfiles.resolve(profileId)?.combat?.weaponId
            ?: ResourceLocation.tryParse(parameters.data.id)
        if (!FiredVisualInterests.hasInterest(FiredVisualInterestContext(weaponId, profileId))) return

        val ballisticPosition = result.muzzlePosition ?: parameters.shootPosition
        val ballisticDirection = result.direction ?: parameters.shootDirection
        val record = FiredVisualRecord(
            serverSessionId,
            nextSequence.incrementAndGet(),
            source?.id ?: -1,
            source?.uuid,
            shooter?.id ?: -1,
            shooter?.uuid,
            weaponId,
            profileId,
            ballisticPosition,
            ballisticDirection,
            result.spawnedProjectileIds,
            parameters.effectPosition ?: ballisticPosition,
            parameters.effectDirection ?: ballisticDirection,
            FiredVisualFrameReference.from(parameters.frameReference),
        )
        val target = if (source != null) {
            PacketDistributor.TRACKING_ENTITY_AND_SELF.with { source }
        } else {
            PacketDistributor.NEAR.with {
                TargetPoint(
                    ballisticPosition.x,
                    ballisticPosition.y,
                    ballisticPosition.z,
                    FALLBACK_TRACKING_RADIUS,
                    parameters.level.dimension(),
                )
            }
        }
        sendPacketTo(target, FiredVisualMessage(record))
    }
}
