package com.atsuishio.superbwarfare.data.vehicle

import com.atsuishio.superbwarfare.Mod.Companion.loc
import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.annotation.ServerOnly
import com.atsuishio.superbwarfare.config.server.VehicleConfig
import com.atsuishio.superbwarfare.data.*
import com.atsuishio.superbwarfare.data.gun.DefaultGunData
import com.atsuishio.superbwarfare.data.vehicle.subdata.*
import com.atsuishio.superbwarfare.entity.vehicle.damage.DamageModify
import com.atsuishio.superbwarfare.serialization.kserializer.*
import com.atsuishio.superbwarfare.tools.toKxJson
import com.google.gson.JsonObject
import com.google.gson.JsonPrimitive
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.ForgeConfigSpec
import java.util.*
import kotlin.math.max

@Suppress("unused")
@Serializable
class DefaultVehicleData : IDBasedData<DefaultVehicleData> {
    @Transient
    @kotlinx.serialization.Transient
    private var id = ""

    @JvmField
    @Transient
    @kotlinx.serialization.Transient
    var isDefaultData: Boolean = true

    override fun getId(): String {
        return this.id
    }

    override fun setId(id: String) {
        this.id = id
    }

    @SerialName("MaxHealth")
    var maxHealth: Float = 50f

    /** Opt-in light-platform rule; only a direct projectile contact can trigger it. */
    @ServerOnly
    @SerialName("LethalDirectCaliberMm")
    var lethalDirectCaliberMm: Double? = null

    @ServerOnly
    @SerialName("RepairCooldown")
    var repairCooldown: Int = getConfigOrDefault(VehicleConfig.REPAIR_COOLDOWN)

    @ServerOnly
    @SerialName("RepairAmount")
    var repairAmount: Float = getConfigOrDefault(VehicleConfig.REPAIR_AMOUNT).toFloat()

    /**
     * 开始自动扣血时的血量比例
     */
    @ServerOnly
    @SerialName("SelfHurtPercent")
    var selfHurtPercent: Float = 0.1f

    /**
     * 自动扣血每tick扣血量
     */
    @ServerOnly
    @SerialName("SelfHurtAmount")
    var selfHurtAmount: Float = 0.1f

    @SerialName("MaxEnergy")
    var maxEnergy: Int = Int.MAX_VALUE

    @SerialName("OBB")
    var obb: MutableList<OBBInfo> = mutableListOf()

    @SerialName("AircraftTerrainContact")
    var aircraftTerrainContact: AircraftTerrainContact? = null

    @SerialName("AircraftSurfaceModules")
    var aircraftSurfaceModules: List<AircraftSurfaceModuleInfo> = emptyList()

    @SerialName("AircraftSurfaceTransforms")
    var aircraftSurfaceTransforms: Map<String, AircraftSurfaceTransform> = emptyMap()

    /** Validated named frames used by seats, cameras, weapons, HUDs, seekers, and fired visuals. */
    @SerialName("Attachments")
    var attachments: MutableMap<String, VehicleAttachmentInfo> = linkedMapOf()

    /** Optional typed native frame for an independently pitched roof coax. */
    @SerialName("RoofCoaxPitch")
    var roofCoaxPitch: VehicleRoofCoaxPitchFrame? = null

    /** Optional typed native frame for the independently pitched BMP-2M AGS-30 child. */
    @SerialName("Ags30Pitch")
    var ags30Pitch: VehicleAgs30PitchFrame? = null

    @SerialName("Seats")
    private var seats: ObjectToList<SeatInfo>? = ObjectToList()

    fun seats(): MutableList<SeatInfo> {
        if (seats == null) return mutableListOf()
        return Collections.unmodifiableList(seats!!.list)
    }

    /** Client-readable generated primary/secondary weapon declarations. */
    @SerialName("WeaponPairs")
    var weaponPairs: ObjectToList<VehicleWeaponPairData> = ObjectToList()

    @SerialName("UpStep")
    var upStep: Float = 0f

