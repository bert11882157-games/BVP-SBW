package com.atsuishio.superbwarfare.tools

import net.minecraft.resources.ResourceLocation
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

data class ExplosionFxContext(
    val directSource: Entity?,
    val attacker: Entity?,
    val gameplayPosition: Vec3,
    val particlePosition: Vec3,
    val radius: Float,
    val emitFx: Boolean,
    val particleType: ParticleTool.ParticleType,
    val causeId: ResourceLocation?,
    val profileId: ResourceLocation?
)
