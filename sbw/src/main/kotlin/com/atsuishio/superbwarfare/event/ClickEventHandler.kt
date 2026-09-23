package com.atsuishio.superbwarfare.event

import com.atsuishio.superbwarfare.client.screens.WeaponEditScreen
import com.atsuishio.superbwarfare.client.VehicleWeaponSlotCycleClient
import com.atsuishio.superbwarfare.client.VehicleGeometricZeroDistanceClient
import com.atsuishio.superbwarfare.client.input.VehicleControlBindings
import com.atsuishio.superbwarfare.client.input.VehicleDismountInput
import com.atsuishio.superbwarfare.client.input.VehicleWeaponSelectionInput
import com.atsuishio.superbwarfare.client.camera.VehicleFreeCameraController
import com.atsuishio.superbwarfare.client.camera.VehicleOpticalZoomController
import com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponSlot
import com.atsuishio.superbwarfare.compat.CompatHolder
import com.atsuishio.superbwarfare.compat.clothconfig.ClothConfigHelper
import com.atsuishio.superbwarfare.config.client.ReloadConfig
import com.atsuishio.superbwarfare.data.gun.FireMode
import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.GunProp
import com.atsuishio.superbwarfare.data.gun.SeekType
import com.atsuishio.superbwarfare.entity.vehicle.MortarEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.*
import com.atsuishio.superbwarfare.item.ItemScreenProvider
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.atsuishio.superbwarfare.network.message.send.*
import com.atsuishio.superbwarfare.resource.gun.GunResource
import com.atsuishio.superbwarfare.tools.*
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.ChatFormatting
import net.minecraft.client.KeyMapping
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import net.minecraft.sounds.SoundSource
import net.minecraft.world.InteractionHand
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.InputEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.ModList
import net.minecraftforge.fml.common.Mod
import org.lwjgl.glfw.GLFW
import top.theillusivec4.curios.api.CuriosApi

@Mod.EventBusSubscriber(
    bus = Mod.EventBusSubscriber.Bus.FORGE,
    value = [Dist.CLIENT]
)
object ClickEventHandler {
    @JvmField
    var switchZoom: Boolean = false

    private var pressedFireInput: InputConstants.Key? = null
    private var pressedHoldZoomInput: InputConstants.Key? = null
    private var pressedSwitchZoomInput: InputConstants.Key? = null

    private fun profileMapping(ordinary: KeyMapping, vehicle: KeyMapping): KeyMapping =
        if (VehicleControlBindings.isActive()) vehicle else ordinary

    private fun fireMapping(): KeyMapping =
        profileMapping(ModKeyMappings.FIRE, ModKeyMappings.VEHICLE_FIRE)

    private fun holdZoomMapping(): KeyMapping =
        profileMapping(ModKeyMappings.HOLD_ZOOM, ModKeyMappings.VEHICLE_HOLD_ZOOM)

    private fun switchZoomMapping(): KeyMapping =
        profileMapping(ModKeyMappings.SWITCH_ZOOM, ModKeyMappings.VEHICLE_SWITCH_ZOOM)

    private fun reloadMapping(): KeyMapping =
        profileMapping(ModKeyMappings.RELOAD, ModKeyMappings.VEHICLE_RELOAD)

    private fun cycleAmmoMapping(): KeyMapping =
        profileMapping(ModKeyMappings.FIRE_MODE, ModKeyMappings.VEHICLE_CYCLE_AMMO)

    private fun changeAmmoForwardMapping(): KeyMapping = profileMapping(
        ModKeyMappings.CHANGE_AMMO_FORWARD,
        ModKeyMappings.VEHICLE_CHANGE_AMMO_FORWARD,
    )

    private fun changeAmmoBackwardMapping(): KeyMapping = profileMapping(
        ModKeyMappings.CHANGE_AMMO_BACKWARD,
        ModKeyMappings.VEHICLE_CHANGE_AMMO_BACKWARD,
    )

    private fun thermalMapping(): KeyMapping = profileMapping(
        ModKeyMappings.ACTIVE_THERMAL_IMAGING,
        ModKeyMappings.VEHICLE_ACTIVE_THERMAL_IMAGING,
    )

