package com.atsuishio.superbwarfare.client.input

import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.KeyMapping
import net.minecraftforge.client.settings.KeyModifier
import net.minecraftforge.client.settings.IKeyConflictContext

/**
 * A context-aware vehicle binding whose unmodified form remains active while Shift/Ctrl/Alt is
 * held for another vehicle action. Forge's generic NONE modifier handling assumes custom contexts
 * are mutually exclusive with modifier chords, which is incorrect for W+Shift/W+Ctrl driving.
 */
class VehicleKeyMapping @JvmOverloads constructor(
    description: String,
    modifier: KeyModifier,
    type: InputConstants.Type,
    defaultKey: Int,
    category: String,
    private val inputContext: IKeyConflictContext = VehicleKeyConflictContext,
) : KeyMapping(
    description,
    inputContext,
    modifier,
    type,
    defaultKey,
    category,
) {
    private fun modifierIsActive(): Boolean =
        keyModifier == KeyModifier.NONE || keyModifier.isActive(inputContext)

    override fun isActiveAndMatches(keyCode: InputConstants.Key): Boolean =
        keyCode != InputConstants.UNKNOWN
                && keyCode == key
                && inputContext.isActive()
                && modifierIsActive()

    override fun isConflictContextAndModifierActive(): Boolean =
        inputContext.isActive() && modifierIsActive()

    fun claims(keyCode: InputConstants.Key): Boolean = isActiveAndMatches(keyCode)
}
