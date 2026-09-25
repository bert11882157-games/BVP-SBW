package com.atsuishio.superbwarfare.network

import com.atsuishio.superbwarfare.network.PacketDelivery.*
import com.atsuishio.superbwarfare.network.PacketDirection.PLAY_TO_CLIENT
import com.atsuishio.superbwarfare.network.PacketDirection.PLAY_TO_SERVER
import com.atsuishio.superbwarfare.network.PacketPriority.*
import com.atsuishio.superbwarfare.network.message.receive.*
import com.atsuishio.superbwarfare.network.message.send.*
import com.atsuishio.superbwarfare.serialization.ByteBufDecoder
import com.atsuishio.superbwarfare.serialization.ByteBufEncoder
import kotlinx.serialization.serializer
import net.minecraft.network.FriendlyByteBuf
import java.util.function.BiConsumer
import java.util.function.Function

private inline fun <reified T> encodeTo(
    registered: RegisteredPacketSchema,
    output: FriendlyByteBuf,
    value: T,
) {
    val startIndex = output.writerIndex()
    val startedAt = if (NetworkTelemetry.isEnabled()) System.nanoTime() else 0L
    try {
        ByteBufEncoder(output, registered.schema.limits).encodeSerializableValue(serializer(), value)
        val encodedBytes = output.writerIndex() - startIndex
        require(encodedBytes <= registered.schema.limits.maxWireBytes) {
            "${registered.type.simpleName} encoded $encodedBytes bytes; limit=${registered.schema.limits.maxWireBytes}"
        }
        if (startedAt != 0L) {
            NetworkTelemetry.recordEncode(registered, encodedBytes, System.nanoTime() - startedAt)
        }
    } catch (exception: RuntimeException) {
        NetworkTelemetry.recordReject(registered, PacketRejectReason.MALFORMED)
        throw exception
    }
}

private inline fun <reified T> decodeFrom(
    registered: RegisteredPacketSchema,
    input: FriendlyByteBuf,
): T {
    val wireBytes = input.readableBytes()
    val startedAt = if (NetworkTelemetry.isEnabled()) System.nanoTime() else 0L
    try {
        require(wireBytes <= registered.schema.limits.maxWireBytes) {
            "${registered.type.simpleName} declared $wireBytes bytes; limit=${registered.schema.limits.maxWireBytes}"
        }
        return ByteBufDecoder(input, registered.schema.limits).decodeSerializableValue(serializer<T>()).also {
            if (startedAt != 0L) {
                NetworkTelemetry.recordDecode(registered, wireBytes, System.nanoTime() - startedAt)
            }
        }
    } catch (exception: RuntimeException) {
        NetworkTelemetry.recordReject(registered, PacketRejectReason.MALFORMED)
        throw exception
    }
}

private inline fun <reified T : PacketPayload> playTo(
    schema: PacketSchema,
    expectedDirection: PacketDirection,
    reg: (Int, BiConsumer<T, FriendlyByteBuf>, Function<FriendlyByteBuf, T>, BiConsumer<T, PayloadContext>) -> Unit,
) {
    require(schema.direction == expectedDirection) {
        "Wrong direction for ${T::class.java.name}: ${schema.direction}, expected $expectedDirection"
    }
    NetworkPacketManifest.register(schema, T::class.java)
    val registered = NetworkPacketManifest.schemaFor(T::class.java)
    val instance = T::class.objectInstance
    if (instance != null) {
        reg(
            schema.id,
            { _, _ -> NetworkTelemetry.recordEncode(registered, 0, 0) },
            { input ->
                try {
                    val bytes = input.readableBytes()
                    require(bytes == 0) { "${registered.type.simpleName} must not contain a payload" }
                    NetworkTelemetry.recordDecode(registered, 0, 0)
                    instance
                } catch (exception: RuntimeException) {
                    NetworkTelemetry.recordReject(registered, PacketRejectReason.MALFORMED)
                    throw exception
                }
            },
            { msg, context -> msg.handleInternal(msg, context) },
        )
    } else {
        reg(
            schema.id,
            { value, buf -> encodeTo(registered, buf, value) },
            { buf -> decodeFrom(registered, buf) },
            { msg, context -> msg.handleInternal(msg, context) },
        )
    }
}