    private fun ordinaryMappingMatches(mapping: KeyMapping, input: InputConstants.Key): Boolean =
        !VehicleControlBindings.shouldSuppress(mapping) && mapping.isActiveAndMatches(input)

    private fun mouseInput(button: Int): InputConstants.Key =
        InputConstants.Type.MOUSE.getOrCreate(button)

    private fun keyboardInput(event: InputEvent.Key): InputConstants.Key =
        InputConstants.getKey(event.key, event.scanCode)

    private fun controlledVehicleGunData(player: Player): GunData? {
        val vehicle = player.vehicle as? VehicleEntity ?: return null
        val seatIndex = vehicle.getSeatIndex(player)
        if (!vehicle.banHand(player) || !vehicle.hasWeapon(seatIndex)) return null
        return vehicle.getGunData(player)
    }

    private fun handleReloadPress() {
        ClientEventHandler.burstFireAmount = 0
        ClientEventHandler.isEditing = false
        ClientEventHandler.seekingTime = 0
        ClientEventHandler.lockOn = false
        ClientEventHandler.lockingEntity = null
        ClientEventHandler.seekingEntity = null
        ClientEventHandler.lockingPos = null
        sendPacketToServer(ReloadMessage)
    }

    private fun handleCycleAmmoPress(player: Player, stack: ItemStack) {
        val vehicleData = controlledVehicleGunData(player)
        if (vehicleData != null) {
            if (vehicleData.get(GunProp.AMMO_CONSUMER).size > 1) {
                sendPacketToServer(EditMessage(5, add = true, isVehicle = true))
            }
        } else if (stack.item is GunItem) {
            sendPacketToServer(FireModeMessage(false))
        }
        ClientEventHandler.burstFireAmount = 0
    }

    private fun handleChangeAmmoPress(player: Player, stack: ItemStack, add: Boolean) {
        // Pod arrow controls own these keys; do not also change the aircraft's primary ammunition.
        if (com.atsuishio.superbwarfare.client.aircraft.AircraftArmamentClient.isPodActive(player.vehicle as? VehicleEntity)) return
        val vehicleData = controlledVehicleGunData(player)
        if (vehicleData != null) {
            if (vehicleData.get(GunProp.AMMO_CONSUMER).size > 1) {
                sendPacketToServer(EditMessage(5, add = add, isVehicle = true))
                ClientEventHandler.burstFireAmount = 0
            }
            return
        }

        if (stack.item is GunItem) {
            val data = GunData.from(stack)
            if (data.get(GunProp.AMMO_CONSUMER).size > 1) {
                sendPacketToServer(EditMessage(5, add = add, isVehicle = false))
                ClientEventHandler.burstFireAmount = 0
            }
        }
    }

    private fun handleThermalPress(player: Player) {
        val vehicle = player.vehicle
        if (vehicle is VehicleEntity) {
            val index = vehicle.getSeatIndex(player)
            val seat = vehicle.computed().seats().getOrNull(index) ?: return
            if (seat.hasThermalImaging) {
                ClientEventHandler.activeThermalImaging = !ClientEventHandler.activeThermalImaging
                if (ClientEventHandler.activeThermalImaging) {
                    player.playSound(ModSounds.CANNON_ZOOM_IN.get())
                } else {
                    player.playSound(ModSounds.CANNON_ZOOM_OUT.get())
                }
            }
            return
        }

        if (VehicleControlBindings.isActive()) return
        CuriosApi.getCuriosInventory(player).ifPresent {
            it.findFirstCurio(ModItems.THERMAL_IMAGING_GOGGLES.get()).ifPresent {
                ClientEventHandler.activeThermalImaging = !ClientEventHandler.activeThermalImaging
                if (ClientEventHandler.activeThermalImaging) {
                    player.playSound(ModSounds.NIGHT_VISION_ACTIVATE.get())
                } else {
                    player.playSound(ModSounds.CANNON_ZOOM_OUT.get())
                }
            }
        }
    }

