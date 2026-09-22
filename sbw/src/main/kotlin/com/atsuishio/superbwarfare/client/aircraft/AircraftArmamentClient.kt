package com.atsuishio.superbwarfare.client.aircraft

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.client.camera.FixedWingDynamicCamera
import com.atsuishio.superbwarfare.client.camera.VehicleFreeCameraController
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.AircraftArmamentNetwork
import com.atsuishio.superbwarfare.tools.worldToScreen
import com.google.gson.JsonObject
import com.google.gson.JsonArray
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.CameraType
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.network.chat.Component
import net.minecraft.world.phys.Vec2
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.ClientPlayerNetworkEvent
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.client.event.ViewportEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.event.level.LevelEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.lwjgl.glfw.GLFW
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.hypot

/** Server snapshots alone own equipment/designation. Local state owns only UI draft and pod view. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object AircraftArmamentClient {
    private val cache = AircraftArmamentStateCache()
    private var level: Any? = null
    private var podVehicle: VehicleEntity? = null
    private var previousView: CameraType? = null
    private var exitPending: UUID? = null
    private val aim = AircraftPodStabilizer()
    private var podZoom = 1.0
    private var lockTargetEntity: net.minecraft.world.entity.Entity? = null
    private var selectedPair: String? = null
    private var selectedOwner: UUID? = null
    private var warnings = 8

    private fun pilot(): VehicleEntity? {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return null
        val vehicle = player.vehicle as? VehicleEntity ?: return null
        return vehicle.takeIf { player.isAlive && !player.isSpectator && !it.isRemoved && !it.isWreck &&
            it.level() === mc.level &&
            it.getSeatIndex(player) == 0 && it.getNthEntity(0) === player }
    }

    fun controlsActive(): Boolean = Minecraft.getInstance().screen == null &&
        Minecraft.getInstance().isWindowActive &&
        pilot()?.let { getVehicleSnapshot(it) != null } == true

    @JvmStatic fun getState(id: UUID): JsonObject? = cache.get(id)?.json
    @JvmStatic fun getSnapshot(id: UUID): AircraftArmamentSnapshot? = cache.get(id)?.snapshot
    @JvmStatic fun getVehicleSnapshot(vehicle: VehicleEntity): AircraftArmamentSnapshot? =
        cache.get(vehicle.uuid)?.snapshot?.takeIf { level === vehicle.level() && it.entityId == vehicle.id }
    @JvmStatic fun getAllStoreBones(id: UUID): Set<String> = cache.get(id)?.allBones ?: emptySet()
    @JvmStatic fun getVisibleStoreBones(id: UUID): Set<String> = cache.get(id)?.visibleBones ?: emptySet()
    @JvmStatic fun getCatalogueRevision(id: UUID): Long = cache.get(id)?.catalogueRevision ?: -1L

    @JvmStatic fun receive(json: JsonObject) {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) {
            val copied = json.deepCopy()
            mc.execute { receive(copied) }
            return
        }
        if (mc.level == null) return
        syncLevel()
        val open = try {
            json.get("Open")?.let { require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean); it.asBoolean } ?: false
        } catch (_: RuntimeException) { return }
        val previousPointRevision = pilot()?.let { cache.get(it.uuid)?.pointRevision } ?: -1L
        val entry = cache.receive(json) ?: run {
            if (warnings > 0) { warnings--; Mod.LOGGER.warn("Rejected malformed/stale aircraft armament snapshot") }
            return
        }
        val state = entry.snapshot
        val vehicle = pilot() ?: return
        if (vehicle.uuid != state.vehicle || vehicle.id != state.entityId) return
        val seek = state.seek
        if (lockTargetEntity?.uuid != seek?.target || lockTargetEntity?.isRemoved == true)
            lockTargetEntity = seek?.target?.let { id -> mc.level?.entitiesForRendering()?.firstOrNull { it.uuid == id } }
        if (entry.pointRevision > previousPointRevision && state.point != null && isPodActive(vehicle)) aim.designate(state.point)
        if (selectedOwner != state.vehicle) { selectedOwner = state.vehicle; selectedPair = null }
        if (state.definition.mounts.none { it.id == selectedPair }) selectedPair = state.definition.mounts.firstOrNull()?.id
        if (!state.podActive) { if (exitPending == state.vehicle) exitPending = null; leavePod() }
        if (json.has("Definition")) (mc.screen as? AircraftLoadoutScreen)?.accept(state)
        if (open) {
            if (mc.screen !is AircraftLoadoutScreen) mc.setScreen(AircraftLoadoutScreen(state))
        } else if (state.message.isNotBlank() && json.has("Definition")) {
            mc.player?.displayClientMessage(Component.literal(state.message), true)
        }
    }

    fun request(operation: String, payload: JsonObject = JsonObject()): Boolean {
        val vehicle = pilot() ?: return false
        if (payload.toString().length > 4096) {
            Minecraft.getInstance().player?.displayClientMessage(Component.literal("Aircraft request exceeds its safe size limit"), true)
            return false
        }
        AircraftArmamentNetwork.request(operation, vehicle.uuid, payload)
        return true
    }

    fun podManualMask(): Int {
        if (!controlsActive() || !Minecraft.getInstance().mouseHandler.isMouseGrabbed) return 0
        var mask = 0
        for (index in AircraftArmamentKeys.flightMappings.indices) {
            val mapping = AircraftArmamentKeys.flightMappings[index]
            if (mapping.isDown && mapping.isConflictContextAndModifierActive) mask = mask or (1 shl index)
        }
        return mask
    }

    fun legacyPodAxes(vehicle: VehicleEntity): Vec2 = AircraftPodAim.legacyAxes(podManualMask(), vehicle.mouseSensitivity)

    @JvmStatic fun isPodActive(vehicle: VehicleEntity?): Boolean = vehicle != null && podVehicle === vehicle &&
        pilot() === vehicle && exitPending != vehicle.uuid &&
        Minecraft.getInstance().options.cameraType == CameraType.FIRST_PERSON &&
        !VehicleFreeCameraController.hasPresentation(Minecraft.getInstance().player, vehicle) &&
        getVehicleSnapshot(vehicle)?.let { it.podActive && it.definition.pod != null } == true

    private fun leavePod() {
        val mc = Minecraft.getInstance()
        val leavingActivePod = podVehicle != null
        if (podVehicle != null && mc.options.cameraType == CameraType.FIRST_PERSON) {
            previousView?.let(mc.options::setCameraType)
        }
        podVehicle = null; previousView = null
        aim.reset(); podZoom = 1.0
        if (leavingActivePod) FixedWingDynamicCamera.reset()
    }

    private fun syncLevel() {
        val current = Minecraft.getInstance().level
        if (level !== current) {
            com.atsuishio.superbwarfare.client.renderer.FriendlyVehicleMarker.clear()
            leavePod(); cache.clear(); level = current
            selectedOwner = null; selectedPair = null; exitPending = null; warnings = 8
            AircraftSeekerHud.reset(); lockTargetEntity = null
        }
    }

    @SubscribeEvent fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        syncLevel()
        val mc = Minecraft.getInstance()
        val vehicle = pilot()
        val state = vehicle?.let(::getVehicleSnapshot)
        if (vehicle == null) { lockTargetEntity = null }
        AircraftSeekerSounds.update(if (vehicle != null && mc.screen == null && mc.isWindowActive) state?.seek else null)
        if (vehicle != null && state?.podActive == true && exitPending != vehicle.uuid &&
            VehicleFreeCameraController.hasPresentation(mc.player, vehicle)) {
            exitPending = vehicle.uuid
            leavePod()
            request("POD", JsonObject().apply { addProperty("Active", false) })
            return
        }
        if (vehicle == null || state?.podActive != true || state.definition.pod == null || exitPending == vehicle.uuid) {
            leavePod(); return
        }
        if (podVehicle != null && podVehicle !== vehicle) leavePod()
        if (podVehicle == null) {
            previousView = mc.options.cameraType
            podVehicle = vehicle
            mc.options.setCameraType(CameraType.FIRST_PERSON)
            val pod = state.definition.pod
            val origin = cameraPosition(vehicle, 1F) ?: vehicle.position()
            aim.begin(pod, vehicle.getVehicleTransform(1F), origin)
            state.point?.let(aim::designate)
        } else if (mc.options.cameraType != CameraType.FIRST_PERSON) {
            // A manual F5 change wins; restoration must not overwrite that user's new choice.
            exitPending = vehicle.uuid
            leavePod()
            request("POD", JsonObject().apply { addProperty("Active", false) })
        }
    }

    @SubscribeEvent fun renderTick(event: TickEvent.RenderTickEvent) {
        if (event.phase != TickEvent.Phase.START) return
        val vehicle = podVehicle ?: return
        if (!isPodActive(vehicle)) return
        val mc = Minecraft.getInstance()
        if (mc.screen != null || !mc.isWindowActive || !mc.mouseHandler.isMouseGrabbed) {
            aim.resetCursor(); return
        }
        val pod = getVehicleSnapshot(vehicle)?.definition?.pod ?: return
        val origin = cameraPosition(vehicle, event.renderTickTime) ?: return
        aim.sample(mc.mouseHandler.xpos(), mc.mouseHandler.ypos(),
            vehicle.mouseSensitivity.coerceIn(0.01, 2.0), podZoom, pod,
            vehicle.getVehicleTransform(event.renderTickTime), origin)
    }

    private fun press(key: InputConstants.Key, action: Int) {
        if (action != GLFW.GLFW_PRESS) return
        val mc = Minecraft.getInstance()
        val vehicle = pilot() ?: return
        if (mc.screen != null || !mc.isWindowActive) return
        when {
            AircraftArmamentKeys.OPEN.isActiveAndMatches(key) -> request("OPEN")
            AircraftArmamentKeys.POD.isActiveAndMatches(key) -> {
                val active = isPodActive(vehicle)
                if (active) { exitPending = vehicle.uuid; leavePod() }
                request("POD", JsonObject().apply { addProperty("Active", !active) })
            }
            AircraftArmamentKeys.DESIGNATE.isActiveAndMatches(key) -> request("DESIGNATE", JsonObject().apply {
                val active = isPodActive(vehicle)
                addProperty("Pod", active)
                if (active) podDirection(vehicle, mc.frameTime)?.let { direction ->
                    add("Direction", JsonArray().apply { add(direction.x); add(direction.y); add(direction.z) })
                }
            })
            AircraftArmamentKeys.CLEAR.isActiveAndMatches(key) -> request("CLEAR_POINT")
            AircraftArmamentKeys.CYCLE.isActiveAndMatches(key) -> {
                val pairs = getVehicleSnapshot(vehicle)?.definition?.mounts.orEmpty()
                if (pairs.isNotEmpty()) selectedPair = pairs[(pairs.indexOfFirst { it.id == selectedPair } + 1) % pairs.size].id
            }
            AircraftArmamentKeys.FIRE.isActiveAndMatches(key) -> selectedPair?.let { pair ->
                request("FIRE", JsonObject().apply { addProperty("Pair", pair) })
            }
        }
    }

    @SubscribeEvent fun key(event: InputEvent.Key) =
        press(InputConstants.getKey(event.key, event.scanCode), event.action)
    @SubscribeEvent fun mouse(event: InputEvent.MouseButton.Pre) {
        press(InputConstants.Type.MOUSE.getOrCreate(event.button), event.action)
        if (controlsActive() && AircraftArmamentKeys.DESIGNATE.isActiveAndMatches(
                InputConstants.Type.MOUSE.getOrCreate(event.button))) event.isCanceled = true
    }

    @SubscribeEvent(priority = EventPriority.HIGHEST)
    fun scroll(event: InputEvent.MouseScrollingEvent) {
        val vehicle = podVehicle ?: return
        if (!isPodActive(vehicle) || !controlsActive()) return
        val pod = getVehicleSnapshot(vehicle)?.definition?.pod ?: return
        podZoom = (podZoom * Math.pow(1.25, event.scrollDelta)).coerceIn(1.0, pod.maxZoom)
        event.isCanceled = true
    }

    @JvmStatic fun podMagnification(vehicle: VehicleEntity): Double? =
        if (isPodActive(vehicle)) podZoom else null

    @JvmStatic fun cameraPosition(vehicle: VehicleEntity, partial: Float): Vec3? {
        if (!isPodActive(vehicle) || !partial.isFinite()) return null
        val point = getVehicleSnapshot(vehicle)?.definition?.pod?.position ?: return null
        // Pod.Position is a native HULL point, exactly like the server designation ray, not an orbit-camera offset.
        val position = vehicle.transformPosition(vehicle.getVehicleTransform(partial), point.x, point.y, point.z)
        return Vec3(position.x, position.y, position.z).takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() }
    }

    @JvmStatic fun cameraRotation(vehicle: VehicleEntity, partial: Float): Vec2? {
        val direction = podDirection(vehicle, partial) ?: return null
        return Vec2(Math.toDegrees(atan2(-direction.x, direction.z)).toFloat(),
            Math.toDegrees(atan2(-direction.y, hypot(direction.x, direction.z))).toFloat())
    }

    private fun podDirection(vehicle: VehicleEntity, partial: Float): Vec3? {
        if (!isPodActive(vehicle) || !partial.isFinite()) return null
        val pod = getVehicleSnapshot(vehicle)?.definition?.pod ?: return null
        return aim.direction(pod, vehicle.getVehicleTransform(partial), cameraPosition(vehicle, partial) ?: return null)
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    fun cameraRoll(event: ViewportEvent.ComputeCameraAngles) {
        if (isPodActive(podVehicle)) event.roll = 0F
    }

    fun renderHud(graphics: GuiGraphics, partial: Float, width: Int, height: Int) {
        val mc = Minecraft.getInstance()
        if (mc.options.hideGui || mc.screen != null || !partial.isFinite()) return
        val vehicle = pilot() ?: return
        val state = getVehicleSnapshot(vehicle) ?: return
        val color = 0xFFFFC56D.toInt()
        state.point?.let { point ->
            val projected = point.worldToScreen()
            if (projected.x.isFinite() && projected.y.isFinite() && projected.z.isFinite() && projected.z > 1.0E-4 &&
                projected.x in 8.0..(width - 8.0) && projected.y in 8.0..(height - 8.0)) {
                val x = projected.x.toInt(); val y = projected.y.toInt()
                graphics.fill(x - 5, y - 5, x + 6, y - 4, color)
                graphics.fill(x - 5, y + 5, x + 6, y + 6, color)
                graphics.fill(x - 5, y - 5, x - 4, y + 6, color)
                graphics.fill(x + 5, y - 5, x + 6, y + 6, color)
            }
        }
        if (isPodActive(vehicle)) {
            val zoom = String.format(java.util.Locale.ROOT, "%.1fx", podZoom)
            graphics.drawCenteredString(mc.font, "TARGETING POD  $zoom  |  [${AircraftArmamentKeys.DESIGNATE.translatedKeyMessage.string}] Designate", width / 2, 24, color)
            graphics.fill(width / 2 - 5, height / 2, width / 2 + 6, height / 2 + 1, color)
            graphics.fill(width / 2, height / 2 - 5, width / 2 + 1, height / 2 + 6, color)
        }
        state.seek?.let { seek ->
            val target = lockTargetEntity?.takeIf { !it.isRemoved && it.uuid == seek.target }
            val displayPoint = target?.getPosition(partial)?.add(0.0, target.bbHeight * 0.5, 0.0) ?: seek.position
            AircraftSeekerHud.render(graphics, vehicle, seek, displayPoint, partial, width, height)
        }
    }

    @SubscribeEvent fun unload(event: LevelEvent.Unload) { if (event.level === level) reset() }
    @SubscribeEvent fun logout(event: ClientPlayerNetworkEvent.LoggingOut) { reset(); AircraftArmamentNetwork.resetClient() }
    private fun reset() {
        com.atsuishio.superbwarfare.client.renderer.FriendlyVehicleMarker.clear()
        AircraftSeekerHud.reset()
        leavePod(); cache.clear(); level = null; selectedOwner = null; selectedPair = null; exitPending = null
    }
}
