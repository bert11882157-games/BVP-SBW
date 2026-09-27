package com.atsuishio.superbwarfare.client.camera

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent

internal fun transportPassengerView(type: VehicleType?, seatIndex: Int, armed: Boolean): Boolean =
    (type == VehicleType.TANK || type == VehicleType.APC) && seatIndex > 0 && !armed

/** Scoped to an unarmed transport seat; pilots and HMG operators retain their normal views. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object VehiclePassengerCamera {
    private var priorView: CameraType? = null

    @SubscribeEvent
    fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase == TickEvent.Phase.END) enforce()
    }

    @JvmStatic
    fun enforce() {
        val mc = Minecraft.getInstance()
        val player = mc.player
        val vehicle = player?.vehicle as? VehicleEntity
        val index = vehicle?.getSeatIndex(player) ?: -1
        val seat = vehicle?.getSeat(index)
        val restricted = seat != null && transportPassengerView(vehicle?.vehicleType, index, seat.weapons().isNotEmpty())
        if (restricted) {
            if (priorView == null) priorView = mc.options.cameraType
            if (mc.options.cameraType == CameraType.FIRST_PERSON) mc.options.cameraType = CameraType.THIRD_PERSON_BACK
        } else {
            priorView?.let { mc.options.cameraType = it }
            priorView = null
        }
    }
}
