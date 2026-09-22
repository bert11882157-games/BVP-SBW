package com.atsuishio.superbwarfare.entity.mixin

import com.atsuishio.superbwarfare.tools.OBB
import com.atsuishio.superbwarfare.world.phys.ProjectileContact
import net.minecraft.world.entity.Entity

@Suppress("FunctionName")
interface OBBHitter {
    /**
     * 获取当前命中部分
     */
    fun `sbw$getCurrentHitPart`(): OBB.Part?

    /**
     * 设置当前命中部分
     */
    fun `sbw$setCurrentHitPart`(part: OBB.Part)

    fun `sbw$getProjectileContact`(): ProjectileContact?

    fun `sbw$setProjectileContact`(contact: ProjectileContact?)

    companion object {
        @JvmStatic
        fun getInstance(entity: Entity): OBBHitter {
            return entity as OBBHitter
        }
    }
}
