package com.atsuishio.superbwarfare.api.aircraft

import com.atsuishio.superbwarfare.compat.ffa.AircraftFfaBridge
import com.atsuishio.superbwarfare.data.vehicle.subdata.AircraftCountermeasureDefinition
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleType
import com.atsuishio.superbwarfare.entity.projectile.FlareDecoyEntity
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.init.ModItems
import com.atsuishio.superbwarfare.init.ModSounds
import com.atsuishio.superbwarfare.tools.InventoryTool
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.phys.Vec3
import org.joml.Vector3d

/** Aircraft equipment adapter; all timing and level changes belong to the server state machine. */
class AircraftCountermeasures(private val vehicle: VehicleEntity) {
    private var state = AircraftCountermeasureState()
    private var sentLevels = -1
    private var threat = 0
    private var nextWarningAt = 0L
    /** A creative ammo box (vehicle magazine, or carried by the crew) supplies flares and chaff without limit. */
    private var unlimited = false

    fun tick() {
        val level = vehicle.level() as? ServerLevel ?: return
        val now = level.gameTime
        val definition = definition(vehicle)
        val intact = !vehicle.isRemoved && !vehicle.isWreck && vehicle.isAlive
        val incoming = if (intact) IncomingMissileWarning.flags(vehicle) else 0
        if (definition == null || !intact) {
            if (sentLevels > 0) AircraftFfaBridge.levels(vehicle, 0, 0)
            sentLevels = 0
            // Ground vehicles carry only the missile warning.
            vehicle.publishAircraftCountermeasures(AircraftCountermeasureWire.pack(0, 0, 0, false, incoming), 0)
            return
        }
        val pilot = vehicle.getNthEntity(0) as? ServerPlayer
        unlimited = hasUnlimitedSupply(vehicle, pilot)
        val controlled = pilot?.isAlive == true && !pilot.isSpectator && pilot.vehicle === vehicle
        val flareItem = ModItems.FLARE_AMMUNITION.get()
        val chaffItem = ModItems.CHAFF_AMMUNITION.get()
        var output = state.tick(now, controlled && vehicle.decoyInputDown && countItem(flareItem) >= 2,
            controlled && (vehicle.getVehicleFlightControlBits().toInt() and CHAFF_INPUT_BIT) != 0
                && countItem(chaffItem) >= 1,
            definition.flares, definition.chaff, definition.flaresPerSecond, definition.flaresPerBurst,
            definition.chaffReleaseTicks)
        // A failed flare spawn rolls back only that pair. Chaff may have started in the same tick.
        val chaffStarted = output.chaffStarted
        if (output.flarePairs > 0) {
            if (launchPair(level, definition)) consumeItem(flareItem, 2)
            else {
                state.abortPair()
                output = state.tick(now, false, false, definition.flares, definition.chaff,
                    definition.flaresPerSecond, definition.flaresPerBurst, definition.chaffReleaseTicks)
            }
        }
        if (chaffStarted) consumeItem(chaffItem, 1)
        // Chaff presentation consumes the synchronized accepted state on the client.
        val levels = output.flareLevel or (output.chaffLevel shl 8)
        if (sentLevels != levels) {
            AircraftFfaBridge.levels(vehicle, output.chaffLevel, output.flareLevel)
            sentLevels = levels
        }
        if (now % 5L == 0L || !controlled) {
            val observed = if (definition.radarWarningReceiver && controlled)
                AircraftFfaBridge.threatLevel(vehicle) else 0
            if (observed > threat) nextWarningAt = now
            threat = observed
        }
        if (controlled && threat > 0 && now >= nextWarningAt && incoming == 0) {
            // Short, local cockpit chirps. Incoming-lock cadence takes priority over radar tracking; a tracked
            // incoming missile sounds the crew's missile alarm instead.
            pilot!!.playNotifySound(SoundEvents.NOTE_BLOCK_BIT.value(), SoundSource.PLAYERS,
                if (threat == 2) 0.45F else 0.22F, if (threat == 2) 1.8F else 1.3F)
            nextWarningAt = now + if (threat == 2) 6 else 24
        }
        if (threat == 0) nextWarningAt = now
        vehicle.publishAircraftCountermeasures(AircraftCountermeasureWire.pack(
            output.flareLevel, output.chaffLevel, threat, output.chaffEmitting, incoming),
            output.flareCooldown or (output.chaffCooldown shl 9))
        vehicle.decoyReady = definition.flares && output.flareCooldown == 0 && countItem(flareItem) >= 2
        vehicle.decoyReloadCoolDown = output.flareCooldown
    }