    @JvmField
    @SerialName("TrackDistanceMultiply")
    var trackDistanceMultiply: Double = 1.0

    @SerialName("KeepChunkLoaded")
    var keepChunkLoaded: Boolean = true

    /**
     * Server-side parking policy. When enabled, an intact unoccupied vehicle clears controls and horizontal momentum
     * after its engine step. The default remains false so existing Superb Warfare vehicles keep their legacy coast.
     */
    @ServerOnly
    @SerialName("ParkingBrakeWhenUnoccupied")
    var parkingBrakeWhenUnoccupied: Boolean = false

    @SerialName("MouseSensitivity")
    var mouseSensitivity: Double = 0.4

    @SerialName("PassengerRenderScale")
    var passengerRenderScale: Float = 1f

    @SerialName("AllowFreeCam")
    var allowFreeCam: Boolean = false

    @SerialName("HasDecoy")
    var hasDecoy: Boolean = false

    @SerialName("Countermeasures")
    var countermeasures: AircraftCountermeasureDefinition? = null

    @JvmField
    @ServerOnly
    @SerialName("ApplyDefaultDamageModifiers")
    var applyDefaultDamageModifiers: Boolean = true

    @ServerOnly
    @SerialName("LightlyArmored")
    var lightlyArmored: Boolean = false

    /**
     * Damage class of the hull (MBT, MBT_CHASSIS, IFV, WHEELED, LIGHT, CAR, STATIC, AIRPLANE, HELICOPTER; written
     * by tools/damage/balance.py). Scales area blast damage ([com.atsuishio.superbwarfare.tools.blast.VehicleDamageClass]).
     */
    @SerialName("DamageClass")
    var damageClass: String? = null

    /** TNT-equivalent kg of fuel and ammunition that go up when the hull is destroyed (0 = legacy explosion). */
    @ServerOnly
    @SerialName("DeathChargeKg")
    var deathChargeKg: Double = 0.0

    @SerialName("Afterburner")
    var afterburner: Boolean = false

    @ServerOnly
    @SerialName("SendHitParticles")
    var sendHitParticles: Boolean = true

    @JvmField
    @ServerOnly
    @SerialName("DamageModifiers")
    var damageModifiers: ObjectToList<StringToObject<DamageModify>> = ObjectToList()

    @ServerOnly
    @SerialName("Mass")
    var mass: Float = 1f

    @ServerOnly
    @SerialName("DestroyInfo")
    var destroyInfo: DestroyInfo = DestroyInfo()

    @SerialName("SeekInfo")
    var seekInfo: SeekInfo? = null

    @SerialName("VehicleContainerType")
    var vehicleContainerType: VehicleContainerType = VehicleContainerType.MEDIUM

    @SerialName("HasUpgradeSlots")
    var hasUpgradeSlots: Boolean = false

    @SerialName("VehicleIcon")
    var vehicleIcon: SerializedResourceLocation = loc("textures/gun_icon/default_icon.png")

    @SerialName("ContainerIcon")
    var containerIcon: SerializedResourceLocation? = null

    @SerialName("HUDColor")
    var hudColor: ModColor = ModColor(0x66FF00)

    @SerialName("Type")
    var type: VehicleType = VehicleType.EMPTY

    @SerialName("EngineType")
    var engineType: EngineType = EngineType.EMPTY

    /** Typed propulsion authority; false vehicles retain gravity/collision but cannot drive. */
    @ServerOnly
    @SerialName("DriveAuthority")
    var driveAuthority: Boolean = true

    @SerialName("EngineInfo")
    var engineInfo: SerializedGsonObject = JsonObject()

    // 引擎音效
    @SerialName("EngineSound")
    var engineSound: SerializedSoundEvent = SoundEvents.EMPTY

    /** Selects the native engine loop, a custom provider, or silence. Existing data defaults to native behavior. */
    @SerialName("EngineSoundMode")
    var engineSoundMode: VehicleLoopSoundMode = VehicleLoopSoundMode.NATIVE

