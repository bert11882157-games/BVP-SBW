package com.atsuishio.superbwarfare.client.input

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingPilotIntent
import com.atsuishio.superbwarfare.client.FixedWingPilotIntentClient
import com.atsuishio.superbwarfare.client.MouseMovementHandler
import com.atsuishio.superbwarfare.client.camera.VehicleFreeCameraController
import com.atsuishio.superbwarfare.client.camera.FixedWingDynamicCamera
import com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentClient
import com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentKeys
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.event.ClientEventHandler
import com.atsuishio.superbwarfare.event.ClientMouseHandler
import com.atsuishio.superbwarfare.init.ModKeyMappings
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.client.event.ScreenEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.EventPriority
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.client.settings.KeyModifier
import org.lwjgl.glfw.GLFW

/** Render-owned local intent sampling. The transport's END-tick owner alone dispatches packets. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object FixedWingMouseAimInput {
    private data class Press(val key: InputConstants.Key, val modifier: KeyModifier)
    private val held = FixedWingHeldControls<Press>()
    private val sampling = FixedWingMouseAimState()
    private var owner: Player? = null
    private var vehicle: VehicleEntity? = null
    private var epoch = 0L
    private var cameraType: net.minecraft.client.CameraType? = null
    private var frame = 0L
    private var frameNanos = 0L
    private var sampledFrame = Long.MIN_VALUE

    private fun mappings() = arrayOf(ModKeyMappings.FIXED_WING_PITCH_DOWN,
        ModKeyMappings.FIXED_WING_PITCH_UP, ModKeyMappings.FIXED_WING_ROLL_LEFT,
        ModKeyMappings.FIXED_WING_ROLL_RIGHT)

    private fun eligible(player: Player?, candidate: VehicleEntity?): Boolean = player != null &&
        candidate != null && player === Minecraft.getInstance().player && player.isAlive &&
        !player.isSpectator && !candidate.isRemoved && !candidate.isWreck &&
        player.vehicle === candidate && candidate.level() === player.level() &&
        candidate.isFixedWingFlightVehicle() && candidate.getNthEntity(0) === player &&
        candidate.getSeatIndex(player) == 0

    private fun paused(player: Player): Boolean {
        val minecraft = Minecraft.getInstance()
        return minecraft.screen != null || !minecraft.isWindowActive || !minecraft.mouseHandler.isMouseGrabbed ||
            VehicleFreeCameraController.hasPresentation(player, player.vehicle as? VehicleEntity) ||
            ClientMouseHandler.isLinkedDroneView(player)
    }

    fun clear(player: Player? = null) {
        held.clear()
        FixedWingDynamicCamera.reset()
        sampling.reset()
        owner = null
        vehicle = null
        epoch = 0L
        cameraType = null
        if (player != null) FixedWingPilotIntentClient.releaseManual(player)
    }

    private fun bind(player: Player, candidate: VehicleEntity, controlEpoch: Long): Boolean {
        val type = Minecraft.getInstance().options.cameraType
        var guidanceReset = false
        if (owner !== player || vehicle !== candidate || epoch != controlEpoch) {
            held.clear()
            sampling.reset()
            FixedWingDynamicCamera.reset()
            owner = player; vehicle = candidate; epoch = controlEpoch
            guidanceReset = true
        }
        if (cameraType != type) {
            sampling.reset()
            FixedWingDynamicCamera.reset()
            cameraType = type
            guidanceReset = true
        }
        if (guidanceReset) FixedWingPilotIntentClient.clearScreenGuidance(player)
        return guidanceReset
    }

    /** Lifecycle and discrete held/Home edges remain available even without a rendered frame. */
    fun tick(player: Player, candidate: VehicleEntity) {
        if (!eligible(player, candidate) || paused(player)) { clear(player); return }
        val view = FixedWingPilotIntentClient.activeView(player) ?: run { clear(player); return }
        val guidanceReset = bind(player, candidate, view.controlEpoch)
        for ((index, mapping) in mappings().withIndex()) {
            held.retain(index, Press(mapping.key, mapping.keyModifier))
        }
        val mask = held.mask()
        val keepGuidance = !guidanceReset && mask == 0 && !AircraftArmamentClient.sensorView(candidate)
        if (!keepGuidance) sampling.clearInversionRequest()
        FixedWingPilotIntentClient.offer(player, view.directionX, view.directionY, view.directionZ,
            mask, false, view.screenRollInput.takeIf { keepGuidance }, keepGuidance && view.inversionRequested)
    }

    @SubscribeEvent
    fun frame(event: TickEvent.RenderTickEvent) {
        if (event.phase != TickEvent.Phase.START) return
        frame++
        frameNanos = System.nanoTime()
        val player = Minecraft.getInstance().player
        if (player == null || !eligible(player, player.vehicle as? VehicleEntity) || paused(player)) clear(player)
    }

    @SubscribeEvent
    fun rendered(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_LEVEL) return
        if (sampledFrame == frame) return
        sampledFrame = frame
        val minecraft = Minecraft.getInstance()
        val player = minecraft.player ?: return
        val candidate = player.vehicle as? VehicleEntity ?: return
        if (!eligible(player, candidate) || paused(player) || event.camera.entity !== player) return
        val view = FixedWingPilotIntentClient.activeView(player) ?: return
        bind(player, candidate, view.controlEpoch)
        if (AircraftArmamentClient.sensorView(candidate)) {
            // The tick owner still offers the live manual/arrow mask. Pod mouse never reaches flight intent.
            sampling.reset()
            FixedWingDynamicCamera.reset()
            FixedWingPilotIntentClient.clearScreenGuidance(player)
            return
        }
        val matrix = ClientEventHandler.modelViewMatrix
        val projection = ClientEventHandler.projectionMatrix
        if (matrix == null || projection == null) {
            sampling.clearInversionRequest()
            FixedWingPilotIntentClient.clearScreenGuidance(player)
            return
        }
        val basis = FixedWingMouseAimMath.cameraBasis(matrix)
        if (!FixedWingMouseAimMath.validBasis(basis)) {
            sampling.reset()
            FixedWingPilotIntentClient.clearScreenGuidance(player)
            return
        }
        val mask = held.mask()
        val initial = FixedWingPilotIntent.normalized(view.directionX, view.directionY, view.directionZ, mask) ?: run {
            sampling.clearInversionRequest()
            FixedWingPilotIntentClient.clearScreenGuidance(player)
            return
        }
        val cursor = MouseMovementHandler.getMousePos()
        val nose = Vec3.directionFromRotation(candidate.getPitch(event.partialTick),
            candidate.getResolvedChassisYaw(event.partialTick))
        val next = sampling.sample(frame, frameNanos, initial, basis, cursor.x.toDouble(), cursor.y.toDouble(),
            candidate.mouseSensitivity * FixedWingJoystickSensitivity.get(), FixedWingPitchControl.isInverted(),
            view.centeringPending) ?: run {
            FixedWingPilotIntentClient.clearScreenGuidance(player)
            return
        }
        val screenRoll = if (view.centeringPending) null else
            FixedWingMouseAimMath.screenRollError(next, nose.x, nose.y, nose.z, matrix, projection)
        val validGuidance = screenRoll != null
        val inversionRequested = if (validGuidance) sampling.inversionRequested(matrix, projection, nose.x, nose.y, nose.z) else {
            sampling.clearInversionRequest()
            false
        }
        FixedWingPilotIntentClient.offer(player, next.directionX, next.directionY, next.directionZ,
            mask, false, screenRoll.takeIf { validGuidance }, inversionRequested)
        if (validGuidance && sampling.consumedMouseGesture(frame)) {
            FixedWingDynamicCamera.rearmFromMouseGesture(candidate, frame, frameNanos)
        }
        FixedWingDynamicCamera.observe(candidate, frame, frameNanos, event.partialTick,
            next.takeUnless { view.centeringPending }, mask != 0)
    }

    private fun input(key: InputConstants.Key, action: Int) {
        if (action == GLFW.GLFW_RELEASE) {
            // Match the original key even when its modifier was released first.
            for (modifier in KeyModifier.entries) held.release(Press(key, modifier))
            return
        }
        if (action != GLFW.GLFW_PRESS) return
        val player = Minecraft.getInstance().player ?: return
        val candidate = player.vehicle as? VehicleEntity ?: return
        if (ModKeyMappings.FREE_CAMERA.isActiveAndMatches(key)) { clear(player); return }
        if (!eligible(player, candidate) || paused(player)) return
        val view = FixedWingPilotIntentClient.activeView(player) ?: return
        bind(player, candidate, view.controlEpoch)
        if (ModKeyMappings.FLIGHT_RECENTER.isActiveAndMatches(key)) {
            sampling.reset()
            FixedWingDynamicCamera.onFreeCameraReleased(candidate)
            val forward = Vec3.directionFromRotation(candidate.xRot, candidate.getResolvedChassisYaw(1F))
            FixedWingPilotIntentClient.offer(player, forward.x, forward.y, forward.z, held.mask(), true)
            return
        }
        for ((index, mapping) in mappings().withIndex()) {
            if (mapping.isActiveAndMatches(key)) held.press(index, Press(key, mapping.keyModifier))
        }
    }

    @SubscribeEvent
    fun key(event: InputEvent.Key) { input(InputConstants.getKey(event.key, event.scanCode), event.action) }

    @SubscribeEvent(priority = EventPriority.HIGH, receiveCanceled = true)
    fun mouse(event: InputEvent.MouseButton.Pre) {
        input(InputConstants.Type.MOUSE.getOrCreate(event.button), event.action)
    }

    @SubscribeEvent
    fun screen(event: ScreenEvent.Opening) {
        if (event.newScreen != null) clear(Minecraft.getInstance().player)
    }
}
