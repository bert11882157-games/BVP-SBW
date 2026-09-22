package com.atsuishio.superbwarfare.data.vehicle

import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleAttachmentGraph
import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleAttachmentNode
import net.minecraft.world.phys.Vec3

/** Strict reload-time validation for the native attachment graph and every data-authored role. */
object VehicleAttachmentDataValidator {
    @JvmStatic
    fun validate(vehicleId: String, data: DefaultVehicleData) {
        val attachments = data.attachments
        val validFrames = LinkedHashSet(VehicleAttachmentGraph.NATIVE_BASE_FRAMES)
        val nodes = ArrayList<VehicleAttachmentNode>(attachments.size)

        val roofFrame = data.roofCoaxPitch
        val roofChildren = attachments.filterValues { it.parent == "RoofCoaxPitch" }
        if (roofFrame == null) {
            require(roofChildren.isEmpty()) {
                "$vehicleId has RoofCoaxPitch children without the typed RoofCoaxPitch frame"
            }
        } else {
            require(roofFrame.hasTypedIdentity()) {
                "$vehicleId has an invalid RoofCoaxPitch frame contract"
            }
            val muzzleName = roofFrame.muzzleAttachment!!
            val muzzle = attachments[muzzleName]
            require(muzzle != null && muzzle.parent == "RoofCoaxPitch") {
                "$vehicleId RoofCoaxPitch muzzle '$muzzleName' must be a RoofCoaxPitch child"
            }
        }

        val agsFrame = data.ags30Pitch
        val agsChildren = attachments.filterValues { it.parent == "Ags30Pitch" }
        if (agsFrame == null) {
            require(agsChildren.isEmpty()) {
                "$vehicleId has Ags30Pitch children without the typed Ags30Pitch frame"
            }
        } else {
            require(agsFrame.hasTypedIdentity()) {
                "$vehicleId has an invalid Ags30Pitch frame contract"
            }
            val muzzleName = agsFrame.muzzleAttachment!!
            val muzzle = attachments[muzzleName]
            require(muzzle != null && muzzle.parent == "Ags30Pitch") {
                "$vehicleId Ags30Pitch muzzle '$muzzleName' must be an Ags30Pitch child"
            }
            require(data.weapons().containsKey(agsFrame.weaponId)) {
                "$vehicleId Ags30Pitch weapon '${agsFrame.weaponId}' is not present"
            }
        }

        for ((id, info) in attachments) {
            require(id.isNotBlank()) { "$vehicleId has a blank attachment id" }
            require(id !in VehicleAttachmentGraph.NATIVE_BASE_FRAMES) {
                "$vehicleId attachment '$id' shadows a native base frame"
            }
            require(info.parent.isNotBlank()) { "$vehicleId attachment '$id' has a blank parent" }
            requireFinite(vehicleId, id, "Position", info.position)
            requireFinite(vehicleId, id, "Direction", info.direction)
            require(info.direction.lengthSqr() > 1.0E-12) {
                "$vehicleId attachment '$id' has a zero Direction"
            }
            validFrames += id
            nodes += VehicleAttachmentNode(id, info.parent)
        }

        // The graph constructor proves parent existence and acyclicity.
        VehicleAttachmentGraph(nodes, VehicleAttachmentGraph.NATIVE_BASE_FRAMES)

        for ((weaponName, weapon) in data.weapons()) {
            val shootPos = weapon.shootPos
            validateRefs(vehicleId, "weapon '$weaponName' MuzzleAttachments", shootPos.muzzleAttachments, validFrames)
            validateRefs(
                vehicleId, "weapon '$weaponName' MuzzleDirectionAttachments",
                shootPos.muzzleDirectionAttachments, validFrames,
            )
            validateRef(vehicleId, "weapon '$weaponName' HudOriginAttachment", shootPos.hudOriginAttachment, validFrames)
            validateRef(
                vehicleId, "weapon '$weaponName' HudDirectionAttachment",
                shootPos.hudDirectionAttachment, validFrames,
            )
            validateRef(vehicleId, "weapon '$weaponName' ViewAttachment", shootPos.viewAttachment, validFrames)
            validateRef(
                vehicleId, "weapon '$weaponName' ViewDirectionAttachment",
                shootPos.viewDirectionAttachment, validFrames,
            )
            validateRef(vehicleId, "weapon '$weaponName' SeekAttachment", shootPos.seekAttachment, validFrames)
            validateRef(
                vehicleId, "weapon '$weaponName' SeekDirectionAttachment",
                shootPos.seekDirectionAttachment, validFrames,
            )
            validateRefs(vehicleId, "weapon '$weaponName' EffectAttachments", shootPos.effectAttachments, validFrames)
            validateRefs(
                vehicleId, "weapon '$weaponName' EffectDirectionAttachments",
                shootPos.effectDirectionAttachments, validFrames,
            )
        }

        for ((seatIndex, seat) in data.seats().withIndex()) {
            validateRef(vehicleId, "seat $seatIndex BodyAttachment", seat.bodyAttachment, validFrames)
            seat.cameraPos?.let { camera ->
                validateRef(vehicleId, "seat $seatIndex EyeAttachment", camera.eyeAttachment, validFrames)
                validateRef(vehicleId, "seat $seatIndex ZoomEyeAttachment", camera.zoomEyeAttachment, validFrames)
                validateRef(
                    vehicleId, "seat $seatIndex DirectionAttachment",
                    camera.directionAttachment, validFrames,
                )
                validateRef(
                    vehicleId, "seat $seatIndex ZoomDirectionAttachment",
                    camera.zoomDirectionAttachment, validFrames,
                )
            }
        }
    }

    private fun validateRefs(vehicleId: String, owner: String, refs: List<String>, validFrames: Set<String>) {
        refs.forEachIndexed { index, ref -> validateRef(vehicleId, "$owner[$index]", ref, validFrames) }
    }

    private fun validateRef(vehicleId: String, owner: String, ref: String?, validFrames: Set<String>) {
        if (ref == null) return
        require(ref.isNotBlank()) { "$vehicleId $owner is blank" }
        require(ref in validFrames) { "$vehicleId $owner references missing attachment '$ref'" }
    }

    private fun requireFinite(vehicleId: String, attachmentId: String, field: String, value: Vec3) {
        require(value.x.isFinite() && value.y.isFinite() && value.z.isFinite()) {
            "$vehicleId attachment '$attachmentId' has non-finite $field"
        }
    }
}