private inline fun <reified T : ServerPacketPayload> playToServer(schema: PacketSchema) {
    playTo<T>(schema, PLAY_TO_SERVER) { id, enc, dec, handler ->
        NetworkRegistry.playToServer(id, T::class.java, enc, dec, handler)
    }
}

private inline fun <reified T : ClientPacketPayload> playToClient(schema: PacketSchema) {
    playTo<T>(schema, PLAY_TO_CLIENT) { id, enc, dec, handler ->
        NetworkRegistry.playToClient(id, T::class.java, enc, dec, handler)
    }
}

private fun clientSchema(
    id: Int,
    limits: PacketCodecLimits = PacketLimitProfiles.TINY,
    delivery: PacketDelivery = LATEST_SNAPSHOT,
    priority: PacketPriority = P2,
    owner: String = "network.client",
    feature: String = "legacy",
    since: Int = 1,
) = PacketSchema(id, PLAY_TO_CLIENT, since, limits = limits, delivery = delivery, priority = priority,
    handlerOwner = owner, feature = feature)

private fun serverSchema(
    id: Int,
    limits: PacketCodecLimits = PacketLimitProfiles.TINY,
    delivery: PacketDelivery = RELIABLE_EDGE,
    priority: PacketPriority = P0,
    owner: String = "network.server",
    feature: String = "legacy",
    since: Int = 1,
    rate: Int = 20,
    burst: Int = rate * 2,
) = PacketSchema(id, PLAY_TO_SERVER, since, limits = limits, delivery = delivery, priority = priority,
    handlerOwner = owner, feature = feature, ratePerSecond = rate, burst = burst)

fun initializeNetwork() {
    registerPayloads()
    NetworkPacketManifest.seal()
}

