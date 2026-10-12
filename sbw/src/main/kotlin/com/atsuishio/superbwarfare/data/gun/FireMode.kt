package com.atsuishio.superbwarfare.data.gun

import com.google.gson.annotations.SerializedName
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
enum class FireMode(val typeName: String) {
    @SerializedName("Semi")
    @SerialName("Semi")
    SEMI("Semi"),

    @SerializedName("Burst")
    @SerialName("Burst")
    BURST("Burst"),

    @SerializedName("Auto")
    @SerialName("Auto")
    AUTO("Auto");

    override fun toString() = typeName

    companion object {
        fun tryParse(value: String?): FireMode = entries.firstOrNull { it.typeName == value } ?: SEMI
    }
}
