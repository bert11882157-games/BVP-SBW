package com.atsuishio.superbwarfare.api.vehicle.weapon

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.data.vehicle.DefaultVehicleData
import com.atsuishio.superbwarfare.api.weapon.ShotRejectionReason
import com.atsuishio.superbwarfare.api.weapon.ShotResult
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.phys.Vec3
import java.util.ArrayDeque
import java.util.LinkedHashMap
import java.util.UUID
import kotlin.math.max
import kotlin.math.min
import kotlin.math.floor

/** Server-owned cadence, catch-up, and fixed-window heat lifecycle for addon-profiled vehicle weapons. */
class VehicleWeaponScheduler(private val vehicle: VehicleEntity) {
    private val triggerIntents = LinkedHashMap<UUID, TriggerIntent>()
    private val secondaryTriggerIntents = LinkedHashMap<UUID, TriggerIntent>()
    private val states = LinkedHashMap<ScheduleKey, RuntimeState>()
    private val activeTriggers = LinkedHashMap<ScheduleKey, ActiveTrigger>()
    private val snapshotBuffer = ArrayList<VehicleWeaponScheduleSnapshot>()
    private var executingProfile: VehicleWeaponScheduleProfile? = null
    private var lastSyncedPayload = ""
    private var clientPayload = ""
    private var clientSnapshots: List<VehicleWeaponScheduleSnapshot> = emptyList()

    fun hasSchedule(controller: LivingEntity): Boolean {
        return VehicleWeaponScheduleProviders.hasSchedule(vehicle, controller)
    }

    fun updateTrigger(
        controller: LivingEntity,
        held: Boolean,
        targetEntityUuid: UUID?,
        targetPos: Vec3?,
    ): Boolean {
        val resolved = VehicleWeaponScheduleProviders.resolve(vehicle, controller)
        if (!held) {
            triggerIntents.remove(controller.uuid)
            releaseIntentEdge(controller, resolved?.selection, VehicleWeaponSlot.PRIMARY)
            return true
        }
        if (resolved == null) {
            triggerIntents.remove(controller.uuid)
            VehicleWeaponShotDiagnostics.recordBoundary(
                vehicle,
                controller,
                vehicle.getGunName(vehicle.getSeatIndex(controller)) ?: "<selected>",
                VehicleWeaponShotDiagnostics.Boundary.SCHEDULER,
                "NO_RESOLVED_SELECTION",
            )
            VehicleWeaponShotDiagnostics.record(
                vehicle,
                controller,
                "<selected>",
                ShotResult.rejected(ShotRejectionReason.NO_WEAPON),
            )
            return false
        }

        triggerIntents[controller.uuid] = TriggerIntent(
            targetEntityUuid, targetPos, vehicle.getSeatIndex(controller),
        )
        return true
    }

    fun updateSecondaryTrigger(controller: LivingEntity, held: Boolean): Boolean {
        val resolved = resolveSecondary(controller)
        if (!held) {
            secondaryTriggerIntents.remove(controller.uuid)
            releaseIntentEdge(controller, resolved?.selection, VehicleWeaponSlot.SECONDARY)
            return true
        }
        if (resolved == null) {
            secondaryTriggerIntents.remove(controller.uuid)
            VehicleWeaponShotDiagnostics.recordBoundary(
                vehicle,
                controller,
                vehicle.getGunName(vehicle.getSeatIndex(controller)) ?: "<secondary>",
                VehicleWeaponShotDiagnostics.Boundary.SCHEDULER,
                "NO_RESOLVED_SECONDARY",
            )
            VehicleWeaponShotDiagnostics.record(
                vehicle,
                controller,
                "<secondary>",
                ShotResult.rejected(ShotRejectionReason.NO_WEAPON),
            )
            return false
        }
        secondaryTriggerIntents[controller.uuid] = TriggerIntent(
            null, null, vehicle.getSeatIndex(controller),
        )
        return true
    }

    private fun releaseIntentEdge(controller: LivingEntity, selection: VehicleWeaponSelection?,
                                  slot: VehicleWeaponSlot) {
        selection?.let { states[it.scheduleKey()]?.releaseEdge() }
        // Fixed banks may fan one held slot into several independent weapon schedules.
        for ((key, trigger) in activeTriggers) {
            if (trigger.slot == slot && trigger.origin.controller.uuid == controller.uuid) {
                states[key]?.releaseEdge()
            }
        }
    }