    /** Selects the native tracked-running loop, a custom provider, or silence independently of the engine loop. */
    @SerialName("TrackSoundMode")
    var trackSoundMode: VehicleLoopSoundMode = VehicleLoopSoundMode.NATIVE

    /** Namespaced lookup key for CUSTOM engine and/or track loop providers. */
    @SerialName("CustomSoundProfileId")
    var customSoundProfileId: SerializedResourceLocation? = null

    // 喇叭音效
    @SerialName("HornSound")
    var hornSound: SerializedSoundEvent = SoundEvents.EMPTY

    // 第三人称视角
    @SerialName("ThirdPersonCameraPos")
    var thirdPersonCameraPos: SerializedVec3 = Vec3(0.0, 1.0, 3.0)

    @SerialName("HasLowHealthWarning")
    var hasLowHealthWarning: Boolean = true

    @SerialName("RotateOffsetHeight")
    var rotateOffsetHeight: Float = 0f

    @SerialName("Weapons")
    private var weapons: MutableMap<String, SerializedGsonObject> = mutableMapOf()

    @Transient
    @kotlinx.serialization.Transient
    private var processedWeapons: MutableMap<String, DefaultGunData>? = null

    fun weapons(): MutableMap<String, DefaultGunData> {
        if (processedWeapons != null) return processedWeapons!!

        val map = hashMapOf<String, DefaultGunData>()

        for (entry in weapons.entries) {
            var value = entry.value
            value = value.deepCopy()

            val primitive = value.get("Template")

            if (primitive is JsonPrimitive && primitive.isString) {
                value.remove("Template")
                val templateValue = weapons[primitive.getAsString()]
                if (templateValue != null) {
                    val newValue = templateValue.deepCopy()
                    for (kv in value.entrySet()) {
                        newValue.add(kv.key, kv.value)
                    }
                    value = newValue
                }
            }

            val decoded = try {
                DataLoader.JSON.decodeFromJsonElement(
                    DefaultGunData.serializer(),
                    value.asJsonObject.toKxJson()
                )
            } catch (exception: Exception) {
                // A malformed optional weapon must not poison every valid weapon on this
                // vehicle.  Skip it once for this cached decode; selection/scheduling then
                // fail closed for that identity without recursively retrying the profile.
                Mod.LOGGER.error(
                    "Skipping malformed weapon '${entry.key}' for vehicle '${this.id}'",
                    exception,
                )
                continue
            }
            // Ground vehicle stations have no heat mechanic, including alternate/template weapons.
            if (type !in setOf(VehicleType.AIRPLANE, VehicleType.HELICOPTER, VehicleType.DRONE, VehicleType.BOAT)) {
                decoded.overheatEnabled = false
                decoded.heatPerShoot = 0.0
            }
            map[entry.key] = decoded
        }

        processedWeapons = Collections.unmodifiableMap(map)
        return processedWeapons!!
    }

    /**
     * 碰撞等级，范围是0~4
     * 0 - 无法撞坏方块
     * 1 - 允许撞坏软方块
     * 2 - 允许撞坏普通方块
     * 3 - 允许撞坏硬方块
     * 4 - 允许野兽撞击模式
     */
    @SerialName("CollisionLevel")
    var collisionLevel: CollisionLevel = CollisionLevel()

    // 主武器位
    @SerialName("TurretPos")
    var turretPos: SerializedVec3? = null

    @SerialName("TurretTurnSpeed")
    var turretTurnSpeed: SerializedVec2 = Vec2(5f, 5f)

    @SerialName("TurretYawRange")
    var turretYawRange: SerializedVec2 = Vec2(-514f, 514f)

    @SerialName("TurretPitchRange")
    var turretPitchRange: SerializedVec2 = Vec2(-10f, 30f)

    @SerialName("TurretControllerIndex")
    var turretControllerIndex: Int = 0

    @SerialName("TurretCustomPitch")
    var turretCustomPitch: Float = 0f