    @SubscribeEvent
    fun onButtonReleased(event: InputEvent.MouseButton.Pre) {
        if (event.action != InputConstants.RELEASE) return

        val input = mouseInput(event.button)
        VehicleDismountInput.handleInput(input, event.action)
        VehicleFreeCameraController.handleInput(input, event.action)
        VehicleOpticalZoomController.releaseHold(input)
        if (notInGame) return

        val player = localPlayer ?: return
        if (player.hasEffect(ModMobEffects.SHOCK.get())) return

        if (input == pressedFireInput) {
            handleWeaponFireRelease()
            pressedFireInput = null
        }

        if (input == pressedHoldZoomInput) {
            handleWeaponZoomRelease()
            pressedHoldZoomInput = null
        } else if (input == pressedSwitchZoomInput && !switchZoom) {
            handleWeaponZoomRelease()
        }
        if (input == pressedSwitchZoomInput) pressedSwitchZoomInput = null
    }

    private fun cancelFireKey(player: Player, stack: ItemStack): Boolean {
        val vehicle = player.vehicle
        return stack.item is GunItem || stack.`is`(ModItems.MONITOR.get()) || stack.`is`(ModItems.LUNGE_MINE.get())
                || stack.`is`(ModItems.ARTILLERY_INDICATOR.get()) || player.hasEffect(ModMobEffects.SHOCK.get())
                || (vehicle is VehicleEntity && vehicle.banHand(player))
    }

    private fun cancelZoomKey(player: Player, stack: ItemStack): Boolean {
        val vehicle = player.vehicle
        return stack.item is GunItem ||
                (vehicle is VehicleEntity && vehicle.banHand(player) && !stack.item.isEdible)
    }

    @SubscribeEvent
    fun onButtonPressed(event: InputEvent.MouseButton.Pre) {
        if (VehicleWeaponSelectionInput.handleInput(mouseInput(event.button), event.action)) {
            event.isCanceled = true
            return
        }
        if (event.action != InputConstants.PRESS) return

        val input = mouseInput(event.button)
        VehicleDismountInput.handleInput(input, event.action)
        VehicleFreeCameraController.handleInput(input, event.action)
        if (notInGame) return

        val player = localPlayer ?: return
        if (player.isSpectator) return

        if (player.hasEffect(ModMobEffects.SHOCK.get())) {
            event.isCanceled = true
            return
        }

        val stack = player.mainHandItem
        val button = event.button

        val fireKey = fireMapping()
        if (fireKey.isActiveAndMatches(input)
            && cancelFireKey(player, stack)
        ) {
            event.isCanceled = true
        }

        val zoomKey = holdZoomMapping()
        if (zoomKey.isActiveAndMatches(input)
            && cancelZoomKey(player, stack)
        ) {
            event.isCanceled = true
        }

        if (button == GLFW.GLFW_MOUSE_BUTTON_MIDDLE) {
            if (stack.`is`(ModItems.ARTILLERY_INDICATOR.get())) {
                event.isCanceled = true
            }
            if (stack.`is`(ModItems.MONITOR.get()) && player.offhandItem.`is`(ModItems.ARTILLERY_INDICATOR.get())) {
                event.isCanceled = true
            }
        }

        if (ordinaryMappingMatches(ModKeyMappings.MARK, input)) {
            if (stack.`is`(ModItems.ARTILLERY_INDICATOR.get())) {
                sendPacketToServer(SetFiringParametersMessage)
            }
            if (stack.`is`(ModItems.MONITOR.get()) && player.offhandItem.`is`(ModItems.ARTILLERY_INDICATOR.get())) {
                droneLeftClick(stack, player)
            }
        }

        if (stack.item is GunItem
            || player.vehicle is VehicleEntity
            || stack.`is`(ModItems.MONITOR.get())
            || stack.`is`(ModItems.LUNGE_MINE.get())
            || (stack.`is`(Items.SPYGLASS) && player.isScoping && player.offhandItem.`is`(ModItems.FIRING_PARAMETERS.get()))
            || stack.`is`(ModItems.ARTILLERY_INDICATOR.get())
        ) {
            if (fireKey.isActiveAndMatches(input)) {
                pressedFireInput = input
                handleWeaponFirePress(player, stack)
            }

            if (zoomKey.isActiveAndMatches(input)) {
                pressedHoldZoomInput = input
                handleWeaponZoomPress(player, stack)
                VehicleOpticalZoomController.beginHold(player, input)
                switchZoom = false
            } else if (switchZoomMapping().isActiveAndMatches(input)) {
                pressedSwitchZoomInput = input
                handleWeaponZoomPress(player, stack)
                switchZoom = !switchZoom
            }
        }

        if (reloadMapping().isActiveAndMatches(input)) handleReloadPress()
        if (cycleAmmoMapping().isActiveAndMatches(input)) handleCycleAmmoPress(player, stack)
        if (changeAmmoForwardMapping().isActiveAndMatches(input)) {
            handleChangeAmmoPress(player, stack, add = false)
        }
        if (changeAmmoBackwardMapping().isActiveAndMatches(input)) {
            handleChangeAmmoPress(player, stack, add = true)
        }
        if (thermalMapping().isActiveAndMatches(input)) handleThermalPress(player)
    }