    fun tick() {
        if (vehicle.level().isClientSide) return

        val tick = vehicle.tickCount
        refreshActiveTriggers()
        val removedStaleState = states.keys.removeAll {
            vehicle.getGunName(it.seatIndex, it.weaponIndex) != it.weaponName
        }
        if (activeTriggers.isEmpty() && states.values.all { it.isStableIdle() }) {
            if (removedStaleState) syncSnapshots()
            return
        }

        states.forEach { (key, state) -> state.tick(tick, key in activeTriggers) }
        activeTriggers.forEach { (key, trigger) -> processTrigger(key, trigger, tick) }
        syncSnapshots()
    }

    private fun refreshActiveTriggers() {
        activeTriggers.clear()
        val iterator = triggerIntents.iterator()
        while (iterator.hasNext()) {
            val (controllerUuid, intent) = iterator.next()
            val controller = findPassenger(controllerUuid)
            if (controller == null || vehicle.getSeatIndex(controller) != intent.seatIndex) {
                iterator.remove()
                continue
            }

            val resolved = VehicleWeaponScheduleProviders.resolve(vehicle, controller)
            if (resolved == null) {
                iterator.remove()
                VehicleWeaponShotDiagnostics.recordBoundary(
                    vehicle,
                    controller,
                    vehicle.getGunName(vehicle.getSeatIndex(controller)) ?: "<selected>",
                    VehicleWeaponShotDiagnostics.Boundary.SCHEDULER,
                    "NO_RESOLVED_SELECTION",
                )
                VehicleWeaponShotDiagnostics.record(
                    vehicle,
                    controller,
                    "<selected>",
                    ShotResult.rejected(ShotRejectionReason.NO_WEAPON),
                )
                continue
            }
            if (!activate(resolved, intent, VehicleWeaponSlot.PRIMARY)) iterator.remove()
        }

        val secondaryIterator = secondaryTriggerIntents.iterator()
        while (secondaryIterator.hasNext()) {
            val (controllerUuid, intent) = secondaryIterator.next()
            val controller = findPassenger(controllerUuid)
            if (controller == null || vehicle.getSeatIndex(controller) != intent.seatIndex) {
                secondaryIterator.remove()
                continue
            }
            val resolved = resolveSecondary(controller)
            if (resolved == null) {
                secondaryIterator.remove()
                VehicleWeaponShotDiagnostics.recordBoundary(
                    vehicle,
                    controller,
                    vehicle.getGunName(vehicle.getSeatIndex(controller)) ?: "<secondary>",
                    VehicleWeaponShotDiagnostics.Boundary.SCHEDULER,
                    "NO_RESOLVED_SECONDARY",
                )
                VehicleWeaponShotDiagnostics.record(
                    vehicle,
                    controller,
                    "<secondary>",
                    ShotResult.rejected(ShotRejectionReason.NO_WEAPON),
                )
                continue
            }
            if (!activate(resolved, intent, VehicleWeaponSlot.SECONDARY)) {
                secondaryIterator.remove()
            }
        }
    }

    private fun activate(
        anchor: ResolvedVehicleWeaponSchedule,
        intent: TriggerIntent,
        slot: VehicleWeaponSlot,
    ): Boolean {
        val expanded = VehicleFixedGunBanks.expand(anchor, slot == VehicleWeaponSlot.PRIMARY)
        if (expanded == null) {
            VehicleWeaponShotDiagnostics.recordBoundary(
                vehicle,
                anchor.selection.controller,
                anchor.selection.weaponName,
                VehicleWeaponShotDiagnostics.Boundary.SCHEDULER,
                "INVALID_FIXED_GUN_BANK",
            )
            return false
        }

        val origin = anchor.selection
        val definition = vehicle.computed()
        val fixedBank = VehicleFixedGunBanks.bankSnapshot(origin)

        for (resolved in expanded) {
            val key = resolved.selection.scheduleKey()
            // PRIMARY is visited first. An overlapping SECONDARY hold cannot double the rate.
            if (activeTriggers.containsKey(key)) continue
            activeTriggers[key] = ActiveTrigger(
                resolved, intent, origin, slot, definition, fixedBank,
            )
            if (states[key]?.matches(resolved.profile) != true) {
                states[key] = RuntimeState(resolved.profile)
            }
        }
        return true
    }