    @SerialName("HudType")
    var hudType: String = "@Empty"

    @SerialName("BarrelPos")
    var barrelPos: SerializedVec3 = Vec3.ZERO

    // 乘客位武器
    @SerialName("PassengerWeaponStationPos")
    var passengerWeaponStationPos: SerializedVec3? = null

    @SerialName("PassengerWeaponStationBarrelPos")
    var passengerWeaponStationBarrelPos: SerializedVec3 = Vec3.ZERO

    /**
     * Optional typed binding for arbitrary passenger-bed stations.  Null preserves the legacy
     * PassengerMachineGun/turret-relative contract; HULL bindings are validated by VehicleEntity
     * against their authored marker attachments before becoming active.
     */
    @SerialName("PassengerWeaponStationBinding")
    var passengerWeaponStationBinding: PassengerWeaponStationBinding? = null

    @SerialName("PassengerWeaponStationTurnSpeed")
    var passengerWeaponStationTurnSpeed: SerializedVec2 = Vec2(5f, 5f)

    @SerialName("PassengerWeaponStationYawRange")
    var passengerWeaponStationYawRange: SerializedVec2 = Vec2(-514f, 514f)

    @SerialName("PassengerWeaponStationPitchRange")
    var passengerWeaponStationPitchRange: SerializedVec2 = Vec2(-10f, 30f)

    @SerialName("PassengerWeaponStationControllerIndex")
    var passengerWeaponStationControllerIndex: Int = 1

    /** Marks a vehicle's passenger weapon station as a remote weapon station for client-side CCIP admission. */
    @SerialName("RemoteWeaponStation")
    var remoteWeaponStation: Boolean = false

    @SerialName("HasCCIP")
    var hasCCIP: Boolean = false

    @SerialName("HasFCS")
    var hasFCS: Boolean = false

    @SerialName("HasERA")
    var hasERA: Boolean = false

    /** Client-readable authored optical magnification bounds: [minimum, maximum]. */
    @SerialName("OpticalZoomRange")
    var opticalZoomRange: SerializedVec2 = Vec2(2f, 12f)

    @SerialName("HasNVD")
    var hasNVD: Boolean = false

    @SerialName("HasTVD")
    var hasTVD: Boolean = false

    @SerialName("HasAutoloader")
    var hasAutoloader: Boolean = false

    @SerialName("Fires")
    var fires: MutableList<String> = mutableListOf()

    @JvmField
    @SerialName("UsePassengerCreativeAmmoBox")
    var usePassengerCreativeAmmoBox: Boolean = true

    @SerialName("Gravity")
    var gravity: Double = 0.06

    @SerialName("TerrainCompat")
    var terrainCompat: MutableList<SerializedVec3> = mutableListOf()

    @SerialName("TerrainCompatRotateRate")
    var terrainCompatRotateRate: Float = 1f

    /** Fit a common support plane instead of applying independent contact torques. */
    @SerialName("TerrainCompatFitPlane")
    var terrainCompatFitPlane: Boolean = false

    // 受惯性影响的旋转幅度
    @SerialName("InertiaRotateRate")
    var inertiaRotateRate: Float = 0f

    override fun limit() {
        com.atsuishio.superbwarfare.api.aircraft.AircraftSurfaceModules.validate(this)
        require(aircraftTerrainContact?.valid() != false) { "AircraftTerrainContact requires two finite nonempty boxes" }
        this.maxHealth = max(this.maxHealth, 0f)
        this.repairCooldown = max(this.repairCooldown, 0)
        this.maxEnergy = max(this.maxEnergy, 0)
        this.obb = this.obb.map {
            it.limit()
            it
        }.toMutableList()

        this.collisionLevel.level = this.collisionLevel.level.coerceIn(0, 4)
    }

    companion object {
        private fun <T> getConfigOrDefault(config: ForgeConfigSpec.ConfigValue<T>): T {
            return try {
                config.get()
            } catch (exception: Exception) {
                config.getDefault()
            }
        }
    }
}