    /**
     * 枪械交互时禁止挥舞手臂
     */
    @SubscribeEvent
    fun stopSwing(event: InputEvent.InteractionKeyMappingTriggered) {
        val player = localPlayer ?: return
        if (player.getItemInHand(event.hand).item is GunItem) {
            event.setSwingHand(false)
        }
    }

    @SubscribeEvent
    fun onMouseScrolling(event: InputEvent.MouseScrollingEvent) {
        val player = localPlayer ?: return
        if (notInGame) return
        if (player.hasEffect(ModMobEffects.SHOCK.get())) {
            return
        }

        val stack = player.mainHandItem
        val scroll = event.scrollDelta
        val vehicle = player.vehicle

        // 按下自由视角键时，为载具调整相机距离
        if (vehicle is VehicleEntity && VehicleFreeCameraController.isActive(player)) {
            if (player == vehicle.firstPassenger) {
                ClientMouseHandler.custom3pDistance =
                    (ClientMouseHandler.custom3pDistance - event.scrollDelta).coerceIn(-3.0, 8.0)
            }
            event.isCanceled = true
            return
        }
        if (VehicleOpticalZoomController.adjustFromScroll(player, scroll)) {
            event.isCanceled = true
            return
        }

        // 未按下shift时，为有武器的载具切换武器
        if (!Screen.hasShiftDown()
            && vehicle is VehicleEntity
            && vehicle.hasWeapon(vehicle.getSeatIndex(player))
            && vehicle.banHand(player)
        ) {
            if (ClientEventHandler.switchVehicleWeaponCooldown <= 0) {
                if (scroll == 0.0) return
                com.atsuishio.superbwarfare.client.VehicleWeaponSlotCycleClient.request(
                    com.atsuishio.superbwarfare.api.vehicle.weapon.VehicleWeaponSlot.SECONDARY,
                    if (scroll > 0.0) -1 else 1)
                ClientEventHandler.switchVehicleWeaponCooldown = 3
            }
            event.isCanceled = true
        }

        if (stack.item is GunItem && ClientEventHandler.zoom) {
            val data = GunData.from(stack)
            if (data.canSwitchScope()) {
                sendPacketToServer(SwitchScopeMessage(scroll))
            } else if (data.canAdjustZoom() || stack.`is`(ModItems.MINIGUN.get())) {
                sendPacketToServer(AdjustZoomFovMessage(scroll))
            }
            event.isCanceled = true
        }

        if (stack.`is`(ModItems.MONITOR.get()) && stack.getOrCreateTag().getBoolean("Using")
            && stack.getOrCreateTag().getBoolean("Linked")
        ) {
            ClientEventHandler.droneFov = (ClientEventHandler.droneFov + 0.4 * scroll).coerceIn(1.0, 6.0)
            event.isCanceled = true
        }

        if (player.isUsingItem && player.useItem.`is`(ModItems.ARTILLERY_INDICATOR.get())) {
            ClientEventHandler.artilleryIndicatorCustomZoom =
                (ClientEventHandler.artilleryIndicatorCustomZoom + 0.4 * scroll).coerceIn(-2.0, 6.0)
            event.isCanceled = true
        }

        val looking = TraceTool.findLookingEntity(player, 6.0)
        if (looking is MortarEntity && player.isShiftKeyDown) {
            sendPacketToServer(AdjustMortarAngleMessage(scroll))
            event.isCanceled = true
        }
    }

