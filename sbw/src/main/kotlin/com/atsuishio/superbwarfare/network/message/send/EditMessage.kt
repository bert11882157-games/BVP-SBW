package com.atsuishio.superbwarfare.network.message.send

import com.atsuishio.superbwarfare.api.weapon.ReloadTransitionPolicy
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunData.Companion.from
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.value.AttachmentType
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.event.GunEventHandler
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.atsuishio.superbwarfare.network.PayloadContext
import com.atsuishio.superbwarfare.network.ServerPacketPayload
import com.atsuishio.superbwarfare.tools.playLocalSound
import kotlinx.serialization.Serializable
import net.minecraft.world.entity.Entity

@Serializable
data class EditMessage(val type: Int, val add: Boolean, val isVehicle: Boolean) : ServerPacketPayload() {
    override fun PayloadContext.handler() {
        val player = sender()
        val vehicle = player.vehicle

        if (isVehicle && vehicle is VehicleEntity) {
            if (type != 5) return

            vehicle.modifyGunData(vehicle.getSeatIndex(player)) { data ->
                val changed = data.cycleAmmoConsumer(
                    add,
                    vehicle.ammoSupplier,
                    vehicle.vehicleReloadTransitionPolicy(),
                )
                if (!changed) return@modifyGunData

                com.atsuishio.superbwarfare.api.weapon.VehicleReloadAudio.cancel(
                    vehicle,
                    data.vehicleWeaponIdentity,
                    "ammo_consumer_changed",
                )
                val sound = data.get(GunProp.SOUND_INFO).change ?: return@modifyGunData
                player.playLocalSound(sound, 4f, 1f)
            }
        } else {
            val stack = player.mainHandItem
            val item = stack.item
            if (item !is GunItem) return

            val data = from(stack)
            when (type) {
                0 -> {
                    var att = data.attachment.get(AttachmentType.BARREL)
                    att = setAttachment(item.validBarrels, att, add)
                    data.attachment.set(AttachmentType.BARREL, att)
                }

                1 -> {
                    var att = data.attachment.get(AttachmentType.SCOPE)
                    att = setAttachment(item.validScopes, att, add)
                    data.attachment.set(AttachmentType.SCOPE, att)
                }

                2 -> {
                    var att = data.attachment.get(AttachmentType.GRIP)
                    att = setAttachment(item.validGrips, att, add)
                    data.attachment.set(AttachmentType.GRIP, att)
                }

                3 -> {
                    var att = data.attachment.get(AttachmentType.STOCK)
                    att = setAttachment(item.validStocks, att, add)
                    data.attachment.set(AttachmentType.STOCK, att)
                }

                4 -> {
                    var att = data.attachment.get(AttachmentType.MAGAZINE)
                    att = setAttachment(item.validMagazines, att, add)
                    data.withdrawAmmo(player)
                    data.attachment.set(AttachmentType.MAGAZINE, att)
                }

                5 -> {
                    if (!data.cycleAmmoConsumer(add, player)) {
                        data.save()
                        return
                    }
                }
            }
            data.save()
            player.playLocalSound(ModSounds.EDIT.get(), 1f, 1f)
        }
    }

    private fun GunData.cycleAmmoConsumer(
        add: Boolean, ammoSupplier: Entity?,
        policy: ReloadTransitionPolicy = ReloadTransitionPolicy.LEGACY,
    ): Boolean {
        val size = get(GunProp.AMMO_CONSUMER).size
        if (size == 0) return false

        val currentIndex = Math.floorMod(selectedAmmoType.get(), size)
        val targetIndex = Math.floorMod(currentIndex + (if (add) 1 else -1), size)
        val transition = changeAmmoConsumerWithResult(targetIndex, ammoSupplier, policy)
        return transition.previousAmmoConsumerIndex != transition.selectedAmmoConsumerIndex
    }

    private fun setAttachment(arr: IntArray, value: Int, add: Boolean): Int {
        if (arr.isEmpty()) return 0

        val sorted = arr.copyOf(arr.size).sorted()
        var index = sorted.binarySearch(value)
        if (index < 0) index = -index - 1

        index = (if (add) (index + 1) else (index + arr.size - 1)) % arr.size
        return sorted[index]
    }
}


