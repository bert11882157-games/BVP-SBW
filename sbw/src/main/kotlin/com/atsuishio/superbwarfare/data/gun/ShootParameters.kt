package com.atsuishio.superbwarfare.data.gun

import com.atsuishio.superbwarfare.api.weapon.ShotFrameReference
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponGuidanceContext
import net.minecraft.server.level.ServerLevel
import net.minecraft.world.entity.Entity
import net.minecraft.world.phys.Vec3
import java.util.*

/**
 * 开火参数
 * 
 * @param ammoSupplier   弹药提供者
 * @param shooter        射击者
 * @param level          ServerLevel
 * @param shootPosition  子弹位置
 * @param shootDirection 射击方向
 * @param data           GunData
 * @param spread         子弹散布
 * @param zoom           是否开镜
 * @param targetEntityUUID           已锁定实体UUID
 * @param targetPos           已锁定位置
 */
@JvmRecord
data class ShootParameters(
    @JvmField val ammoSupplier: Entity?,
    @JvmField val shooter: Entity?,
    @JvmField val level: ServerLevel,
    @JvmField val shootPosition: Vec3,
    @JvmField val shootDirection: Vec3,
    @JvmField val data: GunData,
    @JvmField val spread: Double,
    @JvmField val zoom: Boolean,
    @JvmField val targetEntityUUID: UUID?,
    @JvmField val targetPos: Vec3?,
    @JvmField val emitNativeSound: Boolean = true,
    /** Optional presentation frame; ballistic creation continues to use shootPosition/shootDirection. */
    @JvmField val effectPosition: Vec3? = null,
    @JvmField val effectDirection: Vec3? = null,
    /** Pre-shot vehicle frame identity; never reselect this from mutated GunData during publication. */
    @JvmField val frameReference: ShotFrameReference? = null,
    /** Immutable launch selection used only by server-side wire-guided steering. */
    @JvmField val weaponGuidanceContext: VehicleWeaponGuidanceContext? = null,
    /** Optional effective projectile data for one typed belt round; ammo state remains [data]. */
    @JvmField val projectileData: GunData? = null,
    /** Per-round tracer presentation override, carried in the immutable launch context. */
    @JvmField val projectileBeltTracer: ProjectileBeltTracer? = null,
)