    @SubscribeEvent
    fun onKeyPressed(event: InputEvent.Key) {
        val key = event.key
        if (key < 0) return

        val input = keyboardInput(event)
        if (VehicleWeaponSelectionInput.handleInput(input, event.action)) return
        VehicleDismountInput.handleInput(input, event.action)
        VehicleFreeCameraController.handleInput(input, event.action)
        if (event.action == GLFW.GLFW_RELEASE) {
            VehicleOpticalZoomController.releaseHold(input)
        }
        if (notInGame) return

        val player = localPlayer ?: return

        if (player.isSpectator) return
        if (player.hasEffect(ModMobEffects.SHOCK.get())) return

        val stack = player.mainHandItem
        val vehicle = player.vehicle

        if (event.action == GLFW.GLFW_PRESS) {
            // Slot cycling is an input edge only. The server remains the sole owner of the
            // selected primary/secondary indices; no client shadow selection is maintained.
            if (ModKeyMappings.VEHICLE_SWITCH_PRIMARY.isActiveAndMatches(input)) {
                VehicleWeaponSlotCycleClient.request(VehicleWeaponSlot.PRIMARY)
            }
            if (ModKeyMappings.VEHICLE_CYCLE_SIGHT_ZERO.isActiveAndMatches(input)) {
                VehicleGeometricZeroDistanceClient.requestCycle(player)
            }

            if (thermalMapping().isActiveAndMatches(input)) {
                handleThermalPress(player)
            }

            if (ordinaryMappingMatches(Minecraft.getInstance().options.keyJump, input)) {
                handleDoubleJump(player)
                handleParachute()
            }

            if (ordinaryMappingMatches(ModKeyMappings.CONFIG, input)) {
                handleConfigScreen(player)
            }
            if (reloadMapping().isActiveAndMatches(input)) {
                handleReloadPress()
            }
            if (cycleAmmoMapping().isActiveAndMatches(input)) {
                handleCycleAmmoPress(player, stack)
            }
            if (ordinaryMappingMatches(ModKeyMappings.CHANGE_FIRE_MODE_BACKWARD, input)) {
                sendPacketToServer(FireModeMessage(false))
                ClientEventHandler.burstFireAmount = 0
            }
            if (ordinaryMappingMatches(ModKeyMappings.CHANGE_FIRE_MODE_FORWARD, input)) {
                sendPacketToServer(FireModeMessage(true))
                ClientEventHandler.burstFireAmount = 0
            }
            if (ordinaryMappingMatches(ModKeyMappings.INTERACT, input)) {
                if (stack.item is GunItem) {
                    KeyMapping.click(mc.options.keyUse.key)
                } else if (stack.`is`(ModItems.MONITOR.get())) {
                    sendPacketToServer(InteractMessage)
                }
            }

            // 玩家手持枪械时，处理卸弹/切换弹种
            if (controlledVehicleGunData(player) == null && stack.item is GunItem) {
                val data = GunData.from(stack)
                if (ordinaryMappingMatches(ModKeyMappings.UNLOAD, input)) {
                    if (data.useBackpackAmmo() || data.ammo.get() + data.virtualAmmo.get() <= 0) return
                    sendPacketToServer(UnloadMessage)
                    ClientEventHandler.burstFireAmount = 0
                }
            }

            // 玩家位于载具上时，处理切换弹种
            if (changeAmmoForwardMapping().isActiveAndMatches(input)) {
                handleChangeAmmoPress(player, stack, add = false)
            }
            if (changeAmmoBackwardMapping().isActiveAndMatches(input)) {
                handleChangeAmmoPress(player, stack, add = true)
            }

            if (ordinaryMappingMatches(ModKeyMappings.EDIT_MODE, input)) {
                val item = stack.item
                if (item is ItemScreenProvider) {
                    val screen = item.getItemScreen(stack, player, InteractionHand.MAIN_HAND)
                    if (screen != null) {
                        Minecraft.getInstance().setScreen(screen)
                        if (screen is WeaponEditScreen) {
                            ClientEventHandler.onOpenEditScreen()
                        }
                        return
                    }
                }

                val offHand = player.offhandItem
                val offHandItem = offHand.item
                if (offHandItem is ItemScreenProvider) {
                    val screen = offHandItem.getItemScreen(offHand, player, InteractionHand.OFF_HAND)
                    if (screen != null) {
                        Minecraft.getInstance().setScreen(screen)
                        return
                    }
                }
            }

            if (ordinaryMappingMatches(ModKeyMappings.BREATH, input)
                && !ClientEventHandler.exhaustion
                && ClientEventHandler.zoom
            ) {
                ClientEventHandler.breath = true
            }
            if (ordinaryMappingMatches(ModKeyMappings.SENSITIVITY_INCREASE, input)) {
                sendPacketToServer(SensitivityMessage(true))
            }
            if (ordinaryMappingMatches(ModKeyMappings.SENSITIVITY_REDUCE, input)) {
                sendPacketToServer(SensitivityMessage(false))
            }

            if (stack.item is GunItem
                || vehicle is VehicleEntity
                || stack.`is`(ModItems.MONITOR.get())
                || (stack.`is`(Items.SPYGLASS) && player.isScoping && player.offhandItem.`is`(ModItems.FIRING_PARAMETERS.get()))
                || (stack.`is`(ModItems.ARTILLERY_INDICATOR.get()))
            ) {
                if (fireMapping().isActiveAndMatches(input)) {
                    pressedFireInput = input
                    handleWeaponFirePress(player, stack)
                }

                if (holdZoomMapping().isActiveAndMatches(input)) {
                    pressedHoldZoomInput = input
                    handleWeaponZoomPress(player, stack)
                    VehicleOpticalZoomController.beginHold(player, input)
                    switchZoom = false
                    return
                }

                if (switchZoomMapping().isActiveAndMatches(input)) {
                    pressedSwitchZoomInput = input
                    handleWeaponZoomPress(player, stack)
                    switchZoom = !switchZoom
                }
            }

            if (ordinaryMappingMatches(ModKeyMappings.MARK, input)) {
                if (stack.`is`(ModItems.ARTILLERY_INDICATOR.get())) {
                    sendPacketToServer(SetFiringParametersMessage)
                }
                if (stack.`is`(ModItems.MONITOR.get()) && player.offhandItem.`is`(ModItems.ARTILLERY_INDICATOR.get())) {
                    droneLeftClick(stack, player)
                }
            }
        } else if (event.action == GLFW.GLFW_RELEASE) {
            if (input == pressedFireInput) {
                handleWeaponFireRelease()
                pressedFireInput = null
            }

            if (input == pressedHoldZoomInput) {
                handleWeaponZoomRelease()
                pressedHoldZoomInput = null
            } else if (input == pressedSwitchZoomInput && !switchZoom) {
                handleWeaponZoomRelease()
            }
            if (input == pressedSwitchZoomInput) pressedSwitchZoomInput = null

            if (ModKeyMappings.BREATH.key == input) {
                ClientEventHandler.breath = false
            }
        }
    }

