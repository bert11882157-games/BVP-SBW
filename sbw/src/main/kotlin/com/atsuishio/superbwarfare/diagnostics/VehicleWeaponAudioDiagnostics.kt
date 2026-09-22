package com.atsuishio.superbwarfare.diagnostics

import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.sounds.SoundEvent
import net.minecraft.world.entity.LivingEntity

/** Audio-domain records share the Elite switch, queue, session and output; no independent capture. */
object VehicleWeaponAudioDiagnostics {
    enum class Disposition {
        PLAYED, RELOAD_STARTED, RELOAD_FINISHED, AUTHORITATIVE_STOP, DUPLICATE_SUPPRESSED,
        FALLBACK_SUPPRESSED, CLAIM_REJECTED, NO_SOUND_CONFIGURED, CLIENT_RESOLVED,
    }

    @JvmStatic @JvmOverloads
    fun recordServer(
        vehicle: VehicleEntity, controller: LivingEntity?, data: GunData?,
        channel: String, disposition: Disposition, sound: SoundEvent? = null, detail: String? = null,
    ) {
        if (!EliteDiagnostics.isServerEnabled()) return
        EliteDiagnostics.record(vehicle, "audio", disposition.name,
            "channel", channel, "controller", controller?.uuid, "seat", vehicle.getSeatIndex(controller),
            "weapon", data?.vehicleWeaponIdentity, "sound_event", sound?.location,
            "loaded", data?.ammo?.get(), "capacity", data?.get(GunProp.MAGAZINE),
            "reserve", data?.countBackupAmmo(vehicle.ammoSupplier),
            "reload_revision", data?.reload?.soundCycleRevision(), "remaining_ticks", data?.reload?.time(),
            "reload_state", data?.reload?.state()?.name, "reload_stage", data?.reload?.stage(), "detail", detail)
    }
}