    private fun isCurrent(trigger: ActiveTrigger): Boolean {
        val selection = trigger.resolved.selection
        val controller = selection.controller
        val origin = trigger.origin
        if (!controller.isAlive || controller.vehicle !== vehicle ||
            vehicle.getSeatIndex(controller) != origin.seatIndex ||
            vehicle.computed() !== trigger.definition ||
            vehicle.getGunName(selection.seatIndex, selection.weaponIndex) != selection.weaponName
        ) return false

        val currentIndex = when (trigger.slot) {
            VehicleWeaponSlot.PRIMARY -> vehicle.getSelectedWeapon(origin.seatIndex)
            VehicleWeaponSlot.SECONDARY -> vehicle.getSecondaryWeaponIndex(origin.seatIndex)
        }
        if (currentIndex != origin.weaponIndex ||
            vehicle.getGunName(origin.seatIndex, origin.weaponIndex) != origin.weaponName
        ) return false

        val bank = trigger.fixedBank ?: return selection.weaponIndex == origin.weaponIndex
        if (VehicleFixedGunBanks.bankSnapshot(origin) != bank ||
            selection.weaponName !in bank
        ) return false

        return VehicleFixedGunBanks.permitsEvent(selection, trigger.resolved.profile)
    }

    private fun processTrigger(key: ScheduleKey, trigger: ActiveTrigger, tick: Int) {
        val state = states[key] ?: return
        repeat(trigger.resolved.profile.maxCatchUpEvents) {
            if (!state.canAttempt()) return
            state.consumeCredit()

            val result = execute(trigger)
            if (!result.isAccepted()) {
                state.recordRejected(result)
                return
            }

            notifyAcceptedShot(trigger, result, state, key, tick)
        }
    }

    private fun notifyAcceptedShot(
        trigger: ActiveTrigger,
        result: ShotResult,
        state: RuntimeState,
        key: ScheduleKey,
        tick: Int,
    ) {
        val scheduledSoundDue = state.recordAccepted(tick)
        val snapshot = state.snapshot(key)
        try {
            trigger.resolved.provider.onAcceptedShot(
                trigger.resolved.selection,
                result,
                snapshot,
                scheduledSoundDue,
                trigger.resolved.profile.soundPitch(snapshot.heatFraction),
            )
        } catch (exception: RuntimeException) {
            Mod.LOGGER.warn(
                "Vehicle weapon schedule provider {} failed after accepted shot on entity {}",
                trigger.resolved.providerId,
                vehicle.id,
                exception,
            )
        }
    }

    fun shouldEmitNativeSound(): Boolean = executingProfile?.emitNativeSound ?: true

    fun permitsDirectAttempt(controller: LivingEntity): Boolean {
        return executingProfile != null || !hasSchedule(controller)
    }

    fun snapshot(seatIndex: Int, weaponIndex: Int): VehicleWeaponScheduleSnapshot? {
        val name = vehicle.getGunName(seatIndex, weaponIndex) ?: return null
        val key = ScheduleKey(seatIndex, weaponIndex, name)
        return states[key]?.snapshot(key)
    }

    fun clientSnapshot(
        payload: String,
        seatIndex: Int,
        weaponIndex: Int,
    ): VehicleWeaponScheduleSnapshot? {
        if (payload != clientPayload) {
            clientPayload = payload
            clientSnapshots = VehicleWeaponScheduleSnapshots.decode(payload)
        }
        return clientSnapshots.firstOrNull {
            it.seatIndex == seatIndex && it.weaponIndex == weaponIndex
        }
    }

    private fun execute(trigger: ActiveTrigger): ShotResult {
        if (!isCurrent(trigger)) {
            val selection = trigger.resolved.selection
            VehicleWeaponShotDiagnostics.recordBoundary(
                vehicle, selection.controller, selection.weaponName,
                VehicleWeaponShotDiagnostics.Boundary.SCHEDULER,
                "STALE_OR_UNSUPPORTED_TRIGGER_CONTEXT",
            )
            return ShotResult.rejected(ShotRejectionReason.NO_WEAPON, selection.weaponName)
        }
        executingProfile = trigger.resolved.profile
        return try {
            val target = VehicleWeaponTargetValidator.sanitize(
                vehicle,
                trigger.resolved.selection,
                trigger.intent.targetEntityUuid,
                trigger.intent.targetPos,
            )
            val selection = trigger.resolved.selection
            if (selection.weaponIndex == vehicle.getSelectedWeapon(selection.seatIndex)) {
                vehicle.vehicleShootResult(selection.controller, target.entityUuid, target.position)
            } else {
                vehicle.vehicleShootResult(
                    selection.controller,
                    selection.weaponName,
                    target.entityUuid,
                    target.position,
                )
            }
        } finally {
            executingProfile = null
        }
    }