    fun handleWeaponFirePress(player: Player, stack: ItemStack) {
        ClientEventHandler.isEditing = false

        if (player.hasEffect(ModMobEffects.SHOCK.get())) return

        val vehicle = player.vehicle

        if (vehicle is VehicleEntity && vehicle.banHand(player)) {
            if (vehicle.hasWeapon(vehicle.getSeatIndex(player))) {
                ClientEventHandler.holdFireVehicle = true
            }
            return
        }

        if (stack.`is`(ModItems.ARTILLERY_INDICATOR.get())) {
            ClientEventHandler.holdingFireKey = true
        }

        if (stack.`is`(Items.SPYGLASS) && player.isScoping && player.offhandItem.`is`(ModItems.FIRING_PARAMETERS.get())) {
            sendPacketToServer(SetFiringParametersMessage)
        }

        if (stack.`is`(ModItems.MONITOR.get())) {
            if (player.offhandItem.`is`(ModItems.ARTILLERY_INDICATOR.get())) {
                ClientEventHandler.holdingFireKey = true
            } else {
                droneLeftClick(stack, player)
            }
        }

        if (stack.`is`(ModItems.LUNGE_MINE.get())) {
            ClientEventHandler.usingLunge = true
        }

        val item = stack.item
        if (item is GunItem
            && ClientEventHandler.clientTimer.progress == 0L
            && !notInGame
        ) {
            val data = GunData.from(stack)
            val resource = GunResource.compute(stack)

            // TODO 整合特殊处理
            if (!(stack.`is`(ModItems.BOCEK.get()))) {
                if (!data.meleeOnly()) {
                    // 普通枪（？）
                    if (stack.`is`(ModItems.QL_1031.get()) && data.selectedFireModeInfo().name == "Hold"
                        && item.canShoot(data, player)
                    ) {
                        player.playSound(ModSounds.QL_1031_CHARGE.get(), 1f, 1f)
                        ClientEventHandler.shouldPlayDischargeSound = true
                    }

                    val triggerSound = resource.triggerSound
                    if (triggerSound != null && !data.meleeOnly()) {
                        player.playSound(triggerSound, 1f, 1f)
                    }
                }
            } else {
                // 波塞克特殊处理
                ClientEventHandler.bowPower = 0.0
                ClientEventHandler.holdingFireKey = true
                player.setSprinting(false)
                if (data.hasEnoughAmmoToShoot(player)) {
                    return
                }
            }

            if (!data.useBackpackAmmo() && !data.meleeOnly() && !data.hasEnoughAmmoToShoot(player) && data.reload.time() == 0) {
                if (ReloadConfig.LEFT_CLICK_RELOAD.get()) {
                    sendPacketToServer(ReloadMessage)
                    ClientEventHandler.burstFireAmount = 0
                    ClientEventHandler.seekingTime = 0
                    ClientEventHandler.lockOn = false
                    ClientEventHandler.lockingEntity = null
                    ClientEventHandler.seekingEntity = null
                    ClientEventHandler.lockingPos = null
                }
            } else {
                sendPacketToServer(FireKeyMessage(0, ClientEventHandler.bowPower, ClientEventHandler.zoom))
                if ( ClientEventHandler.drawTime < 0.01) {
                    val fireMode = data.selectedFireModeInfo().mode
                    if (fireMode == FireMode.BURST) {
                        if (ClientEventHandler.burstFireAmount == 0) {
                            ClientEventHandler.noSprintTicks = 8f
                            player.setSprinting(false)
                            ClientEventHandler.burstFireAmount = data.get(GunProp.BURST_AMOUNT)
                        }
                    } else if (fireMode == FireMode.SEMI) {
                        if (ClientEventHandler.burstFireAmount == 0) {
                            ClientEventHandler.noSprintTicks = 3f
                            player.setSprinting(false)
                            ClientEventHandler.burstFireAmount = 1
                        }
                    }

                    ClientEventHandler.holdingFireKey = true
                    player.setSprinting(false)
                }
            }
        }
    }

