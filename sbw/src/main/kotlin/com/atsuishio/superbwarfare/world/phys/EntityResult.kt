package com.atsuishio.superbwarfare.world.phys

import com.atsuishio.superbwarfare.tools.OBB
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3

class EntityResult @JvmOverloads constructor(
    val entity: Entity,
    @get:JvmName("getHitPos") val hitVec: Vec3,
    @get:JvmName("isHeadshot") val headshot: Boolean,
    @get:JvmName("isLegShot") val legShot: Boolean,
    val hitPart: OBB.Part? = null,
)