    private fun syncSnapshots() {
        snapshotBuffer.clear()
        for ((key, state) in states) {
            snapshotBuffer.add(state.snapshot(key))
        }
        val payload = VehicleWeaponScheduleSnapshots.encode(snapshotBuffer)
        if (payload == lastSyncedPayload) return
        lastSyncedPayload = payload
        vehicle.publishWeaponScheduleSnapshots(payload)
    }

    private fun findPassenger(uuid: UUID): LivingEntity? {
        for (passenger in vehicle.passengers) {
            if (passenger is LivingEntity && passenger.uuid == uuid &&
                passenger.isAlive && passenger.vehicle === vehicle
            ) return passenger
        }
        return null
    }

    private fun resolveSecondary(controller: LivingEntity): ResolvedVehicleWeaponSchedule? {
        val seatIndex = vehicle.getSeatIndex(controller)
        val secondaryIndex = vehicle.getSecondaryWeaponIndex(seatIndex) ?: return null
        return VehicleWeaponScheduleProviders.resolve(vehicle, controller, secondaryIndex)
    }

    private data class TriggerIntent(
        val targetEntityUuid: UUID?,
        val targetPos: Vec3?,
        val seatIndex: Int,
    )

    private data class ActiveTrigger(
        val resolved: ResolvedVehicleWeaponSchedule,
        val intent: TriggerIntent,
        val origin: VehicleWeaponSelection,
        val slot: VehicleWeaponSlot,
        val definition: DefaultVehicleData,
        val fixedBank: List<String>?,
    )

    internal data class ScheduleKey(
        val seatIndex: Int,
        val weaponIndex: Int,
        val weaponName: String,
    )

    private fun VehicleWeaponSelection.scheduleKey() =
        ScheduleKey(seatIndex, weaponIndex, weaponName)

    internal class RuntimeState(private val profile: VehicleWeaponScheduleProfile) {
        private val recentProjectileTicks = ArrayDeque<Int>()
        private var lastCreditTick = Int.MIN_VALUE
        private var lastTriggerActivityTick = Int.MIN_VALUE
        private var bulletsSinceSound = 0
        private var firstSoundAfterPress = true
        private var rawTriggerHeld = false
        private var triggerHeld = false
        private var shotCredits = 0.0
        private var overheatTicks = 0
        private var acceptedSequence = 0L
        private var acceptedOnCurrentPress = false
        private var lastDecision = "idle"

        /** Preserve packet release edges even when release and repress precede one server tick. */
        fun releaseEdge() {
            rawTriggerHeld = false
            if (!profile.repeatWhileHeld) {
                releaseTrigger()
                lastTriggerActivityTick = Int.MIN_VALUE
            }
            // Credits, their timestamp, heat and automatic release grace remain unchanged.
        }

        fun tick(tick: Int, held: Boolean) {
            val continuouslyHeld = held && rawTriggerHeld
            rawTriggerHeld = held
            if (held) lastTriggerActivityTick = tick

            tickOverheatCooldown()
            trimRecentProjectileTicks(tick)
            advanceShotCredits(tick, continuouslyHeld && overheatTicks <= 0)

            val effectiveHeld = held || hasRecentTriggerActivity(tick)
            if (effectiveHeld && overheatTicks <= 0) {
                if (!triggerHeld) {
                    firstSoundAfterPress = true
                    acceptedOnCurrentPress = false
                    lastDecision = "pressed"
                }
                triggerHeld = true
                return
            }

            decayHeatAfterRelease(tick)
            releaseTrigger()
        }

        fun canAttempt(): Boolean {
            if (!rawTriggerHeld || !triggerHeld) {
                lastDecision = "reject_idle"
                return false
            }
            if (shotCredits < 1.0) {
                lastDecision = "reject_cooldown"
                return false
            }
            if (!profile.repeatWhileHeld && acceptedOnCurrentPress) {
                lastDecision = "reject_semi_held"
                return false
            }
            return true
        }

        fun consumeCredit() {
            shotCredits = max(0.0, shotCredits - 1.0)
            lastDecision = "attempt"
        }

        fun recordRejected(result: ShotResult) {
            // Reload/ammo/bolt admission did not launch a projectile. Keep one ready
            // attempt so a 5 RPM launcher does not lose another 12 seconds after reload.
            // processTrigger returns immediately on rejection: never retry in this tick.
            if (result.reason == ShotRejectionReason.CANNOT_SHOOT) {
                shotCredits = min(1.0, shotCredits + 1.0)
            }
            lastDecision = "reject_${result.reason.name.lowercase()}"
        }

