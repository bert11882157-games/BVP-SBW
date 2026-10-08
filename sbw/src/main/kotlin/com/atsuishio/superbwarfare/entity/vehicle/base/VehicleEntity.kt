package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.weapon.ReloadTransitionPolicy
import com.atsuishio.superbwarfare.api.weapon.ShotRejectionReason
import com.atsuishio.superbwarfare.api.weapon.ShotResult
import com.atsuishio.superbwarfare.api.weapon.ShotFrameReference
import com.atsuishio.superbwarfare.api.vehicle.aim.*
import com.atsuishio.superbwarfare.api.vehicle.camera.VehicleCameraMode
import com.atsuishio.superbwarfare.api.vehicle.camera.VehicleSeatPoseResolver
import com.atsuishio.superbwarfare.api.vehicle.camera.VehicleSeatPoseSnapshot
import com.atsuishio.superbwarfare.api.vehicle.camera.controlsPlayerLookAim
import com.atsuishio.superbwarfare.api.vehicle.damage.*
import com.atsuishio.superbwarfare.api.vehicle.destruction.VehicleDestructionContext
import com.atsuishio.superbwarfare.api.vehicle.flight.*
import com.atsuishio.superbwarfare.api.vehicle.lifecycle.VehicleItemLifecycleCodec
import com.atsuishio.superbwarfare.api.vehicle.lifecycle.VehicleItemLifecycleProviders
import com.atsuishio.superbwarfare.api.vehicle.module.*
import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleAttachmentResolver
import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleAttachmentSnapshot
import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleChassisPresentation
import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleChassisPresentationTimeline
import com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseProvider
import com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseSnapshot
import com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseComposition
import com.atsuishio.superbwarfare.api.vehicle.pose.VehicleTransformSnapshot
import com.atsuishio.superbwarfare.api.vehicle.action.*
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponScheduleSnapshot
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponScheduler
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponShotDiagnostics
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleMuzzleFrame
import com.atsuishio.superbwarfare.api.vehicle.weapon.ActiveVehicleWeaponPair
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponPair
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponPairProvider
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponSlot
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponGuidance
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponGuidanceContext
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleHudAimRay
import com.atsuishio.superbwarfare.capability.ModCapabilities
import com.atsuishio.superbwarfare.capability.energy.SyncedEntityEnergyStorage
import com.atsuishio.superbwarfare.capability.player.PlayerVariable
import com.atsuishio.superbwarfare.client.VehicleAimPresentationController
import com.atsuishio.superbwarfare.client.camera.VehicleCameraRequest
import com.atsuishio.superbwarfare.client.camera.VehicleCameraResolver
import com.atsuishio.superbwarfare.client.particle.CannonMuzzleFlareOption
import com.atsuishio.superbwarfare.client.particle.CustomCloudOption
import com.atsuishio.superbwarfare.config.server.MiscConfig
import com.atsuishio.superbwarfare.config.server.VehicleConfig
import com.atsuishio.superbwarfare.data.DataLoader
import com.atsuishio.superbwarfare.data.gun.AmmoConsumer
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.loot.WreckageLootData
import com.atsuishio.superbwarfare.data.loot.WreckageLootDataManager
import com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData
import com.atsuishio.superbwarfare.data.vehicle.VehicleData
import com.atsuishio.superbwarfare.data.vehicle.VehiclePropertyModifier
import com.atsuishio.superbwarfare.data.vehicle.subdata.*
import com.atsuishio.superbwarfare.data.vehicle.subdata.EngineInfo.*
import com.atsuishio.superbwarfare.diagnostics.VehicleWeaponAudioDiagnostics
import com.atsuishio.superbwarfare.entity.OBBEntity
import com.atsuishio.superbwarfare.api.vehicle.collision.AircraftCollisionSnapshot
import com.atsuishio.superbwarfare.entity.getValue
import com.atsuishio.superbwarfare.entity.setValue
import com.atsuishio.superbwarfare.entity.vehicle.DroneEntity
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity
import com.atsuishio.superbwarfare.entity.vehicle.Tom6Entity
import com.atsuishio.superbwarfare.entity.vehicle.damage.DamageModifier
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleMiscUtils
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleMotionUtils
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleEngineUtils.helicopterControlLifecycle
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils.getXRotFromVector
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleVecUtils.getYRotFromVector
import com.atsuishio.superbwarfare.entity.vehicle.utils.VehicleWeaponUtils
import com.atsuishio.superbwarfare.event.GunEventHandler
import com.atsuishio.superbwarfare.event.GunEventHandler.tryStartReload
import com.atsuishio.superbwarfare.event.ClientMouseHandler
import com.atsuishio.superbwarfare.init.*
import com.atsuishio.superbwarfare.inventory.handler.VehicleContainerHandler
import com.atsuishio.superbwarfare.inventory.menu.*
import com.atsuishio.superbwarfare.item.container.ContainerBlockItem
import com.atsuishio.superbwarfare.item.curio.DogTagItem
import com.atsuishio.superbwarfare.network.message.receive.ClientIndicatorMessage
import com.atsuishio.superbwarfare.network.message.receive.ClientVehicleItemMessage
import com.atsuishio.superbwarfare.network.message.receive.EntitySyncMessage
import com.atsuishio.superbwarfare.network.AimPresentationTraceSample
import com.atsuishio.superbwarfare.network.NetworkTelemetry
import com.atsuishio.superbwarfare.network.VehicleHelicopterAtgmCameraRayState
import com.atsuishio.superbwarfare.network.VehicleHelicopterAtgmCameraRayTransport
import com.atsuishio.superbwarfare.tools.*
import com.atsuishio.superbwarfare.tools.OBB.Part.*
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.tools.RangeTool.calculateFiringSolution
import com.atsuishio.superbwarfare.tools.VectorTool.combineRotationsTurret
import com.atsuishio.superbwarfare.tools.VectorTool.lerpGetEntityBoundingBoxCenter
import com.atsuishio.superbwarfare.world.saveddata.TDMSavedData
import com.google.common.collect.ImmutableList
import com.mojang.math.Axis
import net.minecraft.ChatFormatting
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.Holder
import net.minecraft.core.NonNullList
import net.minecraft.core.particles.ParticleOptions
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.core.registries.Registries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntArrayTag
import net.minecraft.nbt.IntTag
import net.minecraft.nbt.ListTag
import net.minecraft.nbt.Tag
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.syncher.EntityDataAccessor
import net.minecraft.network.syncher.EntityDataSerializers
import net.minecraft.network.syncher.SynchedEntityData
import net.minecraft.network.syncher.SynchedEntityData.DataValue
import net.minecraft.resources.ResourceKey
import net.minecraft.resources.ResourceLocation
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.server.level.TicketType
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.util.Mth
import net.minecraft.util.RandomSource
import net.minecraft.world.ContainerHelper
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.SimpleMenuProvider
import net.minecraft.world.damagesource.DamageSource
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.effect.MobEffects
import net.minecraft.world.entity.*
import net.minecraft.world.entity.item.ItemEntity
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.AbstractArrow
import net.minecraft.world.entity.projectile.Projectile
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.entity.vehicle.DismountHelper
import net.minecraft.world.inventory.AbstractContainerMenu
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.NameTagItem
import net.minecraft.world.level.ChunkPos
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.gameevent.GameEvent
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import net.minecraft.world.phys.shapes.VoxelShape
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.common.ForgeMod
import net.minecraftforge.common.capabilities.Capability
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.FakePlayer
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.energy.IEnergyStorage
import net.minecraftforge.items.ItemHandlerHelper
import net.minecraftforge.network.NetworkHooks
import net.minecraftforge.registries.ForgeRegistries
import org.joml.*
import java.util.*
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Function
import javax.annotation.ParametersAreNonnullByDefault
import kotlin.math.*
import kotlin.random.Random

abstract class VehicleEntity(pEntityType: EntityType<*>, pLevel: Level) : Entity(pEntityType, pLevel),
    VehiclePropertyModifier, HasCustomInventoryScreen, OBBEntity, com.atsuishio.superbwarfare.api.projectile.ProjectileCollisionTarget {

    private val persistentStateOwner = VehiclePersistentStateOwner()
    private val synchronizedStateOwner = VehicleSynchronizedStateOwner()
    private val vehicleWeaponRuntime = VehicleWeaponRuntime(this) {
        weaponScheduler.shouldEmitNativeSound()
    }
    private val kinematicStateOwner = VehicleKinematicStateOwner()
    private val combatStateOwner = VehicleCombatStateOwner()
    private val vehicleModuleStateService = VehicleModuleStateService(VehicleEntityModuleStateAccess(this))
    private val vehicleDestructionLifecycleService = VehicleDestructionLifecycleService(this)
    private val vehicleDamageLifecycleService = VehicleDamageLifecycleService(this)
    private val vehicleCollisionEnvironmentService = VehicleCollisionEnvironmentService(this)
    private val aircraftTerrainCollisionService = AircraftTerrainCollisionService(this)
    internal val collisionResponse = VehicleCollisionResponseService(this)
    private val fixedWingGroundContactService = FixedWingGroundContactService(this)
    private val vehicleGroundMotionService = VehicleGroundMotionService(this)
    private val lifecycleStateOwner = VehicleLifecycleStateOwner()
    private val clientPresentationStateOwner = VehicleClientPresentationStateOwner()

    private var activeDestructionContext: VehicleDestructionContext?
        get() = vehicleDestructionLifecycleService.activeContext
        set(value) {
            vehicleDestructionLifecycleService.activeContext = value
        }
    private val weaponScheduler = VehicleWeaponScheduler(this)
    private val vehicleAimController = VehicleAimController(this)
    private val vehicleFlightController = VehicleFlightController(this)
    private val aircraftCountermeasures = com.atsuishio.superbwarfare.api.aircraft.AircraftCountermeasures(this)
    private val vehicleTickPipeline = VehicleTickPipeline(this)
    private val vehicleActionController = VehicleActionController(this, Consumer(::publishVehicleActionSnapshots))
    /** One bounded launch-owner stream per occupied operator/seat; pilot and gunner never overwrite. */
    private val acceptedHelicopterAtgmCameraRays
        get() = combatStateOwner.acceptedHelicopterAtgmCameraRays
    private var vehicleInputBits: Short
        get() = synchronizedStateOwner.vehicleInputBits
        set(value) {
            synchronizedStateOwner.vehicleInputBits = value
        }
    var gunDataMap: Map<String, GunData>
        get() {
            val rawMap = entityData.get(GUN_DATA_MAP)
            val config = computed()
            return vehicleWeaponRuntime.resolveGunDataMap(rawMap, config)
        }
        set(value) {
            if (entityData.get(GUN_DATA_MAP) == value) {
                NetworkTelemetry.recordEntityDataSuppressed("vehicle_gun_data_map_logical_equal")
                return
            }
            val copied = value.toMap()
            entityData.set(GUN_DATA_MAP, copied)
            vehicleWeaponRuntime.invalidateResolvedGunData()
        }

    internal fun publishedGunDataSnapshot(): Map<String, GunData> = entityData.get(GUN_DATA_MAP)

    internal fun publishWeaponRuntimeSnapshot(snapshot: Map<String, GunData>) {
        entityData.set(GUN_DATA_MAP, snapshot)
    }

    private fun publishTextSnapshot(
        accessor: EntityDataAccessor<String>,
        metricName: String,
        payload: String,
    ) {
        if (level().isClientSide) return
        if (entityData.get(accessor) == payload) {
            NetworkTelemetry.recordEntityDataSuppressed(metricName)
            return
        }
        if (NetworkTelemetry.isEnabled()) {
            NetworkTelemetry.recordEntityDataDirty(metricName, payload.toByteArray(Charsets.UTF_8).size)
        }
        entityData.set(accessor, payload)
    }

    internal fun publishModuleStateSnapshot(payload: String) =
        publishTextSnapshot(MODULE_STATE_SNAPSHOT, "vehicle_module_state_snapshot", payload)

    internal fun reportDamageDebug(source: DamageSource, amount: Float) {
        damageDebugResultReceiver?.sendSystemMessage(DamageHandler.getDamageInfo(this, source, amount))
    }

    internal fun invokeVanillaHurt(source: DamageSource, amount: Float): Boolean = super.hurt(source, amount)

    fun getSeat(seatIndex: Int) =
        computed().seats().getOrNull(seatIndex)

    fun getSeat(passenger: Entity?): SeatInfo? {
        return getSeat(getSeatIndex(passenger))
    }

    /**
     * 获取载具座位上选中的武器
     *
     * @param seatIndex 座位号
     * @return 武器数据
     */
    fun getGunData(seatIndex: Int): GunData? {
        val selectedIndex = getSelectedWeapon(seatIndex)
        if (selectedIndex < 0) return null
        return getGunData(seatIndex, selectedIndex)
    }

    /**
     * 获取载具座位上指定编号的武器
     *
     * @param seatIndex   座位号
     * @param weaponIndex 武器号
     * @return 武器数据
     */
    fun getGunData(seatIndex: Int, weaponIndex: Int): GunData? {
        val seat = getSeat(seatIndex) ?: return null

        val name = getWeaponIds(seatIndex).getOrNull(weaponIndex) ?: return null

        return getGunData(name)
    }

    /**
     * 获取载具的乘客座位上指定编号的武器
     *
     * @param passenger   乘客
     * @param weaponIndex 武器号
     * @return 武器数据
     */
    fun getGunData(passenger: Entity?, weaponIndex: Int) = getGunData(getSeatIndex(passenger), weaponIndex)

    /**
     * 获取载具的乘客座位上选中的武器
     *
     * @param passenger 乘客
     * @return 武器数据
     */
    fun getGunData(passenger: Entity?) =
        getGunData(passenger, this.getSelectedWeapon(this.getSeatIndex(passenger)))

    /**
     * 根据名称获取武器
     *
     * @param name 武器名称
     * @return 武器数据
     */
    fun getGunData(name: String) = this.gunDataMap[name]
        ?: com.atsuishio.superbwarfare.api.aircraft.AircraftStoreWeapons.data(this, name)

    /** Stable native order plus authored mount channels; both weapon slots use this list. */
    fun getWeaponIds(seatIndex: Int): List<String> =
        com.atsuishio.superbwarfare.api.aircraft.AircraftStoreWeapons.ids(
            this, seatIndex, getSeat(seatIndex)?.weapons() ?: emptyList())

    fun getGunName(seatIndex: Int): String? {
        if (seatIndex < 0) return null
        val seat = getSeat(seatIndex) ?: return null

        val weaponIndex = getSelectedWeapon(seatIndex)
        if (weaponIndex < 0) return null

        val weapons = getWeaponIds(seatIndex)
        if (weaponIndex >= weapons.size) return null

        return getGunName(seatIndex, weaponIndex)
    }

    fun getGunName(seatIndex: Int, weaponIndex: Int): String? {
        return getWeaponIds(seatIndex).getOrNull(weaponIndex)
    }

    fun modifyGunData(seatIndex: Int, weaponIndex: Int, consumer: Consumer<GunData>) {
        modifyGunData(getGunName(seatIndex, weaponIndex), consumer)
    }

    fun modifyGunData(seatIndex: Int, consumer: Consumer<GunData>) {
        modifyGunData(getGunName(seatIndex), consumer)
    }

    fun modifyGunData(name: String?, consumer: Consumer<GunData>) {
        if (name == null) return
        if (com.atsuishio.superbwarfare.api.aircraft.AircraftGunPodGroups.isAlias(name)) {
            com.atsuishio.superbwarfare.api.aircraft.AircraftGunPodGroups.members(this, name).forEach { modifyGunData(it, consumer) }
            return
        }

        // Weapons of the live map are changed in place and published like the per-tick publisher does.
        if (vehicleWeaponRuntime.modifyLive(name, consumer)) return

        val map = this.gunDataMap.toMutableMap()
        var data = getGunData(name) ?: return

        data = data.copy()
        data.vehicleWeaponIdentity = name
        consumer.accept(data)
        data.save()
        map[name] = data

        gunDataMap = map
    }

    /** Reload behavior is native by default; addons may opt their own vehicles into another policy. */
    open fun vehicleReloadTransitionPolicy(): ReloadTransitionPolicy = ReloadTransitionPolicy.LEGACY

    /** First-entry reload credit is opt-in so native SBW vehicles keep their placement lifecycle. */
    protected open fun initialPassengerReloadProgressPercent(): Int = 0

    private var initialPassengerReloadProgressApplied: Boolean
        get() = persistentStateOwner.initialPassengerReloadProgressApplied
        set(value) {
            persistentStateOwner.initialPassengerReloadProgressApplied = value
        }

    /** Entity-owned configuration state; avoids a global weak-cache lookup on every data read. */
    private val vehicleData = VehicleData(this)
    private var vehicleAimProfileDataOwner: DefaultVehicleData?
        get() = clientPresentationStateOwner.vehicleAimProfileDataOwner
        set(value) {
            clientPresentationStateOwner.vehicleAimProfileDataOwner = value
        }
    private val vehicleAimProfileCache
        get() = clientPresentationStateOwner.vehicleAimProfileCache

    var obb = listOf<OBBInfo>()
        protected set

    private var vehiclePoseSequence: Int
        get() = clientPresentationStateOwner.vehiclePoseSequence
        set(value) {
            clientPresentationStateOwner.vehiclePoseSequence = value
        }
    private var vehiclePosePrevious: VehiclePoseSnapshot
        get() = clientPresentationStateOwner.vehiclePosePrevious
        set(value) {
            clientPresentationStateOwner.vehiclePosePrevious = value
        }
    private var vehiclePoseCurrent: VehiclePoseSnapshot
        get() = clientPresentationStateOwner.vehiclePoseCurrent
        set(value) {
            clientPresentationStateOwner.vehiclePoseCurrent = value
        }
    private var vehiclePosePayload: String
        get() = clientPresentationStateOwner.vehiclePosePayload
        set(value) {
            clientPresentationStateOwner.vehiclePosePayload = value
        }
    private var vehiclePoseClientUpdateTick: Int
        get() = clientPresentationStateOwner.vehiclePoseClientUpdateTick
        set(value) {
            clientPresentationStateOwner.vehiclePoseClientUpdateTick = value
        }
    private var vehiclePoseAbsolutePending: PendingVehiclePoseAbsolute?
        get() = clientPresentationStateOwner.vehiclePoseAbsolutePending
        set(value) {
            clientPresentationStateOwner.vehiclePoseAbsolutePending = value
        }
    private val vehicleChassisPresentationTimeline
        get() = clientPresentationStateOwner.vehicleChassisPresentationTimeline
    private val vehicleAttachmentResolver = VehicleAttachmentResolver()
    private val vehicleClientPresentationService =
        VehicleClientPresentationService(this, clientPresentationStateOwner, vehicleAimController)
    private val aimPresentationCache
        get() = clientPresentationStateOwner.aimPresentationCache
    private var aimPresentationCacheTick: Int
        get() = clientPresentationStateOwner.aimPresentationCacheTick
        set(value) {
            clientPresentationStateOwner.aimPresentationCacheTick = value
        }
    private var aimPresentationCachePartialBits: Int
        get() = clientPresentationStateOwner.aimPresentationCachePartialBits
        set(value) {
            clientPresentationStateOwner.aimPresentationCachePartialBits = value
        }
    private var aimPresentationCacheEpoch: Int
        get() = clientPresentationStateOwner.aimPresentationCacheEpoch
        set(value) {
            clientPresentationStateOwner.aimPresentationCacheEpoch = value
        }
    private val aimPresentationContinuity
        get() = clientPresentationStateOwner.aimPresentationContinuity
    private val aimPresentationResolvedSequences
        get() = clientPresentationStateOwner.aimPresentationResolvedSequences
    private val aimPresentationTraceCacheHits
        get() = clientPresentationStateOwner.aimPresentationTraceCacheHits
    private var aimPresentationContinuityEpoch: Int
        get() = clientPresentationStateOwner.aimPresentationContinuityEpoch
        set(value) {
            clientPresentationStateOwner.aimPresentationContinuityEpoch = value
        }
    private val aimPresentationAdmissionReasons
        get() = clientPresentationStateOwner.aimPresentationAdmissionReasons
    private var aimPresentationControllerEpochInitialized: Boolean
        get() = clientPresentationStateOwner.aimPresentationControllerEpochInitialized
        set(value) {
            clientPresentationStateOwner.aimPresentationControllerEpochInitialized = value
        }
    private var aimPresentationTurretControllerUuid: UUID?
        get() = clientPresentationStateOwner.aimPresentationTurretControllerUuid
        set(value) {
            clientPresentationStateOwner.aimPresentationTurretControllerUuid = value
        }
    private var aimPresentationStationControllerUuid: UUID?
        get() = clientPresentationStateOwner.aimPresentationStationControllerUuid
        set(value) {
            clientPresentationStateOwner.aimPresentationStationControllerUuid = value
        }
    var lastRequestedMovement: Vec3
        get() = kinematicStateOwner.lastRequestedMovement
        private set(value) {
            kinematicStateOwner.lastRequestedMovement = value
        }
    var lastResolvedMovement: Vec3
        get() = kinematicStateOwner.lastResolvedMovement
        private set(value) {
            kinematicStateOwner.lastResolvedMovement = value
        }
    var lastCollisionStepCandidateDeltaY: Double
        get() = kinematicStateOwner.lastCollisionStepCandidateDeltaY
        private set(value) {
            kinematicStateOwner.lastCollisionStepCandidateDeltaY = value
        }
    var lastCollisionStepAppliedDeltaY: Double
        get() = kinematicStateOwner.lastCollisionStepAppliedDeltaY
        private set(value) {
            kinematicStateOwner.lastCollisionStepAppliedDeltaY = value
        }


    var engineInfo: EngineInfo? = null
    private val vehicleEngineRuntime = VehicleEngineRuntime { error ->
        Mod.LOGGER.error("Failed to parse engine info for vehicle {}", this, error)
    }

    protected var interpolationSteps = 0
    protected var xO = 0.0
    protected var yO = 0.0
    protected var zO = 0.0

    var roll = 0f
        set(value) {
            field = value
            refreshPhysicalCollisionBounds()
        }

    var prevRoll = 0f
    var repairCoolDown = maxRepairCoolDown()

    var crash = false

    open var turretYRot = 0f
    open var turretXRot = 0f
    open var turretYRotO = 0f
    open var turretXRotO = 0f
    var turretYRotLock = 0f

    var gunYRot = 0f
    var gunXRot = 0f
    var gunYRotO = 0f
    var gunXRotO = 0f

    protected var noPassengerTime = 0
    protected var damageDebugResultReceiver: Player? = null

    var decoyReloadCoolDown = 0

    var lastTickSpeed = 0.0
    protected var lastTickVerticalSpeed = 0.0

    var collisionCoolDown = 0

    private var wasEngineRunning: Boolean
        get() = lifecycleStateOwner.wasEngineRunning
        set(value) {
            lifecycleStateOwner.wasEngineRunning = value
        }
    private var wasHornWorking: Boolean
        get() = lifecycleStateOwner.wasHornWorking
        set(value) {
            lifecycleStateOwner.wasHornWorking = value
        }
    private var wasStuka: Boolean
        get() = lifecycleStateOwner.wasStuka
        set(value) {
            lifecycleStateOwner.wasStuka = value
        }
    private var wasHeliCrash: Boolean
        get() = lifecycleStateOwner.wasHeliCrash
        set(value) {
            lifecycleStateOwner.wasHeliCrash = value
        }
    private var wasVehicleSkip: Boolean
        get() = lifecycleStateOwner.wasVehicleSkip
        set(value) {
            lifecycleStateOwner.wasVehicleSkip = value
        }

    private var wasFiring: Boolean
        get() = lifecycleStateOwner.wasFiring
        set(value) {
            lifecycleStateOwner.wasFiring = value
        }

    open var targetSpeed = 0.0
        get() = if (vehicleActionController.allowsMovement()) field else 0.0

    var rudderRot = 0f
    var rudderRotO = 0f
    var leftWheelRot = 0f
    var rightWheelRot = 0f
    var leftWheelRotO = 0f
    var rightWheelRotO = 0f

    var leftTrackO = 0f
    var rightTrackO = 0f
    var leftTrack = 0f
    var rightTrack = 0f

    var propellerRot = 0f
    var propellerRotO = 0f

    var recoilShake = 0.0
    var recoilShakeO = 0.0

    var flap1LRot = 0f
    var flap1LRotO = 0f
    var flap1RRot = 0f
    var flap1RRotO = 0f
    var flap1L2Rot = 0f
    var flap1L2RotO = 0f
    var flap1R2Rot = 0f
    var flap1R2RotO = 0f
    var flap2LRot = 0f
    var flap2LRotO = 0f
    var flap2RRot = 0f
    var flap2RRotO = 0f
    var flap3Rot = 0f
    var flap3RotO = 0f
    private var gearRotO = 0f

    var gearRot = 0f

    var engineStart = false
    var engineStartOver = false
    var holdTick = 0
    var holdPowerTick = 0

    var destroyRot = 0f

    var jumpCoolDown = 0
    var deltaMovementO: Vec3 = deltaMovement
    var positionO: Vec3 = Vec3.ZERO

    var absoluteSpeed = 0.0
    var absoluteSpeedO = 0.0
    var absoluteSpeedLerp = 0.0

    var pitchAngle = 0f
    var pitchVelocity = 0f
    var prevPitchAngle = 0f
    var rollAngle = 0f
    var rollVelocity = 0f
    var prevRollAngle = 0f
    var prevMotion: Vec3? = null

    var lastDamageSource: DamageSource? = null
        get() {
            if (this.level().gameTime - this.lastDamageStamp > 40L) {
                this.lastDamageSource = null
            }
            return field
        }
    var lastDamageStamp: Long = 0

    private fun initOBB() {
        this.obb = data().getDefault().copy().obb.toList()
    }

    override fun onSyncedDataUpdated(dataValues: MutableList<DataValue<*>>) {
        super.onSyncedDataUpdated(dataValues)
        var overrideChanged = false
        var poseChanged = false
        var flightChanged = false
        var actionChanged = false
        var modulesChanged = false
        for (index in dataValues.indices) {
            when (dataValues[index].id()) {
                OVERRIDE.id -> overrideChanged = true
                VEHICLE_POSE_SNAPSHOT.id -> poseChanged = true
                VEHICLE_FLIGHT_INSTRUMENT_SNAPSHOT.id -> flightChanged = true
                VEHICLE_ACTION_SNAPSHOT.id -> actionChanged = true
                MODULE_STATE_SNAPSHOT.id -> modulesChanged = true
            }
        }
        if (overrideChanged) invalidateVehicleData()
        if (level().isClientSide) {
            if (modulesChanged) vehicleModuleStateService.invalidateClientSnapshot()
            if (poseChanged) consumeSyncedVehiclePose()
            if (flightChanged) {
                vehicleFlightController.consumeClient(entityData.get(VEHICLE_FLIGHT_INSTRUMENT_SNAPSHOT))
            }
            if (actionChanged) {
                vehicleActionController.consumeClient(entityData.get(VEHICLE_ACTION_SNAPSHOT))
            }
        }
        if (overrideChanged || poseChanged || dataValues.any { it.id() == SYNCHED_GEAR_ROT.id })
            refreshPhysicalCollisionBounds()
    }

    override fun onSyncedDataUpdated(dataAccessor: EntityDataAccessor<*>) {
        super.onSyncedDataUpdated(dataAccessor)
        if (dataAccessor == OVERRIDE) invalidateVehicleData()
        if (dataAccessor == OVERRIDE || dataAccessor == SYNCHED_GEAR_ROT)
            refreshPhysicalCollisionBounds()
    }

    fun processInput(keys: Short) {
        vehicleInputBits = keys
        leftInputDown =
            (keys.toInt() and 0b00000001) > 0
        rightInputDown =
            (keys.toInt() and 0b00000010) > 0
        forwardInputDown =
            (keys.toInt() and 0b00000100) > 0
        backInputDown =
            (keys.toInt() and 0b00001000) > 0
        upInputDown =
            (keys.toInt() and 0b00010000) > 0
        downInputDown =
            (keys.toInt() and 0b00100000) > 0
        decoyInputDown =
            (keys.toInt() and 0b01000000) > 0
        fireInputDown =
            (keys.toInt() and 0b10000000) > 0
        sprintInputDown =
            (keys.toInt() and 256) > 0
        if (!level().isClientSide) {
            (this as? FixedWingFlightStrategyProvider)
                ?.createFixedWingFlightStrategy(this)
                ?.observePilotThrottleInput(this, currentFixedWingThrottleAxis())
        }
    }

    private fun currentFixedWingThrottleAxis(): Double = when {
        backInputDown -> -1.0
        forwardInputDown -> 1.0
        else -> 0.0
    }

    @get:JvmName("forwardInputDown")
    var forwardInputDown by FORWARD_INPUT_DOWN

    @get:JvmName("backInputDown")
    var backInputDown by BACK_INPUT_DOWN

    @get:JvmName("leftInputDown")
    var leftInputDown by LEFT_INPUT_DOWN

    @get:JvmName("rightInputDown")
    var rightInputDown by RIGHT_INPUT_DOWN

    @get:JvmName("upInputDown")
    var upInputDown by UP_INPUT_DOWN

    @get:JvmName("downInputDown")
    var downInputDown by DOWN_INPUT_DOWN

    @get:JvmName("fireInputDown")
    var fireInputDown by FIRE_INPUT_DOWN

    @get:JvmName("decoyInputDown")
    var decoyInputDown by DECOY_INPUT_DOWN

    @get:JvmName("sprintInputDown")
    var sprintInputDown by SPRINT_INPUT_DOWN

    fun mouseInput(x: Double, y: Double) {
        if (!x.isFinite() || !y.isFinite()) return
        val finiteX = x.toFloat()
        val finiteY = y.toFloat()
        if (!finiteX.isFinite() || !finiteY.isFinite()) return
        mouseMoveSpeedX = finiteX
        mouseMoveSpeedY = finiteY
    }

    /** Flight input has pilot priority; weapon aim does not own an aircraft stick. */
    fun acceptVehicleMouseInput(controller: Player, x: Double, y: Double) {
        if (controller.vehicle !== this || firstPassenger !== controller) return
        if (!x.isFinite() || !y.isFinite()) return
        // Fixed-wing flight consumes epoch-bound world intent, never legacy mouse deltas.
        if (isFixedWingFlightVehicle()) return
        if (vehicleAimController.capturesMouseInput(controller)) {
            mouseMoveSpeedX = 0F
            mouseMoveSpeedY = 0F
            return
        }
        mouseInput(x, y)
    }

    /** Pilot intent only: no camera, weapon, motion, or defensive-station authority. */
    @JvmOverloads
    fun acceptFixedWingPilotIntent(
        controller: Player, controlEpoch: Long, sequence: Long,
        worldX: Double, worldY: Double, worldZ: Double, manualMask: Int, centerAim: Boolean,
        screenRollInput: Float? = null, firstPerson: Boolean = false,
        inversionRequested: Boolean = false,
    ): Boolean {
        if (level().isClientSide) return false
        val strategy = resolveVehicleFlightStrategy() as? FixedWingFlightStrategy ?: return false
        return strategy.acceptPilotIntent(this, controller, controlEpoch, sequence,
            worldX, worldY, worldZ, manualMask, centerAim, screenRollInput, firstPerson,
            inversionRequested)
    }

    fun getFixedWingPilotIntentState(
        controller: Player,
    ): com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntentSnapshot? {
        if (level().isClientSide) return null
        val strategy = resolveVehicleFlightStrategy() as? FixedWingFlightStrategy ?: return null
        return strategy.pilotIntentSnapshot(this, controller)
    }

    private fun clearFixedWingPilotControls() {
        if (!isFixedWingFlightVehicle()) return
        (this as FixedWingFlightStrategyProvider)
            .createFixedWingFlightStrategy(this)?.clearPilotControls()
    }

    var mouseMoveSpeedX by MOUSE_SPEED_X
    var mouseMoveSpeedY by MOUSE_SPEED_Y

    // container start
    private val inventoryEnergyService = VehicleInventoryEnergyService(this)
    val inventory: VehicleContainerHandler
        get() = inventoryEnergyService.inventory

    fun getItems() = inventoryEnergyService.items()

    protected fun resizeItems() = inventoryEnergyService.resizeItems()

    open fun getContainerSize(): Int {
        return computed().vehicleContainerType.size
    }

    fun getItem(slot: Int): ItemStack = inventoryEnergyService.getItem(slot)

    fun removeItem(slot: Int, pAmount: Int): ItemStack = inventoryEnergyService.removeItem(slot, pAmount)

    open var maxStackSize: Int = 64

    fun setItem(slot: Int, pStack: ItemStack) = inventoryEnergyService.setItem(slot, pStack)

    open fun setChanged() = inventoryEnergyService.publishChanged()

    /** Transport adapter kept on the entity so inventory services do not own packet emission. */
    internal fun publishVehicleInventorySnapshot(snapshot: CompoundTag) {
        if (level().isClientSide) return
        sendPacketToTrackingThis(ClientVehicleItemMessage(id, snapshot))
    }

    fun clearContent() = inventoryEnergyService.clear()

    fun hasContainer() = this.getContainerSize() > 0

    open fun canPlaceItem(slot: Int, stack: ItemStack): Boolean =
        inventoryEnergyService.canPlaceItem(slot, stack)

    fun canTakeItem(slot: Int): Boolean = inventoryEnergyService.canTakeItem(slot)

    override fun remove(reason: RemovalReason) {
        clearFlightPilotControls()
        if (!this.level().isClientSide) {
            com.atsuishio.superbwarfare.api.weapon.VehicleReloadAudio.cancel(this, reason = "vehicle_removed")
        }
        if (!this.level().isClientSide && reason != RemovalReason.DISCARDED && reason != RemovalReason.UNLOADED_WITH_PLAYER) {
            inventoryEnergyService.dropContentsOnRemoval()
        }
        acceptedHelicopterAtgmCameraRays.clear()
        if (level().isClientSide) {
            VehicleSeatPoseResolver.invalidateVehicle(uuid)
            VehicleCameraResolver.invalidateVehicle(uuid)
        }
        vehicleFlightController.close()
        vehiclePoseAbsolutePending = null
        vehicleChassisPresentationTimeline.clear()
        vehicleAimController.clearClientPresentation()
        aimPresentationControllerEpochInitialized = false
        aimPresentationTurretControllerUuid = null
        aimPresentationStationControllerUuid = null
        invalidateChassisPresentationCache()
        super.remove(reason)
    }

    override fun openCustomInventoryScreen(player: Player) {
        if (player is ServerPlayer) {
            this.openMenu(player)
        }
    }

    fun hasMenu() = computed().vehicleContainerType.hasMenu()

    open fun openMenu(player: Player) {
        if (player is ServerPlayer) {
            NetworkHooks.openScreen(
                player, SimpleMenuProvider(
                    { containerId, inv, player -> createMenu(containerId, inv, player) },
                    Component.translatable(this.type.descriptionId)
                )
            ) { buf -> buf.writeInt(this.id) }
        }
    }

    open fun createMenu(
        pContainerId: Int,
        pPlayerInventory: Inventory,
        pPlayer: Player
    ): AbstractContainerMenu? {
        if (!pPlayer.isSpectator && this.hasMenu()) {
            val computed = computed()
            val type = computed.vehicleContainerType
            if (!type.hasMenu()) return null
            return when (type) {
                VehicleContainerType.MINI -> MiniVehicleContainerMenu(pContainerId, pPlayerInventory, this.id)
                VehicleContainerType.SMALL -> SmallVehicleContainerMenu(pContainerId, pPlayerInventory, this.id)
                VehicleContainerType.MEDIUM -> MediumVehicleContainerMenu(pContainerId, pPlayerInventory, this.id)
                VehicleContainerType.LARGE -> LargeVehicleContainerMenu(pContainerId, pPlayerInventory, this.id)
                VehicleContainerType.HUGE -> HugeVehicleContainerMenu(pContainerId, pPlayerInventory, this.id)
                else -> null
            }
        }
        return null
    }

    // container end
    // 自定义骑乘
    private val seatingStateOwner = VehicleSeatingStateOwner(generatePassengersList())
    private val passengerSlots: MutableList<Entity?>
        get() = seatingStateOwner.slots

    private fun generatePassengersList() = MutableList(maxPassengers) { null as Entity? }

    protected fun initSeatData(targetSize: Int) {
        seatingStateOwner.ensureSize(targetSize) { passenger ->
            if (passenger.vehicle === this) passenger.stopRiding()
            else seatingStateOwner.remove(passenger)
        }
        passengers = ImmutableList.copyOf(passengerSlots.filterNotNull())
    }

    protected fun checkSeatsSize() {
        val targetSize = computed().seats().size
        if (targetSize == passengerSlots.size) return

        initSeatData(targetSize)
    }

    /**
     * 获取按顺序排列的成员列表
     *
     * @return 按顺序排列的成员列表
     */
    fun getOrderedPassengers(): MutableList<Entity?> {
        checkSeatsSize()
        return passengerSlots
    }

    // 仅在客户端存在的实体顺序获取，用于在客户端正确同步实体座位顺序
    var entityIndexOverride: Function<Entity, Int>? = null

    override fun addPassenger(pPassenger: Entity) {
        check(pPassenger.vehicle === this) { "Use x.startRiding(y), not y.addPassenger(x)" }
        checkSeatsSize()

        val index = seatingStateOwner.insert(pPassenger, entityIndexOverride)
        if (index < 0) {
            // startRiding already assigned Entity.vehicle before calling this method.
            pPassenger.stopRiding()
            return
        }

        pPassenger.persistentData.putInt(TAG_SEAT_INDEX, index)
        if (index == 0) clearFlightPilotControls()

        this.passengers =
            ImmutableList.copyOf(passengerSlots.stream().filter { obj: Entity? -> Objects.nonNull(obj) }.toList())
        vehicleAimController.onControlContextChanged(pPassenger)
        this.gameEvent(GameEvent.ENTITY_MOUNT, pPassenger)

        applyInitialPassengerReloadProgress(pPassenger)

        this.setChanged()
    }

    private fun applyInitialPassengerReloadProgress(passenger: Entity) {
        if (level().isClientSide || initialPassengerReloadProgressApplied) return
        if (passenger !is Player || passenger is FakePlayer) return

        val progressPercent = initialPassengerReloadProgressPercent()
        if (progressPercent <= 0) return

        initialPassengerReloadProgressApplied = true
        for (weaponName in gunDataMap.keys.toList()) {
            modifyGunData(weaponName) { data ->
                val hadStartRequest = data.reload.reloadStarter.shouldStart()
                        || data.reload.singleReloadStarter.shouldStart()
                data.reload.setPendingProgressPercent(progressPercent.coerceIn(0, 99))
                tryStartReload(ammoSupplier, data)
                val hasStartRequest = data.reload.reloadStarter.shouldStart()
                        || data.reload.singleReloadStarter.shouldStart()
                if (!data.reloading() && !hasStartRequest && !hadStartRequest) {
                    data.reload.clearPendingProgress()
                }
            }
        }
        setChanged()
    }

    override fun removePassenger(pPassenger: Entity) {
        check(pPassenger.vehicle !== this) { "Use x.stopRiding(y), not y.removePassenger(x)" }
        checkSeatsSize()

        val index = getSeatIndex(pPassenger)
        if (index == 0) clearFlightPilotControls()

        if (!level().isClientSide) {
            com.atsuishio.superbwarfare.api.weapon.VehicleReloadAudio.detachListener(this, pPassenger.uuid, "passenger_dismounted")
        }

        acceptedHelicopterAtgmCameraRays.entries.removeIf {
            it.key.seatIndex == index && it.key.controllerUuid == pPassenger.uuid
        }

        if (level().isClientSide) VehicleSeatPoseResolver.invalidatePassenger(pPassenger.uuid)
        if (level().isClientSide) VehicleCameraResolver.invalidateController(pPassenger.uuid)

        seatingStateOwner.remove(pPassenger)
        this.passengers =
            ImmutableList.copyOf(passengerSlots.stream().filter { obj: Entity? -> Objects.nonNull(obj) }
                .toList())
        vehicleAimController.onPassengerRemoved(pPassenger)

        pPassenger.boardingCooldown = 60
        this.gameEvent(GameEvent.ENTITY_DISMOUNT, pPassenger)
    }

    fun data(): VehicleData = vehicleData

    /**
     * Entity invokes defineSynchedData from its superclass constructor. At that point
     * VehicleEntity's fields have not been initialized yet, so configuration reads must
     * remain safe until this constructor's init block has published the entity cache.
     */
    fun computed(): DefaultVehicleData =
        if (!isInitialized) VehicleData.getDefault(type) else vehicleData.compute()

    private fun invalidateVehicleData() {
        vehicleData.update()
        vehicleAimProfileDataOwner = null
        vehicleAimProfileCache.clear()
        vehicleWeaponRuntime.invalidateConfiguration()
    }

    override fun getStepHeight() = computed().upStep

    override fun getFirstPassenger(): Entity? {
        checkSeatsSize()
        return seatingStateOwner.first()
    }

    /**
     * 获取第index个乘客
     *
     * @param index 目标座位
     * @return 目标座位的乘客
     */
    fun getNthEntity(index: Int): Entity? {
        checkSeatsSize()
        return seatingStateOwner.at(index)
    }

    /**
     * 尝试切换座位
     *
     * @param entity 乘客
     * @param index  目标座位
     * @return 是否切换成功
     */
    fun changeSeat(entity: Entity, index: Int): Boolean {
        if (index < 0 || index >= this.maxPassengers) return false
        checkSeatsSize()
        if (seatingStateOwner.at(index) != null) return false

        val previousIndex = seatingStateOwner.indexOf(entity)
        if (previousIndex < 0) return false
        acceptedHelicopterAtgmCameraRays.entries.removeIf {
            it.key.controllerUuid == entity.uuid && it.key.seatIndex == previousIndex
        }
        if (!seatingStateOwner.move(entity, index)) return false
        if (!level().isClientSide) {
            com.atsuishio.superbwarfare.network.VehicleDismountServer.invalidateDismountGesture(entity)
        }
        if (previousIndex == 0 || index == 0) clearFlightPilotControls()

        entity.persistentData.putInt(TAG_SEAT_INDEX, index)
        vehicleAimController.onControlContextChanged(entity)

        // 在服务端运行时，向所有玩家同步载具座位信息
        val level = this.level()
        if (level is ServerLevel) {
            level.getPlayers { true }
                .forEach { p -> p!!.connection.send(ClientboundSetPassengersPacket(this)) }
        }

        return true
    }

    /**
     * 获取乘客所在座位索引
     *
     * @param entity 乘客
     * @return 座位索引
     */
    fun getSeatIndex(entity: Entity?): Int {
        checkSeatsSize()
        return seatingStateOwner.indexOf(entity)
    }

    /**
     * 获取乘客所在座位索引，用于下车时的位置判定
     * 下车前会先移除载具，因此 [VehicleEntity.getSeatIndex] 会返回-1
     *
     * @param entity 乘客
     * @return 座位索引
     */
    fun getTagSeatIndex(entity: Entity) = entity.persistentData.getInt(TAG_SEAT_INDEX)

    val thirdPersonCameraPosition: Vec3
        get() {
            val pos = computed().thirdPersonCameraPos
            return Vec3(pos.z + ClientMouseHandler.custom3pDistanceLerp, pos.y, pos.x)
        }

    private val flightAttitudeCache = VehicleFlightAttitude.Cache()

    private fun interpolatedFlightAttitude(tickDelta: Float) = flightAttitudeCache.sample(
        yRotO, xRotO, prevRoll, yRot, xRot, roll, tickDelta,
    )

    fun getRoll(tickDelta: Float) = if (isFixedWingFlightVehicle())
        interpolatedFlightAttitude(tickDelta).roll else Mth.lerp(tickDelta, prevRoll, roll)
    fun getYaw(tickDelta: Float) = if (isFixedWingFlightVehicle())
        interpolatedFlightAttitude(tickDelta).yaw else Mth.lerp(tickDelta, yRotO, yRot)
    open fun getPitch(tickDelta: Float) = if (isFixedWingFlightVehicle())
        interpolatedFlightAttitude(tickDelta).pitch else Mth.lerp(tickDelta, xRotO, xRot)

    fun setZRot(rot: Float) {
        roll = rot
    }

    fun turretTurnSound(diffX: Float, diffY: Float, pitch: Float) {
        if (this is MortarEntity) return
        // authored turret audio is a start / loop / stop voice driven by the slew rate, not one-shots per tick
        if (level().isClientSide && authoredTurretAudio.test(this)) return
        if (level().isClientSide && (Math.abs(diffY) > 0.5 || Math.abs(diffX) > 0.5)) {
            level().playLocalSound(
                this.x,
                this.y + this.bbHeight * 0.5,
                this.z,
                ModSounds.TURRET_TURN.get(),
                this.soundSource,
                min(0.15 * (max(Mth.abs(diffX), Mth.abs(diffY))), 0.75).toFloat(),
                (random.nextFloat() * 0.05f + pitch),
                false
            )
        }
    }

    /**
     * 受击时是否出现粒子效果
     */
    fun shouldSendHitParticles() = computed().sendHitParticles

    /** Whether armor non-penetrations may pass reduced direct damage to this vehicle. */
    fun isLightlyArmored() = computed().lightlyArmored

    /**
     * Whether an addon armor-hitbox system resolves direct hits on this vehicle. Such vehicles take no area
     * damage from TNT-equivalent charges below the vehicle threshold (25 kg by default); vehicles without
     * armor hitboxes (aircraft, helicopters, trucks) still take infantry-style blast falloff.
     */
    open fun hasArmorHitboxes(): Boolean = false

    /** Whether this vehicle's passenger weapon station is a client-visible remote weapon station. */
    fun isRemoteWeaponStation() = computed().remoteWeaponStation

    /**
     * 受击时是否出现音效
     */
    open fun shouldSendHitSounds() = true

    /** Deprecated source-compatibility adapter; storage ownership lives in the inventory/energy service. */
    @Deprecated("Use getEnergyStorage(); direct storage ownership is internal")
    protected var energyStorage: SyncedEntityEnergyStorage
        get() = inventoryEnergyService.syncedEnergyStorage()
        set(value) = inventoryEnergyService.replaceEnergyStorage(value)

    /** Deprecated source-compatibility adapter; capability lifetime is service-owned. */
    @Deprecated("Use getCapability(ForgeCapabilities.ENERGY)")
    protected var energyOptional: LazyOptional<IEnergyStorage>
        get() = inventoryEnergyService.energyCapability()
        set(value) = inventoryEnergyService.replaceEnergyCapability(value)

    // Entity invokes defineSynchedData before VehicleEntity's field initializers run.
    var isInitialized: Boolean = false
        protected set

    override fun defineSynchedData() {
        with(this.entityData) {
            define(OVERRIDE, "")
            define(HEALTH, getMaxHealth())
            define(LAST_ATTACKER_UUID, "undefined")
            define(LAST_DRIVER_UUID, "undefined")
            define(DOG_TAG_ICON, List(16) { List(16) { -1 } })
            define(GUN_DATA_MAP, mapOf())

            define(AI_TURRET_TARGET_UUID, "undefined")
            define(AI_PASSENGER_WEAPON_TARGET_UUID, "undefined")

            define(DELTA_ROT, 0f)
            define(MOUSE_SPEED_X, 0f)
            define(MOUSE_SPEED_Y, 0f)

            define(TURRET_HEALTH, getTurretMaxHealth())
            define(L_WHEEL_HEALTH, getWheelMaxHealth())
            define(R_WHEEL_HEALTH, getWheelMaxHealth())
            define(MAIN_ENGINE_HEALTH, getEngineMaxHealth())
            define(SUB_ENGINE_HEALTH, getEngineMaxHealth())

            define(TURRET_DAMAGED, false)
            define(L_WHEEL_DAMAGED, false)
            define(R_WHEEL_DAMAGED, false)
            define(MAIN_ENGINE_DAMAGED, false)
            define(SUB_ENGINE_DAMAGED, false)

            define(CANNON_RECOIL_TIME, 0)
            define(CANNON_RECOIL_FORCE, 0f)
            define(POWER, 0f)
            define(YAW_WHILE_SHOOT, 0f)
            define(SERVER_YAW, yRot)
            define(SERVER_PITCH, xRot)
            define(VEHICLE_POSE_SNAPSHOT, "")
            define(VEHICLE_FLIGHT_INSTRUMENT_SNAPSHOT, "")
            define(WEAPON_SCHEDULER_SNAPSHOT, "")
            define(VEHICLE_AIM_SNAPSHOT, "")
            define(VEHICLE_ACTION_SNAPSHOT, "")
            define(MODULE_STATE_SNAPSHOT, "")
            define(DECOY_READY, false)
            define(AIRCRAFT_COUNTERMEASURE_LEVELS, 0)
            define(AIRCRAFT_COUNTERMEASURE_TIMERS, 0)
            define(SYNCHED_GEAR_ROT, 0f)
            define(GEAR_UP, false)
            define(FORWARD_INPUT_DOWN, false)
            define(BACK_INPUT_DOWN, false)
            define(LEFT_INPUT_DOWN, false)
            define(RIGHT_INPUT_DOWN, false)
            define(UP_INPUT_DOWN, false)
            define(DOWN_INPUT_DOWN, false)
            define(FIRE_INPUT_DOWN, false)
            define(DECOY_INPUT_DOWN, false)
            define(SPRINT_INPUT_DOWN, false)

            define(PLANE_BREAK, 0f)
            define(SELECTED_WEAPON, List(maxPassengers) { 0 })
            define(SECONDARY_WEAPON, List(maxPassengers) { -1 })
            define(ENERGY, 0)
            define(SYNCHED_PROPELLER_ROT, 0f)

            define(HORN_VOLUME, 0f)
            define(LASER_LENGTH, 0f)
            define(LASER_SCALE, 0f)
            define(LASER_SCALE_O, 0f)
            define(CHARGE_PROGRESS, 0f)
            define(IS_WRECK, false)
            define(AIRCRAFT_WRECK_START, -1L)
            define(AIRCRAFT_WRECK_MOTION_X, 0F)
            define(AIRCRAFT_WRECK_MOTION_Y, 0F)
            define(AIRCRAFT_WRECK_MOTION_Z, 0F)
            define(AIRCRAFT_WRECK_WINGS, -1)
            define(AIRCRAFT_WRECK_IMPACT_TIME, -1L)
            define(SYMPATHETIC_DETONATED, false)
            define(TURRET_BURNED, false)
            define(HOVER_MODE, false)
            define(TURRET_BURN_TIMER, 0)
        }
    }

    // energy start
    /**
     * Propulsion and control power is unlimited under the no-fuel policy.
     *
     * This is separate from the Forge energy capability below, which remains persistent and may be
     * consumed by weapons whose configured ammo type is ENERGY. Fuel support must update this
     * policy, its availability check, and [consumeOperationalPowerOnServer] together while preserving
     * the stored-energy API.
     */
    fun isOperationalPowerLimited(): Boolean = false

    fun hasOperationalPower(amount: Int): Boolean = true

    /**
     * Server-authoritative operational-power debit. The no-fuel policy debits nothing;
     * [consumeOperationalPowerOnServer] supplies the protected implementation hook.
     */
    fun consumeOperationalPower(amount: Int) {
        if (amount <= 0 || this.level() !is ServerLevel) return
        consumeOperationalPowerOnServer(amount)
    }

    protected open fun consumeOperationalPowerOnServer(amount: Int) = Unit

    /**
     * Consumes persistent stored energy for energy-ammo and other explicit Forge-energy gameplay.
     * Vehicle propulsion/control must use the operational-power policy above instead.
     *
     * @param amount stored energy to consume
     */
    fun consumeEnergy(amount: Int) {
        inventoryEnergyService.consumeEnergy(amount)
    }

    protected fun canConsume(amount: Int): Boolean = inventoryEnergyService.canConsume(amount)

    var energy: Int
        get() = inventoryEnergyService.energy()
        set(pEnergy) = inventoryEnergyService.setEnergy(pEnergy)

    fun getEnergyStorage(): IEnergyStorage? = inventoryEnergyService.getEnergyStorage()

    val maxEnergy: Int
        get() = inventoryEnergyService.maxEnergy()


    fun hasEnergyStorage() = inventoryEnergyService.hasEnergyStorage()

    // energy end
    /**
     * 当前情况载具是否可以开火
     *
     * @param living 玩家
     * @return 是否可以开火
     */
    open fun canShoot(living: LivingEntity?): Boolean {
        val gunData = getGunData(getSeatIndex(living))
        return gunData != null && gunData.canShoot(this.ammoSupplier)
    }

    /**
     * 主武器射速
     *
     * @return 射速
     */
    fun vehicleWeaponRpm(living: LivingEntity?): Int {
        val data = getGunData(getSeatIndex(living))
        if (data == null || data.get(GunProp.RPM) <= 0) return 60
        return data.get(GunProp.RPM)
    }

    fun vehicleWeaponRpm(seatIndex: Int): Int {
        val data = getGunData(seatIndex)
        if (data == null || data.get(GunProp.RPM) <= 0) return 60
        return data.get(GunProp.RPM)
    }

    fun vehicleWeaponRpm(weaponName: String): Int {
        val data = getGunData(weaponName) ?: return 1
        return data.get(GunProp.RPM).coerceAtLeast(1)
    }

    fun hasScheduledWeapon(controller: LivingEntity?): Boolean {
        return controller != null && weaponScheduler.hasSchedule(controller)
    }

    fun updateScheduledWeaponTrigger(
        controller: LivingEntity?,
        held: Boolean,
        targetEntityUuid: UUID?,
        targetPos: Vec3?,
    ): Boolean {
        if (controller == null) {
            VehicleWeaponShotDiagnostics.recordBoundary(
                this,
                null,
                "<selected>",
                VehicleWeaponShotDiagnostics.Boundary.INPUT,
                "NO_CONTROLLER",
            )
            return false
        }
        return weaponScheduler.updateTrigger(controller, held, targetEntityUuid, targetPos)
    }

    /**
     * Secondary held-fire edge.  It shares the normal scheduler and authority path and never
     * mutates [selectedWeapon].  The server revalidates the occupied seat and current ordered
     * slot context on every edge; stale action sessions therefore cannot fire an ABA weapon.
     */
    fun updateSecondaryWeaponTrigger(controller: LivingEntity?, held: Boolean): Boolean {
        if (controller == null) {
            VehicleWeaponShotDiagnostics.recordBoundary(
                this,
                null,
                "<secondary>",
                VehicleWeaponShotDiagnostics.Boundary.INPUT,
                "NO_CONTROLLER",
            )
            return false
        }
        if (controller.vehicle !== this || level().isClientSide) {
            VehicleWeaponShotDiagnostics.recordBoundary(
                this,
                controller,
                "<secondary>",
                VehicleWeaponShotDiagnostics.Boundary.INPUT,
                "NOT_MOUNTED_OR_CLIENT",
            )
            return false
        }
        val seatIndex = getSeatIndex(controller)
        if (!isSecondaryWeaponContextValid(controller, seatIndex, secondaryWeaponContextToken(seatIndex))) {
            weaponScheduler.updateSecondaryTrigger(controller, false)
            return false
        }
        return weaponScheduler.updateSecondaryTrigger(controller, held)
    }

    fun getWeaponScheduleSnapshot(
        seatIndex: Int,
        weaponIndex: Int,
    ): VehicleWeaponScheduleSnapshot? {
        if (!level().isClientSide) {
            return weaponScheduler.snapshot(seatIndex, weaponIndex)
        }
        return weaponScheduler.clientSnapshot(
            entityData.get(WEAPON_SCHEDULER_SNAPSHOT),
            seatIndex,
            weaponIndex,
        )
    }

    fun publishWeaponScheduleSnapshots(payload: String) {
        publishTextSnapshot(WEAPON_SCHEDULER_SNAPSHOT, "vehicle_weapon_scheduler_snapshot", payload)
    }

    fun getWeaponHeat(living: LivingEntity?): Int {
        val gunData = getGunData(getSeatIndex(living)) ?: return 0
        return Math.round(gunData.heat.get()).toInt()
    }

    fun getWeaponHeat(seatIndex: Int): Int {
        val gunData = getGunData(seatIndex) ?: return 0
        return Math.round(gunData.heat.get()).toInt()
    }

    fun getWeaponHeat(weaponName: String): Int {
        val gunData = getGunData(weaponName) ?: return 0
        return Math.round(gunData.heat.get()).toInt()
    }

    fun getWeaponHeat(seatIndex: Int, weaponIndex: Int): Int {
        val gunData = getGunData(seatIndex, weaponIndex) ?: return 0
        return Math.round(gunData.heat.get()).toInt()
    }

    fun getShootAnimationTimer(weaponName: String): Int {
        val gunData = getGunData(weaponName) ?: return 0
        return gunData.shootAnimationTimer.get()
    }

    fun getShootAnimationTimer(seatIndex: Int, weaponIndex: Int): Int {
        val gunData = getGunData(seatIndex, weaponIndex) ?: return 0
        return gunData.shootAnimationTimer.get()
    }

    open fun vehicleShoot(living: LivingEntity?, weaponName: String) {
        if (!isVehicleActionFireAllowed()) {
            reportVehicleShotResult(
                living,
                weaponName,
                ShotResult.rejected(ShotRejectionReason.ACTION_BLOCKED, weaponName),
            )
            return
        }
        vehicleShootResult(living, weaponName)
    }

    /** One admission boundary shared by named, targeted, primary, and secondary transactions. */
    fun isVehicleActionFireAllowed(): Boolean =
        vehicleActionController.allowsFire() && areVehicleWeaponModulesOperational()

    /** Subclass module policy belongs here, not in individual shooting overloads. */
    protected open fun areVehicleWeaponModulesOperational(): Boolean = true

    /**
     * Reports a rejected result only when the opt-in bounded diagnostics probe is enabled.
     * Keeping this at the common result boundary observes every scheduler/explicit-weapon path
     * without changing fire admission or any accepted-shot side effect.
     */
    private fun reportVehicleShotResult(
        living: LivingEntity?,
        weaponName: String?,
        result: ShotResult,
    ): ShotResult {
        if (!result.isAccepted()) {
            when (result.reason) {
                ShotRejectionReason.NO_WEAPON -> VehicleWeaponShotDiagnostics.recordBoundary(
                    this,
                    living,
                    weaponName ?: result.weaponName,
                    VehicleWeaponShotDiagnostics.Boundary.SELECTION,
                    "NO_GUN_DATA_OR_SLOT",
                )

                ShotRejectionReason.NOT_SERVER_AUTHORITY -> VehicleWeaponShotDiagnostics.recordBoundary(
                    this,
                    living,
                    weaponName ?: result.weaponName,
                    VehicleWeaponShotDiagnostics.Boundary.INPUT,
                    "NOT_SERVER",
                )

                ShotRejectionReason.ACTION_BLOCKED -> VehicleWeaponShotDiagnostics.recordBoundary(
                    this,
                    living,
                    weaponName ?: result.weaponName,
                    VehicleWeaponShotDiagnostics.Boundary.INPUT,
                    "ACTION_BLOCKED",
                )

                else -> Unit
            }
        }
        VehicleWeaponShotDiagnostics.record(this, living, weaponName ?: result.weaponName, result)
        return result
    }

    open fun vehicleShootResult(living: LivingEntity?, weaponName: String): ShotResult {
        return reportVehicleShotResult(living, weaponName,
            vehicleWeaponRuntime.fire(living, weaponName, null, null) { true })
    }

    /**
     * Explicit-weapon scheduler path.  Unlike the selected-weapon dispatcher this can fire a
     * paired secondary while preserving the equipped index; it retains the same muzzle,
     * ShootParameters, ammo, acceptance, recoil, visual, and sound lifecycle.
     */
    open fun vehicleShootResult(
        living: LivingEntity?,
        weaponName: String,
        targetEntityUuid: UUID?,
        targetPos: Vec3?,
    ): ShotResult {
        return reportVehicleShotResult(living, weaponName,
            vehicleWeaponRuntime.fire(living, weaponName, targetEntityUuid, targetPos) { true })
    }

    open fun vehicleShoot(living: LivingEntity?, uuid: UUID?, targetPos: Vec3?) {
        if (!isVehicleActionFireAllowed()) {
            reportVehicleShotResult(
                living,
                null,
                ShotResult.rejected(ShotRejectionReason.ACTION_BLOCKED),
            )
            return
        }
        vehicleShootResult(living, uuid, targetPos)
    }

    open fun vehicleShootResult(living: LivingEntity?, uuid: UUID?, targetPos: Vec3?): ShotResult {
        val result = vehicleWeaponRuntime.fire(living, null, uuid, targetPos) { permitsVehicleShotAttempt(living) }
        return reportVehicleShotResult(living, result.weaponName, result)
    }

    /** Allows custom result-bearing weapons to preserve the scheduler's direct-attempt gate. */
    protected fun permitsVehicleShotAttempt(living: LivingEntity?): Boolean {
        return living == null || weaponScheduler.permitsDirectAttempt(living)
    }

    open fun afterShoot(gunData: GunData?, shootVec: Vec3) {
        if (gunData != null) {
            val recoilTime = gunData.get(GunProp.RECOIL_TIME)
            if (recoilTime > 0) {
                if (recoilTime > cannonRecoilTime) {
                    cannonRecoilTime = recoilTime
                }

                val angle = Mth.wrapDegrees(
                    -getYRotFromVector(getViewVector(1f)) + getYRotFromVector(shootVec)
                ).toFloat()

                val vo = Vec3(0.0, 0.0, 1.0)
                val f =
                    0.3 * cannonRecoilForce * (cannonRecoilTime / recoilTime).toDouble()
                val v1 = vo.yRot(yawWhileShoot * Mth.DEG_TO_RAD).scale(f)
                val v2 = vo.yRot(angle * Mth.DEG_TO_RAD).scale(gunData.get(GunProp.RECOIL_FORCE).toDouble())
                val v3 = v1.add(v2)

                yawWhileShoot =
                    Mth.wrapDegrees(-getYRotFromVector(vo) + getYRotFromVector(v3))
                        .toFloat()
                cannonRecoilForce = v3.length().toFloat()

                gunData.shakePlayers(this)
            }
        }
    }

    fun playShootSound3p(living: LivingEntity?, weaponName: String) {
        val gunData = this.getGunData(weaponName) ?: return
        val pos = getShootPos(weaponName, 1f)

        playShootSound3p(living, gunData, pos)
    }

    fun playShootSound3p(living: LivingEntity?, seatIndex: Int) {
        val gunData = this.getGunData(seatIndex) ?: return
        val pos = getShootPos(living, 1f)

        playShootSound3p(living, gunData, pos)
    }

    fun playShootSound3p(living: LivingEntity?, gunData: GunData?, pos: Vec3?) {
        val serverLevel = this.level() as? ServerLevel ?: return

        if (gunData == null) return

        val soundInfo = gunData.get(GunProp.SOUND_INFO)
        // Use the accepted weapon's heat, not the seat's selected primary.  Explicit
        // secondary shots have an independent heat/reload lifecycle.
        val weaponHeat = Math.round(gunData.heat.get()).toInt()
        val pitch = if (weaponHeat <= 60) 1f else (1 - 0.011 * abs(60 - weaponHeat)).toFloat()

        val listener: Entity?

        if (living != null && (living.vehicle !== this || living.vehicle == null)) {
            listener = null
        } else {
            // Suppress the muzzle-positioned copy only when this accepted weapon's native
            // hook already owns the shooter's local sound.  Do not compare against the
            // selected gun: an explicit paired secondary is intentionally not selected.
            listener = if (living != null && gunData.item.nativeFireSoundOwnsListener(gunData, living)) {
                living
            } else {
                null
            }
        }

        val soundRadius = gunData.get(GunProp.SOUND_RADIUS)
        val fire3P = soundInfo.fire3P
        val fire3PFar = soundInfo.fire3PFar
        val fire3PVeryFar = soundInfo.fire3PVeryFar

        // A few vehicle profiles intentionally reuse one sample for multiple distance tiers.
        // Keep the widest effective radius for that sample instead of stacking identical copies.
        val soundsByLocation = linkedMapOf<ResourceLocation, Triple<SoundEvent, Float, String>>()
        fun addSound(sound: SoundEvent?, radius: Float, channel: String) {
            if (sound == null) return

            val existing = soundsByLocation[sound.location]
            if (existing == null) {
                soundsByLocation[sound.location] = Triple(sound, radius, channel)
            } else if (radius > existing.second) {
                VehicleWeaponAudioDiagnostics.recordServer(
                    this,
                    living,
                    gunData,
                    existing.third,
                    VehicleWeaponAudioDiagnostics.Disposition.DUPLICATE_SUPPRESSED,
                    existing.first,
                    "same_event_as=$channel kept_radius=$radius suppressed_radius=${existing.second}",
                )
                soundsByLocation[sound.location] = Triple(sound, radius, channel)
            } else {
                VehicleWeaponAudioDiagnostics.recordServer(
                    this,
                    living,
                    gunData,
                    channel,
                    VehicleWeaponAudioDiagnostics.Disposition.DUPLICATE_SUPPRESSED,
                    sound,
                    "same_event_as=${existing.third} kept_radius=${existing.second} suppressed_radius=$radius",
                )
            }
        }

        addSound(fire3P, (soundRadius * 0.4f * soundInfo.fire3PGain).toFloat(), "VEHICLE_FIRE_3P")
        addSound(fire3PFar, (soundRadius * 0.7f * soundInfo.fire3PFarGain).toFloat(), "VEHICLE_FIRE_FAR")
        addSound(
            fire3PVeryFar,
            (soundRadius * soundInfo.fire3PVeryFarGain).toFloat(),
            "VEHICLE_FIRE_VERY_FAR",
        )

        pos?.let {
            for ((sound, radius, channel) in soundsByLocation.values) {
                VehicleWeaponAudioDiagnostics.recordServer(
                    this,
                    living,
                    gunData,
                    channel,
                    VehicleWeaponAudioDiagnostics.Disposition.PLAYED,
                    sound,
                    "radius=$radius pitch=$pitch listener=${listener?.uuid ?: "<none>"}",
                )
            }
            // one event, every tier: each client plays the interior (crew) or near/far/very-far variant for its
            // distance, delayed by the speed of sound and Doppler-shifted (SpatialAudio)
            val cue = com.atsuishio.superbwarfare.api.audio.SpatialAudio.weaponCue(
                soundInfo.fire1P, fire3P, fire3PFar, fire3PVeryFar,
                (soundRadius * 0.4f * soundInfo.fire3PGain).toFloat(),
                (soundRadius * 0.7f * soundInfo.fire3PFarGain).toFloat(),
                (soundRadius * soundInfo.fire3PVeryFarGain).toFloat(),
            )
            com.atsuishio.superbwarfare.api.audio.SpatialAudio.emit(
                serverLevel, it, cue, soundInfo.volume.takeIf { v -> v.isFinite() && v > 0f } ?: 1f, pitch, this,
                living ?: controllingPassenger,
                com.atsuishio.superbwarfare.api.audio.SpatialAudio.Category.WEAPON, gunData.vehicleWeaponIdentity,
            )
        }
    }

    /**
     * 获取该槽位当前的武器编号，返回-1则表示该位置没有可用武器
     *
     * @param seatIndex 槽位
     * @return 武器类型
     */
    fun getWeaponIndex(seatIndex: Int) =
        getSelectedWeapon(seatIndex)

    /**
     * 检测载具是否有武器
     *
     * @return 是否有武器
     */
    fun hasWeapon(): Boolean {
        return this.computed().seats().stream()
            .filter { seat: SeatInfo? -> seat!!.weapons().isNotEmpty() }
            .flatMap { seat: SeatInfo? -> seat!!.weapons().stream() }
            .filter { name: String? -> !name.isNullOrEmpty() }
            .anyMatch { name -> this.getGunData(name) != null }
    }

    /**
     * 检测该槽位是否有可用武器
     *
     * @param seatIndex 武器槽位
     * @return 武器是否可用
     */
    fun hasWeapon(seatIndex: Int): Boolean {
        if (seatIndex < 0 || seatIndex >= this.maxPassengers) return false
        return this.getGunData(seatIndex) != null
    }

    /** Sets the primary slot for a seat. Explicit selection retains legacy pair semantics. */
    fun setWeaponIndex(seatIndex: Int, selectedWeaponIndex: Int) {
        setWeaponSlotIndex(seatIndex, VehicleWeaponSlot.PRIMARY, selectedWeaponIndex)
    }

    /**
     * Changes one independently selectable slot. Explicit selection of the other occupied slot
     * still swaps the pair; cycling below filters that slot instead of swapping it.
     */
    fun setWeaponSlotIndex(
        seatIndex: Int,
        slot: VehicleWeaponSlot,
        targetWeaponIndex: Int,
    ): Boolean = vehicleWeaponRuntime.setWeaponSlotIndex(seatIndex, slot, targetWeaponIndex)

    /** Advances one slot while never selecting the weapon currently occupying the other slot. */
    fun cycleWeaponSlot(
        seatIndex: Int,
        slot: VehicleWeaponSlot,
        delta: Int = 1,
    ): Boolean = vehicleWeaponRuntime.cycleWeaponSlot(seatIndex, slot, delta)

    /**
     * 切换武器事件
     *
     * @param seatIndex 武器槽位
     * @param value     数值（可能为-1~1之间的滚动，或绝对数值）
     * @param isScroll  是否是滚动事件
     */
    fun changeWeapon(seatIndex: Int, value: Int, isScroll: Boolean) {
        if (seatIndex < 0 || seatIndex >= this.maxPassengers) return

        val candidates = vehicleWeaponRuntime.validWeaponIndices(seatIndex)
        if (candidates.isEmpty()) return
        if (isScroll) {
            if (!cycleWeaponSlot(seatIndex, VehicleWeaponSlot.PRIMARY, value)) return
            val weapon = getGunData(seatIndex, getSelectedWeapon(seatIndex)) ?: return
            val sound = weapon.get(GunProp.SOUND_INFO).change
            if (sound != null) {
                this.level().playSound(null, this, sound, this.soundSource, 1f, 1f)
            }
            return
        }

        val currentIndex = vehicleWeaponRuntime.resolvedPrimaryIndex(seatIndex, candidates)
        val typeIndex = if (seatIndex == 0 &&
            com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager.definition(this) != null)
            candidates.getOrNull(value.coerceIn(0, candidates.lastIndex))
        else candidates.firstOrNull { it == value }
            ?: candidates.getOrNull(value.coerceIn(0, candidates.lastIndex))
        if (typeIndex == null) return
        if (typeIndex == currentIndex) return

        val weapon = getGunData(seatIndex, typeIndex) ?: return

        // 修改该槽位选择的武器
        if (!setWeaponSlotIndex(seatIndex, VehicleWeaponSlot.PRIMARY, typeIndex)) return

        // 播放武器切换音效
        val sound = weapon.get(GunProp.SOUND_INFO).change
        if (sound != null) {
            this.level().playSound(null, this, sound, this.soundSource, 1f, 1f)
        }
    }

    internal fun withdrawWeaponOnSelectionChange(seatIndex: Int, weaponIndex: Int) {
        if (weaponIndex < 0) return
        getGunName(seatIndex, weaponIndex)?.let { weaponIdentity ->
            com.atsuishio.superbwarfare.api.weapon.VehicleReloadAudio.cancel(
                this,
                weaponIdentity,
                "weapon_slot_changed",
            )
        }
        modifyGunData(seatIndex, weaponIndex) { gunData ->
            if (gunData.get(GunProp.WITHDRAW_AMMO_WHEN_CHANGE_SLOT)) {
                gunData.withdrawAmmo(ammoSupplier)
            }
        }
    }

    internal fun notifyWeaponContextChanged(
        seatIndex: Int,
        previousWeaponIndex: Int,
        selectedWeaponIndex: Int,
    ) {
        getNthEntity(seatIndex)?.let {
            vehicleAimController.onWeaponContextChanged(
                it, seatIndex, previousWeaponIndex, selectedWeaponIndex,
            )
        }
    }

    override fun readAdditionalSaveData(compound: CompoundTag) {
        invalidateVehicleData()
        override = compound.getString("Override")
        // GunData
        val state = compound.getCompound("WeaponState")
        val newMap = mutableMapOf<String, GunData>()
        for (key in state.allKeys) {
            val tag = state.getCompound(key).copy()

            tag.putString("id", "superbwarfare:vehicle_gun")
            tag.putInt("Count", 1)

            newMap[key] = GunData.from(ItemStack.of(tag)).also {
                it.vehicleWeaponIdentity = key
            }
        }
        gunDataMap = newMap

        health = if (compound.contains("Health")) {
            compound.getFloat("Health")
        } else {
            this.getMaxHealth()
        }

        turretHealth = componentHealthOrDefault(compound, "TurretHealth", "TurretDamaged", getTurretMaxHealth())
        leftWheelHealth = componentHealthOrDefault(compound, "LeftWheelHealth", "LeftWheelDamaged", getWheelMaxHealth())
        rightWheelHealth = componentHealthOrDefault(compound, "RightWheelHealth", "RightWheelDamaged", getWheelMaxHealth())
        mainEngineHealth = componentHealthOrDefault(compound, "MainEngineHealth", "MainEngineDamaged", getEngineMaxHealth())
        subEngineHealth = componentHealthOrDefault(compound, "SubEngineHealth", "SubEngineDamaged", getEngineMaxHealth())

        turretDamaged = compound.getBoolean("TurretDamaged")
        leftWheelDamaged = compound.getBoolean("LeftWheelDamaged")
        rightWheelDamaged = compound.getBoolean("RightWheelDamaged")
        mainEngineDamaged = compound.getBoolean("MainEngineDamaged")
        subEngineDamaged = compound.getBoolean("SubEngineDamaged")

        vehicleModuleStateService.readAdditionalSaveData(compound)
        vehicleActionController.readAdditionalSaveData(compound)
        initialPassengerReloadProgressApplied = compound.getBoolean("InitialPassengerReloadProgressApplied")

        power = compound.getFloat("Power")
        decoyReady = compound.getBoolean("DecoyReady")
        aircraftCountermeasures.load(compound)
        synchedGearRot = compound.getFloat("GearRot")
        gearUp = compound.getBoolean("GearUp")
        synchedPropellerRot = compound.getFloat("PropellerRot")
        chargeProgress = compound.getFloat("ChargeProgress")
        lastAttackerUUID = compound.getString("LastAttacker")
        lastDriverUUID = compound.getString("LastDriver")

        val dogTagTag = compound.get("DogTagIcon")
        val list = mutableListOf<List<Short>>()
        if (dogTagTag is ListTag) {
            dogTagTag.forEach {
                val sl = mutableListOf<Short>()
                if (it is IntArrayTag) {
                    sl.addAll(it.asIntArray.map { num -> num.toShort() })
                }
                list.add(sl)
            }
        }
        dogTagIcon = list

        serverYaw = compound.getFloat("ServerYaw")
        serverPitch = compound.getFloat("ServerPitch")

        isWreck = compound.getBoolean("IsWreck")
        aircraftWreckStart = if (compound.contains("AircraftWreckStart")) compound.getLong("AircraftWreckStart").coerceAtLeast(-1L) else -1L
        aircraftWreckMotionX = compound.getFloat("AircraftWreckMotionX").takeIf { it.isFinite() && kotlin.math.abs(it) < 100F } ?: 0F
        aircraftWreckMotionY = compound.getFloat("AircraftWreckMotionY").takeIf { it.isFinite() && kotlin.math.abs(it) < 100F } ?: 0F
        aircraftWreckMotionZ = compound.getFloat("AircraftWreckMotionZ").takeIf { it.isFinite() && kotlin.math.abs(it) < 100F } ?: 0F
        aircraftWreckWings = if (compound.contains("AircraftWreckWings")) compound.getInt("AircraftWreckWings").coerceIn(-1, 3) else -1
        aircraftWreckImpactTime = if (compound.contains("AircraftWreckImpactTime")) compound.getLong("AircraftWreckImpactTime").coerceAtLeast(-1L) else -1L
        aircraftWreckBounces = compound.getInt("AircraftWreckBounces").coerceIn(0, 3)
        sympatheticDetonated = compound.getBoolean("SympatheticDetonated")
        turretBurned = compound.getBoolean("TurretBurned")
        turretBurnTimer = compound.getInt("TurretBurnTimer")

        val selectedWeaponTag = compound.get("SelectedWeapon")
        val selected = if (selectedWeaponTag is IntArrayTag) {
            selectedWeaponTag.asIntArray
        } else {
            IntArray(this.maxPassengers)
        }

        selectedWeapon = List(maxPassengers) { selected.getOrNull(it) ?: -1 }

        val secondaryWeaponTag = compound.get("SecondaryWeapon")
        val secondary = if (secondaryWeaponTag is IntArrayTag) {
            secondaryWeaponTag.asIntArray
        } else {
            IntArray(this.maxPassengers) { -1 }
        }
        secondaryWeapon = List(maxPassengers) { secondary.getOrNull(it) ?: -1 }

        inventoryEnergyService.readEnergy(compound.get("Energy"))

        this.resizeItems()
        if (compound.contains("Inventory")) {
            this.inventory.deserializeNBT(compound.getCompound("Inventory"))
        } else {
            val items = NonNullList.withSize(this.getContainerSize(), ItemStack.EMPTY)
            ContainerHelper.loadAllItems(compound, items)
            this.inventory.setItems(items)
        }
    }

    public override fun addAdditionalSaveData(compound: CompoundTag) {
        checkSeatsSize()

        compound.putFloat("Health", health)

        val overrideString = override
        if (!overrideString.isBlank()) {
            compound.putString("Override", overrideString)
        }
        compound.putString("LastAttacker", lastAttackerUUID)
        compound.putString("LastDriver", lastDriverUUID)

        val listTag = ListTag()
        dogTagIcon.forEach {
            listTag.add(IntArrayTag(it.toShortArray().map { num -> num.toInt() }))
        }
        compound.put("DogTagIcon", listTag)

        val tag = CompoundTag()
        for (kv in gunDataMap.entries) {
            val data = GunData.from(kv.value.stack.copy())
            data.backupAmmoCount.reset()
            data.save()

            val stackTag = data.stack.save(CompoundTag())
            stackTag.remove("id")
            stackTag.remove("count")
            if (stackTag.isEmpty) continue

            tag.put(kv.key, stackTag)
        }

        if (!tag.isEmpty) {
            compound.put("WeaponState", tag)
        }

        compound.putFloat("TurretHealth", turretHealth)
        compound.putFloat("LeftWheelHealth", leftWheelHealth)
        compound.putFloat("RightWheelHealth", rightWheelHealth)
        compound.putFloat("MainEngineHealth", mainEngineHealth)
        compound.putFloat("SubEngineHealth", subEngineHealth)

        compound.putBoolean("TurretDamaged", turretDamaged)
        compound.putBoolean("LeftWheelDamaged", leftWheelDamaged)
        compound.putBoolean("RightWheelDamaged", rightWheelDamaged)
        compound.putBoolean("MainEngineDamaged", mainEngineDamaged)
        compound.putBoolean("SubEngineDamaged", subEngineDamaged)

        vehicleModuleStateService.writeAdditionalSaveData(compound)
        vehicleActionController.writeAdditionalSaveData(compound)
        if (initialPassengerReloadProgressApplied || initialPassengerReloadProgressPercent() > 0) {
            compound.putBoolean("InitialPassengerReloadProgressApplied", initialPassengerReloadProgressApplied)
        }

        compound.putFloat("Power", power)
        compound.putBoolean("DecoyReady", decoyReady)
        aircraftCountermeasures.save(compound)
        compound.putFloat("GearRot", synchedGearRot)
        compound.putBoolean("GearUp", gearUp)
        compound.putFloat("PropellerRot", synchedPropellerRot)
        compound.putFloat("ChargeProgress", chargeProgress)

        compound.putFloat("ServerYaw", serverYaw)
        compound.putFloat("ServerPitch", serverPitch)

        if (this.maxPassengers > 0) {
            compound.putIntArray("SelectedWeapon", selectedWeapon)
            compound.putIntArray("SecondaryWeapon", secondaryWeapon)
        }

        inventoryEnergyService.writeEnergy(compound)

        compound.putBoolean("IsWreck", isWreck)
        compound.putLong("AircraftWreckStart", aircraftWreckStart)
        compound.putFloat("AircraftWreckMotionX", aircraftWreckMotionX)
        compound.putFloat("AircraftWreckMotionY", aircraftWreckMotionY)
        compound.putFloat("AircraftWreckMotionZ", aircraftWreckMotionZ)
        compound.putInt("AircraftWreckWings", aircraftWreckWings)
        compound.putLong("AircraftWreckImpactTime", aircraftWreckImpactTime)
        compound.putInt("AircraftWreckBounces", aircraftWreckBounces)
        compound.putBoolean("SympatheticDetonated", sympatheticDetonated)
        compound.putBoolean("TurretBurned", turretBurned)
        compound.putInt("TurretBurnTimer", turretBurnTimer)

        this.resizeItems()
        compound.put("Inventory", this.inventory.serializeNBT())
    }

    /** Delivers one provider-owned vehicle item before the entity is removed. */
    private fun recoverVehicleItem(player: Player): InteractionResult? {
        val item = VehicleItemLifecycleProviders.createFromEntity(this) ?: return null
        if (level().isClientSide) return InteractionResult.sidedSuccess(true)
        if (item.isEmpty || item.count != 1) return InteractionResult.PASS

        val delivery = item.copy()
        val inserted = player.addItem(delivery)
        val delivered = if (inserted && delivery.isEmpty) {
            true
        } else if (!delivery.isEmpty) {
            player.drop(delivery, false) != null
        } else {
            inserted
        }
        if (!delivered) return InteractionResult.PASS

        this.remove(RemovalReason.DISCARDED)
        this.discard()
        return InteractionResult.SUCCESS
    }

    override fun interact(player: Player, hand: InteractionHand): InteractionResult {
        if (player.vehicle === this) return InteractionResult.PASS

        val stack = player.mainHandItem
        if (player.isShiftKeyDown && stack.`is`(ModItems.DOG_TAG.get())) {
            this.dogTagIcon = DogTagItem.getColors(stack).map { it.toList() }.toList()
            return InteractionResult.SUCCESS
        }

        if (stack.item is NameTagItem && stack.hasCustomHoverName()) {
            this.customName = stack.getHoverName()
            stack.shrink(1)
            return InteractionResult.sidedSuccess(this.level().isClientSide())
        }

        if (this.hasMenu() && player.isShiftKeyDown && !stack.`is`(ModTags.Items.TOOLS_CROWBAR)) {
            this.openMenu(player)
            return InteractionResult.sidedSuccess(player.level().isClientSide)
        }

        if (stack.`is`(ModItems.VEHICLE_DAMAGE_ANALYZER.get())) {
            if (!level().isClientSide) {
                if (this.damageDebugResultReceiver != null) {
                    this.damageDebugResultReceiver = null
                    player.displayClientMessage(
                        Component.translatable(
                            "des.superbwarfare.vehicle_damage_analyzer.unbind",
                            this.displayName
                        ), true
                    )
                } else {
                    this.damageDebugResultReceiver = player
                    player.displayClientMessage(
                        Component.translatable(
                            "des.superbwarfare.vehicle_damage_analyzer.bind",
                            this.displayName
                        ), true
                    )
                }
            }
            return InteractionResult.SUCCESS
        }

        if (player.isShiftKeyDown && stack.`is`(ModTags.Items.TOOLS_CROWBAR) && this.getPassengers().isEmpty()) {
            if (isWreck) {
                return InteractionResult.PASS
            } else {
                recoverVehicleItem(player)?.let { return it }
                for (item in this.getRetrieveItems()) {
                    ItemHandlerHelper.giveItemToPlayer(player, item)
                }
                this.remove(RemovalReason.DISCARDED)
                this.discard()
                return InteractionResult.SUCCESS
            }
        } else if (!player.isShiftKeyDown && this.maxPassengers > 0) {
            if (VehicleConfig.SAME_TEAM_ENTER_VEHICLE.get()) {
                for (passenger in this.getPassengers()) {
                    if (passenger.team != null && (TDMSavedData.enabledTDM(passenger) || passenger.team !== player.team)) {
                        return InteractionResult.PASS
                    }
                }

                if (this.lastDriver != null
                    && !SeekTool.IN_SAME_TEAM.test(player, this.lastDriver)
                    && this.lastDriver?.team != null
                ) {
                    return InteractionResult.PASS
                }
            }

            if (isWreck) {
                return InteractionResult.PASS
            }

            if (this.getFirstPassenger() == null) {
                if (player is FakePlayer) return InteractionResult.PASS
                VehicleVecUtils.setDriverAngle(this, player)
                player.isSprinting = false
                if (player.level() is ServerLevel) {
                    return if (player.startRiding(this)) InteractionResult.CONSUME else InteractionResult.PASS
                }
                return InteractionResult.SUCCESS
            } else if (this.getFirstPassenger() !is Player) {
                if (player is FakePlayer) return InteractionResult.PASS
                this.getFirstPassenger()!!.stopRiding()
                VehicleVecUtils.setDriverAngle(this, player)
                player.isSprinting = false
                if (player.level() is ServerLevel) {
                    return if (player.startRiding(this)) InteractionResult.CONSUME else InteractionResult.PASS
                }
                return InteractionResult.SUCCESS
            }
            if (this.canAddPassenger(player)) {
                if (player is FakePlayer) return InteractionResult.PASS
                player.isSprinting = false
                if (player.level() is ServerLevel) {
                    return if (player.startRiding(this)) InteractionResult.CONSUME else InteractionResult.PASS
                }
                return InteractionResult.SUCCESS
            }
        }
        return InteractionResult.PASS
    }

    val lastDriver: Entity?
        get() = EntityFindUtil.findEntity(level(), lastDriverUUID)

    /**
     * Canonical preflight for both legacy and already-resolved vehicle damage.
     * Addons must not bypass SBW immunity, self-fire, or friendly-fire policy.
     */
    fun acceptsDamageSource(source: DamageSource): Boolean =
        vehicleDamageLifecycleService.acceptsSource(source)

    override fun hurt(source: DamageSource, amount: Float): Boolean =
        vehicleDamageLifecycleService.hurt(source, amount)

    /**
     * Commits damage whose armor and penetration math has already been resolved by the caller.
     * This path is server authoritative and intentionally skips [DamageModifier].
     */
    fun applyResolvedDamage(request: ResolvedVehicleDamageRequest): ResolvedVehicleDamageResult =
        vehicleDamageLifecycleService.applyResolved(request)

    /**
     * 控制载具伤害免疫
     *
     * @return DamageModifier
     */
    open fun getDamageModifier(): DamageModifier = data().damageModifier()

    /** Computes the same direct-damage basis used by [hurt] without committing damage. */
    fun computeVehicleDamageAfterModifiers(source: DamageSource, amount: Float): Float =
        vehicleDamageLifecycleService.computeAfterModifiers(source, amount)

    fun getSourceAngle(source: DamageSource, multiplier: Float): Float {
        return VehicleVecUtils.getDamageSourceAngle(this, source, multiplier)
    }

    fun heal(pHealAmount: Float) {
        if (this.level() is ServerLevel) {
            if (health > 0) {
                this.health += pHealAmount
            }
        }
    }

    fun onHurt(pHealAmount: Float, attacker: Entity?, send: Boolean) {
        if (this.level() is ServerLevel) {
            val holder = Holder.direct(ModSounds.INDICATION_VEHICLE.get())

            if (pHealAmount > 0 && send) {
                repairCoolDown = maxRepairCoolDown()
                val passengers = this.getPassengers()
                for (entity in passengers) {
                    if (entity is ServerPlayer) {
                        entity.connection.send(
                            ClientboundSoundPacket(
                                holder,
                                SoundSource.PLAYERS,
                                entity.x,
                                entity.eyeY,
                                entity.z,
                                0.25f + (4.75f * pHealAmount / this.getMaxHealth()),
                                random.nextFloat() * 0.1f + 0.6f,
                                entity.level().random.nextLong()
                            )
                        )
                    }
                }
            }

            this.health -= Math.min(pHealAmount, getMaxHealth() + 1)
        }
    }

    /**
     * Hit feedback for whoever caused committed hull damage: the regular hitmarker (and the vehicle hit sound) for a
     * hit, the red kill marker when this damage destroyed the vehicle. The responsible players are resolved through
     * projectile owners and firing vehicles (every player crewing the vehicle that fired), so shells, missiles, bombs,
     * blasts and armor-resolved hits all report, not only damage whose source entity is the player itself.
     */
    fun sendHitFeedback(source: DamageSource, amount: Float, killed: Boolean) {
        if (this.level() !is ServerLevel || this is DroneEntity || !(amount > 0f)) return
        val players = LinkedHashSet<ServerPlayer>()
        fun collect(entity: Entity?, depth: Int) {
            if (entity == null || depth > 4) return
            when (entity) {
                is ServerPlayer -> players.add(entity)
                is VehicleEntity -> entity.passengers.forEach { collect(it, depth + 1) }
                is Projectile -> collect(entity.owner, depth + 1)
            }
        }
        collect(source.entity, 0)
        if (players.isEmpty()) collect(source.directEntity, 0)
        // Diagnostic launches log the feedback a player receives (the hit-marker checks read it); hits with nobody to
        // tell (AI crews firing at each other) are not logged: a firefight made that thousands of lines a second.
        if (players.isNotEmpty() && HIT_FEEDBACK_LOG) {
            Mod.LOGGER.info("Vehicle hit feedback: {} took {} ({}) from {} -> {}", this.type.descriptionId, amount,
                if (killed) "kill" else "hit", source.msgId, players.map { it.gameProfile.name })
        }
        val holder = Holder.direct(ModSounds.INDICATION_VEHICLE.get())
        for (player in players) {
            if (player.vehicle === this) continue // own vehicle: crashes, self-inflicted blasts
            player.connection.send(
                ClientboundSoundPacket(
                    holder, SoundSource.PLAYERS, player.x, player.eyeY, player.z,
                    0.25f + (2.75f * amount / this.getMaxHealth()).coerceAtMost(2.75f),
                    random.nextFloat() * 0.1f + 0.9f, player.level().random.nextLong()
                )
            )
            player.sendPacket(ClientIndicatorMessage(if (killed) 2 else 0, if (killed) 8 else 5))
        }
    }

    var health: Float
        get() = this.entityData.get(HEALTH)
        set(value) {
            this.entityData.set(HEALTH, value.coerceIn(-this.getMaxHealth() - 10, this.getMaxHealth()))
        }

    fun getMaxHealth() = computed().maxHealth

    open fun getTurretMaxHealth() = 50f
    open fun getWheelMaxHealth() = 50f
    open fun getEngineMaxHealth() = 50f

    override fun lavaHurt() {
        if (tickCount % 10 == 0) {
            this.hurt(this.damageSources().lava(), 4.0f)
        }
    }

    override fun makeStuckInBlock(pState: BlockState, pMotionMultiplier: Vec3) {
        //留空
    }

    @ParametersAreNonnullByDefault
    override fun playStepSound(pPos: BlockPos, pState: BlockState) {
        this.playSound(
            ModSounds.WHEEL_VEHICLE_STEP.get(),
            (deltaMovement.length() * 0.1).toFloat(),
            random.nextFloat() * 0.15f + 1.05f
        )
    }

    override fun canBeCollidedWith(): Boolean {
        if (usesAircraftTerrainContact()) return false
        return this.enableAABB()
    }

    override fun isPickable(): Boolean {
        return !this.isRemoved
    }

    override fun skipAttackInteraction(attacker: Entity): Boolean {
        return hasPassenger(attacker) || super.skipAttackInteraction(attacker)
    }

    override fun canAddPassenger(pPassenger: Entity): Boolean {
        return this.getPassengers().size < this.maxPassengers
    }

    val maxPassengers: Int
        get() = computed().seats().size

    /**
     * 呼吸回血冷却时长(单位:tick)，设为小于0的值以禁用呼吸回血
     */
    open fun maxRepairCoolDown(): Int {
        return computed().repairCooldown
    }

    /**
     * 呼吸回血回血量
     */
    fun repairAmount(): Float {
        return computed().repairAmount
    }

    /** Thin Minecraft lifecycle facade; [VehicleTickPipeline] owns all BVP/SBW phase ordering. */
    @Deprecated("Binary compatibility trampoline; use beforeVehicleTick()/afterVehicleTick() hooks")
    open override fun baseTick() {
        beforeVehicleTick()
        val computed = vehicleTickPipeline.beforeVanillaLifecycle()
        super.baseTick()
        vehicleTickPipeline.afterVanillaLifecycle(computed)
        afterVehicleTick()
    }

    /** Ordered extension seam for state snapshots that historically preceded `super.baseTick()`. */
    protected open fun beforeVehicleTick() = Unit

    /** Ordered extension seam for entity-specific behavior that historically followed the root tick. */
    protected open fun afterVehicleTick() = Unit

    internal fun tickPipelineBeforeVanillaLifecycle(): DefaultVehicleData {
        if (prevMotion == null) {
            prevMotion = this.deltaMovement
        }

        this.prevPitchAngle = this.pitchAngle
        this.prevRollAngle = this.rollAngle


        val computed = computed()
        if (this.level().isClientSide) {
            tickClientSounds(computed)
        } else {
            vehicleWeaponRuntime.normalizePersistedWeaponSlots()
            vehicleWeaponRuntime.tickWeapons()
        }

        this.wasEngineRunning = this.engineRunning()
        this.wasHornWorking = this.hornWorking()
        this.wasStuka = this.stuka()
        this.wasHeliCrash = this.heliCrash()
        this.wasVehicleSkip = this.vehicleSkip()

        this.prevRoll = this.roll

        turretYRotO = this.turretYRot
        turretXRotO = this.turretXRot

        gunYRotO = this.gunYRot
        gunXRotO = this.gunXRot

        leftWheelRotO = this.leftWheelRot
        rightWheelRotO = this.rightWheelRot

        leftTrackO = this.leftTrack
        rightTrackO = this.rightTrack

        rudderRotO = this.rudderRot
        propellerRotO = this.propellerRot
        recoilShakeO = this.recoilShake

        if (jumpCoolDown > 0 && onGround()) {
            jumpCoolDown--
        }

        lastTickSpeed =
            Vec3(this.deltaMovement.x, this.deltaMovement.y + 0.06, this.deltaMovement.z).length()
        lastTickVerticalSpeed = this.deltaMovement.y + 0.06
        if (collisionCoolDown > 0) {
            collisionCoolDown--
        }

        laserScaleO = laserScale

        flap1LRotO = this.flap1LRot
        flap1RRotO = this.flap1RRot
        flap1L2RotO = this.flap1L2Rot
        flap1R2RotO = this.flap1R2Rot
        flap2LRotO = this.flap2LRot
        flap2RRotO = this.flap2RRot
        flap3RotO = this.flap3Rot
        gearRotO = this.gearRot
        deltaMovementO = deltaMovement
        positionO = position()
        absoluteSpeedO = absoluteSpeed

        return computed
    }

    internal fun tickPipelineLifecycleAfterVanilla(computed: DefaultVehicleData) {
        if (laserScale > 0) {
            laserScale = Math.max(laserScale - 0.1f, 0f)
            laserScale *= 0.9f
        }

        if (laserScale == 0f) {
            laserLength = 0f
        }

        if (repairCoolDown > 0) {
            repairCoolDown--
        }

        if (this.health >= this.getMaxHealth()) {
            repairCoolDown = maxRepairCoolDown()
        }

        if (isFixedWingFlightVehicle()) {
            yRot = VehicleFlightAttitude.wrap(yRot)
            xRot = VehicleFlightAttitude.wrap(xRot)
            setZRot(VehicleFlightAttitude.wrap(roll))
            yRotO = VehicleFlightAttitude.alignedPrevious(yRotO, yRot)
            xRotO = VehicleFlightAttitude.alignedPrevious(xRotO, xRot)
            prevRoll = VehicleFlightAttitude.alignedPrevious(prevRoll, roll)
        } else {
            val delta = Math.abs(yRot - yRotO)
            while (yRot > 180f) {
                yRot -= 360f
                yRotO = yRot - delta
            }
            while (yRot <= -180f) {
                yRot += 360f
                yRotO = delta + yRot
            }

            val deltaX = Math.abs(xRot - xRotO)
            while (xRot > 180f) {
                xRot -= 360f
                xRotO = xRot - deltaX
            }
            while (xRot <= -180f) {
                xRot += 360f
                xRotO = deltaX + xRot
            }

            val deltaZ = Math.abs(this.roll - prevRoll)
            while (this.roll > 180f) {
                setZRot(this.roll - 360f)
                prevRoll = this.roll - deltaZ
            }
            while (this.roll <= -180f) {
                setZRot(this.roll + 360f)
                prevRoll = deltaZ + this.roll
            }
        }

        this.handleClientSync()
        vehicleDestructionLifecycleService.tickAfterVanilla()

        if (level() is ServerLevel) {
            vehicleActionController.tickServer()
        }
    }

    internal fun tickPipelineTravel() {
        this.travel()
        this.applyUnoccupiedParkingBrake()
    }

    internal fun tickPipelineControlBeforeMovement(computed: DefaultVehicleData) {
        if (this.health <= computed.selfHurtPercent * this.getMaxHealth()) {
            // 血量过低时自动扣血
            this.onHurt(computed.selfHurtAmount, this.lastAttacker, false)
        } else {
            // 呼吸回血
            if (repairCoolDown == 0 && health > 0
                && VehicleRepairPolicies.resolve(this).passiveHullRepairEnabled
            ) {
                this.heal(repairAmount())
            }
        }

        if (this.maxPassengers > 0 && getFirstPassenger() != null) {
            lastDriverUUID = getFirstPassenger()!!.getStringUUID()
        }

        if (getPassengers().isEmpty()) {
            noPassengerTime++
            if (noPassengerTime > 200) {
                lastDriverUUID = "undefined"
            }
        } else {
            noPassengerTime = 0
        }

        mouseMoveSpeedX *= 0.95f
        mouseMoveSpeedY *= 0.95f

        if (level().isClientSide) {
            refreshClientAimPresentationFromSyncedData()
            vehicleActionController.consumeClient(entityData.get(VEHICLE_ACTION_SNAPSHOT))
        }

        if (!isFixedWingFlightVehicle() && hasTurret()) {
            val turretController = getNthEntity(this.turretControllerIndex)
            if (turretController is Player &&
                !vehicleAimController.handles(turretController, VehicleAimChannel.TURRET)
            ) {
                this.adjustTurretAngle()
            } else if (turretController is Mob &&
                !vehicleAimController.handles(turretController, VehicleAimChannel.TURRET)
            ) {
                this.turretAutoAimFromUuid(aiTurretTargetUUID, turretController)
            }

            if (turretController == null) {
                turretYRotLock = 0f
            }
        }

        if (!isFixedWingFlightVehicle() && hasPassengerWeaponStation()) {
            val passengerWeaponStationController = getNthEntity(this.passengerWeaponStationControllerIndex)
            if ((passengerWeaponStationController is Player &&
                        !vehicleAimController.handles(
                            passengerWeaponStationController,
                            VehicleAimChannel.PASSENGER_WEAPON,
                        )) || passengerWeaponStationController == null
            ) {
                this.adjustWeaponControllerAngle()
            } else if (passengerWeaponStationController is Mob &&
                !vehicleAimController.handles(
                    passengerWeaponStationController,
                    VehicleAimChannel.PASSENGER_WEAPON,
                )
            ) {
                this.passengerWeaponAutoAimFormUuid(aiPassengerWeaponTargetUUID, passengerWeaponStationController)
            }
        }

        for (i in data().getDefault().seats().indices) {
            val mob = getNthEntity(i)
            if (mob is Mob && getGunData(mob) != null && mob.level() is ServerLevel) {
                val target = mob.target
                if (target != null) {
                    mob.lookAt(target, 30f, 30f)
                }
                val aligned = target != null && getShootDirectionForHud(mob, 1f).angleTo(
                    getShootPos(mob, 1f).vectorTo(lerpGetEntityBoundingBoxCenter(target, 1f))
                ) < 4
                if (hasScheduledWeapon(mob)) {
                    updateScheduledWeaponTrigger(
                        mob,
                        target != null && aligned && canShoot(mob),
                        target?.uuid,
                        null,
                    )
                } else if (target != null && aligned && canShoot(mob)) {
                    val rpm = Math.ceil(20f / (vehicleWeaponRpm(mob).toFloat() / 60)).toInt()
                    if (tickCount % rpm == 0) {
                        vehicleShoot(mob, target.uuid, null)
                    }
                }
            }
            if (mob is Player && level() is ServerLevel) {
                if (tickCount % 20 == 0) {
                    val gunData: GunData? = getGunData(mob)
                    if (gunData != null) {
                        if (gunData.selectedAmmoConsumer().type == AmmoConsumer.AmmoConsumeType.ENERGY) {
                            if (!canConsume(gunData.get(GunProp.AMMO_COST_PER_SHOOT))) {
                                mob.displayClientMessage(
                                    Component.translatable("tips.superbwarfare.not.enough.energy"),
                                    true
                                )
                            }
                        } else if (getAmmoCount(mob) < gunData.get(GunProp.AMMO_COST_PER_SHOOT)) {
                            val stack = gunData.selectedAmmoConsumer().stack()
                            if (stack != ItemStack.EMPTY && !InventoryTool.hasCreativeAmmoBox(this) && !gunData.reloading()) {
                                mob.displayClientMessage(
                                    Component.translatable("tips.superbwarfare.need.ammo")
                                        .append(
                                            Component.literal("[").append(stack.hoverName).append("]")
                                                .withStyle(ChatFormatting.YELLOW)
                                        ), true
                                )
                            }
                        }
                    }
                }

                val index: Int = getSeatIndex(mob)
                val seat: SeatInfo = computed().seats()[index]
                if (mob.getCapability(ModCapabilities.PLAYER_VARIABLE, null)
                        .orElse(PlayerVariable()).activeThermalImaging && seat.hasThermalImaging
                ) {
                    mob.addEffect(MobEffectInstance(MobEffects.NIGHT_VISION, 5, 0, false, false))
                }

                vehicleRadar(mob)
            }
        }

        val deltaT = abs(this.turretYRot - turretYRotO)
        while (this.turretYRot > 180f) {
            this.turretYRot -= 360f
            turretYRotO = this.turretYRot - deltaT
        }
        while (this.turretYRot <= -180f) {
            this.turretYRot += 360f
            turretYRotO = deltaT + this.turretYRot
        }

        val deltaG = abs(this.gunYRot - gunYRotO)
        while (this.gunYRot > 180f) {
            this.gunYRot -= 360f
            gunYRotO = this.gunYRot - deltaG
        }
        while (this.gunYRot <= -180f) {
            this.gunYRot += 360f
            gunYRotO = deltaG + this.gunYRot
        }

        if (decoyReloadCoolDown > 0) {
            decoyReloadCoolDown--
        }
    }

    // Blast push spin (degrees per tick), applied where this vehicle's movement is simulated.
    private var blastSpinYaw = 0f
    private var blastSpinPitch = 0f
    private var blastSpinRoll = 0f

    /**
     * Pushes and spins this vehicle for a blast. Velocity in blocks/tick, spins in degrees/tick. A vehicle driven
     * by a player moves on that player's client, so the push is sent there; otherwise it is applied here.
     */
    fun applyBlastPush(vx: Double, vy: Double, vz: Double, yawSpin: Float, pitchSpin: Float, rollSpin: Float) {
        val driver = controllingPassenger
        if (!level().isClientSide && driver is net.minecraft.server.level.ServerPlayer) {
            com.atsuishio.superbwarfare.tools.sendPacketTo(driver,
                com.atsuishio.superbwarfare.network.message.receive.VehicleBlastImpulseMessage(id,
                    vx.toFloat(), vy.toFloat(), vz.toFloat(), yawSpin, pitchSpin, rollSpin))
            return
        }
        receiveBlastImpulse(vx, vy, vz, yawSpin, pitchSpin, rollSpin)
    }

    fun receiveBlastImpulse(vx: Double, vy: Double, vz: Double, yawSpin: Float, pitchSpin: Float, rollSpin: Float) {
        if (!(vx.isFinite() && vy.isFinite() && vz.isFinite())) return
        setDeltaMovement(deltaMovement.add(vx, vy, vz))
        blastSpinYaw += yawSpin
        blastSpinPitch += pitchSpin
        blastSpinRoll += rollSpin
        setOnGround(false)
        hasImpulse = true
    }

    private fun tickBlastSpin() {
        if (blastSpinYaw == 0f && blastSpinPitch == 0f && blastSpinRoll == 0f) return
        yRot += blastSpinYaw
        xRot = (xRot + blastSpinPitch).coerceIn(-89f, 89f)
        setZRot(roll + blastSpinRoll)
        // Airborne spin carries on; ground contact scrubs it quickly.
        val decay = if (onGround()) 0.55f else 0.985f
        blastSpinYaw *= decay
        blastSpinPitch *= if (onGround()) 0.3f else 0.97f
        blastSpinRoll *= if (onGround()) 0.3f else 0.97f
        if (kotlin.math.abs(blastSpinYaw) < 0.02f) blastSpinYaw = 0f
        if (kotlin.math.abs(blastSpinPitch) < 0.02f) blastSpinPitch = 0f
        if (kotlin.math.abs(blastSpinRoll) < 0.02f) blastSpinRoll = 0f
    }

    internal fun tickPipelineMovement() {
        // Authoritative flight clients present server snapshots and vanilla position interpolation.
        if (level().isClientSide && flightStrategyOwnsAttitudeThisTick) return
        tickBlastSpin()
        this.supportEntities()
        collisionResponse.tick()
        this.crushEntities()
        if (!vehicleFlightController.motionIncludesGravityThisTick) {
            this.setDeltaMovement(this.deltaMovement.add(0.0, -this.computed().gravity, 0.0))
        }
        this.move(MoverType.SELF, this.deltaMovement)
        collisionResponse.afterMove()
    }

    internal fun tickPipelinePostMovement(computed: DefaultVehicleData) {
        if (tickCount % 4 == 0) {
            this.clearArrow()
            this.moveOnDragonTeeth()
            this.collideBlocks()
        }

        inventoryEnergyService.chargeFromInventory()

        if (this.level() is ServerLevel) {
            updateBackupAmmoCount()
        }

        hornVolume *= 0.5f

        aircraftCountermeasures.tick()
        if (hasDecoy() && com.atsuishio.superbwarfare.api.aircraft.AircraftCountermeasures.definition(this) == null) {
            if (this.vehicleType == VehicleType.AIRPLANE || this.vehicleType == VehicleType.HELICOPTER) {
                releaseDecoy()
            } else {
                releaseSmokeDecoy(getTurretVector(1f))
            }
        }

        val terrainCompat = this.computed().terrainCompat
        if (!isFixedWingFlightVehicle() && terrainCompat.isNotEmpty()) {
            if (!((vehicleType == VehicleType.AIRPLANE || vehicleType == VehicleType.HELICOPTER) && isWreck)) {
                this.terrainCompact(terrainCompat)
            }
        }

        if (this.leftTrack < 0) {
            this.leftTrackO = this.getTrackAnimationLength().toFloat()
            this.leftTrack = this.getTrackAnimationLength().toFloat()
        }

        if (this.leftTrack > this.getTrackAnimationLength()) {
            this.leftTrackO = 0f
            this.leftTrack = 0f
        }

        if (this.rightTrack < 0) {
            this.rightTrackO = this.getTrackAnimationLength().toFloat()
            this.rightTrack = this.getTrackAnimationLength().toFloat()
        }

        if (this.rightTrack > this.getTrackAnimationLength()) {
            this.rightTrackO = 0f
            this.rightTrack = 0f
        }

        if (turretBurnTimer > 0) {
            turretBurnTimer--
        }

        if (level().isClientSide) {
            absoluteSpeedLerp = Mth.lerp(0.2, absoluteSpeedLerp, positionO.vectorTo(position()).length())
            absoluteSpeed = absoluteSpeedLerp
        }
    }

    internal fun tickPipelinePoseDamageAndChunk(computed: DefaultVehicleData) {
        val previousMotion = prevMotion
        if (previousMotion != null) {
            val motion = this.deltaMovement
            val poseProvider = resolveVehiclePoseProvider()
            if (flightStrategyOwnsAttitudeThisTick) {
                resetLegacyInertiaState()
            } else if (poseProvider == null ||
                (poseProvider.usesLegacyBasePose() && !level().isClientSide)
            ) {
                var acceleration = motion.subtract(previousMotion)

                if (acceleration.length() > 0.02) {
                    acceleration = acceleration.normalize().scale(0.02)
                }

                val yaw = this.yRot
                val sinYaw = Mth.sin(yaw * Mth.DEG_TO_RAD)
                val cosYaw = Mth.cos(yaw * Mth.DEG_TO_RAD)

                val forward = Vec3(-sinYaw.toDouble(), 0.0, cosYaw.toDouble())
                val right = Vec3(-cosYaw.toDouble(), 0.0, -sinYaw.toDouble())

                val accelForward: Double = acceleration.multiply(1.0, 0.0, 1.0).dot(forward)
                val accelRight: Double = acceleration.multiply(1.0, 0.0, 1.0).dot(right)

                val targetPitch = (10 * accelForward).toFloat()
                val omegaP = 2.0f * Math.PI.toFloat() * 2f
                val zetaP = 0.6f
                val angularAccelP: Float = omegaP * omegaP * (targetPitch - pitchAngle) -
                        2 * zetaP * omegaP * pitchVelocity
                pitchVelocity += angularAccelP * 0.05f // dt = 0.05s
                pitchAngle += pitchVelocity * 0.05f

                val targetRoll = (15 * accelRight).toFloat()
                val omegaR = 2.0f * Math.PI.toFloat() * 2f
                val zetaR = 0.6f
                val angularAccelR: Float = omegaR * omegaR * (targetRoll - rollAngle) -
                        2 * zetaR * omegaR * rollVelocity
                rollVelocity += angularAccelR * 0.05f
                rollAngle += rollVelocity * 0.05f

                xRot -= pitchAngle * computed().inertiaRotateRate
                roll -= rollAngle * computed().inertiaRotateRate
            } else if (poseProvider.usesLegacyBasePose()) {
                // The server synchronizes the composed base pose. Clients must not run a second
                // inertia solver or wipe network attitude while consuming that snapshot.
                resetLegacyInertiaState()
            } else {
                resetLegacyInertiaPose()
            }
            prevMotion = motion
        }

        // This phase follows movement and precedes OBB/aim resolution. A ground provider
        // samples exactly this completed pose; flight strategies retain attitude ownership.
        updateVehiclePoseLifecycle()

        if (!level().isClientSide && vehicleType == VehicleType.HELICOPTER &&
            tickCount % 20 == 0 && EliteDiagnostics.isEnabled(level())) {
            EliteDiagnostics.record(this, "helicopter_attitude", "SAMPLE",
                "yaw", yRot, "pitch", xRot, "roll", roll,
                "yaw_step", VehicleFlightAttitude.wrap(yRot - yRotO),
                "pitch_step", VehicleFlightAttitude.wrap(xRot - xRotO),
                "roll_step", VehicleFlightAttitude.wrap(roll - prevRoll),
                "mouse_x", mouseMoveSpeedX, "mouse_y", mouseMoveSpeedY,
                "rotor_coupled", usesRotorCoupledHelicopterControls(),
                "grounded", onGround(), "pilot", firstPassenger?.uuid)
        }

        lowHealthWarning()
        if (!this.enableAABB()) {
            this.handlePartDamaged(this)
            // 处理部件血量
            this.handlePartHealth()
            this.updateOBB()
            // Refresh the physical envelope after rotation, including while stationary.
            this.boundingBox = makeBoundingBox()
        }

        if (level() is ServerLevel && VehicleConfig.VEHICLE_CHUNK_LOADING.get() && computed().keepChunkLoaded) {
            if (com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleSimulationPolicy.shouldRenewSimulationTicket(this)) {
                this.keepChunkLoaded(this.position())
                this.keepChunkLoaded(position().add(deltaMovement.normalize().scale(16.0)))
            } else {
                NetworkTelemetry.recordSystemWork("chunk_ticket.parked_aircraft_idle")
            }
        }
    }

    internal fun tickPipelineAimAndWeapons() {
        // Resolve scheduled shots only after the authoritative chassis pose and aim servo have
        // reached their final state for this tick. Muzzle attachments, projectile spawn, and the
        // accepted fired-visual record must all sample that same completed pose.
        if (level() is ServerLevel) {
            vehicleAimController.tickServer()
            weaponScheduler.tick()
        }
    }

    internal fun tickPipelineRecoil() {
        if (this.cannonRecoilTime > 0) {
            cannonRecoilTime -= 1
        }
        this.recoilShake = Mth.abs(cannonRecoilForce) * 0.0000007 * cannonRecoilTime.toDouble()
            .pow(4.0) * sin(0.2 * Math.PI * (cannonRecoilTime - 2.5))
        cannonRecoilForce *= 0.93f
    }

    private fun tickClientSounds(computed: DefaultVehicleData) {
        // an authored vehicle audio profile voices engine and tracks itself (start/idle/drive/stop, by speed)
        val authoredAudio = authoredEngineAudio.test(this)
        val engineRunning = this.engineRunning() && !authoredAudio
        if (engineRunning) {
            when (computed.engineSoundMode) {
                VehicleLoopSoundMode.NATIVE -> playEngineSound.accept(this)
                VehicleLoopSoundMode.CUSTOM, VehicleLoopSoundMode.OFF -> Unit
            }
            if (computed.engineType == EngineType.TRACK && computed.trackSoundMode == VehicleLoopSoundMode.NATIVE) {
                playTrackSound.accept(this)
            }
        }
        if (this.engineRunning() && this.isInWater) playSwimSound.accept(this)

        if (engineRunning) {
            if (computed.engineSoundMode == VehicleLoopSoundMode.CUSTOM) {
                tickCustomLoopSound.accept(this, VehicleLoopSoundChannel.ENGINE)
            }
            if (computed.engineType == EngineType.TRACK
                && computed.trackSoundMode == VehicleLoopSoundMode.CUSTOM
            ) {
                tickCustomLoopSound.accept(this, VehicleLoopSoundChannel.TRACK)
            }
        }

        if (!this.wasHornWorking && this.hornWorking()) playHornSound.accept(this)
        val aircraft = engineInfo as? Aircraft
        if (!this.wasStuka && this.stuka() && aircraft?.hasStukaSound == true) {
            playStukaSound.accept(this)
        }
        if (!this.wasHeliCrash && this.heliCrash()) playHeliCrashSound.accept(this)
        if (!this.wasVehicleSkip && this.vehicleSkip()) playVehicleSkipSound.accept(this)
        // one evaluation a tick (it resolves the selected weapon and its sound profile)
        val firing = this.isFiring
        if (playFireSound != null && !this.wasFiring && firing) playFireSound!!.accept(this)
        this.wasFiring = firing
    }

    fun keepChunkLoaded(position: Vec3) {
        val chunkPos = ChunkPos(BlockPos.containing(position))
        NetworkTelemetry.recordSystemWork("chunk_ticket.vehicle_refresh")
        (level() as ServerLevel).chunkSource.addRegionTicket(TicketType.POST_TELEPORT, chunkPos, 3, this.id)
    }

    // TODO 添加更多的雷达机制
    fun vehicleRadar(player: Player) {
        if (!MiscConfig.SYNC_ENTITY_OVER_RANGE.get()) return
        if ((level().server?.tickCount ?: return) % MiscConfig.SYNC_ENTITY_INTERVAL.get() != 0) return
        val data = this.getGunData(player) ?: return
        val seekWeaponInfo = data.get(GunProp.SEEK_WEAPON_INFO) ?: return

        val level = this.level()
        if (level is ServerLevel) {
            // 搜索范围
            val seekRange = seekWeaponInfo.seekRange
            // 最小目标高度
            val minTargetHeight = seekWeaponInfo.minTargetHeight
            // 最大目标高度
            val maxTargetHeight = seekWeaponInfo.maxTargetHeight

            val scanStarted = if (NetworkTelemetry.isEnabled()) System.nanoTime() else 0L
            var candidates = 0
            var losChecks = 0
            val hostileList = level.allEntities
                .asSequence()
                .mapNotNull {
                    candidates++
                    val preLosMatch = (it is VehicleEntity || VehicleConfig.inScanList(it.type))
                            && SeekTool.NOT_IN_SMOKE.test(it)
                            && it.distanceToSqr(this) <= seekRange * seekRange
                            && SeekTool.IN_HEIGHT_RANGE.test(it, minTargetHeight, maxTargetHeight)
                            && !SeekTool.IS_FRIENDLY.test(player, it)
                    if (!preLosMatch) return@mapNotNull null
                    losChecks++
                    if (!VectorTool.checkNoClip(eyePosition, it.eyePosition, level())) return@mapNotNull null
                    EntitySyncMessage.SyncedEntity(
                        it.id,
                        ForgeRegistries.ENTITY_TYPES.getKey(it.type)!!,
                        it.position(),
                        it.deltaMovement,
                        it.serializeNBT()
                    )
                }.toList()
            sendPacketTo(player, EntitySyncMessage(level.dimension().location(), hostileList, false))
            NetworkTelemetry.recordSystemWork(
                "contact.vehicle_radar",
                items = candidates,
                recipients = 1,
                serializedTags = hostileList.size,
                losChecks = losChecks,
                workNanos = if (scanStarted == 0L) 0L else System.nanoTime() - scanStarted,
            )
        }
    }

    override fun canFreeze() = false

    open fun updateOBB() {
        // Boxes on the same part share one transform chain; it is only read below (transform / getNormalizedRotation
        // write into their own destinations), so each distinct part is resolved once per update.
        val transforms = HashMap<String?, Matrix4d>(8)
        this.obb.forEach { obbInfo ->
            val transform = transforms.getOrPut(obbInfo.transform) { this.getTransformFromString(obbInfo.transform) }
            val obb = obbInfo.getOBB()
            val worldPos = this.transformPosition(transform, obbInfo.position.x, obbInfo.position.y, obbInfo.position.z)

            if (hasTurret() && sympatheticDetonated && (obbInfo.transform.equals("Turret") || obbInfo.transform.equals("Barrel"))) {
                obb.setExtents(Vector3d(0.0, 0.0, 0.0))
            }

            obb.center.set(Vec3(worldPos.x, worldPos.y, worldPos.z).toVector3d())
            // Opt-in pose owners resolve centers and rotations from the same authoritative frame.
            // Legacy vehicles retain the original independent Rotation selector exactly.
            if (resolveVehiclePoseProvider() != null) {
                obb.updateRotation(transform.getNormalizedRotation(Quaterniond()))
            } else {
                // Fixed-wing centers and bases sample the same current physical attitude.
                obb.updateRotation(if (isFixedWingFlightVehicle()) {
                    this.getRotationFromString(obbInfo.rotation, 1f)
                } else {
                    this.getRotationFromString(obbInfo.rotation)
                })
            }
        }
    }

    /** Clears legacy inertia integration without overwriting a selected strategy's body attitude. */
    private fun resetLegacyInertiaState() {
        pitchAngle = 0f
        prevPitchAngle = 0f
        pitchVelocity = 0f
        rollAngle = 0f
        prevRollAngle = 0f
        rollVelocity = 0f
    }

    /** A non-composing pose provider owns the complete chassis attitude. */
    private fun resetLegacyInertiaPose() {
        resetLegacyInertiaState()
        xRot = 0f
        xRotO = 0f
        roll = 0f
        prevRoll = 0f
    }

    val shootSoundInstance: SoundEvent?
        get() {
            // TODO why 0?
            val gunData = getGunData(0)
            if (gunData != null) {
                val instance = gunData.get(GunProp.SOUND_INFO).fireSoundInstances
                if (instance != null) return instance
            } else {
                return getShootSoundInstance("Main")
            }
            return SoundEvents.EMPTY
        }

    fun getShootSoundInstance(weaponName: String): SoundEvent {
        val gunData = getGunData(weaponName) ?: return SoundEvents.EMPTY

        return gunData.get(GunProp.SOUND_INFO).fireSoundInstances ?: SoundEvents.EMPTY
    }

    val isFiring: Boolean
        get() {
            val gunData = getGunData(0) ?: return false
            // the timer first: resolving the sound profile runs the property modifiers
            if (gunData.shootTimer.get() <= 0) return false
            return gunData.get(GunProp.SOUND_INFO).fireSoundInstances != null
        }

    fun shootingVolume(): Float {
        val gunData = getGunData(0)
        return if (gunData != null) {
            gunData.shootTimer.get() * 0.25f
        } else {
            0f
        }
    }

    fun shootingPitch(): Float {
        val gunData = getGunData(0)
        return if (gunData != null) {
            (0.98f + gunData.shootTimer.get() * 0.01f - (if (gunData.heat.get() > 80) (gunData.heat.get() - 80) * 0.01 else 0.0)).toFloat()
        } else {
            1f
        }
    }

    protected fun updateBackupAmmoCount() {
        // Every channel needs a current presentation count, including secondary weapons. The creative check
        // (passengers' and the vehicle's inventories) is the same for every weapon: made once, not per weapon.
        val supplier = this.ammoSupplier
        val creative = supplier is Player && supplier.isCreative ||
            com.atsuishio.superbwarfare.tools.InventoryTool.hasCreativeAmmoBox(supplier)
        for (name in gunDataMap.keys.toList()) {
            val currentData = getGunData(name) ?: continue
            val count = if (creative) Int.MAX_VALUE else currentData.countBackupAmmoWithoutCreative(supplier)
            if (currentData.backupAmmoCount.get() != count) {
                modifyGunData(name) { it.backupAmmoCount.set(count) }
            }
        }
    }

    open val ammoSupplier: Entity
        /**
         * 获取开火用AmmoSupplier实体
         */
        get() = this

    /** Resolves an addon definition first, then the five stable native legacy adapters. */
    fun getVehicleModuleDefinition(id: ResourceLocation): VehicleModuleDefinition? =
        vehicleModuleStateService.getDefinition(id)

    /** Common-side immutable view; generic states are parsed from the synchronized server snapshot on clients. */
    fun getVehicleModuleState(id: ResourceLocation): VehicleModuleState? =
        vehicleModuleStateService.getState(id)

    /** Includes all native legacy modules and every generic module that has authoritative stored state. */
    fun getVehicleModuleStates(): List<VehicleModuleState> =
        vehicleModuleStateService.getStates()

    fun damageVehicleModule(
        id: ResourceLocation,
        damage: Double,
    ): VehicleModuleState? = vehicleModuleStateService.damage(id, damage)

    fun setVehicleModuleHealth(
        id: ResourceLocation,
        health: Double,
    ): VehicleModuleState? = vehicleModuleStateService.setHealth(id, health)

    fun setVehicleModuleState(
        id: ResourceLocation,
        health: Double,
        destroyed: Boolean,
    ): VehicleModuleState? = vehicleModuleStateService.setState(id, health, destroyed)

    fun handlePartDamaged(obbEntity: OBBEntity) {
        val obbList = obbEntity.getOBBs()
        for (obb in obbList) {
            val pos = obb.center.toVec3()
            when (obb.part) {
                TURRET -> {
                    if (turretDamaged) {
                        this.onTurretDamaged(pos)
                    }
                }

                WHEEL_LEFT -> {
                    if (leftWheelDamaged) {
                        this.onLeftWheelDamaged(pos)
                    }
                }

                WHEEL_RIGHT -> {
                    if (rightWheelDamaged) {
                        this.onRightWheelDamaged(pos)
                    }
                }

                MAIN_ENGINE -> {
                    if (mainEngineDamaged) {
                        this.onEngine1Damaged(pos)
                    }
                }

                SUB_ENGINE -> {
                    if (subEngineDamaged) {
                        this.onEngine2Damaged(pos)
                    }
                }

                else -> {}
            }
        }
    }

    fun handlePartHealth() {
        val policy = VehicleRepairPolicies.resolve(this)
        val criticalHull = health < 0.05 * getMaxHealth()
        when (policy.criticalPartFailureMode) {
            VehicleCriticalPartFailureMode.LEGACY_VEHICLE_TYPE -> {
                if (this.hasTurret()
                    && (vehicleType == VehicleType.AA
                            || vehicleType == VehicleType.APC
                            || vehicleType == VehicleType.TANK)
                    && criticalHull
                ) {
                    turretHealth = 0f
                    mainEngineHealth = 0f
                    subEngineHealth = 0f
                }
                if ((vehicleType == VehicleType.HELICOPTER || vehicleType == VehicleType.AIRPLANE)
                    && criticalHull
                ) {
                    mainEngineHealth = 0f
                    subEngineHealth = 0f
                }
            }

            VehicleCriticalPartFailureMode.TURRET_AND_ENGINES -> if (criticalHull) {
                if (this.hasTurret()) {
                    turretHealth = 0f
                }
                mainEngineHealth = 0f
                subEngineHealth = 0f
            }

            VehicleCriticalPartFailureMode.NONE -> Unit
        }

        val recoverDamaged = policy.damagedRecoveryMode == VehicleDamagedRecoveryMode.HEALTH_THRESHOLD
        val recoveryThreshold = policy.damagedRecoveryThreshold

        if (turretHealth <= 0) {
            turretDamaged = true
        } else if (recoverDamaged && turretHealth > recoveryThreshold * this.getTurretMaxHealth()) {
            turretDamaged = false
        }

        if (leftWheelHealth <= 0) {
            leftWheelDamaged = true
        } else if (recoverDamaged && leftWheelHealth > recoveryThreshold * this.getWheelMaxHealth()) {
            leftWheelDamaged = false
        }

        if (rightWheelHealth <= 0) {
            rightWheelDamaged = true
        } else if (recoverDamaged && rightWheelHealth > recoveryThreshold * this.getWheelMaxHealth()) {
            rightWheelDamaged = false
        }

        if (mainEngineHealth <= 0) {
            mainEngineDamaged = true
        } else if (recoverDamaged && mainEngineHealth > recoveryThreshold * this.getEngineMaxHealth()) {
            mainEngineDamaged = false
        }

        if (subEngineHealth <= 0) {
            subEngineDamaged = true
        } else if (recoverDamaged && subEngineHealth > recoveryThreshold * this.getEngineMaxHealth()) {
            subEngineDamaged = false
        }

        val passiveRepairFraction = policy.passiveModuleRepairFractionPerTick
        if (!isWreck && passiveRepairFraction > 0f) {
            turretHealth = Math.min(
                turretHealth + passiveRepairFraction * this.getTurretMaxHealth(),
                this.getTurretMaxHealth()
            )
            leftWheelHealth = Math.min(
                leftWheelHealth + passiveRepairFraction * this.getWheelMaxHealth(),
                this.getWheelMaxHealth()
            )
            rightWheelHealth = Math.min(
                rightWheelHealth + passiveRepairFraction * this.getWheelMaxHealth(),
                this.getWheelMaxHealth()
            )
            mainEngineHealth = Math.min(
                mainEngineHealth + passiveRepairFraction * this.getEngineMaxHealth(),
                this.getEngineMaxHealth()
            )
            subEngineHealth = Math.min(
                subEngineHealth + passiveRepairFraction * this.getEngineMaxHealth(),
                this.getEngineMaxHealth()
            )
        }
    }

    fun addRandomParticle(
        particleOptions: ParticleOptions,
        pos: Vec3,
        randomPos: Float,
        level: Level,
        speed: Float,
        count: Int
    ) {
        repeat(count) {
            val randomX = 2 * (this.random.nextFloat() - 0.5f)
            val randomY = 2 * (this.random.nextFloat() - 0.5f)
            val randomZ = 2 * (this.random.nextFloat() - 0.5f)
            level.addParticle(
                particleOptions,
                pos.x + randomPos * randomX,
                pos.y + randomPos * randomY,
                pos.z + randomPos * randomZ,
                (randomX * speed).toDouble(),
                (randomY * speed).toDouble(),
                (randomZ * speed).toDouble()
            )
        }
    }

    fun addRandomParticle(
        particleOptions: ParticleOptions,
        pos: Vec3,
        randomPos: Float,
        level: Level,
        count: Int,
        vec3: Vec3
    ) {
        repeat(count) {
            val randomX = 2 * (this.random.nextFloat() - 0.5f)
            val randomY = 2 * (this.random.nextFloat() - 0.5f)
            val randomZ = 2 * (this.random.nextFloat() - 0.5f)
            level.addParticle(
                particleOptions,
                pos.x + randomPos * randomX,
                pos.y + randomPos * randomY,
                pos.z + randomPos * randomZ,
                vec3.x,
                vec3.y,
                vec3.z
            )
        }
    }

    fun defaultPartDamageEffect(pos: Vec3) {
        if (level().isClientSide) {
            addRandomParticle(ModParticleTypes.FIRE_STAR.get(), pos, 0f, level(), 0.25f, 1)
            addRandomParticle(ParticleTypes.LARGE_SMOKE, pos, 0.5f, level(), 0.001f, 1)
        }
    }

    fun onTurretDamaged(pos: Vec3) {
        this.defaultPartDamageEffect(pos)
    }

    fun onLeftWheelDamaged(pos: Vec3) {
        this.defaultPartDamageEffect(pos)
    }

    fun onRightWheelDamaged(pos: Vec3) {
        this.defaultPartDamageEffect(pos)
    }

    open fun onEngine1Damaged(pos: Vec3) {
        this.defaultPartDamageEffect(pos)
    }

    open fun onEngine2Damaged(pos: Vec3) {
        this.defaultPartDamageEffect(pos)
    }

    fun clearArrow() {
        if (tickCount % 5 != 0) return
        this.level().getEntities(
            this,
            this.boundingBox.inflate(0.0, 0.5, 0.0)
        ) { e -> e is AbstractArrow }
            .forEach { obj -> obj.discard() }
    }

    fun lowHealthWarning() {
        // Destroyed vehicles use the bounded near/far wreck fire sequence, not live damage clouds.
        if (isWreck) return
        if (!data().compute().hasLowHealthWarning) return
        if (this.health <= 0.4 * this.getMaxHealth()) {
            addRandomParticle(
                ParticleTypes.LARGE_SMOKE,
                lowHealthSmokeOrigin(0.7f),
                lowHealthSmokeSpread(0.35f),
                level(),
                0.01f,
                1
            )
        }

        if (this.health <= 0.25 * this.getMaxHealth()) {
            playLowHealthParticle()
        }
        if (this.health <= 0.15 * this.getMaxHealth()) {
            playLowHealthParticle()
        }

        if (this.health <= 0.1 * this.getMaxHealth()) {
            val random = 2 * (this.random.nextFloat() - 0.5f)
            if (level().isClientSide) {
                addRandomParticle(
                    ParticleTypes.LARGE_SMOKE,
                    lowHealthSmokeOrigin(0.7f),
                    lowHealthSmokeSpread(0.35f),
                    level(),
                    0.01f,
                    2
                )
                addRandomParticle(
                    ParticleTypes.CAMPFIRE_COSY_SMOKE,
                    lowHealthSmokeOrigin(0.7f),
                    lowHealthSmokeSpread(0.35f),
                    level(),
                    0.01f,
                    2
                )
                addRandomParticle(
                    CustomCloudOption(
                        1f,
                        0.1f,
                        0f,
                        (240 + 40 * random).toInt(),
                        2.5f + 0.5f * random,
                        -0.07f,
                        cooldown = true,
                        light = true
                    ),
                    lowHealthSmokeOrigin(0.85f),
                    lowHealthSmokeSpread(0.35f),
                    level(),
                    0.01f,
                    1
                )
                addRandomParticle(
                    CustomCloudOption(
                        1f,
                        0.35f,
                        0f,
                        (80 + 40 * random).toInt(),
                        1.5f + 0.5f * random,
                        -0.07f,
                        cooldown = false,
                        light = true
                    ),
                    lowHealthSmokeOrigin(0.85f),
                    lowHealthSmokeSpread(0.3f),
                    level(),
                    0.01f,
                    1
                )
            }

            if (computed().destroyInfo.sympatheticDetonation
                && health < 0.05 * getMaxHealth() && this.hasTurret()
                && (vehicleType == VehicleType.AA || vehicleType == VehicleType.APC || vehicleType == VehicleType.TANK)
                && !sympatheticDetonated
                && !turretBurned
            ) {
                turretBurned = true
                turretBurnTimer = 400
            }

            if (turretBurnTimer > 0 && !sympatheticDetonated && health < 0.05 * getMaxHealth()) {
                if (level().isClientSide) {
                    val pos = turretBurnEffectPos()
                    val dir = getUpVec(1f)
                    ParticleTool.spawnDirectionalParticles(
                        (12 + 10 * random).toInt(),
                        0.05 * random.toDouble(),
                        level(),
                        CannonMuzzleFlareOption(1f, 0.97f, 0.97f, 4, 0.5f, 1, 0.3f),
                        dir,
                        pos,
                        4.5 + random
                    )
                    // The burning turret vents a rising flame jet. The fire-stars and lava embers it used to spray arced out
                    // and fell back every tick (a 'fountain') and the embers lay about on the ground afterwards.
                    ParticleTool.spawnDirectionalParticles(
                        (4 + 4 * random).toInt(),
                        0.8 * random.toDouble(),
                        level(),
                        ParticleTypes.FLAME,
                        dir,
                        pos,
                        0.4 + random
                    )
                }

                if (turretBurnTimer == 400) {
                    this.level().playSound(
                        null,
                        onPos,
                        ModSounds.TURRET_BURN_START.get(),
                        SoundSource.BLOCKS,
                        4f,
                        1f + 0.05f * random
                    )
                }
                if (turretBurnTimer % 5 == 0) {
                    this.level().playSound(
                        null,
                        onPos,
                        ModSounds.TURRET_BURN.get(),
                        SoundSource.BLOCKS,
                        1.5f,
                        1f + 0.05f * random
                    )
                }
            }

            if (health > 0.05 * getMaxHealth()) {
                turretBurned = false
                turretBurnTimer = 0
            }

            if (this.tickCount % 15 == 0) {
                this.level().playSound(null, this.onPos, SoundEvents.FIRE_AMBIENT, SoundSource.PLAYERS, 1f, 1f)
            }
        }

    }

    fun turretBurnEffectPos(): Vec3? {
        val pos = turretPos
        val worldPosition = pos?.let {
            transformPosition(
                getVehicleTransform(1f),
                pos.x, pos.y, it.z
            )
        }
        return worldPosition?.let { Vec3(it.x, worldPosition.y, worldPosition.z) }
    }

    fun playLowHealthParticle() {
        if (level().isClientSide) {
            addRandomParticle(
                ParticleTypes.LARGE_SMOKE,
                lowHealthSmokeOrigin(0.7f),
                lowHealthSmokeSpread(0.35f),
                level(),
                0.01f,
                1
            )
            addRandomParticle(
                ParticleTypes.CAMPFIRE_COSY_SMOKE,
                lowHealthSmokeOrigin(0.7f),
                lowHealthSmokeSpread(0.35f),
                level(),
                0.01f,
                1
            )
        }
    }

    /**
     * Where low-health smoke starts. Aircraft: a random point inside the airframe's own hit volumes (owner
     * 2026-09-28: smoke only from points connected to the aircraft, never from the empty air of its bounding box),
     * left behind as it flies. Other vehicles: above the middle of the bounding box.
     */
    private fun lowHealthSmokeOrigin(heightFactor: Float): Vec3 {
        if (vehicleType == VehicleType.AIRPLANE || vehicleType == VehicleType.HELICOPTER) {
            airframePoint()?.let { return it }
        }
        return Vec3(this.x, this.y + heightFactor * bbHeight, this.z)
    }

    private fun lowHealthSmokeSpread(widthFactor: Float): Float =
        if (vehicleType == VehicleType.AIRPLANE || vehicleType == VehicleType.HELICOPTER) 0.15f
        else widthFactor * this.bbWidth

    /** A random point inside one of the aircraft's hit volumes (chosen by volume), or null without volumes. */
    private fun airframePoint(): Vec3? {
        val boxes = getOBBs()
        if (boxes.isEmpty()) return null
        var total = 0.0
        for (box in boxes) total += box.extents.x * box.extents.y * box.extents.z
        var pick = this.random.nextDouble() * total
        var chosen = boxes[0]
        for (box in boxes) {
            pick -= box.extents.x * box.extents.y * box.extents.z
            if (pick <= 0.0) { chosen = box; break }
        }
        val local = org.joml.Vector3d(
            chosen.extents.x * 1.4 * (this.random.nextDouble() - 0.5),
            chosen.extents.y * 1.4 * (this.random.nextDouble() - 0.5),
            chosen.extents.z * 1.4 * (this.random.nextDouble() - 0.5))
        chosen.rotation.transform(local)
        return Vec3(chosen.center.x + local.x, chosen.center.y + local.y, chosen.center.z + local.z)
    }

    fun adjustTurretAngle() {
        VehicleWeaponUtils.adjustTurretAngle(this)
    }

    /** Returns the current primary slot from the deterministic filtered seat order. */
    fun getSelectedWeapon(seatIndex: Int): Int {
        return vehicleWeaponRuntime.selectedWeaponIndex(seatIndex)
    }

    /** Captures the exact launch slot/id; this is metadata only and never changes selection. */
    fun captureVehicleWeaponGuidanceContext(
        controller: LivingEntity?,
        weaponName: String,
        data: GunData,
    ): VehicleWeaponGuidanceContext? {
        val seatIndex = getSeatIndex(controller)
        val weaponIndex = if (seatIndex < 0) -1 else getWeaponIds(seatIndex).indexOf(weaponName)
        if (seatIndex < 0 || weaponIndex < 0 || getGunName(seatIndex, weaponIndex) != weaponName) return null
        val roundId = VehicleWeaponGuidance.atgmRoundId(data)
        return VehicleWeaponGuidanceContext(
            uuid,
            controller?.uuid,
            seatIndex,
            weaponIndex,
            weaponName,
            vehicleType == VehicleType.HELICOPTER && roundId != null && controller != null,
            roundId,
        )
    }

    /**
     * Resolves the launch owner's live Crosshair-A ray; no pilot/gunner handoff is permitted.
     * The accepted camera sample is server-validated and remains keyed by operator/vehicle/seat,
     * so a later weapon-selection change cannot starve an already-launched missile.
     */
    fun resolveLaunchedWeaponGuidanceRay(
        controller: LivingEntity,
        launchContext: VehicleWeaponGuidanceContext,
    ): VehicleHudAimRay? {
        if (launchContext.launcherVehicleUUID != uuid ||
            launchContext.launcherControllerUUID != controller.uuid ||
            controller.vehicle !== this || controller.level() !== level() ||
            getSeatIndex(controller) != launchContext.seatIndex || !controller.isAlive ||
            isRemoved || isWreck ||
            getGunName(launchContext.seatIndex, launchContext.weaponIndex) != launchContext.weaponName
        ) return null
        // Muzzle transforms determine launch placement only. Steering follows the final
        // presented camera, including third-person/zoom offsets, for ground and air vehicles.
        return resolveHelicopterAtgmGuidanceRay(controller, launchContext)
    }

    fun resolveHelicopterAtgmGuidanceRay(
        controller: LivingEntity,
        launchContext: VehicleWeaponGuidanceContext,
    ): VehicleHudAimRay? {
        // Historical public method name; the same authenticated ray now serves every ATGM.
        if (launchContext.launcherVehicleUUID != uuid ||
            launchContext.launcherControllerUUID != controller.uuid ||
            controller.vehicle !== this ||
            controller.level() !== level() ||
            getSeatIndex(controller) != launchContext.seatIndex ||
            !controller.isAlive ||
            isRemoved || isWreck
        ) return null

        // Preserve immutable launch provenance while still requiring the authored launcher to
        // exist.  Current selection is intentionally not checked: guidance may survive a
        // secondary-weapon selection change, but never a lost launcher/controller/seat.
        if (getGunName(launchContext.seatIndex, launchContext.weaponIndex) != launchContext.weaponName) {
            return null
        }

        val serverController = controller as? ServerPlayer ?: return null
        if (serverController.isSpectator) return null
        val accepted = acceptedHelicopterAtgmCameraRays[
            HelicopterAtgmCameraRayKey(controller.uuid, launchContext.seatIndex)
        ] ?: return null
        if (accepted.controllerUuid != controller.uuid ||
            accepted.vehicleId != id || accepted.vehicleUuid != uuid ||
            accepted.seatIndex != launchContext.seatIndex ||
            accepted.dimension != level().dimension().location() ||
            accepted.connection !== serverController.connection.connection ||
            !VehicleHelicopterAtgmCameraRayTransport.isFresh(accepted, level().gameTime)
        ) return null

        return VehicleHudAimRay(accepted.origin, accepted.direction)
    }

    /** Called only by the server-validated ID 64 camera-ray transport. */
    fun acceptHelicopterAtgmCameraRay(state: VehicleHelicopterAtgmCameraRayState) {
        if (level().isClientSide || state.vehicleId != id || state.vehicleUuid != uuid ||
            state.dimension != level().dimension().location()
        ) return
        val key = HelicopterAtgmCameraRayKey(state.controllerUuid, state.seatIndex)
        acceptedHelicopterAtgmCameraRays[key] = state
        while (acceptedHelicopterAtgmCameraRays.size > MAX_HELICOPTER_ATGM_CAMERA_RAY_STREAMS) {
            acceptedHelicopterAtgmCameraRays.entries.iterator().let { iterator ->
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
        }
    }

    /** Exact authored pair metadata; generated VehiclePairs is the canonical source. */
    fun getVehicleWeaponPair(seatIndex: Int): VehicleWeaponPair? {
        if (seatIndex < 0) return null
        val authored = computed().weaponPairs.list.firstOrNull { it.seat == seatIndex }
        if (authored != null) {
            return runCatching {
                VehicleWeaponPair(authored.primary, authored.secondary)
            }.getOrNull()
        }
        return (this as? VehicleWeaponPairProvider)?.getVehicleWeaponPair(seatIndex)
    }

    /** Resolves the current primary/secondary slots for HUD consumers. */
    fun getActiveVehicleWeaponPair(seatIndex: Int): ActiveVehicleWeaponPair? {
        val primaryIndex = getSelectedWeapon(seatIndex)
        val activeId = getGunName(seatIndex, primaryIndex) ?: return null
        val secondaryIndex = getSecondaryWeaponIndex(seatIndex)
        return ActiveVehicleWeaponPair(
            activeId,
            secondaryIndex?.let { getGunName(seatIndex, it) },
        )
    }

    fun getPrimaryWeaponIndex(seatIndex: Int): Int {
        return getSelectedWeapon(seatIndex)
    }

    fun getSecondaryWeaponIndex(seatIndex: Int): Int? {
        return vehicleWeaponRuntime.secondaryWeaponIndex(seatIndex)
    }

    fun hasSecondaryWeapon(seatIndex: Int): Boolean =
        getSecondaryWeaponIndex(seatIndex) != null

    /** Stable server action token for the exact vehicle/dimension/seat/list/slot context. */
    fun secondaryWeaponContextToken(seatIndex: Int): String {
        val ordered = vehicleWeaponRuntime.validWeaponIndices(seatIndex)
        val primary = vehicleWeaponRuntime.resolvedPrimaryIndex(seatIndex, ordered)
        val secondary = vehicleWeaponRuntime.resolvedSecondaryIndex(seatIndex, ordered, primary)
        val ids = ordered.joinToString(",") { getGunName(seatIndex, it).orEmpty() }
        return buildString {
            append(uuid).append('|')
            append(level().dimension().location()).append('|')
            append(id).append('|').append(seatIndex).append('|')
            append(ids).append('|').append(primary).append('|').append(secondary ?: -1)
        }
    }

    /** Validates a held secondary action without consulting mutable client selection state. */
    fun isSecondaryWeaponContextValid(
        controller: LivingEntity?,
        seatIndex: Int,
        token: String,
    ): Boolean {
        if (controller == null || controller.vehicle !== this || controller.level() !== level()) return false
        if (getSeatIndex(controller) != seatIndex || !controller.isAlive || isRemoved || isWreck) return false
        if (!hasSecondaryWeapon(seatIndex)) return false
        return secondaryWeaponContextToken(seatIndex) == token
    }

    private fun exactWeaponIndex(seatIndex: Int, weaponId: String): Int? {
        val weapons = computed().seats().getOrNull(seatIndex)?.weapons() ?: return null
        val index = weapons.indexOf(weaponId)
        return index.takeIf { it >= 0 && getGunData(seatIndex, it) != null }
    }

    /** One immutable profile per configuration/context; null retains native direct-angle behavior. */
    fun resolveVehicleAimProfile(seatIndex: Int, selectedWeaponIndex: Int): VehicleAimProfile? {
        val data = computed()
        if (vehicleAimProfileDataOwner !== data) {
            vehicleAimProfileDataOwner = data
            vehicleAimProfileCache.clear()
        }
        val key = (seatIndex.toLong() shl 32) or (selectedWeaponIndex.toLong() and 0xFFFF_FFFFL)
        vehicleAimProfileCache[key]?.let { return it }
        if (vehicleAimProfileCache.containsKey(key)) return null
        return (this as? VehicleAimProfileProvider)
            ?.createVehicleAimProfile(seatIndex, selectedWeaponIndex)
            .let { authored -> authored ?: if (this !is VehicleAimProfileProvider && hasTurret() &&
                barrelPosition != null && seatIndex == turretControllerIndex &&
                com.atsuishio.superbwarfare.api.vehicle.aim.VehicleLaserRangefinder.enabled(this, seatIndex, selectedWeaponIndex)) {
                VehicleAimProfile.builder(VehicleAimChannel.TURRET)
                    .rates(kotlin.math.abs(turretTurnYSpeed), kotlin.math.abs(turretTurnXSpeed))
                    .yawRange(-turretMaxYaw, -turretMinYaw)
                    .pitchRange(-turretMaxPitch, -turretMinPitch).build()
            } else null }
            .also { vehicleAimProfileCache[key] = it }
    }

    /**
     * Returns the validated live center-screen ray for an ordinary ground TURRET controller.
     * This is an input sample only; it never writes camera state or becomes projectile authority.
     */
    fun resolveVehicleAimCameraRay(
        controller: Entity,
        channel: VehicleAimChannel,
        partialTicks: Float,
    ): VehicleAimCameraRay? {
        if (level().isClientSide || controller !is Player || controller.vehicle !== this ||
            controller.level() !== level() || !controller.isAlive || isRemoved || isWreck ||
            resolveVehicleFlightStrategy() != null
        ) return null
        val seatIndex = getSeatIndex(controller)
        if (seatIndex < 0) return null
        val profile = resolveVehicleAimProfile(seatIndex, getSelectedWeapon(seatIndex))
            ?.takeIf { it.channel == channel && it.defaultMode == VehicleAimMode.PLAYER_LOOK_AIM }
            ?: return null
        val origin = getCameraPos(controller, partialTicks)
        val direction = controller.getViewVector(partialTicks)
        return VehicleAimCameraRay.validated(origin, direction)
    }

    /**
     * Server-owned runtime geometric-zero selection.  The aim controller validates the exact
     * mounted Player/TURRET context. This wrapper delegates the selection without mutating
     * camera or weapon state itself.
     */
    fun setVehicleGeometricZeroDistance(controller: Entity?, requestedDistanceBlocks: Int): Boolean =
        vehicleAimController.setGeometricZeroDistance(controller, requestedDistanceBlocks)

    /** Current authoritative geometric-zero distance for the physical TURRET. */
    fun getVehicleGeometricZeroDistanceBlocks(): Int =
        vehicleAimController.geometricZeroDistanceBlocks()

    /**
     * One-shot server HasFCS G acquisition.  The controller validates the mounted Player,
     * selected TURRET weapon, live raw HUD ray, and all nominal-solver inputs; no client point,
     * direction, camera, or firing state is accepted here.
     */
    fun requestVehicleFcsZero(controller: Entity?, requestSequence: Long): VehicleFcsZeroState =
        vehicleAimController.requestFcsZero(controller, requestSequence)

    fun requestVehicleFcsZeroAsync(controller: Entity?, requestSequence: Long): java.util.concurrent.CompletableFuture<VehicleFcsZeroState> =
        vehicleAimController.requestFcsZeroAsync(controller, requestSequence)

    /** Immutable latest server FCS-zero result for a transport/diagnostic consumer. */
    fun acceptVehicleAimOpticalZoom(controller: Entity, magnification: Float) =
        vehicleAimController.acceptOpticalZoom(controller, magnification)

    fun getVehicleFcsZeroState(): VehicleFcsZeroState = vehicleAimController.fcsZeroState()

    /** Dynamic drive state is applied after authored profile rates, on both authority and prediction. */
    open fun resolveVehicleAimYawRateDegreesPerSecond(
        channel: VehicleAimChannel,
        configuredRate: Float,
    ): Float = if (channel == VehicleAimChannel.TURRET && turretDamaged) configuredRate * 0.2F else configuredRate

    open fun resolveVehicleAimPitchRateDegreesPerSecond(
        channel: VehicleAimChannel,
        configuredRate: Float,
    ): Float = if (channel == VehicleAimChannel.TURRET && turretDamaged) configuredRate * 0.2F else configuredRate

    fun resolveVehicleAimMinPitch(channel: VehicleAimChannel, authoredMinimum: Float): Float =
        if (channel == VehicleAimChannel.TURRET) authoredMinimum + customTurretMaxPitch else authoredMinimum

    fun resolveVehicleAimMaxPitch(channel: VehicleAimChannel, authoredMaximum: Float): Float =
        if (channel == VehicleAimChannel.TURRET) authoredMaximum - customTurretMinPitch else authoredMaximum

    fun getVehicleAimSnapshot(seatIndex: Int, selectedWeaponIndex: Int): VehicleAimSnapshot? {
        refreshClientAimPresentationFromSyncedData()
        return vehicleAimController.snapshot(seatIndex, selectedWeaponIndex)
    }

    fun getVehicleAimSnapshot(channel: VehicleAimChannel): VehicleAimSnapshot? {
        refreshClientAimPresentationFromSyncedData()
        return vehicleAimController.snapshot(channel)
    }

    @OnlyIn(Dist.CLIENT)
    fun getVehicleAimPresentationProfile(
        controller: Entity,
        channel: VehicleAimChannel,
    ): VehicleAimProfile? = vehicleAimController.profileFor(controller, channel)

    /** Uses server truth when available, otherwise the selected profile's immutable mode. */
    fun getVehicleAimPresentationMode(seatIndex: Int, selectedWeaponIndex: Int): VehicleAimMode {
        getVehicleAimSnapshot(seatIndex, selectedWeaponIndex)?.let { return it.mode }
        if (seatIndex < 0 || getSelectedWeapon(seatIndex) != selectedWeaponIndex) return VehicleAimMode.INACTIVE
        val controller = getNthEntity(seatIndex)
        val profile = vehicleAimController.profileFor(controller, VehicleAimChannel.TURRET)
            ?: vehicleAimController.profileFor(controller, VehicleAimChannel.PASSENGER_WEAPON)
        return profile?.defaultMode ?: VehicleAimMode.INACTIVE
    }

    fun publishVehicleAimSnapshots(payload: String) {
        if (!level().isClientSide && entityData.get(VEHICLE_AIM_SNAPSHOT) != payload) {
            NetworkTelemetry.recordSystemWork("aim.snapshot.published")
            if (NetworkTelemetry.isAimPresentationTraceEnabled()) {
                VehicleAimSnapshots.decodeStrict(payload)?.forEach { snapshot ->
                    NetworkTelemetry.recordAimPresentationTrace(
                        AimPresentationTraceSample(
                            stage = "PUBLISHED",
                            entityId = id,
                            clientTick = -1,
                            partialBits = 0,
                            channel = snapshot.channel.ordinal,
                            seatIndex = snapshot.seatIndex,
                            weaponIndex = snapshot.selectedWeaponIndex,
                            sequence = snapshot.sequence,
                            upperSequence = snapshot.sequence,
                            serverTick = snapshot.serverTick,
                            sourceServerTick = snapshot.serverTick.toDouble(),
                            epoch = -1,
                            reason = "SERVER_AUTHORITY",
                            cacheHit = false,
                            direct = true,
                            continuity = false,
                            tailHeld = false,
                            alpha = 1F,
                            axesValid = true,
                            actualYaw = snapshot.actualYaw,
                            actualPitch = snapshot.actualPitch,
                            presentedYaw = snapshot.actualYaw,
                            presentedPitch = snapshot.actualPitch,
                        ),
                    )
                }
            }
        }
        publishTextSnapshot(VEHICLE_AIM_SNAPSHOT, "vehicle_aim_snapshot", payload)
    }

    fun requestVehicleActionInput(
        player: Player,
        vehicleId: Int,
        actionId: ResourceLocation,
        requestSequence: Int,
        held: Boolean,
    ): Boolean = vehicleActionController.requestInput(
        player,
        vehicleId,
        actionId,
        requestSequence,
        held,
    )

    fun getVehicleActionSnapshot(actionId: ResourceLocation): VehicleActionSnapshot? {
        if (level().isClientSide) {
            vehicleActionController.consumeClient(entityData.get(VEHICLE_ACTION_SNAPSHOT))
        }
        return vehicleActionController.snapshot(actionId)
    }

    fun publishVehicleActionSnapshots(payload: String) {
        publishTextSnapshot(VEHICLE_ACTION_SNAPSHOT, "vehicle_action_snapshot", payload)
    }

    fun turretAutoAimFromVector(shootVec: Vec3?) {
        VehicleWeaponUtils.turretAutoAimFromVector(this, shootVec)
    }

    fun turretAutoAimFromUuid(uuid: String, pLiving: LivingEntity?) {
        VehicleWeaponUtils.turretAutoAimFromUuid(this, uuid, pLiving)
    }

    override fun onPassengerTurned(entity: Entity) {
        val seatIndex = getSeatIndex(entity)
        if (seatIndex >= 0) {
            val weaponIndex = getSelectedWeapon(seatIndex)
            val aimMode = getVehicleAimPresentationMode(seatIndex, weaponIndex)
            // A weapon controller's free look is an aim contract, not a successfully resolved
            // camera-attachment side effect. The old BVP path bypassed this clamp directly.
            if (controlsPlayerLookAim(entity, seatIndex, weaponIndex, aimMode)) {
                return
            }
            val pose = resolveVehicleSeatPose(entity, 1F, false)
            if (pose?.cameraMode(aimMode) == VehicleCameraMode.PLAYER_LOOK_AIM) {
                return
            }
        }
        this.clampRotation(entity)
    }

    open val customTurretMinPitch: Float
        /**
         * @return 自定义炮塔最低俯角
         */
        get() = 0f

    val customTurretMaxPitch: Float
        /**
         * @return 自定义炮塔最大仰角
         */
        get() = 0f

    private fun clampRotation(entity: Entity) {
        val index = getSeatIndex(entity)
        val seats = computed().seats()
        if (index < 0 || index >= seats.size) return
        val seat = seats[index]

        var vec3 = getTransformDirection(1f, entity)

        if ((seat.transform == "Barrel" && turretControllerIndex == getSeatIndex(entity)) ||
            (seat.transform == "WeaponStationBarrel" && passengerWeaponStationControllerIndex == getSeatIndex(entity))
        ) {
            val stationBase = if (seat.transform == "WeaponStationBarrel" &&
                isHullParentedPassengerWeaponStation()
            ) "Vehicle" else "Turret"
            vec3 = getTransformDirectionFromString(1f, entity, stationBase)
        }

        val minPitch = -seat.maxPitch + customTurretMaxPitch
        val maxPitch = -seat.minPitch - customTurretMinPitch
        val f = Mth.wrapDegrees(entity.xRot - -getXRotFromVector(vec3)).toFloat()
        val f1 = Mth.clamp(f, minPitch, maxPitch)
        entity.xRotO += f1 - f
        entity.xRot = entity.xRot + f1 - f

        val minYaw = seat.minYaw
        val maxYaw = seat.maxYaw
        val f2 = Mth.wrapDegrees(entity.yRot - -getYRotFromVector(vec3)).toFloat()
        val f3 = Mth.clamp(f2, minYaw, maxYaw)
        entity.yRotO += f3 - f2
        entity.yRot = entity.yRot + f3 - f2

        if (seat.transform == "Turret" && turretControllerIndex == getSeatIndex(entity)) {
            if (!entity.level().isClientSide) return
            if (Minecraft.getInstance().options.cameraType != CameraType.FIRST_PERSON) return

            val f4 = Mth.wrapDegrees(entity.yRot - -getYRotFromVector(vec3)).toFloat()
            val f5 = Mth.clamp(f2, -16f, 16f)
            entity.yRotO += f5 - f4
            entity.yRot = entity.yRot + f5 - f4
        }
    }

    fun copyEntityData(entity: Entity) {
        entity.yRot += destroyRot
        val index = getSeatIndex(entity)
        val seat = computed().seats()[index]
        val vec3 = getTransformDirection(1f, entity)
        val yaw = -getYRotFromVector(vec3).toFloat()

        if (seat.transform == "Vehicle" || seat.transform == "VehicleFlat") {
            if (!seat.canRotateHead) {
                entity.yRot = yaw
            }
        }

        if (!seat.canRotateBody) {
            entity.setYBodyRot(yaw)
        }
    }

    fun getTransformDirection(ticks: Float, entity: Entity): Vec3 {
        val index = getSeatIndex(entity)
        val seat = computed().seats()[index]
        val passengerRot = seat.orientation
        val transform = getTransformFromString(seat.transform, ticks).rotate(Axis.YP.rotationDegrees(-passengerRot))
        val posO = transformPosition(transform, 0.0, 0.0, 0.0)
        val pos = transformPosition(transform, 0.0, 0.0, 1.0)
        return Vec3(posO.x, posO.y, posO.z).vectorTo(Vec3(pos.x, pos.y, pos.z))
    }

    fun getTransformDirectionNoOrientation(ticks: Float, entity: Entity): Vec3 {
        val index = getSeatIndex(entity)
        val seat = computed().seats()[index]
        val transform = getTransformFromString(seat.transform, ticks)
        val posO = transformPosition(transform, 0.0, 0.0, 0.0)
        val pos = transformPosition(transform, 0.0, 0.0, 1.0)
        return Vec3(posO.x, posO.y, posO.z).vectorTo(Vec3(pos.x, pos.y, pos.z))
    }

    fun getTransformDirectionFromString(ticks: Float, entity: Entity, string: String): Vec3 {
        val index = getSeatIndex(entity)
        val seat = computed().seats()[index]
        val passengerRot = seat.orientation
        val transform = getTransformFromString(string, ticks).rotate(Axis.YP.rotationDegrees(-passengerRot))
        val posO = transformPosition(transform, 0.0, 0.0, 0.0)
        val pos = transformPosition(transform, 0.0, 0.0, 1.0)
        return Vec3(posO.x, posO.y, posO.z).vectorTo(Vec3(pos.x, pos.y, pos.z))
    }

    /** One pose supplies rider body placement and both first-person camera anchors. */
    fun resolveVehicleSeatPose(
        passenger: Entity,
        partialTicks: Float,
        zooming: Boolean,
    ): VehicleSeatPoseSnapshot? = VehicleSeatPoseResolver.resolve(this, passenger, partialTicks, zooming)

    public override fun positionRider(passenger: Entity, callback: MoveFunction) {
        if (!this.hasPassenger(passenger)) {
            return
        }

        val index = getSeatIndex(passenger)
        val seats = computed().seats()
        if (index < 0 || index >= seats.size) return

        val resolved = resolveVehicleSeatPose(passenger, 1F, false)
        if (resolved != null) {
            val body = resolved.bodyPosition
            passenger.setPos(body.x, body.y, body.z)
            callback.accept(passenger, body.x, body.y, body.z)
            copyEntityData(passenger)
            return
        }

        // Provider-backed ground presentation must remain atomic. During a temporary coherent
        // frame gap, retain the rider's current safe position rather than falling through to
        // mutable turret/station transforms or rebasing authored offsets onto Vehicle root.
        if (level().isClientSide && resolveVehiclePoseProvider() != null &&
            resolveVehicleFlightStrategy() == null
        ) {
            callback.accept(passenger, passenger.x, passenger.y, passenger.z)
            return
        }

        val seat = seats[index]
        passengerPos(passenger, callback, seat.position, seat.transform)
    }

    fun passengerPos(passenger: Entity, callback: MoveFunction, vec3: Vec3, string: String?) {
        val worldPosition = transformPosition(getTransformFromString(string), vec3.x, vec3.y, vec3.z)
        passenger.setPos(worldPosition.x, worldPosition.y, worldPosition.z)
        callback.accept(passenger, worldPosition.x, worldPosition.y, worldPosition.z)
        copyEntityData(passenger)
    }

    protected var positionTransform = HashMap<String, Function<Float, Matrix4d>>()
    protected var vectorTransform = HashMap<String, Function<Float, Vec3>>()
    protected var rotationTransform = HashMap<String, Function<Float, Quaterniond>>()

    init {
        registerTransforms()
        initOBB()

        inventoryEnergyService.initializeEnergyStorage()
        this.isInitialized = true

        this.health = this.getMaxHealth()
    }

    protected fun registerTransforms() {
        positionTransform["VehicleFlat"] = Function { partialTicks -> this.getVehicleFlatTransform(partialTicks) }
        positionTransform["Turret"] = Function { partialTicks -> this.getTurretTransform(partialTicks) }
        positionTransform["Barrel"] = Function { partialTicks -> this.getBarrelTransform(partialTicks) }
        positionTransform["WeaponStation"] = Function { partialTicks -> this.getGunTransform(partialTicks) }
        positionTransform["WeaponStationBarrel"] =
            Function { partialTicks -> this.getPassengerWeaponStationBarrelTransform(partialTicks) }
        positionTransform["Default"] = Function { ticks -> this.getVehicleTransform(ticks) }

        vectorTransform["Turret"] = Function { pPartialTicks -> this.getTurretVector(pPartialTicks) }
        vectorTransform["Barrel"] = Function { pPartialTicks -> this.getBarrelVector(pPartialTicks) }
        vectorTransform["WeaponStationBarrel"] =
            Function { partialTicks -> this.getPassengerWeaponStationVector(partialTicks) }
        vectorTransform["DeltaMovement"] = Function { _ -> deltaMovement.normalize() }
        vectorTransform["Up"] = Function { ticks -> this.getUpVec(ticks) }
        vectorTransform["Default"] = Function { partialTicks -> this.getViewVector(partialTicks) }

        rotationTransform["WeaponStation"] =
            Function { tick -> VectorTool.combineRotationsPassengerWeaponStation(tick, this) }
        rotationTransform["WeaponStationBarrel"] =
            Function { tick -> VectorTool.combineRotationsPassengerWeaponStationBarrel(tick, this) }
        rotationTransform["Turret"] = Function { tick -> combineRotationsTurret(tick, this) }
        rotationTransform["Barrel"] = Function { tick -> VectorTool.combineRotationsBarrel(tick, this) }
        rotationTransform["RotationsYaw"] = Function { tick -> VectorTool.combineRotationsYaw(tick, this) }
        rotationTransform["Default"] = Function { tick -> VectorTool.combineRotations(tick, this) }
    }

    fun getTransformFromString(string: String?): Matrix4d {
        return getTransformFromString(string, 1f)
    }

    fun getTransformFromString(string: String?, ticks: Float): Matrix4d {
        return positionTransform
            .getOrDefault(string, positionTransform["Default"])!!
            .apply(ticks)
    }

    fun getVectorFromString(string: String?): Vec3 {
        return getVectorFromString(string, 0f)
    }

    fun getVectorFromString(string: String?, ticks: Float): Vec3 {
        return vectorTransform
            .getOrDefault(string, vectorTransform["Default"])!!
            .apply(ticks)
    }

    fun getVectorFromString(string: String, ticks: Float, seatIndex: Int): Vec3 {
        val entity = getNthEntity(seatIndex)
        return when (string) {
            "Bomb" -> bombHitPos(getNthEntity(seatIndex)).subtract(getShootPosForHud(getNthEntity(seatIndex), 1f))
            "Passenger" -> if (entity != null) entity.getViewVector(ticks) else getViewVector(ticks)
            "ClientCamera" -> if (entity != null && entity.level().isClientSide) cameraDirection() else getViewVector(
                ticks
            )

            else -> getVectorFromString(string, ticks)
        }
    }

    fun cameraDirection(): Vec3 {
        return Vec3(Minecraft.getInstance().gameRenderer.mainCamera.lookVector)
    }

    fun getRotationFromString(string: String?): Quaterniond {
        return getRotationFromString(string, 0f)
    }

    fun getRotationFromString(string: String?, ticks: Float): Quaterniond {
        return rotationTransform
            .getOrDefault(string, rotationTransform["Default"])!!
            .apply(ticks)
    }

    /**
     * @return 炮弹发射位置
     */
    fun getShootPos(seatIndex: Int, ticks: Float): Vec3 {
        return getShootPos(getNthEntity(seatIndex), ticks)
    }

    fun bombHitPos(entity: Entity?): Vec3 {
        val gunData = getGunData(entity)
        return if (gunData != null && level().isClientSide) {
            val baseVelocity = getShootVec(entity, 1f).normalize().scale(gunData.get(GunProp.VELOCITY))
            val launchVelocity = if (gunData.get(GunProp.ADD_SHOOTER_DELTA_MOVEMENT)) {
                baseVelocity.add(deltaMovement)
            } else {
                baseVelocity
            }
            ProjectileCalculator.calculatePreciseImpactPoint(
                level(),
                getShootPosForHud(entity, 1f),
                launchVelocity.normalize(),
                launchVelocity.length(),
                -getProjectileGravity(entity).toDouble()
            )
        } else {
            Vec3.ZERO
        }
    }

    /**
     * @param entity 操控载具的实体
     * @return 炮弹发射位置
     */
    fun getShootPos(entity: Entity?, ticks: Float): Vec3 {
        val data = getGunData(getSeatIndex(entity))
        if (data != null) {
            attachmentPosition(data.firePositionAttachment(), ticks)?.let { return it }
            val vec3 = data.firePosition()

            val worldPosition = transformPosition(
                this.getTransformFromString(data.get(GunProp.SHOOT_POS).transform, ticks),
                vec3.x, vec3.y, vec3.z
            )

            return Vec3(worldPosition.x, worldPosition.y, worldPosition.z)
        }
        return getEyePosition(ticks)
    }

    fun getShootPos(weaponName: String, ticks: Float): Vec3 {
        val data = getGunData(weaponName)
        if (data != null) {
            attachmentPosition(data.firePositionAttachment(), ticks)?.let { return it }
            val vec3 = data.firePosition()

            val worldPosition = transformPosition(
                this.getTransformFromString(data.get(GunProp.SHOOT_POS).transform, ticks),
                vec3.x, vec3.y, vec3.z
            )

            return Vec3(worldPosition.x, worldPosition.y, worldPosition.z)
        }
        return getEyePosition(ticks)
    }

    fun resolveMuzzleFrame(entity: Entity?, partialTicks: Float): VehicleMuzzleFrame? {
        val seatIndex = getSeatIndex(entity)
        val weaponIndex = getSelectedWeapon(seatIndex)
        val weaponName = getGunName(seatIndex, weaponIndex) ?: return null
        // A typed HULL station may only fire its exact authored weapon identity. Other weapons
        // on the seat must not silently reuse the station's muzzle/camera frame.
        if (computed().passengerWeaponStationBinding != null &&
            seatIndex == passengerWeaponStationControllerIndex &&
            !isPassengerWeaponStationWeapon(seatIndex, weaponIndex)
        ) return null
        val data = getGunData(weaponName) ?: return null
        val position = getShootPos(entity, partialTicks)
        val direction = getShootVec(entity, partialTicks)
        val effectPosition = getShootEffectPos(entity, partialTicks)
        val effectDirection = getShootEffectVec(entity, partialTicks)
        return VehicleMuzzleFrame(
            weaponName,
            position,
            direction,
            effectPosition,
            effectDirection,
            resolveShotFrameReference(weaponName, data, partialTicks),
        )
    }

    fun resolveMuzzleFrame(weaponName: String, partialTicks: Float): VehicleMuzzleFrame? {
        val data = getGunData(weaponName) ?: return null
        val position = getShootPos(weaponName, partialTicks)
        val direction = getShootVec(weaponName, partialTicks)
        val effectPosition = getShootEffectPos(weaponName, partialTicks)
        val effectDirection = getShootEffectVec(weaponName, partialTicks)
        return VehicleMuzzleFrame(
            weaponName,
            position,
            direction,
            effectPosition,
            effectDirection,
            resolveShotFrameReference(weaponName, data, partialTicks),
        )
    }

    private fun resolveShotFrameReference(
        weaponName: String,
        data: GunData,
        partialTicks: Float,
    ): ShotFrameReference {
        val snapshot = getVehicleAttachmentSnapshot(partialTicks)
        return ShotFrameReference(
            weaponName,
            data.firePositionSlot(),
            data.fireDirectionSlot(),
            data.firePositionAttachment(),
            data.fireDirectionAttachment(),
            data.fireEffectAttachment(),
            data.fireEffectDirectionAttachment(),
            snapshot.sequence,
            snapshot.serverTick,
            level().gameTime,
        )
    }

    /**
     * @param entity 操控载具的实体
     * @return 所有炮弹发射位置的中心点，用于HUD瞄准
     */
    fun getShootPosForHud(entity: Entity?, ticks: Float): Vec3 {
        val data = getGunData(getSeatIndex(entity))
        if (data != null) {
            attachmentPosition(data.get(GunProp.SHOOT_POS).hudOriginAttachment, ticks)?.let { return it }
            val vec3 = data.firePositionForHud()

            val worldPosition = transformPosition(
                this.getTransformFromString(data.get(GunProp.SHOOT_POS).transform, ticks),
                vec3.x, vec3.y, vec3.z
            )

            return Vec3(worldPosition.x, worldPosition.y, worldPosition.z)
        }
        return getEyePosition(ticks)
    }

    /**
     * @param entity 操控载具的实体
     * @return 所有炮弹发射位置的方向，用于HUD瞄准
     */
    fun getShootDirectionForHud(entity: Entity, partialTicks: Float): Vec3 {
        val data = getGunData(getSeatIndex(entity)) ?: return getViewVector(partialTicks)

        attachmentDirection(data.get(GunProp.SHOOT_POS).hudDirectionAttachment, partialTicks)?.let { return it }

        val stringOrVec3 = data.fireDirectionForHud()

        if (stringOrVec3 == null) {
            return getViewVec(entity, partialTicks)
        } else if (stringOrVec3.isString) {
            return getVectorFromString(stringOrVec3.string!!, partialTicks, getSeatIndex(entity))
        } else {
            val vec3 = stringOrVec3.vec3!!
            val worldPosition = transformPosition(
                getTransformFromString(data.get(GunProp.SHOOT_POS).transform, partialTicks),
                vec3.x + stringOrVec3.vec3.x,
                vec3.y + stringOrVec3.vec3.y,
                vec3.z + stringOrVec3.vec3.z
            )

            val worldPositionO = transformPosition(
                getTransformFromString(data.get(GunProp.SHOOT_POS).transform, partialTicks),
                vec3.x, vec3.y, vec3.z
            )

            val startPos = Vec3(worldPositionO.x, worldPositionO.y, worldPositionO.z)
            val endPos = Vec3(worldPosition.x, worldPosition.y, worldPosition.z)
            return startPos.vectorTo(endPos).normalize()
        }
    }

    fun getShootVec(seatIndex: Int, ticks: Float): Vec3? {
        return getShootVec(getNthEntity(seatIndex), ticks)
    }

    fun getShootVec(entity: Entity?, partialTicks: Float): Vec3 {
        val data = getGunData(getSeatIndex(entity))
        attachmentDirection(data?.fireDirectionAttachment(), partialTicks)?.let { return it }
        return VehicleVecUtils.getShootVec(this, entity, partialTicks)
    }

    fun getShootVec(weaponName: String, partialTicks: Float): Vec3 {
        val data = getGunData(weaponName)
        attachmentDirection(data?.fireDirectionAttachment(), partialTicks)?.let { return it }
        return VehicleVecUtils.getShootVec(this, weaponName, partialTicks)
    }

    fun getViewVec(entity: Entity, partialTicks: Float): Vec3 {
        val data = getGunData(getSeatIndex(entity))
        attachmentDirection(data?.get(GunProp.SHOOT_POS)?.viewDirectionAttachment, partialTicks)?.let { return it }
        return VehicleVecUtils.getViewVec(this, entity, partialTicks)
    }

    fun getViewPos(entity: Entity, partialTicks: Float): Vec3? {
        val data = getGunData(getSeatIndex(entity))
        attachmentPosition(data?.get(GunProp.SHOOT_POS)?.viewAttachment, partialTicks)?.let { return it }
        return VehicleVecUtils.getViewPos(this, entity, partialTicks)
    }

    fun getSeekVec(entity: Entity?, partialTicks: Float): Vec3? {
        val data = getGunData(getSeatIndex(entity))
        attachmentDirection(data?.get(GunProp.SHOOT_POS)?.seekDirectionAttachment, partialTicks)?.let { return it }
        return VehicleVecUtils.getSeekVec(this, entity, partialTicks)
    }

    fun getSeekVec(seatIndex: Int, partialTicks: Float): Vec3? {
        return getSeekVec(getNthEntity(seatIndex), partialTicks)
    }

    /** Presentation origin for a logical shot; defaults exactly to the selected ballistic muzzle. */
    fun getShootEffectPos(entity: Entity?, partialTicks: Float): Vec3 {
        val data = getGunData(getSeatIndex(entity))
        return attachmentPosition(data?.fireEffectAttachment(), partialTicks)
            ?: getShootPos(entity, partialTicks)
    }

    fun getShootEffectPos(weaponName: String, partialTicks: Float): Vec3 {
        val data = getGunData(weaponName)
        return attachmentPosition(data?.fireEffectAttachment(), partialTicks)
            ?: getShootPos(weaponName, partialTicks)
    }

    /** Presentation direction for a logical shot; defaults exactly to the ballistic direction. */
    fun getShootEffectVec(entity: Entity?, partialTicks: Float): Vec3 {
        val data = getGunData(getSeatIndex(entity))
        return attachmentDirection(data?.fireEffectDirectionAttachment(), partialTicks)
            ?: getShootVec(entity, partialTicks)
    }

    fun getShootEffectVec(weaponName: String, partialTicks: Float): Vec3 {
        val data = getGunData(weaponName)
        return attachmentDirection(data?.fireEffectDirectionAttachment(), partialTicks)
            ?: getShootVec(weaponName, partialTicks)
    }

    /** Optional native seek origin for consumers that need a trace start as well as a direction. */
    fun getSeekPos(entity: Entity?, partialTicks: Float): Vec3? {
        val data = getGunData(getSeatIndex(entity)) ?: return null
        return attachmentPosition(data.get(GunProp.SHOOT_POS).seekAttachment, partialTicks)
    }

    private fun attachmentPosition(name: String?, partialTicks: Float): Vec3? {
        val frame = name?.takeIf { it.isNotBlank() } ?: return null
        return getVehicleAttachmentSnapshot(partialTicks).point(frame, Vec3.ZERO)
    }

    private fun attachmentDirection(name: String?, partialTicks: Float): Vec3? {
        val frame = name?.takeIf { it.isNotBlank() } ?: return null
        val direction = getVehicleAttachmentSnapshot(partialTicks)
            .direction(frame, Vec3(0.0, 0.0, 1.0))
            ?: return null
        return direction.takeIf { it.lengthSqr() > 1.0E-12 }?.normalize()
    }

    fun getPlayerLookAtEntityOnVehicle(shooter: Entity, entityReach: Double, partialTick: Float): Entity? {
        val eye = getShootPosForHud(shooter, partialTick)
        val distance = entityReach * entityReach
        var hitResult = TraceTool.pickNew(eye, 512.0, this)

        val viewVec = getViewVec(shooter, partialTick)
        val toVec = eye.add(viewVec.x * entityReach, viewVec.y * entityReach, viewVec.z * entityReach)
        val aabb = boundingBox.expandTowards(viewVec.scale(entityReach)).inflate(1.0)
        val entityHitResult = ProjectileUtil.getEntityHitResult(
            this, eye, toVec, aabb,
            { p ->
                !p!!.isSpectator
                        && p.isAlive
                        && SeekTool.BASIC_FILTER.test(p)
                        && !p.type.`is`(ModTags.EntityTypes.DECOY)
                        && SeekTool.NOT_IN_SMOKE.test(p)
                        && p !== shooter
                        && p.vehicle != shooter.vehicle
                        && (p !is Projectile)
            }, distance
        )
        if (entityHitResult != null) {
            hitResult = entityHitResult
        }
        if (hitResult!!.type == HitResult.Type.ENTITY) {
            if (entityHitResult != null) {
                return entityHitResult.entity
            }
        }
        return null
    }

    /**
     * @param entity 操控载具的实体
     * @return 炮弹发射时的初始速度
     */
    fun getProjectileVelocity(entity: Entity?): Float {
        return getProjectileVelocity(getGunData(getSeatIndex(entity)))
    }

    fun getProjectileVelocity(seatIndex: Int): Float {
        return getProjectileVelocity(getGunData(seatIndex))
    }

    fun getProjectileVelocity(weaponName: String): Float {
        return getProjectileVelocity(getGunData(weaponName))
    }

    fun getProjectileVelocity(gunData: GunData?): Float {
        return gunData?.get(GunProp.VELOCITY)?.toFloat() ?: 25f
    }

    /**
     * @param entity 操控载具的实体
     * @return 炮弹重力
     */
    fun getProjectileGravity(entity: Entity?): Float {
        val gunData = getGunData(getSeatIndex(entity)) ?: return 0f

        return gunData.get(GunProp.GRAVITY).toFloat()
    }

    fun getProjectileGravity(seatIndex: Int): Float {
        val gunData = getGunData(seatIndex) ?: return 0f

        return gunData.get(GunProp.GRAVITY).toFloat()
    }

    fun getProjectileGravity(weaponName: String): Float {
        val gunData = getGunData(weaponName) ?: return 0f

        return gunData.get(GunProp.GRAVITY).toFloat()
    }

    fun getProjectileGravity(gunData: GunData?): Float {
        if (gunData == null) return 0f
        return gunData.get(GunProp.GRAVITY).toFloat()
    }

    /**
     * @param entity 操控载具的实体
     * @return 炮弹发射时的散布
     */
    fun getProjectileSpread(entity: Entity?): Float {
        val gunData = getGunData(getSeatIndex(entity)) ?: return 0.5f

        return gunData.get(GunProp.SPREAD).toFloat()
    }

    fun getProjectileSpread(seatIndex: Int): Float {
        val gunData = getGunData(seatIndex) ?: return 0.5f

        return gunData.get(GunProp.SPREAD).toFloat()
    }

    fun getProjectileSpread(weaponName: String): Float {
        val gunData = getGunData(weaponName) ?: return 0.5f

        return gunData.get(GunProp.SPREAD).toFloat()
    }

    fun getProjectileSpread(gunData: GunData?): Float {
        if (gunData == null) return 0.5f
        return gunData.get(GunProp.SPREAD).toFloat()
    }

    /**
     * 根据UUID，使乘客位武器自动瞄准
     *
     * @param uuid    目标的UUID字符串
     * @param pLiving 操控载具的实体
     */
    fun passengerWeaponAutoAimFormUuid(uuid: String?, pLiving: LivingEntity) {
        var target = EntityFindUtil.findEntity(level(), uuid)
        if (target != null) {
            if (target.vehicle != null) {
                target = target.vehicle
            }

            val targetPos = target!!.boundingBox.center
            var targetVel = target.deltaMovement

            if (target is LivingEntity) {
                val gravity = target.getAttributeValue(ForgeMod.ENTITY_GRAVITY.get())
                targetVel = targetVel.add(0.0, gravity, 0.0)
            }

            if (target is Player) {
                targetVel = targetVel.multiply(2.0, 1.0, 2.0)
            }

            val targetVec = calculateFiringSolution(
                getShootPos(pLiving, 1f).subtract(
                    getShootVec(pLiving, 1f).scale(
                        getShootPos(
                            pLiving,
                            1f
                        ).distanceTo(pLiving.position())
                    )
                ),
                targetPos,
                targetVel,
                getProjectileVelocity(pLiving).toDouble(),
                getProjectileGravity(pLiving).toDouble()
            )
            passengerWeaponAutoAimFormVector(targetVec)
        }
    }

    /**
     * 根据方向向量，使乘客位武器自动瞄准
     *
     * @param shootVec 需要让武器站以这个角度发射的向量
     */
    fun passengerWeaponAutoAimFormVector(shootVec: Vec3) {
        val ySpeed = this.passengerWeaponYSpeed
        val xSpeed = this.passengerWeaponXSpeed
        val diffY = Mth.wrapDegrees(
            -getYRotFromVector(shootVec) + getYRotFromVector(
                getPassengerWeaponStationVector(1f)
            )
        ).toFloat()
        val diffX = Mth.wrapDegrees(
            -getXRotFromVector(shootVec) + getXRotFromVector(
                getPassengerWeaponStationVector(1f)
            )
        ).toFloat()

        this.turretTurnSound(diffX, diffY, 0.95f)

        this.gunXRot = Mth.clamp(
            this.gunXRot + Mth.clamp(diffX, -xSpeed, xSpeed),
            -this.passengerWeaponMaxPitch,
            -this.passengerWeaponMinPitch
        )
        this.gunYRot = Mth.clamp(
            this.gunYRot - Mth.clamp(diffY, -ySpeed, ySpeed),
            -this.passengerWeaponMaxYaw,
            -this.passengerWeaponMinYaw
        )
    }

    fun adjustWeaponControllerAngle() {
        val entity = getNthEntity(this.passengerWeaponStationControllerIndex)
        val pos: Vec3? = passengerWeaponStationBarrelPosition
        if (entity != null && pos != null) {
            val aimPos = boundingBox.center.add(entity.getViewVector(1f).scale(512.0))

            val transform: Matrix4d = getGunTransform(1f)
            val worldPosition = transformPosition(transform, pos.x, pos.y, pos.z)

            val aimVec = Vec3(worldPosition.x, worldPosition.y, worldPosition.z).vectorTo(aimPos)
            passengerWeaponAutoAimFormVector(aimVec)
        }

        if (entity == null) {
            gunYRot += turretYRotLock
        }
    }

    /** Native destruction entry point. Addons can use the context overload for one explicit cause. */
    open fun destroy() = vehicleDestructionLifecycleService.destroyBase()

    /** Vehicles with blowout compartments can retain their turret for every destruction cause. */
    open fun allowsTurretEjection(): Boolean = true

    fun destroy(context: VehicleDestructionContext) = vehicleDestructionLifecycleService.destroy(context)

    fun defaultDestructionContext(): VehicleDestructionContext {
        return VehicleDestructionContext.builder()
            .directSource(this)
            .attacker(lastAttacker)
            .explosionCause(Mod.loc("vehicle_destruction"))
            .wreckVisual(ForgeRegistries.ENTITY_TYPES.getKey(type))
            .build()
    }

    fun vehicleExplosion(destroyInfo: DestroyInfo) =
        vehicleDestructionLifecycleService.vehicleExplosion(destroyInfo)

    fun vehicleExplosion(destroyInfo: DestroyInfo, context: VehicleDestructionContext) =
        vehicleDestructionLifecycleService.vehicleExplosion(destroyInfo, context)

    /** Vehicle-owned blasts (death, overkill, cook-off) are not munitions: legacy blast unless a caller sets a charge. */
    fun createCustomExplosion(): CustomExplosion.Builder = CustomExplosion.Builder(this).tntEquivalent(0.0)
        .attacker(this.lastAttacker)

    fun createCustomExplosion(context: VehicleDestructionContext): CustomExplosion.Builder {
        val directSource = context.directSource() ?: this
        return CustomExplosion.Builder(directSource)
            .source(directSource)
            .attacker(context.attacker())
            .position(context.gameplayPosition() ?: Vec3(this.x, this.eyeY, this.z))
            // The destruction context may name the killing projectile; its charge already detonated.
            .tntEquivalent(0.0)
    }

    protected fun crashPassengers() {
        for (entity in this.getPassengers()) {
            if (entity is LivingEntity) {
                repeat(VehicleConfig.AIR_CRASH_EXPLOSION_COUNT.get()) {
                    val tempAttacker = if (entity === this.lastAttacker) null else this.lastAttacker
                    entity.invulnerableTime = 0
                    entity.hurt(
                        ModDamageTypes.causeAirCrashDamage(this.level().registryAccess(), null, tempAttacker),
                        VehicleConfig.AIR_CRASH_EXPLOSION_DAMAGE.get().toFloat()
                    )
                }
            }
        }
    }

    internal fun invokeCrashPassengers() = crashPassengers()

    protected fun explodePassengers() {
        for (entity in this.getPassengers()) {
            if (entity !is LivingEntity) continue
            repeat(VehicleConfig.SELF_EXPLOSION_COUNT.get()) {
                val tempAttacker = if (entity === this.lastAttacker) null else this.lastAttacker
                entity.invulnerableTime = 0
                entity.hurt(
                    ModDamageTypes.causeVehicleExplosionDamage(
                        this.level().registryAccess(),
                        null,
                        tempAttacker
                    ), VehicleConfig.SELF_EXPLOSION_DAMAGE.get().toFloat()
                )
            }
        }
    }

    internal fun invokeExplodePassengers() = explodePassengers()

    internal fun sampleTurretWreckImpulseNoise(): Double =
        random.triangle(0.0, 0.0172275 * 12.toDouble())

    internal fun applyTrackGroundMotion(engineInfo: EngineInfo.Track) {
        vehicleGroundMotionService.stepTrack(engineInfo)
    }

    internal fun applyWheelGroundMotion(engineInfo: EngineInfo.Wheel) {
        vehicleGroundMotionService.stepWheel(engineInfo)
    }

    open fun travel() {
        vehicleFlightController.beginTick()
        // Passengerless native vehicles must not retain a departed driver's raw packet bits.
        // Remote drones override travel() and retain their separate monitor-control lifecycle.
        if (passengers.isEmpty()) {
            processInput(0)
            mouseMoveSpeedX = 0F
            mouseMoveSpeedY = 0F
        }
        val computed = computed()

        val engineType = computed.engineType
        val hadEngineInfo = this.engineInfo != null
        val engineConfigurationChanged = vehicleEngineRuntime.bind(computed, engineType, computed.engineInfo, engineInfo)
        if (engineConfigurationChanged) engineInfo = vehicleEngineRuntime.engine
        tickFixedWingLandingGear()
        if (isWreck && (vehicleType == VehicleType.AIRPLANE || vehicleType == VehicleType.HELICOPTER) &&
            vehicleFlightController.apply(null)) return
        // Fixed-wing providers are opt-in and must be checked before the legacy FIXED early
        // return.  Existing helicopter/native providers continue through their original decode
        // and lifecycle order below; they are not queried until after their engine is decoded.
        val fixedWingStrategy = (this as? FixedWingFlightStrategyProvider)
            ?.createFixedWingFlightStrategy(this)
        if (fixedWingStrategy != null && vehicleFlightController.apply(fixedWingStrategy)) {
            return
        }
        if (!computed.driveAuthority &&
            (engineType == EngineType.WHEEL || engineType == EngineType.TRACK || engineType == EngineType.WHEELCHAIR)
        ) {
            // A typed no-drive ground vehicle still participates in the normal gravity/collision
            // transaction below; only engine-generated horizontal motion is suppressed.
            val motion = deltaMovement
            deltaMovement = Vec3(0.0, motion.y, 0.0)
            targetSpeed = 0.0
            power = 0f
            vehicleFlightController.apply(null)
            return
        }
        if (engineType == EngineType.EMPTY) {
            vehicleFlightController.apply(null)
            return
        }
        if (engineType == EngineType.FIXED) {
            vehicleFlightController.apply(null)
            this.fixedEngine()
            return
        }


        val strategy = resolveVehicleFlightStrategy()
        if (vehicleFlightController.apply(strategy)) {
            return
        }

        // Preserve upstream's exact first-tick behavior: newly decoded legacy engines start next tick.
        if (hadEngineInfo && !engineConfigurationChanged) {
            this.engineInfo?.work(this)
        }
    }

    /** Opt-in strategy selection. A null result leaves the complete upstream EngineInfo path intact. */
    fun resolveVehicleFlightStrategy(): VehicleFlightStrategy? =
        (this as? VehicleFlightStrategyProvider)?.createVehicleFlightStrategy(this)
            ?: (this as? FixedWingFlightStrategyProvider)?.createFixedWingFlightStrategy(this)

    /** Typed fixed-wing identity used by input/presentation consumers; no vehicle-name heuristic. */
    fun isFixedWingFlightVehicle(): Boolean = this is FixedWingFlightStrategyProvider

    /** Fixed-wing gear is an opt-in capability, independent of the legacy aircraft engine. */
    fun hasFixedWingLandingGear(): Boolean {
        if (!isFixedWingFlightVehicle()) return false
        val value = computed().engineInfo.get("HasGear") ?: return false
        return value.isJsonPrimitive && value.asJsonPrimitive.isBoolean && value.asBoolean
    }

    /** Logical-server pilot request; action locks and incomplete gear travel reject the edge. */
    fun requestFixedWingLandingGearToggle(operator: Player): Boolean {
        if (level().isClientSide || isWreck || !operator.isAlive || operator.vehicle !== this
            || getSeatIndex(operator) != 0 || !hasFixedWingLandingGear()
            || !vehicleActionController.allowsMovement() || !vehicleActionController.allowsFire()
            || !FixedWingLandingGear.canToggle(synchedGearRot, onGround(), gearUp)) return false
        gearUp = !gearUp
        return true
    }

    private fun tickFixedWingLandingGear() {
        if (!isFixedWingFlightVehicle()) return
        val available = hasFixedWingLandingGear()
        if (!level().isClientSide) {
            val valid = synchedGearRot.isFinite() && synchedGearRot in 0F..1F
            // No automatic deployment (owner 2026-09-29): only an unavailable or corrupt gear state resets it.
            if (!available || !valid) gearUp = false
            synchedGearRot = if (available) {
                FixedWingLandingGear.nextFraction(synchedGearRot, gearUp, onGround())
            } else 0F
        }
        // Fixed-wing presentation and far copies both consume the same normalized fraction.
        gearRot = if (available && synchedGearRot.isFinite()) synchedGearRot.coerceIn(0F, 1F) else 0F
    }

    /** Current authoritative instruments, interpolated only for client consumers. */
    fun getVehicleFlightInstrumentSnapshot(partialTicks: Float): VehicleFlightInstrumentSnapshot =
        vehicleFlightController.snapshot(partialTicks)

    /** Read-only flight instruments aligned to this entity's rendered previous/current pose. */
    fun getVehicleFlightPresentationSnapshot(partialTicks: Float): VehicleFlightInstrumentSnapshot =
        vehicleFlightController.presentationSnapshot(partialTicks)

    /** Accepted fixed-wing controls on the same clock as the rendered body; null is neutral. */
    fun getVehicleFlightControlSurfaceSnapshot(partialTicks: Float):
        com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingControlSurfaceSnapshot? {
        // A far render copy (beyond entity tracking range) never receives the flight payload; its accepted
        // controls, throttle and afterburner arrive with the far snapshot instead.
        if (level().isClientSide && com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleCopies.isCopy(this)) {
            val far = com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleCopies.frame(this)
                ?.fixedWingControls ?: return null
            return com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingControlSurfaceSnapshot(
                far.serverTick, far.elevator, far.aileron, far.rudder, far.airbrake,
                far.throttle.toFloat().coerceIn(0F, 1F), far.afterburner)
        }
        return vehicleFlightController.controlSurfacePresentationSnapshot(partialTicks)
    }

    /** Per-tick precedence flag: a selected strategy outranks terrain, inertia, and pose providers. */
    val flightStrategyOwnsAttitudeThisTick: Boolean
        get() = vehicleFlightController.strategyOwnsAttitudeThisTick

    fun readVehicleFlightInstrumentPayload(): String = entityData.get(VEHICLE_FLIGHT_INSTRUMENT_SNAPSHOT)

    fun publishVehicleFlightInstrumentPayload(payload: String) {
        publishTextSnapshot(VEHICLE_FLIGHT_INSTRUMENT_SNAPSHOT, "vehicle_flight_snapshot", payload)
    }

    /** Applies final strategy motion without invoking any legacy engine or force model. */
    fun applyVehicleFlightMotion(motion: Vec3, clientTelemetry: Boolean) {
        if (isFixedWingFlightVehicle() && !hasFiniteFixedWingMotion(motion)) return
        setDeltaMovement(motion)
        deltaMovementO = motion
        val horizontal = motion.horizontalDistance()
        lastTickSpeed = horizontal
        lastTickVerticalSpeed = motion.y
        if (clientTelemetry) {
            absoluteSpeedO = horizontal
            absoluteSpeedLerp = horizontal
            absoluteSpeed = horizontal
        }
    }

    /** Applies the selected strategy's finite body attitude on the server and its synchronized copy on clients. */
    fun applyVehicleFlightAttitude(yaw: Float, pitch: Float, bodyRoll: Float) {
        require(yaw.isFinite() && pitch.isFinite() && bodyRoll.isFinite()) { "Flight attitude must be finite" }
        if (level().isClientSide) {
            yRotO = yRot
            xRotO = xRot
            prevRoll = roll
        }
        yRot = yaw
        xRot = pitch
        setZRot(bodyRoll)
        if (isFixedWingFlightVehicle() || vehicleType == VehicleType.HELICOPTER) {
            yRot = VehicleFlightAttitude.wrap(yRot)
            xRot = VehicleFlightAttitude.wrap(xRot)
            setZRot(VehicleFlightAttitude.wrap(roll))
            yRotO = VehicleFlightAttitude.alignedPrevious(yRotO, yRot)
            xRotO = VehicleFlightAttitude.alignedPrevious(xRotO, xRot)
            prevRoll = VehicleFlightAttitude.alignedPrevious(prevRoll, roll)
        }
    }

    private var rotorCoupledHelicopterControlEpoch = 0L

    /** Explicit provider opt-in; native helicopters keep their existing control lifecycle. */
    open fun usesRotorCoupledHelicopterControls(): Boolean = false

    /**
     * Helicopter engine load for audio, 0..1: rotor-coupled helicopters keep power at rotorPower x 0.12, so it is
     * scaled back to the rotor's 0..1; legacy helicopter controls use power directly.
     */
    fun helicopterEngineLoad(): Float {
        val p = kotlin.math.abs(power)
        return (if (usesRotorCoupledHelicopterControls()) p / 0.12f else p).coerceIn(0f, 1f)
    }

    fun getRotorCoupledHelicopterControlEpoch(): Long = rotorCoupledHelicopterControlEpoch

    /** Existing server-validated control bits; this accessor does not acquire or retain input. */
    fun getVehicleFlightControlBits(): Short = vehicleInputBits

    private fun clearFlightPilotControls() {
        clearFixedWingPilotControls()
        if (level().isClientSide || !usesRotorCoupledHelicopterControls()) return
        processInput(0)
        mouseInput(0.0, 0.0)
        hoverMode = false
        rotorCoupledHelicopterControlEpoch =
            if (rotorCoupledHelicopterControlEpoch == Long.MAX_VALUE) 0L
            else rotorCoupledHelicopterControlEpoch + 1L
    }

    /**
     * Server-only engine admission and submerged damage for a rotor-owned strategy.
     * No native spool, attitude, lift, drag, gravity, or movement is executed here.
     */
    fun prepareRotorCoupledHelicopterDrive(): Boolean {
        if (level().isClientSide || !usesRotorCoupledHelicopterControls()) return false
        val info = engineInfo as? Helicopter ?: return false
        if (info is Aircraft || !info.energyCostRate.isFinite() ||
            info.energyCostRate < 0.0 || info.energyCostRate > Int.MAX_VALUE.toDouble() ||
            !info.pitchSpeed.isFinite() || info.pitchSpeed !in 0F..1F ||
            !info.yawSpeed.isFinite() || info.yawSpeed !in 0F..1F ||
            !info.rollSpeed.isFinite() || info.rollSpeed !in 0F..1F
        ) return false
        if (isInFluidType && tickCount % 4 == 0 &&
            VehicleVecUtils.getSubmergedHeight(this) > 0.5 * bbHeight
        ) {
            hurt(
                com.atsuishio.superbwarfare.init.ModDamageTypes.causeVehicleStrikeDamage(
                    level().registryAccess(), this, getNthEntity(0) ?: this
                ),
                6F + (20.0 * (lastTickSpeed - 0.4) * (lastTickSpeed - 0.4)).toFloat()
            )
            crash = true
        }
        return !isWreck && health > 0.1F * getMaxHealth() &&
            hasOperationalPower(info.energyCostRate.toInt())
    }

    /**
     * Publishes an already accepted physical rotor sample. The 0.12 conversion is the legacy
     * visual/audio unit only, never another authority or spool. One energy debit is made while
     * the engine drives; a coasting rotor remains visible after loss of drive.
     */
    fun applyRotorCoupledHelicopterPresentation(rotorPower: Double, engineDriving: Boolean): Boolean {
        if (level().isClientSide || !usesRotorCoupledHelicopterControls() ||
            !rotorPower.isFinite() || rotorPower !in 0.0..1.0
        ) return false
        val info = engineInfo as? Helicopter ?: return false
        if (info is Aircraft || !info.energyCostRate.isFinite() ||
            info.energyCostRate < 0.0 || info.energyCostRate > Int.MAX_VALUE.toDouble()
        ) return false
        if (engineDriving && !engineStart) {
            val gain = info.engineSoundVolume.takeIf { it.isFinite() && it >= 0F } ?: 0F
            val startVolume = (gain * 0.12F).coerceAtMost(1F)
            if (startVolume > 0F) {
                level().playSound(null, this, info.engineStartSound, soundSource, startVolume, 1F)
            }
        }
        engineStart = engineDriving
        engineStartOver = engineDriving && rotorPower > 0.0
        power = (rotorPower * 0.12).toFloat()
        synchedPropellerRot = power
        propellerRot += 30F * synchedPropellerRot
        if (engineDriving) consumeOperationalPower((info.energyCostRate * rotorPower).toInt())
        return true
    }

    /** Reuses native helicopter engine/attitude state without applying its lift, drag, or motion. */
    fun applyHelicopterControlLifecycleForFlightStrategy(): Boolean {
        if (level().isClientSide) return false
        val helicopter = engineInfo as? Helicopter ?: return false
        this.helicopterControlLifecycle(helicopter)
        return true
    }

    fun createVehicleFlightInputContext(): VehicleFlightInputContext {
        val fixedWing = isFixedWingFlightVehicle()
        return VehicleFlightInputContext(
            serverTick = level().gameTime,
            rawInputBits = vehicleInputBits,
            mouseInputX = mouseMoveSpeedX.toDouble(),
            mouseInputY = mouseMoveSpeedY.toDouble(),
            previousMotion = deltaMovementO,
            requestedMotion = deltaMovement,
            lookDirection = getViewVector(1F),
            upDirection = getUpVec(1F),
            enginePower = power.toDouble(),
            occupied = passengers.isNotEmpty(),
            wreck = isWreck,
            hoverMode = hoverMode,
            gravityPerTick = computed().gravity,
            airVelocity = deltaMovement,
            throttleInput = when {
                forwardInputDown && !backInputDown -> 1.0
                else -> 0.0
            },
            // Positive fixed-wing pitch input is the accepted nose-control direction; the legacy
            // mouse channels remain untouched for every other engine/strategy.
            pitchInput = -mouseMoveSpeedY.toDouble(),
            rollInput = when {
                rightInputDown && !leftInputDown -> 1.0
                leftInputDown && !rightInputDown -> -1.0
                else -> 0.0
            },
            yawInput = mouseMoveSpeedX.toDouble(),
            afterburnerRequested = sprintInputDown,
            brakeRequested = downInputDown || backInputDown,
            onGround = onGround(),
            inFluid = isInFluidType,
            bodyYawDegrees = yRot.toDouble(),
            bodyPitchDegrees = xRot.toDouble(),
            bodyRollDegrees = roll.toDouble(),
            fixedWingThrottleAxis = currentFixedWingThrottleAxis(),
            fixedWingMousePitchDelta = if (fixedWing) {
                0.0
            } else {
                -mouseMoveSpeedY.toDouble()
            },
            fixedWingMouseRollDelta = if (fixedWing) {
                0.0
            } else {
                mouseMoveSpeedX.toDouble()
            },
            fixedWingRudderInput = when {
                rightInputDown && !leftInputDown -> 1.0
                leftInputDown && !rightInputDown -> -1.0
                else -> 0.0
            },
            fixedWingAfterburnerRequested = false,
            fixedWingAirbrakeRequested = downInputDown,
            fixedWingRecenterRequested = !fixedWing && upInputDown,
        )
    }

    /** Applies the opt-in server-authored parking policy without changing legacy vehicle defaults. */
    protected open fun applyUnoccupiedParkingBrake() {
        if (isFixedWingFlightVehicle()) return
        if (level().isClientSide || isWreck || passengers.isNotEmpty()) return
        if (!computed().parkingBrakeWhenUnoccupied) return
        if (!onGround() || isInFluidType) return

        forwardInputDown = false
        backInputDown = false
        leftInputDown = false
        rightInputDown = false
        targetSpeed = 0.0
        power = 0f

        val motion = deltaMovement
        if (kotlin.math.abs(motion.x) > 1.0E-4 || kotlin.math.abs(motion.z) > 1.0E-4) {
            deltaMovement = Vec3(0.0, motion.y, 0.0)
        }
    }

    open fun getEngineSoundVolume(): Float {
        val computed = computed()

        if (isFixedWingFlightVehicle()) {
            val gain = computed.engineInfo.get("EngineSoundVolume")
                ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isNumber }
                ?.asFloat?.takeIf { it.isFinite() && it >= 0F } ?: 0.4F
            return fixedWingEngineSoundPower() * gain
        }

        val engineType = computed.engineType
        if (engineType == EngineType.EMPTY || engineType == EngineType.FIXED) return 0f

        val engineInfo = this.engineInfo ?: return 0f

        return when (engineType) {
            EngineType.TRACK -> Math.max(
                Mth.abs(power),
                Mth.abs(1.4f * deltaRot)
            ) * engineInfo.engineSoundVolume

            EngineType.HELICOPTER -> synchedPropellerRot * engineInfo.engineSoundVolume
            else -> Mth.abs(power) * engineInfo.engineSoundVolume
        }
    }

    fun getVehicleTransform(ticks: Float): Matrix4d {
        val transformV = this.getVehicleYOffsetTransform(ticks)
        val transform = Matrix4d()
        val worldPosition = transformPosition(transform, 0.0, -this.rotateOffsetHeight, 0.0)
        transformV.translate(worldPosition.x, worldPosition.y, worldPosition.z)
        return applyVehiclePoseExtension(transformV, ticks)
    }

    fun getVehicleTransformWithCustomPitch(ticks: Float): Matrix4d {
        val transformV = this.getVehicleYOffsetTransform(ticks)
        val transform = Matrix4d()
        val worldPosition = transformPosition(transform, 0.0, -this.rotateOffsetHeight, 0.0)
        transformV.translate(worldPosition.x, worldPosition.y, worldPosition.z)
        transformV.rotate(Axis.XP.rotationDegrees(turretCustomPitch))
        return applyVehiclePoseExtension(transformV, ticks)
    }

    // From Immersive_Aircraft
    fun getVehicleYOffsetTransform(partialTicks: Float): Matrix4d {
        if (flightStrategyOwnsAttitudeThisTick || resolveVehiclePoseProvider() == null) {
            return VehicleVecUtils.getVehicleYOffsetTransform(this, partialTicks)
        }

        // The yaw/offset matrix is already positioned at the native rotation pivot, so applying
        // synchronized base pitch then roll here reproduces the original SBW matrix order.
        return getVehiclePoseSnapshot(partialTicks).applyBaseAttitude(
            VehicleVecUtils.getVehicleYawOffsetTransform(this, partialTicks)
        )
    }

    val rotateOffsetHeight: Double
        get() = computed().rotateOffsetHeight.toDouble()

    fun getVehicleFlatTransform(partialTicks: Float): Matrix4d {
        return VehicleVecUtils.getVehicleFlatTransform(this, partialTicks)
    }

    fun getClientVehicleTransform(partialTicks: Float): Matrix4d {
        if (flightStrategyOwnsAttitudeThisTick || resolveVehiclePoseProvider() == null) {
            return VehicleVecUtils.getClientVehicleTransform(this, partialTicks)
        }

        val pose = getVehiclePoseSnapshot(partialTicks)
        val transform = VehicleVecUtils.getClientVehicleYawOffsetTransform(this, partialTicks)
        // Preserve SBW's legacy camera matrix: native pitch combines with free-camera pitch and
        // native roll is intentionally omitted. The provider extension remains appended afterward.
        transform.rotate(
            Axis.XP.rotationDegrees(
                (pose.basePitchDegrees.toDouble() + ClientMouseHandler.freeCameraPitch).toFloat()
            )
        )
        return pose.applyExtension(transform, rotateOffsetHeight)
    }

    private fun applyVehiclePoseExtension(transform: Matrix4d, partialTicks: Float): Matrix4d {
        // A selected flight strategy is the sole attitude owner for this tick.
        if (flightStrategyOwnsAttitudeThisTick) return transform
        if (resolveVehiclePoseProvider() == null) return transform
        return getVehiclePoseSnapshot(partialTicks).applyExtension(transform, rotateOffsetHeight)
    }

    /** Override to select a provider per instance; the interface implementation is the default. */
    val terrainSupportPose = com.atsuishio.superbwarfare.entity.vehicle.utils.TerrainSupportPose(this)

    fun resolveVehiclePoseProvider(): VehiclePoseProvider? = (this as? VehiclePoseProvider)
        ?: if (computed().terrainCompatFitPlane) terrainSupportPose else null

    /** Current authority on the server and one immutable presentation sample on the client. */
    fun getVehiclePoseSnapshot(partialTicks: Float): VehiclePoseSnapshot =
        resolveChassisPresentation(partialTicks).pose

    /** Vanilla entity interpolation retained for null-provider and legacy-payload fallbacks. */
    fun getLegacyInterpolatedPosition(partialTicks: Float): Vec3 = Vec3(
        Mth.lerp(partialTicks.toDouble(), xo, x),
        Mth.lerp(partialTicks.toDouble(), yo, y),
        Mth.lerp(partialTicks.toDouble(), zo, z),
    )

    /**
     * Resolves the sole immutable chassis presentation sample for this render time. Packet receipt
     * never clears this cache, so consumers cannot split across old/new samples within a frame.
     */
    fun resolveChassisPresentation(partialTicks: Float): VehicleChassisPresentation {
        com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleCopies.frame(this)?.let {
            return it.chassis
        }
        return vehicleClientPresentationService.resolveChassisPresentation(partialTicks)
    }

    /**
     * Resolves one immutable actual-aim/chassis/attachment tuple. This is client presentation only:
     * it never writes entity axes, motion, collision, command, servo, scheduler, or ballistic state.
     */
    fun resolveAimPresentationFrame(
        controller: Entity?,
        partialTicks: Float,
    ): VehicleAimPresentationFrame? {
        return vehicleClientPresentationService.resolveAimPresentationFrame(controller, partialTicks)
    }

    fun resolveAimPresentationFrame(
        channel: VehicleAimChannel,
        seatIndex: Int,
        weaponIndex: Int,
        partialTicks: Float,
    ): VehicleAimPresentationFrame? {
        return vehicleClientPresentationService.resolveAimPresentationFrame(
            channel,
            seatIndex,
            weaponIndex,
            partialTicks,
        )
    }

    internal fun recordAimPresentationFrameTrace(
        key: AimPresentationCacheKey,
        frame: VehicleAimPresentationFrame?,
        partialBits: Int,
        epoch: Int,
        cacheHit: Boolean,
    ) {
        if (!NetworkTelemetry.isAimPresentationTraceEnabled()) return
        val aim = frame?.aim
        val continuity = frame?.continuityReprojected == true
        val reason = when {
            continuity -> "CONTINUITY"
            frame != null -> aimPresentationAdmissionReasons[key]?.name ?: "RESOLVED"
            else -> aimPresentationAdmissionReasons[key]?.name ?: "NULL"
        }
        NetworkTelemetry.recordAimPresentationTrace(
            AimPresentationTraceSample(
                stage = "RESOLVED",
                entityId = id,
                clientTick = tickCount,
                partialBits = partialBits,
                channel = key.channel.ordinal,
                seatIndex = key.seatIndex,
                weaponIndex = key.weaponIndex,
                sequence = aim?.upperSequence ?: -1,
                upperSequence = aim?.upperSequence ?: -1,
                serverTick = aim?.sourceServerTick?.takeIf { it.isFinite() }?.toLong() ?: -1L,
                sourceServerTick = aim?.sourceServerTick?.takeIf { it.isFinite() } ?: -1.0,
                epoch = epoch,
                reason = reason,
                cacheHit = cacheHit,
                direct = frame != null && !continuity && aim?.tailHeld != true && aim?.contextRebound != true,
                continuity = continuity,
                tailHeld = aim?.tailHeld == true,
                alpha = aim?.alpha ?: 1F,
                axesValid = frame != null,
                actualYaw = aim?.actualYaw ?: 0F,
                actualPitch = aim?.actualPitch ?: 0F,
                presentedYaw = frame?.presentedTurretYaw ?: 0F,
                presentedPitch = frame?.presentedTurretPitch ?: 0F,
            ),
        )
    }

    /**
     * Opt-in, transition-only low-cardinality diagnostics. At most eight channel/context keys are
     * retained per vehicle and no UUID, coordinates, weapon names, or payload values are recorded.
     */
    internal fun recordAimPresentationAdmission(
        key: AimPresentationCacheKey,
        reason: AimPresentationAdmissionReason,
    ) {
        if (!NetworkTelemetry.isEnabled() || aimPresentationAdmissionReasons[key] == reason) return
        if (key !in aimPresentationAdmissionReasons &&
            aimPresentationAdmissionReasons.size >= MAX_AIM_PRESENTATION_REASON_KEYS
        ) {
            aimPresentationAdmissionReasons.entries.iterator().run {
                if (hasNext()) {
                    next()
                    remove()
                }
            }
        }
        aimPresentationAdmissionReasons[key] = reason
        NetworkTelemetry.recordSystemWork(reason.metricName)
    }

    /**
     * Bounded same-epoch continuity for a transient aim-sample gap. The authoritative world ray
     * remains frozen while its presentation-only local axes, attachments, and muzzle are rebuilt
     * against the current coherent chassis sample. No mutable entity aim axis or client solver is
     * consulted, and lifecycle resets cannot cross the epoch guard.
     */
    fun reprojectAimPresentationContinuity(
        previous: VehicleAimPresentationFrame,
        partialTicks: Float,
    ): VehicleAimPresentationFrame? {
        // Local station axes already recompose through the accepted timeline. An old absolute
        // ray must not be held against a newly rolled hull when the exact context is unavailable.
        if (isPassengerStationLocalAim(previous.seatIndex, previous.selectedWeaponIndex)) return null
        if (!level().isClientSide || isWreck || resolveVehicleFlightStrategy() != null ||
            previous.vehicleUuid != uuid ||
            previous.presentationEpoch != getAimPresentationEpoch() ||
            getSelectedWeapon(previous.seatIndex) != previous.selectedWeaponIndex ||
            getGunName(previous.seatIndex, previous.selectedWeaponIndex) != previous.weaponName
        ) return null
        val gunData = getGunData(previous.seatIndex, previous.selectedWeaponIndex) ?: return null
        val directionFrameName = gunData.fireDirectionAttachment()?.takeIf { it.isNotBlank() }
            ?: if (previous.channel == VehicleAimChannel.TURRET) "Barrel" else "WeaponStationBarrel"
        // These child directions are authored offsets, not a second turret-axis command.
        if (vehicleAttachmentResolver.followsTurretPitch(directionFrameName)) return null

        val partial = partialTicks.coerceIn(0F, 1F)
        val presentedChassis = resolveChassisPresentation(partial)
        val ageTicks = tickCount - previous.capturedClientTick
        if (ageTicks !in 0..MAX_AIM_PRESENTATION_CONTINUITY_TICKS.toInt()) return null
        val authoritativeDirection = previous.authoritativeWorldDirection
            .takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.lengthSqr() > 1.0E-12 }
            ?.normalize() ?: return null
        val base = vehicleAttachmentResolver.resolveAimBaseTransform(this, presentedChassis) ?: return null
        var presentedTurretYaw: Float
        var presentedTurretPitch: Float
        var presentedStationYaw: Float? = null
        var presentedStationPitch: Float? = null
        if (previous.channel == VehicleAimChannel.TURRET) {
            val local = base.worldDirectionToLocal(authoritativeDirection)
            val angles = VehicleAimMath.directionAngles(local.x, local.y, local.z)
            presentedTurretYaw = angles.yaw
            presentedTurretPitch = angles.pitch
        } else {
            val turretWorldDirection = previous.attachments
                .direction("Barrel", Vec3(0.0, 0.0, 1.0))
                ?.takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.lengthSqr() > 1.0E-12 }
                ?.normalize() ?: return null
            val turretLocal = base.worldDirectionToLocal(turretWorldDirection)
            val turretAngles = VehicleAimMath.directionAngles(turretLocal.x, turretLocal.y, turretLocal.z)
            presentedTurretYaw = turretAngles.yaw
            presentedTurretPitch = turretAngles.pitch
            val stationLocal = base.worldDirectionToLocal(authoritativeDirection)
            val stationAngles = VehicleAimMath.directionAngles(stationLocal.x, stationLocal.y, stationLocal.z)
            presentedStationYaw = stationAngles.yaw
            presentedStationPitch = stationAngles.pitch
        }

        val presentedAttachments = vehicleAttachmentResolver.resolveAimPresentation(
            this,
            presentedChassis,
            presentedTurretYaw,
            presentedTurretPitch,
            presentedStationYaw,
            presentedStationPitch,
        ) ?: return null
        val positionFrameName = gunData.firePositionAttachment()?.takeIf { it.isNotBlank() }
            ?: directionFrameName
        val muzzlePosition = presentedAttachments.point(positionFrameName, Vec3.ZERO) ?: return null
        val presentedDirection = presentedAttachments
            .direction(directionFrameName, Vec3(0.0, 0.0, 1.0))
            ?.takeIf { it.lengthSqr() > 1.0E-12 }?.normalize() ?: return null
        if (presentedDirection.dot(authoritativeDirection) < AIM_PRESENTATION_DIRECTION_DOT_MINIMUM) return null
        val effectPosition = gunData.fireEffectAttachment()?.takeIf { it.isNotBlank() }
            ?.let { presentedAttachments.point(it, Vec3.ZERO) } ?: muzzlePosition
        val effectDirection = gunData.fireEffectDirectionAttachment()?.takeIf { it.isNotBlank() }
            ?.let { presentedAttachments.direction(it, Vec3(0.0, 0.0, 1.0)) }
            ?.takeIf { it.lengthSqr() > 1.0E-12 }?.normalize() ?: presentedDirection
        val frameReference = ShotFrameReference(
            previous.weaponName,
            gunData.firePositionSlot(),
            gunData.fireDirectionSlot(),
            gunData.firePositionAttachment(),
            gunData.fireDirectionAttachment(),
            gunData.fireEffectAttachment(),
            gunData.fireEffectDirectionAttachment(),
            presentedAttachments.sequence,
            presentedAttachments.serverTick,
            level().gameTime,
        )
        return previous.copy(
            aim = previous.aim.copy(
                presentationServerTick = presentedChassis.presentationServerTick,
                tailHeld = true,
                lockedDiagnostic = false,
            ),
            presentedChassis = presentedChassis,
            presentedTurretYaw = presentedTurretYaw,
            presentedTurretPitch = presentedTurretPitch,
            presentedStationYaw = presentedStationYaw,
            presentedStationPitch = presentedStationPitch,
            authoritativeWorldDirection = authoritativeDirection,
            attachments = presentedAttachments,
            muzzle = VehicleMuzzleFrame(
                previous.weaponName,
                muzzlePosition,
                authoritativeDirection,
                effectPosition,
                effectDirection,
                frameReference,
            ),
            continuityReprojected = true,
        )
    }

    /**
     * A client render of the local mounted operator owns the latest receipt clock for its
     * controller channel. A local passenger HMG also owns the passive TURRET parent that is
     * composed for that station. Remote observers must keep the delayed chassis-time pairing.
     */
    private fun isLocallyOwnedAimPresentation(
        channel: VehicleAimChannel,
        seatIndex: Int,
    ): Boolean {
        if (!level().isClientSide) return false
        val player = Minecraft.getInstance().player ?: return false
        if (player.level() !== level() || player.vehicle !== this) return false
        val localSeat = getSeatIndex(player)
        if (localSeat == seatIndex) return true
        return channel == VehicleAimChannel.TURRET &&
            hasPassengerWeaponStation() &&
            localSeat == passengerWeaponStationControllerIndex &&
            seatIndex == turretControllerIndex
    }

    internal fun buildAimPresentationFrame(
        key: AimPresentationCacheKey,
        partialTicks: Float,
    ): VehicleAimPresentationFrame? {
        val (channel, seatIndex, weaponIndex) = key
        fun reject(reason: AimPresentationAdmissionReason): VehicleAimPresentationFrame? {
            recordAimPresentationAdmission(key, reason)
            return null
        }
        val weaponName = getGunName(seatIndex, weaponIndex)
            ?: return reject(AimPresentationAdmissionReason.REJECT_WEAPON)
        val gunData = getGunData(seatIndex, weaponIndex)
            ?: return reject(AimPresentationAdmissionReason.REJECT_WEAPON)
        val hullStation = channel == VehicleAimChannel.PASSENGER_WEAPON && isHullParentedPassengerWeaponStation()
        val stationLocal = hullStation && isPassengerStationLocalAim(seatIndex, weaponIndex)
        val providerBacked = resolveVehiclePoseProvider() != null && !stationLocal
        val presentedChassis = resolveChassisPresentation(partialTicks)
        val local = isLocallyOwnedAimPresentation(channel, seatIndex)
        val exactAim = when {
            !providerBacked || local -> vehicleAimController.resolveLatestClientPresentation(
                channel,
                seatIndex,
                weaponIndex,
                partialTicks,
                presentedChassis.presentationServerTick,
            )

            else -> vehicleAimController.resolveClientPresentationAt(
                channel,
                seatIndex,
                weaponIndex,
                presentedChassis.presentationServerTick,
            )
        }
        val aim = exactAim ?: run {
            val physical = when {
                !providerBacked || local -> vehicleAimController.resolveLatestClientChannelPresentation(
                    channel,
                    partialTicks,
                    presentedChassis.presentationServerTick,
                )

                else -> vehicleAimController.resolveClientChannelPresentationAt(
                    channel,
                    presentedChassis.presentationServerTick,
                )
            } ?: return reject(AimPresentationAdmissionReason.REJECT_AIM)
            if (physical.seatIndex != seatIndex ||
                !sharesClientPhysicalAimSemantics(
                    seatIndex,
                    physical.selectedWeaponIndex,
                    weaponIndex,
                )
            ) return reject(AimPresentationAdmissionReason.REJECT_AIM)
            // This is the same physical channel sampled under the independently synchronized
            // identity. Retarget only the presentation context and fail the lock diagnostic
            // closed until the exact replacement baseline arrives.
            physical.copy(
                selectedWeaponIndex = weaponIndex,
                contextRebound = true,
                lockedDiagnostic = false,
            )
        }
        val expectedMode = getVehicleAimPresentationMode(seatIndex, weaponIndex)
        if (aim.mode != expectedMode) return reject(AimPresentationAdmissionReason.REJECT_MODE)
        val heldAim = aim.tailHeld || aim.contextRebound
        // Provider-backed vehicles pair aim with the exact authoritative chassis tick. Vehicles
        // that deliberately have no pose provider (for example wheeled BVP ground vehicles) keep
        // their established vanilla chassis clock; capture that immutable render sample once and
        // compose the authoritative aim onto it instead of falling back to mutable muzzle fields.
        // A held/rebound current-context sample is an event-bound hull-local physical axis under
        // ordinary tank aiming, so it is deliberately composed with the current coherent chassis.
        var sourceChassis = if (providerBacked && !local && !heldAim) {
            vehicleChassisPresentationTimeline.resolveAuthoritativeAt(aim.sourceServerTick)
                ?: return reject(AimPresentationAdmissionReason.REJECT_CHASSIS)
        } else {
            presentedChassis
        }

        val sourceTurretAim = when (channel) {
            VehicleAimChannel.TURRET -> aim
            VehicleAimChannel.PASSENGER_WEAPON -> if (hullStation) aim else if (providerBacked) {
                if (local) {
                    vehicleAimController.resolveLatestClientChannelPresentation(
                        VehicleAimChannel.TURRET,
                        partialTicks,
                        presentedChassis.presentationServerTick,
                    )
                } else {
                    vehicleAimController.resolveClientChannelPresentationAt(
                        VehicleAimChannel.TURRET,
                        aim.sourceServerTick,
                    )
                } ?: if (heldAim) {
                    vehicleAimController.resolveLatestClientChannelPresentation(
                        VehicleAimChannel.TURRET,
                        partialTicks,
                        presentedChassis.presentationServerTick,
                    )
                } else null
            } else {
                vehicleAimController.resolveLatestClientChannelPresentation(
                    VehicleAimChannel.TURRET,
                    partialTicks,
                    presentedChassis.presentationServerTick,
                )
            }
        } ?: return reject(AimPresentationAdmissionReason.REJECT_PARENT)
        if (channel == VehicleAimChannel.PASSENGER_WEAPON && !hullStation &&
            !heldAim && !sourceTurretAim.tailHeld && !sourceTurretAim.contextRebound &&
            kotlin.math.abs(sourceTurretAim.sourceServerTick - aim.sourceServerTick) >
            AIM_PRESENTATION_TIME_EPSILON
        ) return reject(AimPresentationAdmissionReason.REJECT_PARENT_TIME)
        val eventBoundHeld = heldAim || sourceTurretAim.tailHeld || sourceTurretAim.contextRebound
        if (providerBacked && eventBoundHeld) sourceChassis = presentedChassis
        val sourceStationYaw = aim.actualYaw.takeIf { channel == VehicleAimChannel.PASSENGER_WEAPON }
        val sourceStationPitch = aim.actualPitch.takeIf { channel == VehicleAimChannel.PASSENGER_WEAPON }
        val sourceTurretYaw = if (hullStation) 0F else sourceTurretAim.actualYaw
        val sourceTurretPitch = if (hullStation) 0F else sourceTurretAim.actualPitch
        val sourceAttachments = vehicleAttachmentResolver.resolveAimPresentation(
            this,
            sourceChassis,
            sourceTurretYaw,
            sourceTurretPitch,
            sourceStationYaw,
            sourceStationPitch,
        ) ?: return reject(AimPresentationAdmissionReason.REJECT_ATTACHMENTS)
        val directionFrameName = gunData.fireDirectionAttachment()?.takeIf { it.isNotBlank() }
            ?: if (channel == VehicleAimChannel.TURRET) "Barrel" else "WeaponStationBarrel"
        // The historical chassis remains the authority/provenance check for an exact sample, but
        // both exact and held renders use one current presentation basis. This prevents the
        // reason transition itself from changing the rendered clock while still advancing with
        // every admitted actual-axis sample.
        val currentBasisAttachments = if (providerBacked && sourceChassis !== presentedChassis) {
            vehicleAttachmentResolver.resolveAimPresentation(
                this,
                presentedChassis,
                sourceTurretYaw,
                sourceTurretPitch,
                sourceStationYaw,
                sourceStationPitch,
            ) ?: return reject(AimPresentationAdmissionReason.REJECT_ATTACHMENTS)
        } else {
            sourceAttachments
        }
        val authoritativeDirection = (if (providerBacked) {
            currentBasisAttachments.direction(directionFrameName, Vec3(0.0, 0.0, 1.0))
        } else {
            sourceAttachments.direction(directionFrameName, Vec3(0.0, 0.0, 1.0))
        })
            ?.takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.lengthSqr() > 1.0E-12 }
            ?.normalize() ?: return reject(AimPresentationAdmissionReason.REJECT_DIRECTION)

        var presentedTurretYaw = sourceTurretYaw
        var presentedTurretPitch = sourceTurretPitch
        var presentedStationYaw = sourceStationYaw
        var presentedStationPitch = sourceStationPitch
        val source = if (!providerBacked) {
            VehicleAimPresentationFrame.Source.AUTHORITATIVE_LEGACY_CHASSIS
        } else {
            val base = vehicleAttachmentResolver.resolveAimBaseTransform(this, presentedChassis)
                ?: return reject(AimPresentationAdmissionReason.REJECT_ATTACHMENTS)
            if (channel == VehicleAimChannel.TURRET) {
                // A fitted muzzle may have an authored local direction. Its current-chassis
                // graph already contains the accepted axes; inferring them from that child ray
                // would apply the authored direction twice.
                if (!vehicleAttachmentResolver.followsTurretPitch(directionFrameName)) {
                    val localDirection = base.worldDirectionToLocal(authoritativeDirection)
                    val angles = VehicleAimMath.directionAngles(localDirection.x, localDirection.y, localDirection.z)
                    presentedTurretYaw = angles.yaw
                    presentedTurretPitch = angles.pitch
                }
            } else if (!hullStation) {
                val turretWorldDirection = currentBasisAttachments
                    .direction("Barrel", Vec3(0.0, 0.0, 1.0))
                    ?.normalize() ?: return reject(AimPresentationAdmissionReason.REJECT_DIRECTION)
                val turretLocal = base.worldDirectionToLocal(turretWorldDirection)
                val turretAngles = VehicleAimMath.directionAngles(turretLocal.x, turretLocal.y, turretLocal.z)
                presentedTurretYaw = turretAngles.yaw
                presentedTurretPitch = turretAngles.pitch
                val stationLocal = base.worldDirectionToLocal(authoritativeDirection)
                val stationAngles = VehicleAimMath.directionAngles(stationLocal.x, stationLocal.y, stationLocal.z)
                presentedStationYaw = stationAngles.yaw
                presentedStationPitch = stationAngles.pitch
            }
            // Hull-mounted truck-bed weapons have no parent tank barrel. Their accepted local
            // station axes were already composed with the current chassis above; requiring a
            // nonexistent Barrel frame here stranded their rider/camera on the cold-start pose.
            if (local) VehicleAimPresentationFrame.Source.AUTHORITATIVE_LOCAL_REPROJECTED
            else VehicleAimPresentationFrame.Source.AUTHORITATIVE_REMOTE
        }

        val presentedAttachments = if (sourceChassis === presentedChassis &&
            presentedTurretYaw == sourceTurretYaw &&
            presentedTurretPitch == sourceTurretPitch &&
            presentedStationYaw == sourceStationYaw && presentedStationPitch == sourceStationPitch
        ) {
            sourceAttachments
        } else {
            vehicleAttachmentResolver.resolveAimPresentation(
                this,
                presentedChassis,
                presentedTurretYaw,
                presentedTurretPitch,
                presentedStationYaw,
                presentedStationPitch,
            ) ?: return reject(AimPresentationAdmissionReason.REJECT_ATTACHMENTS)
        }
        val positionFrameName = gunData.firePositionAttachment()?.takeIf { it.isNotBlank() }
            ?: directionFrameName
        val muzzlePosition = presentedAttachments.point(positionFrameName, Vec3.ZERO)
            ?: return reject(AimPresentationAdmissionReason.REJECT_MUZZLE)
        val presentedDirection = presentedAttachments
            .direction(directionFrameName, Vec3(0.0, 0.0, 1.0))
            ?.takeIf { it.lengthSqr() > 1.0E-12 }?.normalize()
            ?: return reject(AimPresentationAdmissionReason.REJECT_DIRECTION)
        if (presentedDirection.dot(authoritativeDirection) < AIM_PRESENTATION_DIRECTION_DOT_MINIMUM) {
            return reject(AimPresentationAdmissionReason.REJECT_DIRECTION)
        }
        val effectPosition = gunData.fireEffectAttachment()?.takeIf { it.isNotBlank() }
            ?.let { presentedAttachments.point(it, Vec3.ZERO) } ?: muzzlePosition
        val effectDirection = gunData.fireEffectDirectionAttachment()?.takeIf { it.isNotBlank() }
            ?.let { presentedAttachments.direction(it, Vec3(0.0, 0.0, 1.0)) }
            ?.takeIf { it.lengthSqr() > 1.0E-12 }?.normalize() ?: presentedDirection
        val frameReference = ShotFrameReference(
            weaponName,
            gunData.firePositionSlot(),
            gunData.fireDirectionSlot(),
            gunData.firePositionAttachment(),
            gunData.fireDirectionAttachment(),
            gunData.fireEffectAttachment(),
            gunData.fireEffectDirectionAttachment(),
            presentedAttachments.sequence,
            presentedAttachments.serverTick,
            level().gameTime,
        )
        val muzzle = VehicleMuzzleFrame(
            weaponName,
            muzzlePosition,
            authoritativeDirection,
            effectPosition,
            effectDirection,
            frameReference,
        )
        val admittedAim = if (eventBoundHeld) aim.copy(lockedDiagnostic = false) else aim
        recordAimPresentationAdmission(
            key,
            when {
                aim.contextRebound || sourceTurretAim.contextRebound ->
                    AimPresentationAdmissionReason.ADMIT_REBOUND
                (aim.tailHeld || sourceTurretAim.tailHeld) && aim.alpha < 1F ->
                    AimPresentationAdmissionReason.ADMIT_BLEND
                aim.tailHeld || sourceTurretAim.tailHeld ->
                    AimPresentationAdmissionReason.ADMIT_HELD
                else -> AimPresentationAdmissionReason.ADMIT_EXACT
            },
        )
        return VehicleAimPresentationFrame(
            uuid,
            vehicleAimController.clientPresentationEpoch,
            channel,
            seatIndex,
            weaponIndex,
            weaponName,
            source,
            admittedAim,
            sourceChassis,
            presentedChassis,
            presentedTurretYaw,
            presentedTurretPitch,
            presentedStationYaw,
            presentedStationPitch,
            authoritativeDirection,
            presentedAttachments,
            muzzle,
            tickCount,
            continuityReprojected = providerBacked && eventBoundHeld,
        )
    }

    internal fun sharesClientPhysicalAimSemantics(
        seatIndex: Int,
        previousWeaponIndex: Int,
        selectedWeaponIndex: Int,
    ): Boolean {
        if (previousWeaponIndex == selectedWeaponIndex) return true
        val previous = resolveVehicleAimProfile(seatIndex, previousWeaponIndex) ?: return false
        val selected = resolveVehicleAimProfile(seatIndex, selectedWeaponIndex) ?: return false
        return previous.channel == selected.channel &&
            previous.directionFrame == selected.directionFrame &&
            previous.yawRateDegreesPerSecond == selected.yawRateDegreesPerSecond &&
            previous.pitchRateDegreesPerSecond == selected.pitchRateDegreesPerSecond &&
            previous.minYaw == selected.minYaw && previous.maxYaw == selected.maxYaw &&
            previous.minPitch == selected.minPitch && previous.maxPitch == selected.maxPitch &&
            previous.softYawLimitDegrees == selected.softYawLimitDegrees &&
            previous.softPitchLimitDegrees == selected.softPitchLimitDegrees &&
            previous.defaultMode == selected.defaultMode &&
            previous.snapToNeutralWhenInactive == selected.snapToNeutralWhenInactive &&
            previous.neutralYaw == selected.neutralYaw && previous.neutralPitch == selected.neutralPitch
    }

    /**
     * Completes every render-visible compatible identity rebind before exposing the epoch.
     * Consumers may capture this value and resolve TURRET/PASSENGER frames without resolution
     * advancing the epoch underneath their validation.
     */
    fun getAimPresentationEpoch(): Int {
        refreshClientAimPresentationFromSyncedData()
        prepareClientAimPresentationContext(VehicleAimChannel.TURRET, turretControllerIndex)
        if (hasPassengerWeaponStation()) {
            prepareClientAimPresentationContext(
                VehicleAimChannel.PASSENGER_WEAPON,
                passengerWeaponStationControllerIndex,
            )
        }
        return vehicleAimController.clientPresentationEpoch
    }

    private fun prepareClientAimPresentationContext(
        channel: VehicleAimChannel,
        seatIndex: Int,
    ) {
        if (!level().isClientSide || seatIndex < 0) return
        val weaponIndex = getSelectedWeapon(seatIndex)
        if (weaponIndex < 0) return
        val profile = resolveVehicleAimProfile(seatIndex, weaponIndex)
            ?.takeIf { it.channel == channel } ?: return
        val mode = vehicleAimController.snapshot(seatIndex, weaponIndex)?.mode
            ?: profile.defaultMode
        vehicleAimController.rebindCompatibleClientPresentation(
            channel,
            seatIndex,
            weaponIndex,
            mode,
        ) { previousWeaponIndex ->
            sharesClientPhysicalAimSemantics(
                seatIndex,
                previousWeaponIndex,
                weaponIndex,
            )
        }
    }

    /**
     * One client presentation anchor for hull rendering, cameras, riders, attachments, weapons,
     * and debug transforms. Server gameplay always uses the live authoritative entity position.
     */
    fun getResolvedChassisPosition(partialTicks: Float): Vec3 =
        resolveChassisPresentation(partialTicks).anchor

    /** One provider-backed heading sample for hull and every dependent transform consumer. */
    fun getResolvedChassisYaw(partialTicks: Float): Float =
        resolveChassisPresentation(partialTicks).chassisYawDegrees

    /** Translation from the vanilla renderer entry position to the sequenced chassis anchor. */
    fun getResolvedChassisRenderOffset(partialTicks: Float): Vec3 =
        getResolvedChassisPosition(partialTicks).subtract(getLegacyInterpolatedPosition(partialTicks))

    fun getResolvedChassisWorldY(partialTicks: Float): Double {
        val sample = resolveChassisPresentation(partialTicks)
        if (flightStrategyOwnsAttitudeThisTick || resolveVehiclePoseProvider() == null) return sample.anchor.y
        return sample.resolvedWorldY
    }

    fun getVehiclePoseReceiptTick(): Int = vehiclePoseClientUpdateTick

    fun getVehiclePositionTarget(): Vec3 = Vec3(xO, yO, zO)

    fun getVehiclePositionInterpolationSteps(): Int = interpolationSteps

    /** Defensive immutable view of the complete chassis local-to-world matrix. */
    fun getVehicleTransformSnapshot(partialTicks: Float): VehicleTransformSnapshot {
        val pose = getVehiclePoseSnapshot(partialTicks)
        return VehicleTransformSnapshot("Vehicle", pose.sequence, pose.serverTick, getVehicleTransform(partialTicks))
    }

    fun vehicleLocalToWorld(localPoint: Vec3, partialTicks: Float): Vec3 =
        getVehicleTransformSnapshot(partialTicks).localToWorld(localPoint)

    fun worldToVehicleLocal(worldPoint: Vec3, partialTicks: Float): Vec3 =
        getVehicleTransformSnapshot(partialTicks).worldToLocal(worldPoint)

    fun worldDirectionToVehicleLocal(worldDirection: Vec3, partialTicks: Float): Vec3 =
        getVehicleTransformSnapshot(partialTicks).worldDirectionToLocal(worldDirection)

    /** Exact pre-turret frame, including authored turretCustomPitch and provider/base pose. */
    fun getTurretBaseTransformSnapshot(partialTicks: Float): VehicleTransformSnapshot {
        val pose = getVehiclePoseSnapshot(partialTicks)
        return VehicleTransformSnapshot(
            "TurretBase",
            pose.sequence,
            pose.serverTick,
            getVehicleTransformWithCustomPitch(partialTicks),
        )
    }

    fun worldDirectionToTurretBaseLocal(worldDirection: Vec3, partialTicks: Float): Vec3 =
        getTurretBaseTransformSnapshot(partialTicks).worldDirectionToLocal(worldDirection)

    /**
     * Immutable named-frame snapshot. The default set mirrors the legacy transform registry;
     * opt-in providers may atomically augment or replace it without changing old callers.
     */
    fun getVehicleAttachmentSnapshot(partialTicks: Float): VehicleAttachmentSnapshot =
        vehicleAttachmentResolver.resolve(this, partialTicks)

    private fun usesCoherentCameraAttachmentTimeline(controller: Entity): Boolean {
        if (!level().isClientSide || (resolveVehicleFlightStrategy() != null &&
            !isPassengerStationLocalAimController(controller))) return false
        if (resolveVehiclePoseProvider() != null) return true
        val player = controller as? Player ?: return false
        val seatIndex = getSeatIndex(player)
        if (seatIndex < 0) return false
        val weaponIndex = getSelectedWeapon(seatIndex)
        return weaponIndex >= 0 && resolveVehicleAimProfile(seatIndex, weaponIndex) != null
    }

    /**
     * Camera-safe attachment ownership. Provider-backed clients and explicitly profiled
     * providerless Player channels use the immutable actual frame. Before that frame exists,
     * only chassis-rooted attachments are exposed. Native/unprofiled, server, and flight paths
     * retain the established legacy snapshot.
     */
    fun resolveCameraAttachmentSnapshot(
        controller: Entity,
        partialTicks: Float,
    ): VehicleAttachmentSnapshot? {
        if (!usesCoherentCameraAttachmentTimeline(controller)) {
            return getVehicleAttachmentSnapshot(partialTicks)
        }
        return resolveAimPresentationFrame(controller, partialTicks)
            ?.takeIf { it.presentationEpoch == getAimPresentationEpoch() }
            ?.attachments
            ?: vehicleAttachmentResolver.resolveChassisPresentation(
                this,
                resolveChassisPresentation(partialTicks),
            )
    }

    /**
     * Chassis-only camera fallback for every coherent camera-timeline context. Providerless
     * profiled ground channels use their existing finite vanilla chassis presentation; no pose
     * provider or solver is introduced. The snapshot exposes no aim-bearing native frame.
     */
    fun resolveChassisCameraAttachmentSnapshot(
        controller: Entity,
        partialTicks: Float,
    ): VehicleAttachmentSnapshot? {
        if (!usesCoherentCameraAttachmentTimeline(controller)) return null
        return vehicleAttachmentResolver.resolveChassisPresentation(
            this,
            resolveChassisPresentation(partialTicks),
        )
    }

    fun hasTurret() = this.turretPos != null

    val turretPos: Vec3?
        get() = computed().turretPos

    open val turretControllerIndex: Int
        get() = computed().turretControllerIndex

    val turretTurnXSpeed: Float
        /**
         * @return 炮塔最大俯仰速度
         */
        get() = computed().turretTurnSpeed.x * 1.15F

    val turretTurnYSpeed: Float
        /**
         * @return 炮塔最大偏航速度
         */
        get() = computed().turretTurnSpeed.y * 1.15F

    val turretMinYaw: Float
        /**
         * @return 炮塔最小偏航
         */
        get() = computed().turretYawRange.x

    val turretMaxYaw: Float
        /**
         * @return 炮塔最大偏航
         */
        get() = computed().turretYawRange.y

    val turretMinPitch: Float
        /**
         * @return 炮塔最小俯角
         */
        get() = computed().turretPitchRange.x

    val turretMaxPitch: Float
        /**
         * @return 炮塔最大仰角
         */
        get() = computed().turretPitchRange.y

    val barrelPosition: Vec3?
        get() = computed().barrelPos

    fun hasPassengerWeaponStation(): Boolean {
        val data = computed()
        val binding = data.passengerWeaponStationBinding
        if (data.passengerWeaponStationPos == null) return false
        if (binding == null) return true
        if (!binding.hasTypedIdentity()) return false
        if (binding.parent != PassengerWeaponStationParent.HULL) return true
        val camera = binding.cameraAttachment?.takeIf { it.isNotBlank() } ?: return false
        val muzzle = binding.muzzleAttachment?.takeIf { it.isNotBlank() } ?: return false
        val cameraInfo = data.attachments[camera] ?: return false
        val muzzleInfo = data.attachments[muzzle] ?: return false
        // Marker frames must remain descendants of the authored station articulation. A
        // vehicle-root fallback would silently detach a truck-bed weapon from its yaw/pitch rig.
        val cameraParented = cameraInfo.parent == "WeaponStation" || cameraInfo.parent == "WeaponStationBarrel"
        val muzzleParented = muzzleInfo.parent == "WeaponStation" || muzzleInfo.parent == "WeaponStationBarrel"
        return cameraParented && muzzleParented
    }

    /** The authored parent of the active station; legacy data remains turret-relative. */
    val passengerWeaponStationParent: PassengerWeaponStationParent
        get() = if (isHullParentedPassengerWeaponStation()) {
            PassengerWeaponStationParent.HULL
        } else {
            PassengerWeaponStationParent.TURRET
        }

    /** Exact authored weapon identity for a typed station, or null for legacy data. */
    val passengerWeaponStationWeaponId: String?
        get() = computed().passengerWeaponStationBinding
            ?.takeIf { it.hasTypedIdentity() && hasPassengerWeaponStation() }
            ?.weaponId

    val passengerWeaponStationWeaponKind: PassengerWeaponStationWeaponKind?
        get() = computed().passengerWeaponStationBinding
            ?.takeIf { it.hasTypedIdentity() && hasPassengerWeaponStation() }
            ?.weaponKind

    /** HULL parent is enabled only after all required typed identity/marker checks pass. */
    fun isHullParentedPassengerWeaponStation(): Boolean {
        val binding = computed().passengerWeaponStationBinding ?: return false
        return hasPassengerWeaponStation() && binding.parent == PassengerWeaponStationParent.HULL
    }

    val passengerWeaponStationBaseYawDegrees: Float
        get() = computed().passengerWeaponStationBinding
            ?.takeIf { it.hasTypedIdentity() && isHullParentedPassengerWeaponStation() }
            ?.baseYawDegrees ?: 0F

    /** Exact opt-in for a non-pilot, hull-local station; shared by camera and input consumers. */
    fun isPassengerStationLocalAim(seatIndex: Int, weaponIndex: Int): Boolean {
        if (seatIndex <= 0 || !isHullParentedPassengerWeaponStation() ||
            !isPassengerWeaponStationWeapon(seatIndex, weaponIndex)
        ) return false
        val profile = resolveVehicleAimProfile(seatIndex, weaponIndex) ?: return false
        return profile.channel == VehicleAimChannel.PASSENGER_WEAPON &&
                profile.directionFrame == VehicleAimDirectionFrame.PASSENGER_STATION_LOCAL
    }

    fun isPassengerStationLocalAimController(controller: Entity?): Boolean {
        if (controller == null || controller.vehicle !== this || isRemoved || isWreck ||
            !controller.isAlive || controller.level() !== level()
        ) return false
        val seat = getSeatIndex(controller)
        return seat >= 0 && isPassengerStationLocalAim(seat, getSelectedWeapon(seat))
    }

    fun getPassengerStationAimBase(partialTicks: Float): Matrix4d? {
        if (!isHullParentedPassengerWeaponStation()) return null
        val pivot = passengerWeaponStationPosition ?: return null
        return VehiclePassengerStationFrame.base(getVehicleTransform(partialTicks), pivot,
            passengerWeaponStationBaseYawDegrees)
    }

    /**
     * Exact station admission for a seat/weapon pair. Legacy stations retain the one historical
     * PassengerMachineGun id; typed stations never infer a role from display text or position.
     */
    fun isPassengerWeaponStationWeapon(seatIndex: Int, weaponIndex: Int): Boolean {
        if (!hasPassengerWeaponStation() || seatIndex != passengerWeaponStationControllerIndex) return false
        val weaponName = getGunName(seatIndex, weaponIndex) ?: return false
        val binding = computed().passengerWeaponStationBinding
        return if (binding == null) {
            weaponName == "PassengerMachineGun"
        } else {
            binding.containsWeapon(weaponName)
        }
    }

    fun isPassengerWeaponStationHeavyMachineGun(seatIndex: Int, weaponIndex: Int): Boolean {
        if (!isPassengerWeaponStationWeapon(seatIndex, weaponIndex)) return false
        val binding = computed().passengerWeaponStationBinding
        return binding == null || binding.weaponKind == PassengerWeaponStationWeaponKind.HEAVY_MACHINE_GUN
    }

    /**
     * Typed heat policy for HMGs mounted on ground tanks.  The station binding (or the one
     * canonical legacy PassengerMachineGun identity) is the only role source; localized labels,
     * vehicle ids, and caliber/name heuristics are intentionally not consulted.
     */
    fun isTankMountedHeavyMachineGun(seatIndex: Int, weaponIndex: Int): Boolean {
        if (vehicleType != VehicleType.TANK) return false
        return isPassengerWeaponStationHeavyMachineGun(seatIndex, weaponIndex)
    }

    /** Resolves the same policy from a GunData copy used by the firing/tick paths. */
    fun isTankMountedHeavyMachineGun(data: GunData): Boolean {
        if (vehicleType != VehicleType.TANK) return false
        val identity = data.vehicleWeaponIdentity?.takeIf { it.isNotBlank() } ?: return false
        val seats = computed().seats()
        for (seatIndex in seats.indices) {
            val weaponIndex = seats[seatIndex].weapons().indexOf(identity)
            if (weaponIndex >= 0 && isTankMountedHeavyMachineGun(seatIndex, weaponIndex)) {
                return true
            }
        }
        return false
    }

    val passengerWeaponStationPosition: Vec3?
        get() = computed().passengerWeaponStationPos

    val passengerWeaponStationBarrelPosition: Vec3?
        get() = computed().passengerWeaponStationBarrelPos

    val passengerWeaponStationControllerIndex: Int
        get() = computed().passengerWeaponStationControllerIndex

    val passengerWeaponYSpeed: Float
        /**
         * @return 乘客武器站最大偏航速度
         */
        get() = computed().passengerWeaponStationTurnSpeed.y * 1.15F

    val passengerWeaponXSpeed: Float
        /**
         * @return 乘客武器站最大俯仰速度
         */
        get() = computed().passengerWeaponStationTurnSpeed.x * 1.15F

    val passengerWeaponMinPitch: Float
        /**
         * @return 乘客武器站最小仰角
         */
        get() = computed().passengerWeaponStationPitchRange.x

    val passengerWeaponMaxPitch: Float
        /**
         * @return 乘客武器站最大仰角
         */
        get() = computed().passengerWeaponStationPitchRange.y

    val passengerWeaponMinYaw: Float
        /**
         * @return 炮塔最小偏航
         */
        get() = computed().passengerWeaponStationYawRange.x

    val passengerWeaponMaxYaw: Float
        /**
         * @return 炮塔最大偏航
         */
        get() = computed().passengerWeaponStationYawRange.y


    val turretCustomPitch: Float
        /**
         * @return 炮塔自定义俯仰
         */
        get() = computed().turretCustomPitch


    fun getTurretTransform(partialTicks: Float): Matrix4d {
        return VehicleVecUtils.getTurretTransform(this, partialTicks)
    }

    fun getTurretVector(pPartialTicks: Float): Vec3 {
        return VehicleVecUtils.getTurretVector(this, pPartialTicks)
    }

    fun getBarrelTransform(partialTicks: Float): Matrix4d {
        return VehicleVecUtils.getBarrelTransform(this, partialTicks)
    }

    fun getGunTransform(partialTicks: Float): Matrix4d {
        return VehicleVecUtils.getGunTransform(this, partialTicks)
    }

    fun getPassengerWeaponStationBarrelTransform(partialTicks: Float): Matrix4d {
        return VehicleVecUtils.getPassengerWeaponStationBarrelTransform(this, partialTicks)
    }

    fun getPassengerWeaponStationVector(partialTicks: Float): Vec3 {
        return VehicleVecUtils.getPassengerWeaponStationVector(this, partialTicks)
    }

    fun transformPosition(transform: Matrix4d, x: Double, y: Double, z: Double): Vector4d {
        return transform.transform(Vector4d(x, y, z, 1.0))
    }

    private fun updateVehiclePoseLifecycle() {
        if (level().isClientSide) {
            consumeSyncedVehiclePose()
            if (!flightStrategyOwnsAttitudeThisTick && resolveVehiclePoseProvider() != null &&
                isControlledByLocalInstance
            ) {
                vehicleChassisPresentationTimeline.recordLocalPrediction(
                    level().gameTime,
                    position(),
                    yRot,
                )
                vehicleChassisPresentationTimeline.advanceLocal()
                invalidateChassisPresentationCache()
            }
            return
        }

        val sampled = VehiclePoseComposition.sample(
            vehiclePoseCurrent, resolveVehiclePoseProvider(), flightStrategyOwnsAttitudeThisTick,
            xRot, roll, position(), yRot,
        ) ?: return

        vehiclePoseSequence += 1
        vehiclePosePrevious = vehiclePoseCurrent
        vehiclePoseCurrent = sampled.withAuthority(vehiclePoseSequence, level().gameTime)
        vehiclePosePayload = vehiclePoseCurrent.encode()
        publishTextSnapshot(VEHICLE_POSE_SNAPSHOT, "vehicle_pose_snapshot", vehiclePosePayload)
        NetworkTelemetry.recordChassisTrace(
            entityId = id,
            serverTick = vehiclePoseCurrent.serverTick,
            sequence = vehiclePoseCurrent.sequence,
            requestedMovementY = lastRequestedMovement.y,
            resolvedMovementY = lastResolvedMovement.y,
            collisionStepCandidateDeltaY = lastCollisionStepCandidateDeltaY,
            collisionStepAppliedDeltaY = lastCollisionStepAppliedDeltaY,
            anchor = position(),
            chassisYawDegrees = vehiclePoseCurrent.chassisYawDegrees,
            groundBias = vehiclePoseCurrent.groundBias,
            collisionStepOffset = vehiclePoseCurrent.collisionStepOffset,
            collisionStepVelocity = vehiclePoseCurrent.collisionStepVelocity,
            resolvedChassisWorldY = vehiclePoseCurrent.resolvedChassisWorldY() ?: y,
        )
        invalidateAttachmentSnapshot()
    }

    private fun consumeSyncedVehiclePose() {
        if (!level().isClientSide) return
        val payload = entityData.get(VEHICLE_POSE_SNAPSHOT)
        if (payload == vehiclePosePayload) return
        vehiclePosePayload = payload

        val incoming = VehiclePoseSnapshot.decode(payload) ?: return
        var recoveryBaseline = false
        if (vehiclePoseCurrent.sequence == 0 && vehiclePosePrevious.sequence == 0) {
            vehiclePosePrevious = incoming
            vehiclePoseCurrent = incoming
            vehiclePoseAbsolutePending = null
            recoveryBaseline = true
        } else {
            if (!incoming.isNewerThan(vehiclePoseCurrent)) return
            val absolutePending = vehiclePoseAbsolutePending
            val followsAbsolute = absolutePending != null &&
                incoming.sequence != absolutePending.afterSequence &&
                incoming.sequence - absolutePending.afterSequence > 0
            val genuineDiscontinuity = followsAbsolute && (
                absolutePending!!.hardDiscontinuity ||
                    VehicleChassisPresentationTimeline.isSpatialDiscontinuity(
                        vehiclePoseCurrent,
                        incoming,
                    )
                )
            if (genuineDiscontinuity) {
                vehicleChassisPresentationTimeline.clear()
                vehicleAimController.clearClientPresentation()
                vehiclePosePrevious = incoming
                vehiclePoseCurrent = incoming
                recoveryBaseline = true
            } else {
                vehiclePosePrevious = vehiclePoseCurrent
                vehiclePoseCurrent = incoming
            }
            if (followsAbsolute) vehiclePoseAbsolutePending = null
        }
        vehiclePoseClientUpdateTick = tickCount
        vehicleChassisPresentationTimeline.offer(incoming, recoveryBaseline)
        // Receipt deliberately does not invalidate presentation/attachment caches. Consumers that
        // already resolved this frame remain on one immutable sample; the next client tick advances.
    }

    private fun invalidateAttachmentSnapshot() {
        vehicleAttachmentResolver.invalidate()
    }

    private fun updateClientAimPresentationControllerEpoch() {
        val turretController = getNthEntity(turretControllerIndex)?.uuid
        val stationController = if (hasPassengerWeaponStation()) {
            getNthEntity(passengerWeaponStationControllerIndex)?.uuid
        } else null
        if (aimPresentationControllerEpochInitialized) {
            val changedChannels = EnumSet.noneOf(VehicleAimChannel::class.java)
            if (turretController != aimPresentationTurretControllerUuid) {
                changedChannels.add(VehicleAimChannel.TURRET)
            }
            if (stationController != aimPresentationStationControllerUuid) {
                changedChannels.add(VehicleAimChannel.PASSENGER_WEAPON)
            }
            if (changedChannels.isNotEmpty()) {
                vehicleAimController.beginClientControllerEpoch(changedChannels)
                aimPresentationCache.clear()
                aimPresentationCacheTick = Int.MIN_VALUE
            }
        }
        aimPresentationControllerEpochInitialized = true
        aimPresentationTurretControllerUuid = turretController
        aimPresentationStationControllerUuid = stationController
    }

    /**
     * Entity-data accessors can become visible to rendering before this entity's next client tick.
     * Consume the current complete aim payload at every render-visible read boundary so a selected
     * weapon and its authoritative replacement baseline are observed atomically. The controller's
     * payload equality guard makes repeated reads idempotent, while the established channel policy
     * preserves provider presentation ownership and legacy/native axis application semantics.
     */
    internal fun refreshClientAimPresentationFromSyncedData(
        receiptPartialTicks: Float = clientAimPresentationPartialTicks(),
    ) {
        if (!level().isClientSide) return
        vehicleAimController.consumeClient(
            entityData.get(VEHICLE_AIM_SNAPSHOT),
            receiptPartialTicks.coerceIn(0F, 1F),
        )
        // A controller/seat topology change and its complete multi-channel aim payload are
        // independently synchronized. Consume the payload first so beginning the new controller
        // epoch can atomically retain its freshly delivered physical actual(s), including the
        // PASSENGER_WEAPON + passive TURRET parent pair. The epoch step then revokes target/lock
        // diagnostics and bounds that immutable baseline until normal publication resumes.
        updateClientAimPresentationControllerEpoch()
    }

    private fun clientAimPresentationPartialTicks(): Float =
        if (level().isClientSide) Minecraft.getInstance().getFrameTime().coerceIn(0F, 1F) else 0F

    internal fun shouldApplyClientAimChannel(channel: VehicleAimChannel): Boolean =
        (resolveVehiclePoseProvider() == null || resolveVehicleFlightStrategy() != null) &&
            !VehicleAimPresentationController.isPredicting(this, channel)

    private fun invalidateChassisPresentationCache() {
        vehicleClientPresentationService.invalidateChassisCache()
        aimPresentationCache.clear()
        aimPresentationResolvedSequences.clear()
        aimPresentationAdmissionReasons.clear()
        aimPresentationCacheTick = Int.MIN_VALUE
        invalidateAttachmentSnapshot()
    }

    fun handleClientSync() {
        if (level() is ServerLevel && tickCount % 2 == 0) {
            serverYaw = yRot
            serverPitch = xRot
        }
        if (isControlledByLocalInstance) {
            interpolationSteps = 0
            syncPacketPositionCodec(x, y, z)
        }

        if (level().isClientSide && resolveVehicleFlightStrategy() == null &&
            resolveVehiclePoseProvider() != null
        ) {
            consumeSyncedVehiclePose()
            if (isControlledByLocalInstance) {
                if (vehicleChassisPresentationTimeline.beginLocal(position(), yRot)) {
                    vehicleAimController.clearClientPresentation()
                    invalidateChassisPresentationCache()
                }
                return
            }
            interpolationSteps = 0
            if (vehicleChassisPresentationTimeline.beginRemote(tickCount)) {
                vehicleAimController.clearClientPresentation()
            }
            vehicleChassisPresentationTimeline.advanceRemote(tickCount)
            val presentation = vehicleChassisPresentationTimeline.resolveRemote(0F) ?: return
            setPos(presentation.anchor.x, presentation.anchor.y, presentation.anchor.z)
            setRot(presentation.chassisYawDegrees, xRot)
            // Packet handlers own Entity.positionCodec. The delayed presentation anchor must
            // never replace the authoritative relative-move decoding base.
            invalidateChassisPresentationCache()
            return
        }
        if (level().isClientSide) {
            vehiclePoseAbsolutePending = null
            if (vehicleChassisPresentationTimeline.suspendClientOwnership()) {
                vehicleAimController.clearClientPresentation()
                invalidateChassisPresentationCache()
            }
        }
        if (interpolationSteps <= 0) {
            return
        }

        // handleClientSync runs before travel selects the current tick's strategy. Query the
        // provider now and retain legacy yaw/pitch until the first valid flight baseline arrives.
        val flightSnapshotOwnsClientAttitude = level().isClientSide &&
            resolveVehicleFlightStrategy() != null &&
            vehicleFlightController.hasClientInstrumentSnapshot()

        val interpolatedX = x + (xO - x) / interpolationSteps.toDouble()
        val interpolatedY = y + (yO - y) / interpolationSteps.toDouble()
        val interpolatedZ = z + (zO - z) / interpolationSteps.toDouble()

        if (!flightSnapshotOwnsClientAttitude) {
            val diffY = Mth.wrapDegrees(serverYaw - this.yRot)
            val diffX = Mth.wrapDegrees(serverPitch - this.xRot)

            this.yRot += 0.1f * diffY
            this.xRot += 0.1f * diffX
        }

        setPos(interpolatedX, interpolatedY, interpolatedZ)

        --interpolationSteps
    }

    override fun lerpTo(
        x: Double,
        y: Double,
        z: Double,
        yaw: Float,
        pitch: Float,
        interpolationSteps: Int,
        teleport: Boolean
    ) {
        val clientFlight = level().isClientSide && resolveVehicleFlightStrategy() != null
        val smoothFlightAbsolute = teleport && clientFlight &&
            vehicleFlightController.hasClientInstrumentSnapshot() &&
            !com.atsuishio.superbwarfare.api.vehicle.flight.FlightPositionCorrection.shouldSnap(
                position().distanceToSqr(Vec3(x, y, z)), deltaMovement.length(),
            )
        if (smoothFlightAbsolute) {
            // Keep relative decoding authoritative even while the displayed entity catches up.
            syncPacketPositionCodec(x, y, z)
        }
        if (teleport && !smoothFlightAbsolute) {
            val target = Vec3(x, y, z)
            val usesProviderTimeline = level().isClientSide &&
                resolveVehiclePoseProvider() != null && resolveVehicleFlightStrategy() == null
            val poseAlreadyMatches = usesProviderTimeline &&
                poseMatchesVanillaAbsolute(vehiclePoseCurrent, target, yaw)
            val hardDiscontinuity = usesProviderTimeline && !poseAlreadyMatches &&
                VehicleChassisPresentationTimeline.isSpatialDiscontinuity(
                    vehiclePoseCurrent,
                    vehiclePoseCurrent.copy(
                        anchor = target,
                        chassisYawDegrees = yaw,
                    ),
                )

            // The absolute packet remains the sole authoritative owner of the relative-move codec
            // base even when provider presentation intentionally stays on its delayed raw position.
            syncPacketPositionCodec(x, y, z)
            this.interpolationSteps = 0

            if (usesProviderTimeline && !hardDiscontinuity) {
                if (poseAlreadyMatches) {
                    vehiclePosePrevious = vehiclePoseCurrent
                    vehiclePoseClientUpdateTick = tickCount
                    vehiclePoseAbsolutePending = null
                } else {
                    // Moving periodic absolute refreshes arrive before that tick's dirty entity-data
                    // pose. Preserve raw position/rotation, old fields, and the current buffer phase
                    // until the next newer schema-2 sample classifies and consumes this refresh.
                    vehiclePoseAbsolutePending = PendingVehiclePoseAbsolute(
                        target,
                        yaw,
                        vehiclePoseCurrent.sequence,
                        false,
                    )
                }
                invalidateChassisPresentationCache()
                return
            }

            if (usesProviderTimeline) {
                vehicleChassisPresentationTimeline.clear()
                vehicleAimController.clearClientPresentation()
                vehiclePoseAbsolutePending = PendingVehiclePoseAbsolute(
                    target = target,
                    wireYaw = yaw,
                    afterSequence = vehiclePoseCurrent.sequence,
                    hardDiscontinuity = true,
                )
            } else {
                vehiclePoseAbsolutePending = null
            }

            // A periodic absolute refresh encodes angles in 1.40625-degree steps. Stationary
            // legacy ground vehicles already have exact SERVER_YAW/PITCH values; snapping to
            // the byte angle here makes the hull and attached sights jump, then creep back.
            // Moving corrections, mismatched angle buckets and other attitude owners stay native.
            val stationaryGroundRefresh = level().isClientSide && !clientFlight &&
                !usesProviderTimeline && (engineInfo is Track || engineInfo is Wheel) &&
                position().distanceToSqr(target) < 1.0E-6
            val refreshYaw = GroundRotationRefresh.angle(yaw, serverYaw, stationaryGroundRefresh)
            val refreshPitch = GroundRotationRefresh.angle(pitch, serverPitch, stationaryGroundRefresh)
            this.xO = x
            this.yO = y
            this.zO = z
            setPos(x, y, z)
            setRot(refreshYaw, refreshPitch)
            yRotO = refreshYaw
            xRotO = refreshPitch
            invalidateChassisPresentationCache()
            return
        }
        this.xO = x
        this.yO = y
        this.zO = z
        this.interpolationSteps = if (level().isClientSide &&
            resolveVehiclePoseProvider() != null && !clientFlight
        ) {
            0
        } else if (clientFlight) {
            interpolationSteps.coerceAtLeast(1)
        } else {
            10
        }
        if (clientFlight) {
            vehicleFlightController.beginClientPositionInterpolation(this.interpolationSteps)
        }
    }

    private fun poseMatchesVanillaAbsolute(
        pose: VehiclePoseSnapshot,
        target: Vec3,
        wireYaw: Float,
    ): Boolean {
        val encodedPoseYaw = (pose.chassisYawDegrees * 256.0F / 360.0F).toInt().toByte()
        val decodedPoseYaw = (encodedPoseYaw.toInt() * 360).toFloat() / 256.0F
        return pose.anchor?.distanceToSqr(target)?.let { it < 1.0E-6 } == true &&
                decodedPoseYaw == wireYaw
    }

    override fun getDismountLocationForPassenger(passenger: LivingEntity): Vec3 {
        val index = this.getTagSeatIndex(passenger)
        return if (index < 0) {
            super.getDismountLocationForPassenger(passenger)
        } else {
            this.getDismountLocationForIndex(passenger, index)
        }
    }

    /**
     * 获取第N个乘客的坐下位置
     *
     * @param passenger 乘客
     * @param index     座位
     * @return 下车的位置
     */
    fun getDismountLocationForIndex(passenger: LivingEntity, index: Int): Vec3 {
        val seats = this.computed().seats()
        if (index >= seats.size) return dismount(passenger)

        val dismountInfo = seats[index].dismountInfo
        if (dismountInfo != null) {
            val vec3 = dismountInfo.position
            if (vec3 != null) {
                val worldPosition = transformPosition(
                    this.getTransformFromString(dismountInfo.transform),
                    vec3.x, vec3.y, vec3.z
                )
                return Vec3(worldPosition.x, worldPosition.y, worldPosition.z)
            } else {
                return dismount(passenger)
            }
        } else {
            return dismount(passenger)
        }
    }

    fun dismount(passenger: LivingEntity): Vec3 {
        val vec3d = VehicleMiscUtils.getDismountOffset(
            this,
            (bbWidth * Mth.SQRT_OF_TWO).toDouble(),
            (passenger.bbWidth * Mth.SQRT_OF_TWO).toDouble()
        )
        val ox = x - vec3d.x
        val oz = z + vec3d.z
        val exitPos = BlockPos(ox.toInt(), y.toInt(), oz.toInt())
        val floorPos = exitPos.below()
        if (!level().isWaterAt(floorPos)) {
            val list = mutableListOf<Vec3>()
            val exitHeight = level().getBlockFloorHeight(exitPos)
            if (DismountHelper.isBlockFloorValid(exitHeight)) {
                list.add(Vec3(ox, exitPos.y.toDouble() + exitHeight, oz))
            }
            val floorHeight = level().getBlockFloorHeight(floorPos)
            if (DismountHelper.isBlockFloorValid(floorHeight)) {
                list.add(Vec3(ox, floorPos.y.toDouble() + floorHeight, oz))
            }
            for (entityPose in passenger.dismountPoses) {
                for (vec3d2 in list) {
                    if (!DismountHelper.canDismountTo(level(), vec3d2, passenger, entityPose)) continue
                    passenger.pose = entityPose
                    return vec3d2
                }
            }
        }
        return super.getDismountLocationForPassenger(passenger)
    }

    fun getEjectionPosition(passenger: LivingEntity, index: Int): Vec3 {
        val seats = this.computed().seats()
        if (index >= seats.size) return passenger.position()

        val dismountInfo = seats[index].dismountInfo
        if (dismountInfo != null) {
            val vec3 = dismountInfo.ejectPosition ?: return passenger.position()
            val worldPosition = transformPosition(
                this.getTransformFromString(dismountInfo.transform),
                vec3.x, vec3.y, vec3.z
            )

            return Vec3(worldPosition.x, worldPosition.y, worldPosition.z)
        }
        return passenger.position()
    }

    fun allowEjection(seatIndex: Int) =
        computed().seats().getOrNull(seatIndex)?.dismountInfo?.canEject ?: false

    fun removeSeatIndexTag(entity: Entity) {
        entity.persistentData.remove(TAG_SEAT_INDEX)
    }

    fun getEjectionMovement(entity: LivingEntity?, index: Int): Vec3 {
        val dismountInfo = this.computed().seats().getOrNull(index)?.dismountInfo ?: return deltaMovement

        val force = dismountInfo.ejectForce
        val stringOrVec3 = dismountInfo.ejectDirection

        if (stringOrVec3 == null) {
            return deltaMovement.add(getUpVec(1f).scale(force))
        } else if (stringOrVec3.isString) {
            return deltaMovement.add(
                getVectorFromString(
                    stringOrVec3.string!!,
                    1f,
                    getSeatIndex(entity)
                ).scale(force)
            )
        } else {
            val vec3 = stringOrVec3.vec3!!
            val worldPosition = transformPosition(
                getTransformFromString(dismountInfo.transform),
                vec3.x + stringOrVec3.vec3.x,
                vec3.y + stringOrVec3.vec3.y,
                vec3.z + stringOrVec3.vec3.z
            )

            val worldPositionO = transformPosition(
                getTransformFromString(dismountInfo.transform),
                vec3.x,
                vec3.y,
                vec3.z
            )

            val startPos = Vec3(worldPositionO.x, worldPositionO.y, worldPositionO.z)
            val endPos = Vec3(worldPosition.x, worldPosition.y, worldPosition.z)
            return deltaMovement.add(startPos.vectorTo(endPos).normalize().scale(force))
        }
    }

    val vehicleIcon: ResourceLocation?
        get() = computed().vehicleIcon

    fun allowFreeCam() = computed().allowFreeCam

    fun getUpVec(ticks: Float): Vec3 {
        val transform = getVehicleTransform(ticks)
        val force0 = transformPosition(transform, 0.0, 0.0, 0.0)
        val force1 = transformPosition(transform, 0.0, 1.0, 0.0)
        return Vec3(force0.x, force0.y, force0.z).vectorTo(Vec3(force1.x, force1.y, force1.z))
    }

    fun getRightVec(ticks: Float): Vec3 {
        val transform = getVehicleTransform(ticks)
        val force0 = transformPosition(transform, 0.0, 0.0, 0.0)
        val force1 = transformPosition(transform, -1.0, 0.0, 0.0)
        return Vec3(force0.x, force0.y, force0.z).vectorTo(Vec3(force1.x, force1.y, force1.z))
    }

    // 本方法留空
    override fun push(pX: Double, pY: Double, pZ: Double) {}

    fun getBarrelVector(pPartialTicks: Float): Vec3 {
        val transform = getBarrelTransform(pPartialTicks)
        val rootPosition = transformPosition(transform, 0.0, 0.0, 0.0)
        val targetPosition = transformPosition(transform, 0.0, 0.0, 1.0)
        return Vec3(rootPosition.x, rootPosition.y, rootPosition.z).vectorTo(
            Vec3(
                targetPosition.x,
                targetPosition.y,
                targetPosition.z
            )
        )
    }

    fun getBarrelXRot(pPartialTicks: Float): Float {
        return Mth.lerp(pPartialTicks, turretXRotO - this.xRotO, this.turretXRot - this.xRot)
    }

    fun getBarrelYRot(pPartialTick: Float): Float {
        return -Mth.wrapDegrees(
            Mth.rotLerp(pPartialTick, turretYRotO - this.yRotO, this.turretYRot - this.yRot)
        )
    }

    fun getGunXRot(pPartialTicks: Float): Float {
        return Mth.lerp(pPartialTicks, gunXRotO - this.xRotO, this.gunXRot - this.xRot)
    }

    fun getGunYRot(pPartialTick: Float): Float {
        return -Mth.wrapDegrees(
            Mth.rotLerp(pPartialTick, gunYRotO - this.yRotO, this.gunYRot - this.yRot)
        )
    }

    fun getTurretYaw(pPartialTick: Float): Float {
        return Mth.rotLerp(pPartialTick, turretYRotO, this.turretYRot)
    }

    fun getTurretPitch(pPartialTick: Float): Float {
        return Mth.lerp(pPartialTick, turretXRotO, this.turretXRot)
    }

    fun getCameraPos(entity: Entity, partialTicks: Float): Vec3 {
        resolveVehicleSeatPose(entity, partialTicks, false)?.let { return it.eyePosition }
        return VehicleVecUtils.getCameraPos(this, entity, partialTicks)
    }

    fun cameraDirection(entity: Entity, partialTicks: Float): Vec3 {
        resolveVehicleSeatPose(entity, partialTicks, false)?.direction?.let { return it }
        return VehicleVecUtils.getCameraDirection(this, entity, partialTicks)
    }

    fun getZoomPos(entity: Entity, partialTicks: Float): Vec3 {
        resolveVehicleSeatPose(entity, partialTicks, true)?.let { return it.eyePosition }
        return VehicleVecUtils.getZoomPos(this, entity, partialTicks)
    }

    open fun getZoomDirection(entity: Entity, partialTicks: Float): Vec3 {
        resolveVehicleSeatPose(entity, partialTicks, true)?.direction?.let { return it }
        return VehicleVecUtils.getZoomDirection(this, entity, partialTicks)
    }

    open val mouseSensitivity: Double
        get() = computed().mouseSensitivity

    val passengerRenderScale: Float
        get() = computed().passengerRenderScale

    fun gearRot(tickDelta: Float) =
        com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleCopies.frame(this)?.snapshot?.gear
            ?: Mth.lerp(tickDelta, gearRotO, this.gearRot)

    val mass: Float
        get() = computed().mass

    /** Motion is blocks/tick; its squared speed must remain finite after conversion to m/s. */
    private fun hasFiniteFixedWingMotion(motion: Vec3): Boolean =
        motion.x.isFinite() && motion.y.isFinite() && motion.z.isFinite() &&
            (motion.lengthSqr() * 400.0).isFinite()

    override fun setDeltaMovement(pDeltaMovement: Vec3) {
        if (isFixedWingFlightVehicle()) {
            if (hasFiniteFixedWingMotion(pDeltaMovement)) {
                super.setDeltaMovement(pDeltaMovement)
            }
            return
        }
        val currentMomentum = this.deltaMovement

        // 计算当前速度和新速度的标量大小
        val currentSpeedSq = currentMomentum.lengthSqr()
        val newSpeedSq = pDeltaMovement.lengthSqr()

        // 只在新速度大于当前速度时（加速过程）进行检查
        if (newSpeedSq > currentSpeedSq) {
            // 计算加速度向量
            val acceleration = pDeltaMovement.subtract(currentMomentum)

            // 检查加速度大小是否超过阈值
            if (acceleration.lengthSqr() > 8) {
                // 限制加速度不超过阈值
                val limitedAcceleration = acceleration.normalize().scale(0.125)
                val finalMomentum = currentMomentum.add(limitedAcceleration)

                super.setDeltaMovement(limitGroundSpeed(finalMomentum))
                return
            }
        }
        // 对于减速或允许的加速，直接设置新动量
        super.setDeltaMovement(limitGroundSpeed(pDeltaMovement))
    }

    /**
     * Ground vehicles never legitimately exceed [MAX_GROUND_HORIZONTAL_SPEED] horizontally; anything faster is an
     * injected impulse stacking up (owner 2026-09-29, collision launches), so the excess is dropped.
     */
    private fun limitGroundSpeed(motion: Vec3): Vec3 {
        when (vehicleType) {
            VehicleType.TANK, VehicleType.APC, VehicleType.AA, VehicleType.CAR, VehicleType.ARTILLERY,
            VehicleType.DEFENSE -> Unit
            else -> return motion
        }
        val horizontalSq = motion.x * motion.x + motion.z * motion.z
        val max = MAX_GROUND_HORIZONTAL_SPEED
        if (horizontalSq <= max * max) return motion
        val scale = max / kotlin.math.sqrt(horizontalSq)
        return Vec3(motion.x * scale, motion.y, motion.z * scale)
    }

    override fun addDeltaMovement(pAddend: Vec3) {
        if (isFixedWingFlightVehicle()) {
            if (hasFiniteFixedWingMotion(pAddend)) {
                val motion = this.deltaMovement.add(pAddend)
                if (hasFiniteFixedWingMotion(motion)) {
                    super.setDeltaMovement(motion)
                }
            }
            return
        }
        var pAddend = pAddend
        val length = pAddend.length()
        if (length > 0.1) pAddend = pAddend.scale(0.1 / length)

        super.addDeltaMovement(pAddend)
    }

    /**
     * 玩家在载具上的灵敏度调整
     *
     * @param original   原始灵敏度
     * @param zoom       是否在载具上瞄准
     * @param seatIndex  玩家座位
     * @param isOnGround 载具是否在地面
     * @return 调整后的灵敏度
     */
    open fun getSensitivity(original: Double, zoom: Boolean, seatIndex: Int, isOnGround: Boolean): Double {
        val seat = computed().seats()[seatIndex]
        val sensitivity = seat.sensitivity
        return if (zoom) sensitivity.x * original else if (Minecraft.getInstance().options.cameraType
                .isFirstPerson
        ) sensitivity.y * original else sensitivity.z * original
    }

    val vehicleItemIcon: ResourceLocation?
        /**
         * 载具在集装箱物品上显示的贴图
         */
        get() = computed().containerIcon

    /**
     * 判断一个座位是否是封闭的（封闭载具座位具有免疫负面效果等功能）
     * 默认认为隐藏乘客的座位均为封闭座位
     *
     * @param index 位置
     */
    fun isEnclosed(index: Int): Boolean {
        val seats = computed().seats()

        val seat = seats.getOrNull(index) ?: return false
        if (seat.isEnclosed == null) {
            return seat.hidePassenger
        }

        return seat.isEnclosed!!
    }

    fun isEnclosed(passenger: Entity?): Boolean {
        return isEnclosed(getSeatIndex(passenger))
    }

    fun exposesPassengerToFire(passenger: Entity?): Boolean =
        getSeat(passenger)?.exposedToFire == true && !isEnclosed(passenger)

    /**
     * 是否禁用玩家手臂
     *
     * @param entity 玩家
     */
    fun banHand(entity: LivingEntity?): Boolean {
        val index = getSeatIndex(entity)

        val gunData = getGunData(index)
        val seat = computed().seats().getOrNull(index) ?: return false
        return gunData != null || seat.banHand
    }

    /**
     * 是否隐藏载具上的玩家
     *
     * @return 是否隐藏
     */
    fun hidePassenger(index: Int): Boolean {
        val seats = computed().seats()
        if (index < 0 || index >= seats.size) return false

        val seat = seats[index]
        return seat.hidePassenger
    }

    fun hidePassenger(passenger: Entity?) = hidePassenger(getSeatIndex(passenger))

    fun getAmmoCount(living: LivingEntity?): Int {
        val data = getGunData(getSeatIndex(living)) ?: return 0
        return getAmmo(data)
    }

    fun getAmmoCount(seatIndex: Int): Int {
        val data = getGunData(seatIndex) ?: return 0
        return getAmmo(data)
    }

    fun getAmmoCount(weaponName: String): Int {
        val data = getGunData(weaponName) ?: return 0
        return getAmmo(data)
    }

    fun getAmmo(data: GunData) = if (data.useBackpackAmmo()) data.backupAmmoCount.get() else data.ammo.get()

    /**
     * Encodes the durable payload used by an add-on vehicle item.  This deliberately starts
     * from the entity's additional state instead of Entity.save(), then copies only the bounded
     * vehicle-owned fields below; UUID, position, passengers, motion, dimension, and authority
     * epochs therefore never enter an item.
     */
    fun createVehicleItemState(): CompoundTag {
        val typeId = ForgeRegistries.ENTITY_TYPES.getKey(this.type) ?: return CompoundTag()
        val full = CompoundTag()
        addAdditionalSaveData(full)
        val state = CompoundTag()
        state.putInt(VehicleItemLifecycleCodec.SCHEMA_TAG, VehicleItemLifecycleCodec.SCHEMA_VERSION)
        state.putString(VehicleItemLifecycleCodec.ENTITY_TYPE_TAG, typeId.toString())
        for (key in VEHICLE_ITEM_DURABLE_KEYS) {
            full.get(key)?.let { state.put(key, it.copy()) }
        }
        // The fitted suspended armament lives in the entity's persistent data, which the save above does not
        // include: carry it (minus per-world launch timers) so a recovered aircraft keeps its paid-for stores.
        armamentForVehicleItem()?.let { state.put(VEHICLE_ITEM_ARMAMENT_TAG, it) }
        return state
    }

    private fun armamentForVehicleItem(): CompoundTag? {
        val tag = persistentData.get(com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager.EQUIPMENT)
            as? CompoundTag ?: return null
        return tag.copy().also { it.remove("LastFire") }.takeIf { !it.isEmpty }
    }

    /**
     * Restores a previously encoded durable payload onto a fresh, unspawned entity.  Validation
     * is completed before any entity state is touched; callers remain responsible for collision
     * checks, spawn, final placement orientation, and consuming the item only after spawn.
     */
    fun restoreVehicleItemState(state: CompoundTag): Boolean {
        if (level().isClientSide || isRemoved || isWreck || passengers.isNotEmpty()) return false
        if (!isValidVehicleItemState(state)) return false

        val durable = CompoundTag()
        for (key in VEHICLE_ITEM_DURABLE_KEYS) {
            state.get(key)?.let { durable.put(key, it.copy()) }
        }

        return try {
            // Virtual dispatch intentionally lets BVP restore its own bounded ERA/module state
            // after the shared hull/weapon state has been loaded.
            readAdditionalSaveData(durable)
            (state.get(VEHICLE_ITEM_ARMAMENT_TAG) as? CompoundTag)?.let {
                persistentData.put(com.atsuishio.superbwarfare.api.aircraft.AircraftArmamentManager.EQUIPMENT, it.copy())
            }
            isWreck = false
            aircraftWreckStart = -1L
            aircraftWreckMotionX = 0F
            aircraftWreckMotionY = 0F
            aircraftWreckMotionZ = 0F
            aircraftWreckWings = -1
            aircraftWreckImpactTime = -1L
            aircraftWreckBounces = 0
            sympatheticDetonated = false
            turretBurned = false
            turretBurnTimer = 0
            setDeltaMovement(Vec3.ZERO)
            deltaMovementO = Vec3.ZERO
            power = 0f
            serverYaw = yRot
            serverPitch = xRot
            acceptedHelicopterAtgmCameraRays.clear()
            !isWreck && passengers.isEmpty() && health.isFinite() && health >= 0f
        } catch (_: RuntimeException) {
            false
        }
    }

    private fun isValidVehicleItemState(state: CompoundTag): Boolean {
        val expectedType = ForgeRegistries.ENTITY_TYPES.getKey(this.type)?.toString() ?: return false
        if (state.getInt(VehicleItemLifecycleCodec.SCHEMA_TAG) != VehicleItemLifecycleCodec.SCHEMA_VERSION ||
            state.getString(VehicleItemLifecycleCodec.ENTITY_TYPE_TAG) != expectedType ||
            state.allKeys.any { it != VehicleItemLifecycleCodec.SCHEMA_TAG &&
                it != VehicleItemLifecycleCodec.ENTITY_TYPE_TAG && it != VEHICLE_ITEM_ARMAMENT_TAG &&
                it !in VEHICLE_ITEM_DURABLE_KEYS }
        ) return false

        val healthFields = arrayOf(
            "Health" to getMaxHealth(),
            "TurretHealth" to getTurretMaxHealth(),
            "LeftWheelHealth" to getWheelMaxHealth(),
            "RightWheelHealth" to getWheelMaxHealth(),
            "MainEngineHealth" to getEngineMaxHealth(),
            "SubEngineHealth" to getEngineMaxHealth(),
        )
        for ((key, maximum) in healthFields) {
            if (!state.contains(key, Tag.TAG_FLOAT.toInt())) return false
            val value = state.getFloat(key)
            if (!maximum.isFinite() || maximum < 0f || !value.isFinite() || value < 0f || value > maximum + 0.001f) return false
        }

        if (state.contains("Override") && !state.contains("Override", Tag.TAG_STRING.toInt())) return false
        if (state.contains("WeaponState") && state.get("WeaponState") !is CompoundTag) return false
        if (state.contains("Inventory") && state.get("Inventory") !is CompoundTag) return false
        if (state.contains("Energy") && state.get("Energy") !is IntTag) return false
        if (state.contains(VehicleModuleStateService.MODULE_STATES_TAG) &&
            !validVehicleItemModuleState(state.get(VehicleModuleStateService.MODULE_STATES_TAG))
        ) return false
        if (state.contains("DogTagIcon") && !validVehicleItemDogTag(state.get("DogTagIcon"))) return false
        if (state.contains("BvpSpentEraBricks") && state.get("BvpSpentEraBricks") !is CompoundTag) return false
        if (state.contains(VEHICLE_ITEM_ARMAMENT_TAG) && state.get(VEHICLE_ITEM_ARMAMENT_TAG) !is CompoundTag) return false

        val weaponIndicesOptional = maxPassengers == 0
        if (!validVehicleItemWeaponIndices(state.get("SelectedWeapon"), allowMissing = weaponIndicesOptional) ||
            !validVehicleItemWeaponIndices(state.get("SecondaryWeapon"), allowMissing = weaponIndicesOptional)) return false
        return true
    }

    private fun validVehicleItemWeaponIndices(tag: net.minecraft.nbt.Tag?, allowMissing: Boolean): Boolean {
        if (tag == null) return allowMissing
        if (tag !is IntArrayTag || tag.asIntArray.size != maxPassengers) return false
        for (seatIndex in tag.asIntArray.indices) {
            // The same list the weapon slots index: native weapons, then the suspended-armament mount channels and
            // gun-pod aliases. Checking only the native count refused every aircraft whose selected weapon was a
            // store, so a recovered armed aircraft could not be placed again.
            val weaponCount = getWeaponIds(seatIndex).size
            val index = tag.asIntArray[seatIndex]
            if (index < -1 || index >= weaponCount) return false
        }
        return true
    }

    private fun validVehicleItemModuleState(tag: net.minecraft.nbt.Tag?): Boolean {
        if (tag !is CompoundTag) return false
        for (id in tag.allKeys) {
            if (ResourceLocation.tryParse(id) == null) return false
            val state = tag.get(id) as? CompoundTag ?: return false
            val maximum = state.getFloat(VehicleModuleStateService.MODULE_MAX_HEALTH_TAG)
            val health = state.getFloat(VehicleModuleStateService.MODULE_HEALTH_TAG)
            if (!maximum.isFinite() || maximum <= 0f || !health.isFinite() || health < 0f || health > maximum) {
                return false
            }
        }
        return true
    }

    private fun validVehicleItemDogTag(tag: net.minecraft.nbt.Tag?): Boolean {
        if (tag !is ListTag) return false
        return tag.all { entry -> entry is IntArrayTag && entry.asIntArray.size <= 16 }
    }

    override fun getPickResult(): ItemStack? {
        VehicleItemLifecycleProviders.createFromEntity(this)?.let { return it }
        if (!this.getRetrieveItems().isEmpty()) {
            return this.getRetrieveItems().firstOrNull()
        }
        return ContainerBlockItem.createInstance(this.type)
    }

    open fun useAircraftCamera(seatIndex: Int): Boolean {
        return computed().seats().getOrNull(seatIndex)?.cameraPos?.useAircraftCamera ?: false
    }

    /**
     * 获取视角旋转
     *
     * @param zoom          是否在载具上瞄准
     * @param isFirstPerson 是否是第一人称视角
     */
    @OnlyIn(Dist.CLIENT)
    open fun getCameraRotation(partialTicks: Float, player: Player, zoom: Boolean, isFirstPerson: Boolean): Vec2? =
        VehicleCameraResolver.rotation(VehicleCameraRequest(this, player, partialTicks, zoom, isFirstPerson))

    /**
     * 获取视角位置
     *
     * @param zoom          是否在载具上瞄准
     * @param isFirstPerson 是否是第一人称视角
     */
    @OnlyIn(Dist.CLIENT)
    open fun getCameraPosition(partialTicks: Float, player: Player, zoom: Boolean, isFirstPerson: Boolean): Vec3? =
        VehicleCameraResolver.position(VehicleCameraRequest(this, player, partialTicks, zoom, isFirstPerson))

    /**
     * 是否使用载具固定视角
     */
    @OnlyIn(Dist.CLIENT)
    fun useFixedCameraPos(entity: Entity?): Boolean {
        return computed().seats().getOrNull(getSeatIndex(entity))?.cameraPos?.useFixedCameraPos ?: false
    }

    override fun <T> getCapability(cap: Capability<T?>, side: Direction?): LazyOptional<T?> {
        if (cap === ForgeCapabilities.ENERGY && this.hasEnergyStorage()) {
            return inventoryEnergyService.energyCapability().cast<T?>()
        } else if (cap === ForgeCapabilities.ITEM_HANDLER && this.hasContainer()) {
            return inventoryEnergyService.itemCapability().cast<T?>()
        }
        return super.getCapability(cap, side)
    }

    override fun <T> getCapability(cap: Capability<T?>): LazyOptional<T?> {
        return this.getCapability(cap, null)
    }

    override fun invalidateCaps() {
        super.invalidateCaps()
        if (this.hasContainer()) {
            inventoryEnergyService.invalidateItemCapability()
        }
        if (this.hasEnergyStorage()) {
            inventoryEnergyService.invalidateEnergyCapability()
        }
    }

    override fun reviveCaps() {
        super.reviveCaps()
        if (this.hasContainer()) {
            inventoryEnergyService.reviveItemCapability()
        }
        if (this.hasEnergyStorage()) {
            inventoryEnergyService.reviveEnergyCapability()
        }
    }

    /**
     * 瞄准时的放大倍率
     *
     * @return 放大倍率
     */
    fun getDefaultZoom(entity: Entity?): Double {
        val gunData = getGunData(getSeatIndex(entity))
        return gunData?.get(GunProp.DEFAULT_ZOOM) ?: 1.0
    }

    open fun canCrushEntities() = true

    fun fixedEngine() {
        this.move(MoverType.SELF, Vec3(0.0, this.deltaMovement.y, 0.0))
        if (this.onGround()) {
            this.setDeltaMovement(Vec3.ZERO)
        } else {
            this.setDeltaMovement(Vec3(0.0, this.deltaMovement.y, 0.0))
        }
    }

    fun releaseSmokeDecoy(vec3: Vec3) = VehicleWeaponUtils.releaseSmokeDecoy(this, vec3)

    fun releaseDecoy() {
        if (com.atsuishio.superbwarfare.api.aircraft.AircraftCountermeasures.definition(this) == null)
            VehicleWeaponUtils.releaseDecoy(this)
    }

    /** Accepted server countermeasure levels, also synchronized for cockpit/HUD consumers. */
    fun getFlareLevel(): Int = entityData.get(AIRCRAFT_COUNTERMEASURE_LEVELS) and 255
    fun getChaffLevel(): Int = com.atsuishio.superbwarfare.api.aircraft.AircraftCountermeasureWire.chaff(entityData.get(AIRCRAFT_COUNTERMEASURE_LEVELS))
    fun getAircraftThreatLevel(): Int = com.atsuishio.superbwarfare.api.aircraft.AircraftCountermeasureWire.threat(entityData.get(AIRCRAFT_COUNTERMEASURE_LEVELS))
    fun isChaffEmitting(): Boolean = com.atsuishio.superbwarfare.api.aircraft.AircraftCountermeasureWire.emitting(entityData.get(AIRCRAFT_COUNTERMEASURE_LEVELS))
    /** [com.atsuishio.superbwarfare.api.aircraft.IncomingMissileWarning] flags, synchronized for every vehicle. */
    fun getIncomingMissileWarning(): Int = com.atsuishio.superbwarfare.api.aircraft.AircraftCountermeasureWire.incoming(entityData.get(AIRCRAFT_COUNTERMEASURE_LEVELS))
    fun getFlareCooldownTicks(): Int = entityData.get(AIRCRAFT_COUNTERMEASURE_TIMERS) and 511
    fun getChaffCooldownTicks(): Int = (entityData.get(AIRCRAFT_COUNTERMEASURE_TIMERS) shr 9) and 511
    internal fun publishAircraftCountermeasures(levels: Int, timers: Int) {
        if (level().isClientSide) return
        entityData.set(AIRCRAFT_COUNTERMEASURE_LEVELS, levels)
        entityData.set(AIRCRAFT_COUNTERMEASURE_TIMERS, timers)
    }

    fun terrainCompact(positions: MutableList<Vec3>) {
        VehicleMotionUtils.terrainCompact(this, positions)
    }

    fun getWheelsTransform(partialTicks: Float): Matrix4d {
        return VehicleMotionUtils.getWheelsTransform(this, partialTicks)
    }

    fun moveOnDragonTeeth() {
        if (usesAircraftTerrainContact()) return
        if (this is com.atsuishio.superbwarfare.api.vehicle.deck.DeckSurfaceEntity) return
        VehicleMotionUtils.handleVehicleMoveOnDragonTeeth(this)
    }

    fun collideBlocks() {
        if (usesAircraftTerrainContact()) return
        // a carrier hull moves through water, never breaking blocks (its sweep would cover 10^5 blocks)
        if (this is com.atsuishio.superbwarfare.api.vehicle.deck.DeckSurfaceEntity) return
        if (tickCount % 4 != 0) return
        if (computed().engineType == EngineType.FIXED) return
        if (deltaMovement.lengthSqr() < 0.01) return
        VehicleMotionUtils.collideBlocks(this)
    }


    val lastAttacker: Entity?
        get() = EntityFindUtil.findEntity(level(), lastAttackerUUID)

    /** Fits the physical entity envelope; the default preserves native dimensions. */
    open fun fitEntityCollisionBounds(nativeBounds: AABB): AABB = nativeBounds

    override fun usesDetailedProjectileCollision(): Boolean = isInitialized && computed().aircraftSurfaceModules.isNotEmpty()

    override fun clipProjectile(start: Vec3, end: Vec3): com.atsuishio.superbwarfare.api.projectile.ProjectileCollisionTarget.Hit? =
        com.atsuishio.superbwarfare.api.aircraft.AircraftSurfaceModules.clipProjectile(this,start,end)

    override fun makeBoundingBox(): AABB {
        if (isInitialized && !level().isClientSide) AircraftSurfaceProjectileIndex.update(this)
        if (level().isClientSide) {
            com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleCopies.visualBounds(this)?.let { return it }
        }
        val physical = getAircraftCollisionSnapshot(1F)
        if (physical != null) {
            AircraftCollisionIndex.update(this, physical.queryBounds)
            return physical.queryBounds
        }
        if (isInitialized) AircraftCollisionIndex.remove(this)
        return fitEntityCollisionBounds(super.makeBoundingBox())
    }

    /** Two physical parts sampled together; the returned AABB is discovery geometry only. */
    fun getAircraftCollisionSnapshot(partialTicks: Float): AircraftCollisionSnapshot? {
        if (!isInitialized || !usesAircraftTerrainContact()) return null
        val definition = computed().aircraftTerrainContact ?: return null
        val mask = com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup.mask(this)
        val impacted = aircraftWreckImpactTime >= 0
        // The whole-tick pose is asked for many times a tick (culling bounds, entity pushes, projectile clips);
        // the snapshot is immutable, so reuse it while nothing it is built from has changed.
        val memoable = partialTicks == 1F
        if (memoable) {
            val memo = collisionMemo
            if (memo != null && collisionMemoTick == tickCount && collisionMemoX == x && collisionMemoY == y &&
                collisionMemoZ == z && collisionMemoYaw == yRot && collisionMemoPitch == xRot &&
                collisionMemoRoll == roll && collisionMemoGear == synchedGearRot && collisionMemoMask == mask &&
                collisionMemoImpact == impacted && collisionMemoDefinition === definition) return memo
        }
        val snapshot = AircraftCollisionSnapshot.create(definition, getVehicleTransform(partialTicks), synchedGearRot,
            mask, impacted,
            if (definition.bodyVolumes().any { it.bone != "hull" })
                com.atsuishio.superbwarfare.api.aircraft.AircraftSurfaceModules.boneMatrices(this, partialTicks) else null)
        if (memoable) {
            collisionMemo = snapshot; collisionMemoTick = tickCount; collisionMemoX = x; collisionMemoY = y
            collisionMemoZ = z; collisionMemoYaw = yRot; collisionMemoPitch = xRot; collisionMemoRoll = roll
            collisionMemoGear = synchedGearRot; collisionMemoMask = mask; collisionMemoImpact = impacted
            collisionMemoDefinition = definition
        }
        return snapshot
    }

    private var collisionMemo: AircraftCollisionSnapshot? = null
    private var collisionMemoTick = Int.MIN_VALUE
    private var collisionMemoX = 0.0
    private var collisionMemoY = 0.0
    private var collisionMemoZ = 0.0
    private var collisionMemoYaw = 0F
    private var collisionMemoPitch = 0F
    private var collisionMemoRoll = 0F
    private var collisionMemoGear = 0F
    private var collisionMemoMask = Int.MIN_VALUE
    private var collisionMemoImpact = false
    private var collisionMemoDefinition: Any? = null

    /** UI selection uses physical parts while projectile/module routing keeps its authored API. */
    @JvmOverloads
    fun clipPhysicalCollision(start: Vec3, end: Vec3, partialTicks: Float = 1F): EntityHitResult? {
        val point = getAircraftCollisionSnapshot(partialTicks)?.clip(start, end) ?: return null
        return EntityHitResult(this, point)
    }

    internal fun entityCollisionObbs(): List<OBB> =
        getAircraftCollisionSnapshot(1F)?.activeObbs() ?: getOBBs()

    internal fun usesAircraftPhysicalCollision(): Boolean = isInitialized && usesAircraftTerrainContact()

    private fun refreshPhysicalCollisionBounds() {
        if (!isInitialized) return
        if (!usesAircraftTerrainContact()) {
            AircraftCollisionIndex.remove(this)
            return
        }
        boundingBox = makeBoundingBox()
    }

    override fun setXRot(value: Float) {
        super.setXRot(value)
        refreshPhysicalCollisionBounds()
    }

    override fun setYRot(value: Float) {
        super.setYRot(value)
        refreshPhysicalCollisionBounds()
    }

    override fun onAddedToWorld() {
        super.onAddedToWorld()
        if (!level().isClientSide) AircraftSurfaceProjectileIndex.update(this)
        refreshPhysicalCollisionBounds()
        // FULL far-terrain tickets expose saved targets before their first simulation tick.
        // Derived hit geometry must already match the restored position and orientation.
        updateOBB()
    }

    override fun onRemovedFromWorld() {
        AircraftSurfaceProjectileIndex.remove(this)
        AircraftCollisionIndex.remove(this)
        super.onRemovedFromWorld()
    }

    override fun isInObb(entity: Entity, movement: Vec3): Boolean =
        if (usesAircraftPhysicalCollision() || entity is VehicleEntity && entity.usesAircraftPhysicalCollision())
            VehicleEntityContacts.find(this, entity, movement) != null
        else super<OBBEntity>.isInObb(entity, movement)

    /** Movement and the public entity box use the same fitted envelope. */
    open fun getMovementCollisionBounds(): AABB = makeBoundingBox()

    fun vCollide(pVec: Vec3): Vec3 = vehicleCollisionEnvironmentService.collide(pVec)

    fun vMove(pType: MoverType, pPos: Vec3) = vehicleCollisionEnvironmentService.move(pType, pPos)

    internal fun resolveCollisionBoundingBox(
        movement: Vec3,
        bounds: AABB,
        entityCollisions: List<VoxelShape>,
    ): Vec3 = collideBoundingBox(this, movement, bounds, level(), entityCollisions)

    internal fun backOffFromEdgeForCollision(movement: Vec3, movementType: MoverType): Vec3 =
        maybeBackOffFromEdge(movement, movementType)

    internal fun isHorizontalCollisionMinorForCollision(resolvedMovement: Vec3): Boolean =
        isHorizontalCollisionMinor(resolvedMovement)

    internal fun setOnGroundForCollision(collidedBelow: Boolean, resolvedMovement: Vec3) =
        setOnGroundWithKnownMovement(collidedBelow, resolvedMovement)

    internal fun acceptWheelSupportPitch(pitch: Float) {
        applyVehicleFlightAttitude(yRot, pitch, roll)
        vehicleFlightController.acceptGroundContactPitch(pitch)
    }

    internal fun getOnPositionForCollision(offset: Float): BlockPos = getOnPos(offset)

    private fun usesAircraftTerrainContact(): Boolean =
        (vehicleType == VehicleType.AIRPLANE || vehicleType == VehicleType.HELICOPTER) &&
            computed().aircraftTerrainContact?.valid() == true

    override fun move(movementType: MoverType, movement: Vec3) {
        val aircraftTerrain = computed().aircraftTerrainContact
        if (!level().isClientSide && aircraftTerrain != null && aircraftTerrain.valid() &&
            (vehicleType == VehicleType.AIRPLANE || vehicleType == VehicleType.HELICOPTER)) {
            aircraftTerrainCollisionService.move(movement)
            return
        }
        val fixedWing = isFixedWingFlightVehicle()
        val previousPosition = position()
        val incomingVelocity = deltaMovement
        if (fixedWing && !level().isClientSide) {
            fixedWingGroundContactService.beginMove(movement)
            vehicleCollisionEnvironmentService.move(movementType, movement, false) { nativeResolved ->
                fixedWingGroundContactService.constrainMovement(nativeResolved) { supported ->
                    vehicleCollisionEnvironmentService.collide(supported, false)
                }
            }
        } else {
            vehicleCollisionEnvironmentService.move(movementType, movement, !fixedWing && !level().isClientSide)
        }
        if (fixedWing) {
            fixedWingGroundContactService.afterMove(movement, incomingVelocity, previousPosition)
            return
        }

        if (lastTickSpeed < 0.2 || collisionCoolDown > 0 || this is DroneEntity) return
        val driver = this.lastDriver

        if (verticalCollision) {
            if (this.vehicleType == VehicleType.AIRPLANE
                && ((synchedGearRot > 0.15 && this !is Tom6Entity) || Mth.abs(this.roll) > 20 || Mth.abs(xRot) > 30)
            ) {
                this.hurt(
                    ModDamageTypes.causeVehicleStrikeDamage(
                        this.level().registryAccess(),
                        this,
                        driver ?: this
                    ),
                    if (isWreck) 0f else ((8 + Mth.abs(this.roll * 0.2f)) * (lastTickSpeed - 0.4) * (lastTickSpeed - 0.4)).toFloat()
                )
                this.bounceVertical(
                    Direction.getNearest(
                        this.deltaMovement.x(),
                        this.deltaMovement.y(),
                        this.deltaMovement.z()
                    ).opposite
                )
            } else if (this.vehicleType == VehicleType.HELICOPTER) {
                this.hurt(
                    ModDamageTypes.causeVehicleStrikeDamage(
                        this.level().registryAccess(),
                        this,
                        driver ?: this
                    ), if (isWreck) 0f else (10 * ((lastTickSpeed - 0.4) * (lastTickSpeed - 0.4))).toFloat()
                )
                this.bounceVertical(
                    Direction.getNearest(
                        this.deltaMovement.x(),
                        this.deltaMovement.y(),
                        this.deltaMovement.z()
                    ).opposite
                )
            } else if (Mth.abs(lastTickVerticalSpeed.toFloat()) > 0.4) {
                this.hurt(
                    ModDamageTypes.causeVehicleStrikeDamage(
                        this.level().registryAccess(),
                        this,
                        driver ?: this
                    ),
                    if (isWreck) 0f else (24 * ((Mth.abs(lastTickVerticalSpeed.toFloat()) - 0.4) * (lastTickSpeed - 0.4) * (lastTickSpeed - 0.4))).toFloat()
                )
                if (!this.level().isClientSide) {
                    this.level().playSound(null, this, ModSounds.VEHICLE_STRIKE.get(), this.soundSource, 1f, 1f)
                }
                this.bounceVertical(
                    Direction.getNearest(
                        this.deltaMovement.x(),
                        this.deltaMovement.y(),
                        this.deltaMovement.z()
                    ).opposite
                )
            }
        }

        // A vehicle-against-vehicle contact is charged once, by VehicleCollisionResponseService.
        if (this.horizontalCollision && !collisionResponse.touchedVehicleThisTick()) {
            this.hurt(
                ModDamageTypes.causeVehicleStrikeDamage(
                    this.level().registryAccess(),
                    this,
                    driver ?: this
                ), (18 * ((lastTickSpeed - 0.2) * (lastTickSpeed - 0.2))).toFloat()
            )
            this.bounceHorizontal(
                Direction.getNearest(
                    this.deltaMovement.x(),
                    this.deltaMovement.y(),
                    this.deltaMovement.z()
                ).opposite
            )
            if (!this.level().isClientSide) {
                this.level().playSound(null, this, ModSounds.VEHICLE_STRIKE.get(), this.soundSource, 1f, 1f)
            }
            collisionCoolDown = 4
            crash = true
            power *= 0.8f
        }
    }

    open fun bounceHorizontal(direction: Direction) {
        VehicleMotionUtils.bounceHorizontal(this, direction)
    }

    fun hasRecentFixedWingWorldContact(): Boolean = isFixedWingFlightVehicle() &&
        (aircraftTerrainCollisionService.recentContact() || fixedWingGroundContactService.recentContact())

    fun bounceVertical(direction: Direction) {
        VehicleMotionUtils.bounceVertical(this, direction)
    }

    fun pushNew(pX: Double, pY: Double, pZ: Double) {
        this.setDeltaMovement(this.deltaMovement.add(pX, pY, pZ))
    }

    fun supportEntities() {
        VehicleMotionUtils.supportEntities(this)
    }

    fun getRandom(): RandomSource = this.random

    fun crushEntities() = VehicleMotionUtils.crushEntities(this)

    fun getForwardDirection(): Vector3f = Vector3f(
        Mth.sin(-yRot * (Math.PI.toFloat() / 180)),
        0.0f,
        Mth.cos(yRot * (Math.PI.toFloat() / 180))
    ).normalize()

    fun getRightDirection(): Vector3f = Vector3f(
        Mth.cos(-yRot * (Math.PI.toFloat() / 180)),
        0.0f,
        Mth.sin(yRot * (Math.PI.toFloat() / 180))
    ).normalize()

    fun getEngineSound(): SoundEvent? = this.computed().engineSound

    fun getAcceleration() = absoluteSpeed - absoluteSpeedO

    open fun getTrackAnimationLength() = 100

    fun hasDecoy() = computed().hasDecoy

    open fun engineRunning(): Boolean = if (isFixedWingFlightVehicle()) {
        !isWreck && fixedWingEngineSoundPower() > 0.001F
    } else Math.abs(power) > 0

    private fun fixedWingEngineSoundPower(): Float {
        val flight = resolveVehicleFlightStrategy() as? FixedWingFlightStrategy ?: return 0F
        val referenceThrust = flight.profile.massKg * flight.handling.dryAccelerationMps2
        val thrust = getVehicleFlightInstrumentSnapshot(1F).thrust
        if (!thrust.isFinite() || !referenceThrust.isFinite() || referenceThrust <= 0.0) return 0F
        return (thrust / referenceThrust).coerceIn(0.0, 1.5).toFloat()
    }

    /**
     * 撬棍shift+右键收回载具时返还的物品
     */
    open fun getRetrieveItems(): List<ItemStack> = listOf(ContainerBlockItem.createInstance(this))

    val hudColor: Int
        get() = computed().hudColor.get()

    var power by POWER
    var deltaRot by DELTA_ROT
    var decoyReady by DECOY_READY
    var synchedPropellerRot by SYNCHED_PROPELLER_ROT
    var planeBreak by PLANE_BREAK
    var synchedGearRot by SYNCHED_GEAR_ROT
    var gearUp by GEAR_UP

    var subEngineDamaged by SUB_ENGINE_DAMAGED
    var subEngineHealth by SUB_ENGINE_HEALTH
    var mainEngineDamaged by MAIN_ENGINE_DAMAGED
    var mainEngineHealth by MAIN_ENGINE_HEALTH

    var leftWheelDamaged by L_WHEEL_DAMAGED
    var leftWheelHealth by L_WHEEL_HEALTH
    var rightWheelDamaged by R_WHEEL_DAMAGED
    var rightWheelHealth by R_WHEEL_HEALTH

    var turretDamaged by TURRET_DAMAGED
    var turretHealth by TURRET_HEALTH

    var selectedWeapon by SELECTED_WEAPON
    /** Replicated per-seat secondary slot; paired seats never overwrite [selectedWeapon]. */
    var secondaryWeapon by SECONDARY_WEAPON
    var chargeProgress by CHARGE_PROGRESS

    var laserScale by LASER_SCALE
    var laserScaleO by LASER_SCALE_O
    var laserLength by LASER_LENGTH

    var serverYaw by SERVER_YAW
    var serverPitch by SERVER_PITCH
    var cannonRecoilTime by CANNON_RECOIL_TIME
    var cannonRecoilForce by CANNON_RECOIL_FORCE

    var override by OVERRIDE
    var lastAttackerUUID by LAST_ATTACKER_UUID
    var lastDriverUUID by LAST_DRIVER_UUID
    var dogTagIcon by DOG_TAG_ICON
    var aiTurretTargetUUID by AI_TURRET_TARGET_UUID
    var aiPassengerWeaponTargetUUID by AI_PASSENGER_WEAPON_TARGET_UUID

    var yawWhileShoot by YAW_WHILE_SHOOT
    var hornVolume by HORN_VOLUME

    var isWreck by IS_WRECK
    var aircraftWreckStart by AIRCRAFT_WRECK_START
    var aircraftWreckMotionX by AIRCRAFT_WRECK_MOTION_X
    var aircraftWreckMotionY by AIRCRAFT_WRECK_MOTION_Y
    var aircraftWreckMotionZ by AIRCRAFT_WRECK_MOTION_Z
    var aircraftWreckWings by AIRCRAFT_WRECK_WINGS
    var aircraftWreckImpactTime by AIRCRAFT_WRECK_IMPACT_TIME
    internal var aircraftWreckBounces = 0
    internal var aircraftLastWreckBounce = Long.MIN_VALUE
    var sympatheticDetonated by SYMPATHETIC_DETONATED
    var turretBurned by TURRET_BURNED
    var turretBurnTimer by TURRET_BURN_TIMER
    var hoverMode by HOVER_MODE

    val hornSound: SoundEvent
        get() = this.computed().hornSound

    fun horn() {
        hornVolume += 0.7f
    }

    fun hornWorking() = Math.abs(this.hornVolume) > 0.05

    fun stuka() = xRot > 5 && xRot < 175 && deltaMovement.y < -0.4 && !onGround()
    fun heliCrash() = vehicleType == VehicleType.HELICOPTER && health < getMaxHealth() * 0.1f && !onGround()
    fun vehicleSkip() =
        engineInfo is Wheel && engineInfo !is WheelChair && (if (engineInfo is Track) drift() else upInputDown) && onGround() && deltaMovement.horizontalDistanceSqr() > (if (engineInfo is Track) 0.0004 else 0.01)

    fun drift() = upInputDown && (rightInputDown || leftInputDown)

    val vehicleType: VehicleType?
        get() = computed().type

    /**
     * @author YWZJ Ranpoes
     */
    fun support(entity: Entity) {
        VehicleMotionUtils.support(this, entity)
    }

    val isAmphibious: Boolean
        get() = VehicleMiscUtils.isAmphibious(this)

    @OnlyIn(Dist.CLIENT)
    open fun firstPersonAmmoComponent(data: GunData, player: Player?): Component {
        val name = data.get(GunProp.NAME)
        if (name.isNullOrBlank()) return Component.empty()

        val ammoCount = this.getAmmoCount(player)
        return Component.translatable(name, if (ammoCount == Int.MAX_VALUE) "∞" else ammoCount)
    }

    @OnlyIn(Dist.CLIENT)
    fun thirdPersonAmmoComponent(data: GunData, player: Player?): Component {
        return firstPersonAmmoComponent(data, player)
    }

    override fun getOBBs(): MutableList<OBB> = combatStateOwner.collisionBoxes.select(
        obb, hasFixedWingLandingGear(), synchedGearRot, omitsRunningGearHitboxes(),
    )

    /**
     * Whether the WheelLeft / WheelRight collision boxes are left out of the hit volumes: always for tracked
     * vehicles; add-on vehicles whose armor model resolves every hit override this for wheeled ones too, so a
     * side shot is never eaten by the running gear.
     */
    open fun omitsRunningGearHitboxes(): Boolean = computed().engineType == EngineType.TRACK

    fun getEnergyDataAccessor() = ENERGY

    fun generateWreckageLoot() {
        val data = WreckageLootDataManager.getLootData(this.type) ?: return
        val pools = data.pools
        if (pools.isEmpty()) return
        pools.forEach poolLoop@{ pool ->
            val type = pool.type
            if (type == WreckageLootData.Pool.Type.TURRET_ONLY) return@poolLoop
            val entries = pool.entries
            if (entries.isEmpty()) return@poolLoop
            val source = pool.source
            if (source != "@Default") {
                val lastSource = this.lastDamageSource ?: return@poolLoop
                val parsedLoc = ResourceLocation.tryParse(source) ?: return@poolLoop
                val damageType = ResourceKey.create(Registries.DAMAGE_TYPE, parsedLoc)
                if (!lastSource.`is`(damageType)) return@poolLoop
            } else if (this.lastDamageSource?.`is`(ModDamageTypes.REPAIR_TOOL) == true) {
                return@poolLoop
            }

            repeat(pool.rolls) {
                entries.forEach { entry ->
                    val random = Random.nextDouble()
                    val chance =
                        if (type == WreckageLootData.Pool.Type.VEHICLE_ONLY) {
                            if (this.hasTurret() && this.sympatheticDetonated) {
                                entry.chance
                            } else return@poolLoop
                        } else if (type == WreckageLootData.Pool.Type.COMPLETE) {
                            if (this.hasTurret()) {
                                if (this.sympatheticDetonated) return@poolLoop
                                else entry.chance
                            } else {
                                entry.chance
                            }
                        } else {
                            entry.chance * if (this.hasTurret() && this.sympatheticDetonated) (1.0 - VehicleConfig.TURRET_WRECKAGE_LOOT_RATE.get()) else 1.0
                        }

                    if (random > chance) return@forEach
                    val name = entry.name
                    val item = ForgeRegistries.ITEMS.getValue(ResourceLocation(name)) ?: return@forEach
                    val count = entry.count
                    val entity = ItemEntity(level(), x, (y + 1), z, ItemStack(item, count))
                    entity.setPickUpDelay(10)
                    level().addFreshEntity(entity)
                }
            }
        }
    }

    companion object {
        /** Launch property, read once: log the hit feedback players receive (diagnostic launches only). */
        private val HIT_FEEDBACK_LOG: Boolean = java.lang.Boolean.getBoolean("bvp.diagnostics.scenarios")

        /** Horizontal speed ceiling for ground vehicles (blocks/tick; 3 = 216 km/h). */
        const val MAX_GROUND_HORIZONTAL_SPEED = 3.0
        private const val MAX_HELICOPTER_ATGM_CAMERA_RAY_STREAMS = 16
        private const val AIM_PRESENTATION_DIRECTION_DOT_MINIMUM = 0.99999
        private const val AIM_PRESENTATION_TIME_EPSILON = 1.0E-6
        private const val MAX_AIM_PRESENTATION_REASON_KEYS = 8
        // Active aim is authored every server tick, but entity-data delivery may be delayed or
        // coalesced. Keep the last directly received same-epoch world ray for at most one second;
        // held frames never renew this lease and always lose the lock diagnostic.
        private const val MAX_AIM_PRESENTATION_CONTINUITY_TICKS = 20.0
        /** The fitted suspended armament (AircraftArmamentManager.EQUIPMENT) inside a vehicle item. */
        private const val VEHICLE_ITEM_ARMAMENT_TAG = "AircraftArmament"
        private val VEHICLE_ITEM_DURABLE_KEYS = setOf(
            "Override",
            "Health",
            "WeaponState",
            "TurretHealth",
            "LeftWheelHealth",
            "RightWheelHealth",
            "MainEngineHealth",
            "SubEngineHealth",
            "TurretDamaged",
            "LeftWheelDamaged",
            "RightWheelDamaged",
            "MainEngineDamaged",
            "SubEngineDamaged",
            VehicleModuleStateService.MODULE_STATES_TAG,
            "DogTagIcon",
            "SelectedWeapon",
            "SecondaryWeapon",
            "Energy",
            "Inventory",
            "BvpSpentEraBricks",
        )
        const val TAG_SEAT_INDEX: String = "SBWSeatIndex"
        @JvmField
        val HEALTH: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val OVERRIDE: EntityDataAccessor<String> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val LAST_ATTACKER_UUID: EntityDataAccessor<String> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val LAST_DRIVER_UUID: EntityDataAccessor<String> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val DOG_TAG_ICON: EntityDataAccessor<List<List<Short>>> =
            SynchedEntityData.defineId(VehicleEntity::class.java, ModSerializers.SHORT_LIST_LIST_SERIALIZER.get())

        @JvmField
        val AI_TURRET_TARGET_UUID: EntityDataAccessor<String> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val AI_PASSENGER_WEAPON_TARGET_UUID: EntityDataAccessor<String> = SynchedEntityData.defineId(
            VehicleEntity::class.java, EntityDataSerializers.STRING
        )

        @JvmField
        val DELTA_ROT: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val MOUSE_SPEED_X: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val MOUSE_SPEED_Y: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val SELECTED_WEAPON: EntityDataAccessor<List<Int>> = SynchedEntityData.defineId(
            VehicleEntity::class.java, ModSerializers.INT_LIST_SERIALIZER.get()
        )

        @JvmField
        val SECONDARY_WEAPON: EntityDataAccessor<List<Int>> = SynchedEntityData.defineId(
            VehicleEntity::class.java, ModSerializers.INT_LIST_SERIALIZER.get()
        )

        @JvmField
        val TURRET_HEALTH: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val L_WHEEL_HEALTH: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val R_WHEEL_HEALTH: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val MAIN_ENGINE_HEALTH: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val SUB_ENGINE_HEALTH: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val TURRET_DAMAGED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val L_WHEEL_DAMAGED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val R_WHEEL_DAMAGED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val MAIN_ENGINE_DAMAGED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val SUB_ENGINE_DAMAGED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val HORN_VOLUME: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        var playTrackSound: Consumer<VehicleEntity?> = Consumer { }

        @JvmField
        var playEngineSound: Consumer<VehicleEntity?> = Consumer { }

        /** Client: the vehicle's engine and tracks are voiced by an authored audio profile. */
        @JvmField
        var authoredEngineAudio: java.util.function.Predicate<VehicleEntity> = java.util.function.Predicate { false }

        /** Client: the vehicle's turret slewing is voiced by an authored audio profile. */
        @JvmField
        var authoredTurretAudio: java.util.function.Predicate<VehicleEntity> = java.util.function.Predicate { false }

        @JvmField
        var tickCustomLoopSound: BiConsumer<VehicleEntity?, VehicleLoopSoundChannel> = BiConsumer { _, _ -> }

        @JvmField
        var playSwimSound: Consumer<VehicleEntity?> = Consumer { }

        @JvmField
        var playHornSound: Consumer<VehicleEntity?> = Consumer { }

        @JvmField
        var playStukaSound: Consumer<VehicleEntity?> = Consumer { }

        @JvmField
        var playHeliCrashSound: Consumer<VehicleEntity?> = Consumer { }

        @JvmField
        var playVehicleSkipSound: Consumer<VehicleEntity?> = Consumer { }

        @JvmField
        var playFireSound: Consumer<VehicleEntity>? = Consumer { }

        @JvmField
        val SERVER_YAW: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val SERVER_PITCH: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val VEHICLE_POSE_SNAPSHOT: EntityDataAccessor<String> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val VEHICLE_FLIGHT_INSTRUMENT_SNAPSHOT: EntityDataAccessor<String> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val WEAPON_SCHEDULER_SNAPSHOT: EntityDataAccessor<String> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val VEHICLE_AIM_SNAPSHOT: EntityDataAccessor<String> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val VEHICLE_ACTION_SNAPSHOT: EntityDataAccessor<String> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val MODULE_STATE_SNAPSHOT: EntityDataAccessor<String> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.STRING)

        @JvmField
        val CANNON_RECOIL_TIME: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.INT)

        @JvmField
        val CANNON_RECOIL_FORCE: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val POWER: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val YAW_WHILE_SHOOT: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val DECOY_READY: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val SYNCHED_PROPELLER_ROT: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val SYNCHED_GEAR_ROT: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val GEAR_UP: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val FORWARD_INPUT_DOWN: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val BACK_INPUT_DOWN: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val LEFT_INPUT_DOWN: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val RIGHT_INPUT_DOWN: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val UP_INPUT_DOWN: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val DOWN_INPUT_DOWN: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val DECOY_INPUT_DOWN: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val FIRE_INPUT_DOWN: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val SPRINT_INPUT_DOWN: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val PLANE_BREAK: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val ENERGY: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.INT)

        @JvmField
        val LASER_LENGTH: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val LASER_SCALE: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val LASER_SCALE_O: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val CHARGE_PROGRESS: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)

        @JvmField
        val IS_WRECK: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val SYMPATHETIC_DETONATED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val TURRET_BURNED: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        @JvmField
        val TURRET_BURN_TIMER: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.INT)

        @JvmField
        val HOVER_MODE: EntityDataAccessor<Boolean> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.BOOLEAN)

        // Map SeatIndex -> GunData
        protected val GUN_DATA_MAP: EntityDataAccessor<Map<String, GunData>> =
            SynchedEntityData.defineId(VehicleEntity::class.java, ModSerializers.VEHICLE_GUN_DATA_MAP_SERIALIZER.get())

        private val AIRCRAFT_COUNTERMEASURE_LEVELS: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.INT)
        private val AIRCRAFT_COUNTERMEASURE_TIMERS: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.INT)
        private val AIRCRAFT_WRECK_START: EntityDataAccessor<Long> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.LONG)
        private val AIRCRAFT_WRECK_MOTION_X: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)
        private val AIRCRAFT_WRECK_MOTION_Y: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)
        private val AIRCRAFT_WRECK_MOTION_Z: EntityDataAccessor<Float> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.FLOAT)
        private val AIRCRAFT_WRECK_WINGS: EntityDataAccessor<Int> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.INT)
        private val AIRCRAFT_WRECK_IMPACT_TIME: EntityDataAccessor<Long> =
            SynchedEntityData.defineId(VehicleEntity::class.java, EntityDataSerializers.LONG)
    }
}