private fun registerPayloads() {
    playToClient<ClientIndicatorMessage>(clientSchema(0, delivery = REPLACEABLE_PRESENTATION, owner = "combat.feedback"))
    playToClient<ClientSetMotionMessage>(clientSchema(1, delivery = EXACT_EVENT, priority = P0, owner = "player.motion"))
    playToClient<DataSyncMessage>(clientSchema(2, PacketLimitProfiles.DATASET, BULK_STATE, P0, "data.reload", "dataset_sync"))
    playToClient<ClientMotionSyncMessage>(clientSchema(3, delivery = LATEST_SNAPSHOT, priority = P1, owner = "projectile.motion"))
    playToClient<ClientPhosphorusFireMessage>(clientSchema(4, delivery = RELIABLE_EDGE, priority = P1, owner = "combat.effect"))
    playToClient<ContainerDataMessage>(clientSchema(5, PacketLimitProfiles.CONTROL, LATEST_SNAPSHOT, P2, "menu.energy"))
    playToClient<DrawClientMessage>(clientSchema(6, delivery = RELIABLE_EDGE, owner = "gun.presentation"))
    playToClient<FinishAssemblingVehicleMessage>(clientSchema(7, delivery = EXACT_EVENT, priority = P1, owner = "vehicle.assembly"))
    playToClient<FiredVisualMessage>(clientSchema(8, PacketLimitProfiles.PRESENTATION, EXACT_EVENT, P1, "weapon.fired_visual", "fired_visual", 5))
    playToClient<LivingGunKillMessage>(clientSchema(9, delivery = REPLACEABLE_PRESENTATION, owner = "combat.kill_feed"))
    playToClient<PlayerVariablesSyncMessage>(clientSchema(10, PacketLimitProfiles.CONTROL, LATEST_SNAPSHOT, P1, "player.capability"))
    playToClient<RadarMenuCloseMessage>(clientSchema(11, delivery = RELIABLE_EDGE, owner = "radar.menu"))
    playToClient<RadarMenuOpenMessage>(clientSchema(12, delivery = RELIABLE_EDGE, owner = "radar.menu"))
    playToClient<ResetCameraTypeMessage>(clientSchema(13, delivery = RELIABLE_EDGE, priority = P1, owner = "camera"))
    playToClient<ShakeClientMessage>(clientSchema(14, delivery = REPLACEABLE_PRESENTATION, owner = "fx.shake"))
    playToClient<ShootClientMessage>(clientSchema(15, delivery = EXACT_EVENT, owner = "gun.presentation"))
    playToClient<SoundClientMessage>(clientSchema(16, PacketLimitProfiles.PRESENTATION, REPLACEABLE_PRESENTATION, P2, "fx.sound"))
    playToClient<TDMSyncMessage>(clientSchema(17, PacketLimitProfiles.PRESENTATION, LATEST_SNAPSHOT, P2, "team.sync"))
    playToClient<EntitySyncMessage>(clientSchema(18, PacketLimitProfiles.CONTACT_STATE, LATEST_SNAPSHOT, P3, "contact.legacy", "legacy_contact"))
    playToClient<PlayerInfoSyncMessage>(clientSchema(19, PacketLimitProfiles.PRESENTATION, LATEST_SNAPSHOT, P3, "contact.player", "legacy_contact"))
    playToClient<ClientVehicleItemMessage>(clientSchema(20, PacketLimitProfiles.NBT_STATE, LATEST_SNAPSHOT, P1, "vehicle.inventory"))

    playToServer<AdjustMortarAngleMessage>(serverSchema(21, rate = 20))
    playToServer<AdjustZoomFovMessage>(serverSchema(22, rate = 20))
    playToServer<AimVillagerMessage>(serverSchema(23, owner = "npc.aim", rate = 10))
    playToServer<AssembleVehicleMessage>(serverSchema(24, owner = "vehicle.assembly", rate = 4))
    playToServer<ChangeVehicleSeatMessage>(serverSchema(25, owner = "vehicle.seat", rate = 10))
    playToServer<ActiveThermalImagingMessage>(serverSchema(26, owner = "player.thermal", rate = 10))
    playToServer<ArtilleryIndicatorFireMessage>(serverSchema(27, owner = "weapon.artillery", rate = 4))
    playToServer<DogTagFinishEditMessage>(serverSchema(28, PacketLimitProfiles.CONTROL, owner = "item.dog_tag", rate = 4))
    playToServer<DoubleJumpMessage>(serverSchema(29, owner = "player.movement", rate = 4))
    playToServer<DroneFireMessage>(serverSchema(30, owner = "vehicle.drone", rate = 10))
    playToServer<EditMessage>(serverSchema(31, owner = "gun.edit", rate = 10))
    playToServer<FireKeyMessage>(serverSchema(32, owner = "gun.input", rate = 80, burst = 160))
    playToServer<FireModeMessage>(serverSchema(33, owner = "gun.mode", rate = 10))
    playToServer<FiringParametersEditMessage>(serverSchema(34, owner = "weapon.artillery", rate = 10))
    playToServer<GunReforgeMessage>(serverSchema(35, owner = "gun.reforge", rate = 4))
    playToServer<InteractMessage>(serverSchema(36, owner = "player.interact", rate = 20))
    playToServer<LungeMineAttackMessage>(serverSchema(37, owner = "combat.lunge", rate = 4))
    playToServer<MeleeAttackMessage>(serverSchema(38, PacketLimitProfiles.CONTROL, owner = "combat.melee", rate = 10))
    playToServer<MouseMoveMessage>(serverSchema(39, delivery = LATEST_SNAPSHOT, priority = P1, owner = "vehicle.input", rate = 40, burst = 80))
    playToServer<ParachuteMessage>(serverSchema(40, owner = "player.parachute", rate = 4))
    playToServer<PlayerStopRidingMessage>(serverSchema(41, owner = "vehicle.seat", feature = "dismount_double_tap", since = 25, rate = 12, burst = 24))
    playToServer<RadarChangeModeMessage>(serverSchema(42, owner = "radar.control", rate = 10))
    playToServer<RadarSetPosMessage>(serverSchema(43, owner = "radar.control", rate = 10))
    playToServer<RadarSetTargetMessage>(serverSchema(44, owner = "radar.control", rate = 10))
    playToServer<RadarSetParametersMessage>(serverSchema(45, owner = "radar.control", rate = 10))
    playToServer<ReloadMessage>(serverSchema(46, owner = "gun.reload", rate = 10))
    playToServer<SeekingWeaponWarningMessage>(serverSchema(47, delivery = REPLACEABLE_PRESENTATION, priority = P2, owner = "weapon.seeker", rate = 10))
    playToServer<SensitivityMessage>(serverSchema(48, owner = "gun.settings", rate = 10))
    playToServer<SetFiringParametersMessage>(serverSchema(49, owner = "weapon.artillery", rate = 4))
    playToServer<SetPerkLevelMessage>(serverSchema(50, owner = "gun.reforge", rate = 10))
    playToServer<ShootMessage>(serverSchema(51, delivery = EXACT_EVENT, owner = "gun.fire", rate = 512, burst = 1024))
    playToServer<ShowChargingRangeMessage>(serverSchema(52, owner = "menu.charging", rate = 10))
    playToServer<SwitchScopeMessage>(serverSchema(53, owner = "gun.scope", rate = 10))
    playToServer<SwitchVehicleWeaponMessage>(serverSchema(54, owner = "vehicle.weapon", rate = 20))
    playToServer<UnloadMessage>(serverSchema(55, owner = "gun.reload", rate = 4))
    playToServer<VehicleActionInputMessage>(serverSchema(56, PacketLimitProfiles.CONTROL, owner = "vehicle.action", feature = "vehicle_action", since = 3, rate = 40, burst = 80))
    playToServer<VehicleFireMessage>(serverSchema(57, delivery = RELIABLE_EDGE, owner = "vehicle.weapon", rate = 40, burst = 80))
    playToServer<VehicleMovementMessage>(serverSchema(58, delivery = LATEST_SNAPSHOT, priority = P1, owner = "vehicle.input", rate = 40, burst = 80))
    playToServer<WeaponZoomingMessage>(serverSchema(59, delivery = LATEST_SNAPSHOT, priority = P1, owner = "gun.input", rate = 40, burst = 80))
    playToServer<ZoomMessage>(serverSchema(60, owner = "vehicle.zoom", rate = 10))
    playToServer<BlueprintCraftMessage>(serverSchema(61, owner = "blueprint.craft", rate = 4))
    playToServer<BlueprintSetIndexMessage>(serverSchema(62, owner = "blueprint.menu", rate = 10))

    playToClient<ExplosionBurstMessage>(clientSchema(63, PacketLimitProfiles.PRESENTATION, REPLACEABLE_PRESENTATION, P2, "fx.explosion", "explosion_burst", 3))
    playToServer<VehicleHelicopterAtgmCameraRayMessage>(serverSchema(64, PacketLimitProfiles.TINY, LATEST_SNAPSHOT, P1,
        "vehicle.atgm_camera_ray", "vehicle_atgm_camera_ray", 9, rate = 20, burst = 40))
    playToServer<CycleVehicleWeaponSlotMessage>(serverSchema(65, owner = "vehicle.weapon", feature = "weapon_slot_cycle", since = 12,
        rate = 10, burst = 20))
    playToServer<SetVehicleGeometricZeroDistanceMessage>(serverSchema(66, owner = "vehicle.aim", feature = "geometric_zero_fcs", since = 14,
        rate = 10, burst = 20))
    playToClient<VehicleGeometricZeroDistanceAckMessage>(clientSchema(67, delivery = RELIABLE_EDGE, priority = P1,
        owner = "vehicle.aim", feature = "geometric_zero_fcs", since = 14))
    playToClient<VehicleReloadSoundMessage>(clientSchema(68, delivery = EXACT_EVENT, priority = P1,
        owner = "vehicle.audio", feature = "reload_cycle_audio", since = 15))
    playToClient<EliteDiagnosticsStateMessage>(clientSchema(69, delivery = RELIABLE_EDGE, priority = P1,
        owner = "diagnostics.elite", feature = "elite_diagnostics", since = 15))
    playToClient<FarVehicleFrameMessage>(clientSchema(70, PacketLimitProfiles.FAR_RENDER, LATEST_SNAPSHOT, P3,
        "vehicle.far_render", "far_vehicle_frame", 26))
    playToServer<FixedWingPilotIntentMessage>(serverSchema(71, delivery = LATEST_SNAPSHOT, priority = P1,
        owner = "vehicle.flight", feature = "fixed_wing_pilot_intent", since = 24, rate = 20, burst = 40))
    playToClient<FixedWingPilotIntentStateMessage>(clientSchema(72, delivery = LATEST_SNAPSHOT, priority = P1,
        owner = "vehicle.flight", feature = "fixed_wing_pilot_intent", since = 24))
    playToServer<AircraftArmamentRequestMessage>(serverSchema(73, PacketLimitProfiles.AIRCRAFT_REQUEST,
        delivery = RELIABLE_EDGE, priority = P1, owner = "vehicle.aircraft", feature = "aircraft_armament", since = 23,
        rate = 20, burst = 40))
    playToClient<AircraftArmamentStateMessage>(clientSchema(74, PacketLimitProfiles.AIRCRAFT_STATE,
        delivery = RELIABLE_EDGE, priority = P1, owner = "vehicle.aircraft", feature = "aircraft_armament", since = 23))
    playToServer<FarTerrainRequest>(serverSchema(75, PacketLimitProfiles.CONTROL,
        owner = "vehicle.far_terrain", feature = "far_terrain", since = 31, rate = 20, burst = 40))
    playToClient<FarTerrainPlan>(clientSchema(76, PacketLimitProfiles.FAR_TERRAIN, RELIABLE_EDGE, P3,
        "vehicle.far_terrain", "far_terrain", 26))
    playToClient<FarTerrainChunk>(clientSchema(77, PacketLimitProfiles.FAR_TERRAIN, BULK_STATE, P3,
        "vehicle.far_terrain", "far_terrain", 26))
    playToServer<FarTerrainAck>(serverSchema(78, PacketLimitProfiles.FAR_TERRAIN_CONTROL,
        owner = "vehicle.far_terrain", feature = "far_terrain", since = 26, rate = 20, burst = 40))
    playToClient<FarProjectileStateMessage>(clientSchema(79, delivery = RELIABLE_EDGE, priority = P1,
        owner = "projectile.far_simulation", feature = "far_projectile_pause", since = 28))
    playToClient<FriendlyVehicleStateMessage>(clientSchema(80, PacketLimitProfiles.CONTROL,
        delivery = LATEST_SNAPSHOT, priority = P2, owner = "vehicle.friend_foe",
        feature = "project_rose_occupied_vehicle", since = 36))
    playToClient<SonicBoomMessage>(clientSchema(81, PacketLimitProfiles.PRESENTATION,
        delivery = REPLACEABLE_PRESENTATION, priority = P2, owner = "fx.sonic_boom",
        feature = "sonic_boom", since = 41))
    playToClient<ShockwaveMessage>(clientSchema(82, PacketLimitProfiles.PRESENTATION,
        delivery = REPLACEABLE_PRESENTATION, priority = P2, owner = "fx.shockwave",
        feature = "tnt_shockwave", since = 43))
}