        fun recordAccepted(tick: Int): Boolean {
            acceptedSequence++
            acceptedOnCurrentPress = true
            val firstShot = firstSoundAfterPress
            firstSoundAfterPress = false
            bulletsSinceSound += profile.projectilesPerEvent
            recordProjectileHeat(tick)
            if (firstShot || bulletsSinceSound >= profile.soundIntervalProjectiles) {
                bulletsSinceSound = 0
                return true
            }
            return false
        }

        fun snapshot(key: ScheduleKey): VehicleWeaponScheduleSnapshot {
            val heat = heatFraction()
            val spinning = triggerHeld && overheatTicks <= 0
            return VehicleWeaponScheduleSnapshot(
                profileId = profile.id,
                seatIndex = key.seatIndex,
                weaponIndex = key.weaponIndex,
                triggerHeld = triggerHeld,
                visualStage = if (spinning) 4 else -1,
                currentRpm = if (spinning) profile.bulletRpm else 0,
                maxRpm = profile.bulletRpm,
                shotCredits = shotCredits,
                heatFraction = heat,
                overheatTicks = overheatTicks,
                acceptedSequence = acceptedSequence,
                lastDecision = lastDecision,
            )
        }

        private fun advanceShotCredits(tick: Int, allowCatchUp: Boolean) {
            if (lastCreditTick == Int.MIN_VALUE) {
                lastCreditTick = tick
                shotCredits = 1.0
                return
            }
            val elapsedTicks = max(0, tick - lastCreditTick)
            val eventsPerTick = profile.eventRpm / 1200.0
            val accrued = shotCredits + elapsedTicks * eventsPerTick
            // A released trigger may ready one shot, never bank a burst. Keep fractional
            // cooldown across presses, weapon selection and the presentation release grace.
            // Idle states may skip ticks; their next press still receives at most one event.
            if (!allowCatchUp) {
                shotCredits = min(1.0, accrued)
                lastCreditTick = tick
                return
            }
            // Bound whole catch-up events, retaining fractional phase. Clamping the entire
            // balance silently slows rates such as 1700 or 3400 RPM.
            shotCredits = min(profile.maxCatchUpEvents.toDouble(), floor(accrued)) + (accrued - floor(accrued))
            lastCreditTick = tick
        }

        private fun recordProjectileHeat(tick: Int) {
            val policy = profile.heatPolicy ?: return
            trimRecentProjectileTicks(tick)
            repeat(profile.projectilesPerEvent) {
                recentProjectileTicks.addLast(tick)
            }
            lastDecision = "accepted"
            if (recentProjectileTicks.size >= policy.projectileLimit) {
                recentProjectileTicks.clear()
                overheatTicks = policy.cooldownTicks
                releaseTrigger()
                lastDecision = "overheat"
            }
        }

        private fun trimRecentProjectileTicks(tick: Int) {
            val policy = profile.heatPolicy ?: return
            while (recentProjectileTicks.isNotEmpty()
                && tick - recentProjectileTicks.first() > policy.windowTicks
            ) {
                recentProjectileTicks.removeFirst()
            }
        }

        private fun tickOverheatCooldown() {
            if (overheatTicks <= 0) return
            overheatTicks--
            if (overheatTicks > 0) return
            overheatTicks = 0
            recentProjectileTicks.clear()
            lastTriggerActivityTick = Int.MIN_VALUE
            firstSoundAfterPress = true
            lastDecision = "cooled"
        }

        private fun decayHeatAfterRelease(tick: Int) {
            val policy = profile.heatPolicy ?: return
            if (recentProjectileTicks.isEmpty() || hasRecentTriggerActivity(tick)) return
            if (tick % policy.releaseDecayTicks == 0) {
                recentProjectileTicks.removeFirst()
            }
        }

        private fun releaseTrigger() {
            if (triggerHeld) lastDecision = "released"
            triggerHeld = false
        }

        private fun heatFraction(): Double {
            if (overheatTicks > 0) return 1.0
            val limit = profile.heatPolicy?.projectileLimit ?: return 0.0
            return (recentProjectileTicks.size / limit.toDouble()).coerceIn(0.0, 1.0)
        }

        private fun hasRecentTriggerActivity(tick: Int): Boolean {
            return lastTriggerActivityTick != Int.MIN_VALUE
                && tick - lastTriggerActivityTick <= profile.releaseGraceTicks
        }

        fun isStableIdle(): Boolean =
            !rawTriggerHeld && !triggerHeld && overheatTicks == 0 &&
                    recentProjectileTicks.isEmpty()

        fun matches(profile: VehicleWeaponScheduleProfile): Boolean = this.profile == profile
    }
}