    fun handleWeaponFireRelease() {
        sendPacketToServer(FireKeyMessage(1, ClientEventHandler.bowPower, ClientEventHandler.zoom))
        ClientEventHandler.bowPull = false
        ClientEventHandler.holdingFireKey = false
        ClientEventHandler.holdFireVehicle = false
        ClientEventHandler.isEditing = false
        ClientEventHandler.customRpm = 0

        val player = localPlayer ?: return
        if (player.isSpectator) return

        val stack = player.mainHandItem

        if (stack.`is`(ModItems.BOCEK.get())) {
            sendPacketToServer(ReloadMessage)
        }

        if (stack.item is GunItem) {
            val data = GunData.from(stack)
            val fireMode = data.selectedFireModeInfo().mode

            if (fireMode != FireMode.BURST) {
                ClientEventHandler.burstFireAmount = 0
            }

            if (data.get(GunProp.SEEK_TYPE) == SeekType.HOLD_FIRE) {
                ClientEventHandler.stopWeaponSeekSound(Minecraft.getInstance().player)
            }
        }
    }

    fun handleWeaponZoomPress(player: Player, stack: ItemStack) {
        sendPacketToServer(ZoomMessage(0))

        ClientEventHandler.isEditing = false

        val vehicle = player.vehicle
        if (vehicle is VehicleEntity && vehicle.hasWeapon(vehicle.getSeatIndex(player)) && vehicle.banHand(player)) {
            ClientEventHandler.zoomVehicle = true
            return
        }

        if (stack.item !is GunItem) return
        if (!GunResource.compute(stack).canZoom) return

        val data = GunData.from(stack)
        ClientEventHandler.zoom = true

        val level = data.perk.getLevel(ModPerks.INTELLIGENT_CHIP)
        if (level > 0) {
            if (ClientEventHandler.lockedEntity == null) {
                ClientEventHandler.lockedEntity =
                    if (data.perk.has(ModPerks.PHASE_PENETRATING_BULLET.get()) || data.perk.has(ModPerks.BEAST_BULLET.get())) {
                        SeekTool.seekEntityThroughWall(player, 32 + 8 * (level - 1).toDouble(), 20.0)
                    } else {
                        SeekTool.seekLivingEntity(player, 32 + 8 * (level - 1).toDouble(), 20.0)
                    }
            }
        }
    }

