package com.atsuishio.superbwarfare.client

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.client.animation.AnimationCurves
import com.atsuishio.superbwarfare.client.decorator.ContainerItemDecorator
import com.atsuishio.superbwarfare.client.decorator.LuckyContainerItemDecorator
import com.atsuishio.superbwarfare.client.model.curio.ParachuteModel
import com.atsuishio.superbwarfare.client.model.curio.ThermalImagingGogglesModel
import com.atsuishio.superbwarfare.client.overlay.*
import com.atsuishio.superbwarfare.client.renderer.block.*
import com.atsuishio.superbwarfare.client.renderer.curio.ParachuteRenderer
import com.atsuishio.superbwarfare.client.renderer.curio.ThermalImagingGogglesRenderer
import com.atsuishio.superbwarfare.client.tooltip.*
import com.atsuishio.superbwarfare.client.tooltip.component.*
import com.atsuishio.superbwarfare.init.ModBlockEntities
import com.atsuishio.superbwarfare.init.ModItems
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.EntityRenderersEvent
import net.minecraftforge.client.event.RegisterClientTooltipComponentFactoriesEvent
import net.minecraftforge.client.event.RegisterGuiOverlaysEvent
import net.minecraftforge.client.event.RegisterItemDecorationsEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.event.lifecycle.FMLClientSetupEvent
import top.theillusivec4.curios.api.client.CuriosRendererRegistry
import java.util.LinkedHashMap
import java.util.UUID
import kotlin.math.max
import kotlin.math.min

