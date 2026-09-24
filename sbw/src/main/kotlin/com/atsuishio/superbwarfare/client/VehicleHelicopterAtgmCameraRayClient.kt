package com.atsuishio.superbwarfare.client

import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponGuidance
import com.atsuishio.superbwarfare.client.camera.VehicleFreeCameraController
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.VehicleHelicopterAtgmCameraRayTransport
import com.atsuishio.superbwarfare.network.message.send.VehicleHelicopterAtgmCameraRayMessage
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVector3f
import com.atsuishio.superbwarfare.tools.sendPacketToServer
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import org.joml.Vector3f
import java.util.UUID

/** Final on-screen camera ray for all vehicle ATGM owners; the wire ID/name stays unchanged. */
@OnlyIn(Dist.CLIENT)
object VehicleHelicopterAtgmCameraRayClient {
    private data class Context(
        val levelIdentity: Int,
        val dimension: net.minecraft.resources.ResourceLocation,
        val playerUuid: UUID,
        val vehicleId: Int,
        val vehicleUuid: UUID,
        val seatIndex: Int,
    )

    private data class PendingSample(
        val context: Context,
        val origin: Vec3,
        val direction: Vec3,
    )

    private var context: Context? = null
    private var pending: PendingSample? = null
    private var streamWasEligible = false
    private var contextEpoch = 0L
    private var sequence = 0L
    private var clientTick = 0L

    /** Called once at client-tick END; at most one latest sample is sent. */
    @JvmStatic
    fun tickClientEnd() {
        clientTick = nextNonnegative(clientTick)
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player
        val vehicle = player?.vehicle as? VehicleEntity
        val current = player?.let { vehicle?.let { v -> contextOf(it, v) } }
        if (player == null || vehicle == null || current == null) {
            clearSession()
            return
        }
        if (context != current) {
            context = current
            contextEpoch = nextNonnegative(contextEpoch)
            streamWasEligible = false
            pending = null
        }
        if (!eligible(minecraft, player, vehicle)) {
            pending = null
            streamWasEligible = false
            return
        }
        if (!streamWasEligible) {
            streamWasEligible = true
            contextEpoch = nextNonnegative(contextEpoch)
            pending = null
            return
        }
        val sample = pending?.takeIf { it.context == current } ?: run {
            pending = null
            return
        }
        pending = null
        sequence = nextNonnegative(sequence)
        sendPacketToServer(
            VehicleHelicopterAtgmCameraRayMessage(
                current.playerUuid,
                current.vehicleId,
                current.vehicleUuid,
                current.dimension,
                current.seatIndex,
                contextEpoch,
                sequence,
                clientTick,
                sample.origin.toSerializedVector(),
                sample.direction.toSerializedVector(),
            )
        )
    }

    /** Capture after Camera.setup/HUD camera composition, never from player look or raw vehicle axes. */
    @JvmStatic
    fun capturePresented() {
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val vehicle = player.vehicle as? VehicleEntity ?: return
        val current = contextOf(player, vehicle) ?: return
        if (!eligible(minecraft, player, vehicle)) return
        if (context != current) {
            context = current
            contextEpoch = nextNonnegative(contextEpoch)
            streamWasEligible = false
            pending = null
        }
        val camera = minecraft.gameRenderer.mainCamera
        val origin = camera.position
        val direction = Vec3(camera.lookVector)
        if (!origin.isFinite() || !direction.isFinite()) return
        if (origin.distanceToSqr(vehicle.position()) >
            VehicleHelicopterAtgmCameraRayTransport.MAX_ORIGIN_DISTANCE_BLOCKS *
            VehicleHelicopterAtgmCameraRayTransport.MAX_ORIGIN_DISTANCE_BLOCKS
        ) return
        val lengthSquared = direction.lengthSqr()
        if (lengthSquared < 1.0E-8 || !lengthSquared.isFinite()) return
        pending = PendingSample(current, origin, direction.normalize())
    }

    @JvmStatic
    fun clear() = clearSession()

    private fun contextOf(player: Player, vehicle: VehicleEntity): Context? {
        if (!player.isAlive || player.isSpectator || player.isRemoved || vehicle.isRemoved || vehicle.isWreck ||
            vehicle.level() !== player.level()
        ) return null
        val seatIndex = vehicle.getSeatIndex(player)
        if (seatIndex < 0 || vehicle.getNthEntity(seatIndex) !== player) return null
        return Context(
            System.identityHashCode(player.level()),
            player.level().dimension().location(),
            player.uuid,
            vehicle.id,
            vehicle.uuid,
            seatIndex,
        )
    }

    private fun eligible(minecraft: Minecraft, player: Player, vehicle: VehicleEntity): Boolean {
        if (minecraft.level == null || minecraft.screen != null || minecraft.isPaused || !minecraft.isWindowActive ||
            !player.isAlive || player.isSpectator || vehicle.isRemoved || vehicle.isWreck ||
            VehicleFreeCameraController.hasPresentation(player, vehicle)
        ) return false
        val seatIndex = vehicle.getSeatIndex(player)
        return hasAtgmCapableSeat(vehicle, seatIndex)
    }

    /** Eligibility is seat/pair scoped; current primary selection must not starve an ATGM launch. */
    private fun hasAtgmCapableSeat(vehicle: VehicleEntity, seatIndex: Int): Boolean {
        vehicle.getSeat(seatIndex) ?: return false
        return vehicle.getWeaponIds(seatIndex).any { weaponName ->
            vehicle.getGunData(weaponName)?.let { VehicleWeaponGuidance.isAtgm(it) } == true
        }
    }

    private fun clearSession() {
        context = null
        pending = null
        streamWasEligible = false
    }

    private fun nextNonnegative(value: Long): Long = if (value == Long.MAX_VALUE) 0L else value + 1L

    private fun Vec3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    private fun Vec3.toSerializedVector(): SerializedVector3f =
        Vector3f(x.toFloat(), y.toFloat(), z.toFloat())
}