    fun handleWeaponZoomRelease() {
        sendPacketToServer(ZoomMessage(1))
        ClientEventHandler.zoom = false
        ClientEventHandler.zoomVehicle = false
        ClientEventHandler.lockedEntity = null
        ClientEventHandler.stopWeaponSeekSound(Minecraft.getInstance().player)
        ClientEventHandler.breath = false
    }

    fun handleDoubleJump(player: Player) {
        val level = player.level()
        val x = player.x
        val y = player.y
        val z = player.z

        if (!level.isLoaded(player.blockPosition())) {
            return
        }

        if (ClientEventHandler.canDoubleJump) {
            player.deltaMovement = Vec3(player.lookAngle.x, 0.8, player.lookAngle.z)
            level.playLocalSound(x, y, z, ModSounds.DOUBLE_JUMP.get(), SoundSource.BLOCKS, 1f, 1f, false)
            sendPacketToServer(DoubleJumpMessage)
            ClientEventHandler.canDoubleJump = false
        }
    }

    fun handleParachute() {
        sendPacketToServer(ParachuteMessage)
    }

    fun handleConfigScreen(player: Player) {
        if (ModList.get().isLoaded(CompatHolder.CLOTH_CONFIG)) {
            CompatHolder.hasMod(
                CompatHolder.CLOTH_CONFIG
            ) { mc.setScreen(ClothConfigHelper.getConfigScreen(null)) }
        } else {
            player.displayClientMessage(
                Component.translatable("tips.superbwarfare.no_cloth_config").withStyle(ChatFormatting.RED), true
            )
        }
    }

    fun droneLeftClick(stack: ItemStack, player: Player) {
        if (stack.`is`(ModItems.MONITOR.get()) && stack.getOrCreateTag().getBoolean("Using")
            && stack.getOrCreateTag().getBoolean("Linked")
        ) {
            val drone =
                EntityFindUtil.findDrone(player.level(), stack.getOrCreateTag().getString("LinkedDrone")) ?: return
            val lookingEntity = SeekTool.seekLivingEntity(drone, 512.0, 2 / ClientEventHandler.droneFovLerp)

            val result = player.level().clip(
                ClipContext(
                    drone.eyePosition,
                    drone.eyePosition.add(drone.lookAngle.scale(512.0)),
                    ClipContext.Block.OUTLINE,
                    ClipContext.Fluid.NONE,
                    drone
                )
            )

            var pos = result.location
            if (lookingEntity != null && !player.isShiftKeyDown) {
                pos = lookingEntity.position()
            }

            sendPacketToServer(DroneFireMessage(pos.toVector3f()))
        }
    }
}