@net.minecraftforge.fml.common.Mod.EventBusSubscriber(
    bus = net.minecraftforge.fml.common.Mod.EventBusSubscriber.Bus.MOD,
    value = [Dist.CLIENT]
)
object ClientRenderHandler {
    // TODO 正确赋值该变量
    private const val BULLET_ORIGIN_EASE_TICKS = 5.0
    private const val MAX_BULLET_RENDER_ORIGINS = 2_048
    private val bulletRenderOrigins = object : LinkedHashMap<UUID, BulletRenderOrigin>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<UUID, BulletRenderOrigin>?): Boolean {
            return size > MAX_BULLET_RENDER_ORIGINS
        }
    }

    /** Records the accepted ballistic muzzle for one successfully spawned projectile. */
    @JvmStatic
    fun registerBulletRenderOrigin(projectileId: UUID, position: Vec3) {
        registerBulletRenderOrigin(projectileId, position, null)
    }

    /** Records a presentation-frame resolver with the accepted ballistic muzzle as its fallback. */
    @JvmStatic
    fun registerBulletRenderOrigin(
        projectileId: UUID,
        position: Vec3,
        resolver: BulletRenderOriginResolver?,
    ) {
        if (!position.isFinite()) return
        bulletRenderOrigins[projectileId] = BulletRenderOrigin(position, resolver)
    }

    /**
     * 修改子弹类实体的虚拟渲染位置
     */
    @JvmStatic
    fun markBulletRenderVisible(projectile: Projectile, partialTick: Float) {
        val entry = bulletRenderOrigins[projectile.uuid] ?: return
        if (entry.firstVisibleAge == null) {
            entry.firstVisibleAge = projectile.tickCount + partialTick.toDouble()
        }
    }

    @JvmStatic
    fun transformVirtualRenderPosition(stack: PoseStack, projectile: Projectile, partialTick: Float) {
        val offset = virtualRenderOffset(projectile, partialTick)
        stack.translate(offset.x, offset.y, offset.z)
    }

    @JvmStatic
    fun virtualRenderOffset(projectile: Projectile, partialTick: Float): Vec3 {
        val projectileId = projectile.uuid
        val entry = bulletRenderOrigins[projectileId] ?: return Vec3.ZERO
        val projectileAge = projectile.tickCount + partialTick.toDouble()
        val firstVisibleAge = entry.firstVisibleAge ?: return Vec3.ZERO
        val age = max(0.0, projectileAge - firstVisibleAge)
        if (age >= BULLET_ORIGIN_EASE_TICKS) {
            bulletRenderOrigins.remove(projectileId)
            return Vec3.ZERO
        }
        val origin = entry.resolvedOrigin ?: entry.resolve(partialTick).also { entry.resolvedOrigin = it }
        val rate = 1 - AnimationCurves.EASE_OUT_CIRC.apply(min(1.0, age / BULLET_ORIGIN_EASE_TICKS))
        return origin.subtract(projectile.getPosition(partialTick)).multiply(rate, rate, rate)
    }

    /** Shared missile effects use the same interpolated attachment and muzzle easing as the mesh. */
    @JvmStatic fun missileNozzlePosition(entity: net.minecraft.world.entity.Entity, partial: Float): Vec3 =
        com.atsuishio.superbwarfare.api.effect.MissilePresentation.nozzlePosition(entity, partial).let {
            if (entity is Projectile) it.add(virtualRenderOffset(entity, partial)) else it
        }

    fun interface BulletRenderOriginResolver {
        fun resolve(partialTick: Float): Vec3?
    }

    private class BulletRenderOrigin(
        private val fallback: Vec3,
        private val resolver: BulletRenderOriginResolver?,
    ) {
        var resolvedOrigin: Vec3? = null
        var firstVisibleAge: Double? = null

        fun resolve(partialTick: Float): Vec3 {
            return resolver?.resolve(partialTick)?.takeIf { it.isFinite() } ?: fallback
        }
    }

    private fun Vec3.isFinite(): Boolean = x.isFinite() && y.isFinite() && z.isFinite()

    @SubscribeEvent
    fun registerTooltip(event: RegisterClientTooltipComponentFactoriesEvent) {
        event.register(GunImageComponent::class.java) { ClientGunImageTooltip(it) }
        event.register(BocekImageComponent::class.java) { ClientBocekImageTooltip(it) }
        event.register(CellImageComponent::class.java) { ClientCellImageTooltip(it) }
        event.register(SentinelImageComponent::class.java) { ClientSentinelImageTooltip(it) }
        event.register(ChargingStationImageComponent::class.java) { ClientChargingStationImageTooltip(it) }
        event.register(DogTagImageComponent::class.java) { ClientDogTagImageTooltip(it) }
    }

    @SubscribeEvent
    fun registerRenderers(event: EntityRenderersEvent.RegisterRenderers) {
        event.registerBlockEntityRenderer(ModBlockEntities.CONTAINER.get()) { _ -> ContainerBlockEntityRenderer() }
        event.registerBlockEntityRenderer(ModBlockEntities.FUMO_25.get()) { _ -> FuMO25BlockEntityRenderer() }
        event.registerBlockEntityRenderer(ModBlockEntities.CHARGING_STATION.get()) { _ -> ChargingStationBlockEntityRenderer() }
        event.registerBlockEntityRenderer(ModBlockEntities.SMALL_CONTAINER.get()) { _ -> SmallContainerBlockEntityRenderer() }
        event.registerBlockEntityRenderer(ModBlockEntities.LUCKY_CONTAINER.get()) { _ -> LuckyContainerBlockEntityRenderer() }
        event.registerBlockEntityRenderer(ModBlockEntities.VEHICLE_ASSEMBLING_TABLE.get()) { _ -> VehicleAssemblingTableBlockEntityRenderer() }
        event.registerBlockEntityRenderer(ModBlockEntities.BLUEPRINT_RESEARCH_TABLE.get()) { _ -> BlueprintResearchTableBlockEntityRenderer() }
    }

    @SubscribeEvent
    fun registerGuiOverlays(event: RegisterGuiOverlaysEvent) {
        event.registerBelowAll(KillMessageOverlay.ID, KillMessageOverlay)
        event.registerBelow(Mod.loc(KillMessageOverlay.ID), ArmorPlateOverlay.ID, ArmorPlateOverlay)
        event.registerBelow(Mod.loc(ArmorPlateOverlay.ID), AmmoBarOverlay.ID, AmmoBarOverlay)
        event.registerBelow(Mod.loc(AmmoBarOverlay.ID), IFFOverlay.ID, IFFOverlay)
        event.registerBelow(Mod.loc(IFFOverlay.ID), VehicleTeamOverlay.ID, VehicleTeamOverlay)
        event.registerBelow(Mod.loc(VehicleTeamOverlay.ID), JavelinHudOverlay.ID, JavelinHudOverlay)
        event.registerBelow(Mod.loc(JavelinHudOverlay.ID), IglaHudOverlay.ID, IglaHudOverlay)
        event.registerBelow(Mod.loc(IglaHudOverlay.ID), VehicleHudOverlay.ID, VehicleHudOverlay)
        event.registerBelow(Mod.loc(VehicleHudOverlay.ID), VehicleMainWeaponHudOverlay.ID, VehicleMainWeaponHudOverlay)
        event.registerBelow(
            Mod.loc(VehicleMainWeaponHudOverlay.ID),
            VehicleCrosshairOverlay.ID,
            VehicleCrosshairOverlay
        )
        event.registerBelowAll(StaminaOverlay.ID, StaminaOverlay)
        event.registerBelowAll(AmmoCountOverlay.ID, AmmoCountOverlay)
        event.registerBelowAll(ItemRendererFixOverlay.ID, ItemRendererFixOverlay)
        event.registerBelowAll(CrossHairOverlay.ID, CrossHairOverlay)
        event.registerBelowAll(HeatBarOverlay.ID, HeatBarOverlay)
        event.registerBelowAll(DroneHudOverlay.ID, DroneHudOverlay)
        event.registerBelowAll(RedTriangleOverlay.ID, RedTriangleOverlay)
        event.registerBelowAll(HandsomeFrameOverlay.ID, HandsomeFrameOverlay)
        event.registerBelowAll(SpyglassRangeOverlay.ID, SpyglassRangeOverlay)
        event.registerBelowAll(TowOverlay.ID, TowOverlay)
        event.registerBelowAll(MortarInfoOverlay.ID, MortarInfoOverlay)
        event.registerBelowAll(Type63InfoOverlay.ID, Type63InfoOverlay)
        event.registerBelowAll(SodayoRocketInfoOverlay.ID, SodayoRocketInfoOverlay)
        event.registerAboveAll(IncomingMissileOverlay.ID, IncomingMissileOverlay)
    }

    @SubscribeEvent
    fun registerItemDecorations(event: RegisterItemDecorationsEvent) {
        event.register(ModItems.CONTAINER.get(), ContainerItemDecorator())
        event.register(ModItems.LUCKY_CONTAINER.get(), LuckyContainerItemDecorator())
    }

    @SubscribeEvent
    fun onClientSetup(event: FMLClientSetupEvent?) {
        CuriosRendererRegistry.register(ModItems.PARACHUTE.get()) { ParachuteRenderer() }
        CuriosRendererRegistry.register(ModItems.THERMAL_IMAGING_GOGGLES.get()) { ThermalImagingGogglesRenderer() }
    }

    @SubscribeEvent
    fun registerLayer(event: EntityRenderersEvent.RegisterLayerDefinitions) {
        event.registerLayerDefinition(ParachuteModel.LAYER_LOCATION) { ParachuteModel.createBodyLayer() }
        event.registerLayerDefinition(ThermalImagingGogglesModel.LAYER_LOCATION) { ThermalImagingGogglesModel.createBodyLayer() }
    }
}