    private fun launchPair(level: ServerLevel, definition: AircraftCountermeasureDefinition): Boolean {
        val transform = vehicle.getVehicleTransform(1F)
        val launched = ArrayList<FlareDecoyEntity>(2)
        for ((index, local) in listOf(definition.flareLeftPos, definition.flareRightPos).withIndex()) {
            val position = transform.transformPosition(Vector3d(local.x, local.y, local.z))
            val direction = transform.transformDirection(Vector3d(if (index == 0) -1.0 else 1.0, -0.15, 0.25)).normalize()
            val flare = FlareDecoyEntity(level)
            flare.countermeasureLifetimeTicks = AircraftCountermeasureState.FLARE_LIFETIME_TICKS
            flare.setPos(position.x, position.y, position.z)
            flare.deltaMovement = vehicle.deltaMovement.add(Vec3(direction.x, direction.y, direction.z)
                .scale(definition.flareEjectionSpeed))
            if (!level.addFreshEntity(flare)) {
                launched.forEach { it.discard() }
                return false
            }
            launched.add(flare)
        }
        launched.forEach(AircraftFfaBridge::registerFlare)
        level.playSound(null, vehicle, ModSounds.AIRCRAFT_FLARE_RELEASE.get(), vehicle.soundSource, 1.0F, 1.0F)
        return true
    }

    /** The vehicle magazine supplies countermeasures (one release uses one chaff item or two flares), unless a creative ammo box is present. */
    private fun countItem(item: Item): Int {
        if (unlimited) return Int.MAX_VALUE
        var total = 0
        for (slot in 0 until vehicle.inventory.slots) {
            val stack = vehicle.inventory.getStackInSlot(slot)
            if (stack.`is`(item)) total += stack.count
        }
        return total
    }

    private fun consumeItem(item: Item, amount: Int) {
        if (unlimited) return
        require(countItem(item) >= amount)
        var remaining = amount
        for (slot in 0 until vehicle.inventory.slots) {
            val stack = vehicle.inventory.getStackInSlot(slot)
            if (!stack.`is`(item)) continue
            remaining -= vehicle.inventory.extractItem(slot, remaining, false).count
            if (remaining == 0) break
        }
        check(remaining == 0)
    }

    fun save(tag: CompoundTag) {
        tag.put("AircraftCountermeasures", CompoundTag().apply {
            putLong("FlareReadyAt", state.flareReadyAt)
            putLong("ChaffReadyAt", state.chaffReadyAt)
            putInt("BurstUsed", state.burstExpenditure())
        })
    }

    fun load(tag: CompoundTag) {
        val saved = tag.getCompound("AircraftCountermeasures")
        state.restore(vehicle.level().gameTime, saved.getLong("FlareReadyAt"),
            saved.getLong("ChaffReadyAt"), saved.getInt("BurstUsed"))
    }

    companion object {
        const val CHAFF_INPUT_BIT = 512
        private val legacyFlares = AircraftCountermeasureDefinition(flares = true)

        /** Same rule on both sides: a creative ammo box in the vehicle magazine, or carried by the pilot. */
        @JvmStatic
        fun hasUnlimitedSupply(vehicle: VehicleEntity, pilot: Player?): Boolean =
            InventoryTool.hasCreativeAmmoBoxForVehicle(vehicle) || (pilot != null && InventoryTool.hasCreativeAmmoBox(pilot))

        fun definition(vehicle: VehicleEntity): AircraftCountermeasureDefinition? {
            if (vehicle.vehicleType != VehicleType.AIRPLANE && vehicle.vehicleType != VehicleType.HELICOPTER &&
                !vehicle.isFixedWingFlightVehicle()) return null
            val data = vehicle.computed()
            return data.countermeasures ?: legacyFlares.takeIf { data.hasDecoy }
        }
    }
}
