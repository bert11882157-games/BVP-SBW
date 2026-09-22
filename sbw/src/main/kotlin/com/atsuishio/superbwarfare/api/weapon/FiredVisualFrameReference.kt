package com.atsuishio.superbwarfare.api.weapon

import kotlinx.serialization.Serializable

/**
 * Bounded wire copy of the vehicle frame selected before an accepted shot mutates fire-index state.
 * Sequence/tick fields are provenance only: clients resolve the recorded frame names against one
 * current presentation snapshot and fall back atomically to the record's frozen effect vectors.
 */
@Serializable
data class FiredVisualFrameReference(
    val weaponName: String,
    val positionSlot: Int?,
    val directionSlot: Int?,
    val muzzlePositionAttachment: String?,
    val muzzleDirectionAttachment: String?,
    val effectPositionAttachment: String?,
    val effectDirectionAttachment: String?,
    val sourceTransformSequence: Int,
    val sourceTransformServerTick: Long,
    val serverGameTime: Long,
) {
    init {
        require(validName(weaponName, required = true)) { "Invalid fired-visual weapon name" }
        require(validSlot(positionSlot)) { "Invalid fired-visual position slot" }
        require(validSlot(directionSlot)) { "Invalid fired-visual direction slot" }
        require(validName(muzzlePositionAttachment)) { "Invalid fired-visual muzzle-position attachment" }
        require(validName(muzzleDirectionAttachment)) { "Invalid fired-visual muzzle-direction attachment" }
        require(validName(effectPositionAttachment)) { "Invalid fired-visual effect-position attachment" }
        require(validName(effectDirectionAttachment)) { "Invalid fired-visual effect-direction attachment" }
    }

    companion object {
        const val MAX_NAME_CHARS = 128
        const val MAX_SLOT_INDEX = 2_047

        private fun validSlot(value: Int?): Boolean = value == null || value in 0..MAX_SLOT_INDEX

        private fun validName(value: String?, required: Boolean = false): Boolean {
            if (value == null) return !required
            return value.isNotBlank() && value.length <= MAX_NAME_CHARS
        }

        @JvmStatic
        fun from(reference: ShotFrameReference?): FiredVisualFrameReference? {
            reference ?: return null
            if (!validName(reference.weaponName, required = true)
                || !validSlot(reference.positionSlot)
                || !validSlot(reference.directionSlot)
                || !validName(reference.muzzlePositionAttachment)
                || !validName(reference.muzzleDirectionAttachment)
                || !validName(reference.effectPositionAttachment)
                || !validName(reference.effectDirectionAttachment)
            ) {
                return null
            }
            return FiredVisualFrameReference(
                reference.weaponName,
                reference.positionSlot,
                reference.directionSlot,
                reference.muzzlePositionAttachment,
                reference.muzzleDirectionAttachment,
                reference.effectPositionAttachment,
                reference.effectDirectionAttachment,
                reference.sourceTransformSequence,
                reference.sourceTransformServerTick,
                reference.serverGameTime,
            )
        }
    }
}
