package com.atsuishio.superbwarfare.data.gun

import com.atsuishio.superbwarfare.data.StringOrVec3
import com.atsuishio.superbwarfare.serialization.kserializer.SerializedVec3
import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.world.phys.Vec3

@Serializable
class ShootPos {
    @SerializedName("Transform")
    @SerialName("Transform")
    var transform: String = "Default"

    // TODO 后续替换成kt序列化和kt List

    // 注意这个是复数
    // TODO 允许普通枪使用Positions
    @SerializedName("Positions")
    @SerialName("Positions")
    var positions: java.util.ArrayList<SerializedVec3> = arrayListOf(Vec3.ZERO)

    // TODO 允许普通枪使用Directions
    @SerializedName("Directions")
    @SerialName("Directions")
    var directions: java.util.ArrayList<StringOrVec3> = arrayListOf(StringOrVec3("Default"))

    /** Indexed exactly like Positions; absent data falls back to the legacy transform path. */
    @SerializedName("MuzzleAttachments")
    @SerialName("MuzzleAttachments")
    var muzzleAttachments: java.util.ArrayList<String> = arrayListOf()

    /** Indexed exactly like Directions; absent data falls back to the legacy direction path. */
    @SerializedName("MuzzleDirectionAttachments")
    @SerialName("MuzzleDirectionAttachments")
    var muzzleDirectionAttachments: java.util.ArrayList<String> = arrayListOf()

    @SerializedName("ShootPositionForHud")
    @SerialName("ShootPositionForHud")
    var shootPositionForHud: SerializedVec3? = null

    @SerializedName("ShootDirectionForHud")
    @SerialName("ShootDirectionForHud")
    var shootDirectionForHud: StringOrVec3? = null

    @SerializedName("HudOriginAttachment")
    @SerialName("HudOriginAttachment")
    var hudOriginAttachment: String? = null

    @SerializedName("HudDirectionAttachment")
    @SerialName("HudDirectionAttachment")
    var hudDirectionAttachment: String? = null

    @SerializedName("BoundUpWithAmmoAmount")
    @SerialName("BoundUpWithAmmoAmount")
    var boundUpWithAmmoAmount = false


    @SerializedName("ViewPosition")
    @SerialName("ViewPosition")
    var viewPosition: SerializedVec3? = null

    @SerializedName("ViewAttachment")
    @SerialName("ViewAttachment")
    var viewAttachment: String? = null

    @SerializedName("ViewDirection")
    @SerialName("ViewDirection")
    var viewDirection: StringOrVec3? = null

    @SerializedName("ViewDirectionAttachment")
    @SerialName("ViewDirectionAttachment")
    var viewDirectionAttachment: String? = null

    @SerializedName("SeekAttachment")
    @SerialName("SeekAttachment")
    var seekAttachment: String? = null

    @SerializedName("SeekDirectionAttachment")
    @SerialName("SeekDirectionAttachment")
    var seekDirectionAttachment: String? = null

    /** Optional presentation origins. Ballistic muzzle selection remains unchanged. */
    @SerializedName("EffectAttachments")
    @SerialName("EffectAttachments")
    var effectAttachments: java.util.ArrayList<String> = arrayListOf()

    @SerializedName("EffectDirectionAttachments")
    @SerialName("EffectDirectionAttachments")
    var effectDirectionAttachments: java.util.ArrayList<String> = arrayListOf()
}
