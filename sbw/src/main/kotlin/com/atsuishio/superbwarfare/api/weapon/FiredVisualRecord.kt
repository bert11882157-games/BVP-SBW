package com.atsuishio.superbwarfare.api.weapon

import com.atsuishio.superbwarfare.serialization.kserializer.SerializedResourceLocation
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedUUID
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVec3
import kotlinx.serialization.Serializable

/**
 * Immutable, sequence-addressed presentation record for one accepted logical shot.
 *
 * The source is the entity clients track for the weapon (normally a vehicle or the
 * shooter). The shooter is kept separately so presentation providers never need to
 * reconstruct shot provenance from a newly spawned projectile.
 */
@Serializable
data class FiredVisualRecord(
    val serverSessionId: SerializedUUID,
    val sequence: Long,
    val sourceEntityId: Int,
    val sourceEntityUuid: SerializedUUID?,
    val shooterEntityId: Int,
    val shooterEntityUuid: SerializedUUID?,
    val weaponId: SerializedResourceLocation?,
    val projectileProfileId: SerializedResourceLocation?,
    val muzzlePosition: SerializedVec3,
    val direction: SerializedVec3,
    val spawnedProjectileIds: List<SerializedUUID>,
    val effectPosition: SerializedVec3 = muzzlePosition,
    val effectDirection: SerializedVec3 = direction,
    val frameReference: FiredVisualFrameReference?,
)

fun interface FiredVisualProvider {
    /** Returns true when this provider owns the record's additional presentation. */
    fun handle(record: FiredVisualRecord): Boolean
}
