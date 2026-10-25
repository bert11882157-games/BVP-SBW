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

/**
 * Server-owned cadence, catch-up, and fixed-window heat lifecycle for addon-profiled vehicle weapons.
 *
 * Cadence, catch-up credit and heat belong to the physical weapon (its channel name: one GunData, one magazine), not
 * to the seat that fires it. A weapon listed on several seats (a helicopter's pilot and gunner both selecting the same
 * gun, pod or store) is one schedule: whichever seats hold the trigger, rounds come at that weapon's own rate and heat
 * builds once. Trigger state (who is holding it) stays per seat for presentation and the gun-stow rule.
 */
class VehicleWeaponScheduler(private val vehicle: VehicleEntity) {
    private val triggerIntents = VehicleWeaponTriggerIntents<TriggerIntent, ResolvedVehicleWeaponSchedule>(
        currentSeat = { uuid -> findPassenger(uuid)?.let { vehicle.getSeatIndex(it) } },
        resolve = { uuid, slot ->
            findPassenger(uuid)?.let { controller ->
                when (slot) {
                    VehicleWeaponSlot.PRIMARY -> VehicleWeaponScheduleProviders.resolve(vehicle, controller)
                    VehicleWeaponSlot.SECONDARY -> resolveSecondary(controller)
                }
            }
        },
    )
    /** Per physical weapon ([physicalWeapon] of a key). */
    private val states = LinkedHashMap<String, RuntimeState>()
    private val activeTriggers = LinkedHashMap<ScheduleKey, ActiveTrigger>()
    /** The last tick each seat's own trigger held a weapon (per-seat presentation within the release grace). */
    private val lastSeatHoldTicks = HashMap<ScheduleKey, Int>()
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
            triggerIntents.release(VehicleWeaponSlot.PRIMARY, controller.uuid)
            releaseIntentEdge(controller, resolved?.selection, VehicleWeaponSlot.PRIMARY)
            return true
        }
        if (resolved == null) {
            triggerIntents.release(VehicleWeaponSlot.PRIMARY, controller.uuid)
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

        triggerIntents.retain(VehicleWeaponSlot.PRIMARY, controller.uuid, vehicle.getSeatIndex(controller),
            TriggerIntent(targetEntityUuid, targetPos))
        return true
    }

    fun updateSecondaryTrigger(controller: LivingEntity, held: Boolean): Boolean {
        val resolved = resolveSecondary(controller)
        if (!held) {
            triggerIntents.release(VehicleWeaponSlot.SECONDARY, controller.uuid)
            releaseIntentEdge(controller, resolved?.selection, VehicleWeaponSlot.SECONDARY)
            return true
        }
        if (resolved == null) {
            triggerIntents.release(VehicleWeaponSlot.SECONDARY, controller.uuid)
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
        triggerIntents.retain(VehicleWeaponSlot.SECONDARY, controller.uuid, vehicle.getSeatIndex(controller),
            TriggerIntent(null, null))
        return true
    }

    private fun releaseIntentEdge(controller: LivingEntity, selection: VehicleWeaponSelection?,
                                  slot: VehicleWeaponSlot) {
        // A weapon another seat is still firing keeps its trigger held: one seat letting go does not re-arm it.
        selection?.let { releaseUnlessHeldByOther(physicalWeapon(it.scheduleKey()), controller) }
        // Fixed banks may fan one held slot into several independent weapon schedules.
        for ((key, trigger) in activeTriggers) {
            if (trigger.slot == slot && trigger.origin.controller.uuid == controller.uuid) {
                releaseUnlessHeldByOther(physicalWeapon(key), controller)
            }
        }
    }

    private fun releaseUnlessHeldByOther(weapon: String, controller: LivingEntity) {
        val others = activeTriggers.entries.any { (key, trigger) ->
            physicalWeapon(key) == weapon && trigger.origin.controller.uuid != controller.uuid
        }
        if (!others) states[weapon]?.releaseEdge()
    }

    fun tick() {
        if (vehicle.level().isClientSide) return

        val tick = vehicle.tickCount
        refreshActiveTriggers()
        val removedStaleState = states.keys.removeAll { vehicle.getGunData(it) == null }
        for (key in activeTriggers.keys) lastSeatHoldTicks[key] = tick
        lastSeatHoldTicks.entries.removeIf { (key, at) ->
            tick - at > SEAT_HOLD_MEMORY_TICKS || vehicle.getGunName(key.seatIndex, key.weaponIndex) != key.weaponName
        }
        if (activeTriggers.isEmpty() && states.values.all { it.isStableIdle() }) {
            if (removedStaleState) syncSnapshots()
            return
        }

        val held = activeTriggers.keys.mapTo(HashSet()) { physicalWeapon(it) }
        states.forEach { (weapon, state) -> state.tick(tick, weapon in held) }
        // One physical weapon fires at most its own cadence per tick, however many seats hold it: the first holder
        // spends the shared credits; a later holder only tries when the earlier one could not fire for a reason of
        // its own (a stale selection), never for a second round in the same cadence slot.
        val fired = HashSet<String>()
        activeTriggers.forEach { (key, trigger) ->
            if (admitsSharedAttempt(fired, key)) processTrigger(key, trigger, tick, fired)
        }
        syncSnapshots()
    }

    /** Current qualified hold for aiming, not shot readiness and not the delayed presentation snapshot. */
    fun holdsCurrentWeapon(seatIndex: Int, weaponIndex: Int): Boolean {
        if (vehicle.level().isClientSide || seatIndex < 0 || weaponIndex < 0) return false
        val controller = vehicle.getNthEntity(seatIndex) as? LivingEntity ?: return false
        val name = vehicle.getGunName(seatIndex, weaponIndex) ?: return false
        return triggerIntents.holds(controller.uuid, seatIndex) { anchor, slot ->
            VehicleFixedGunBanks.expand(anchor, slot == VehicleWeaponSlot.PRIMARY)?.any {
                it.selection.seatIndex == seatIndex && it.selection.weaponIndex == weaponIndex &&
                    it.selection.weaponName == name
            } == true
        }
    }

    private fun refreshActiveTriggers() {
        activeTriggers.clear()
        for (slot in VehicleWeaponSlot.entries) {
            triggerIntents.refresh(slot, { uuid ->
                val controller = findPassenger(uuid)
                if (controller != null) {
                    VehicleWeaponShotDiagnostics.recordBoundary(
                        vehicle, controller,
                        vehicle.getGunName(vehicle.getSeatIndex(controller))
                            ?: if (slot == VehicleWeaponSlot.PRIMARY) "<selected>" else "<secondary>",
                        VehicleWeaponShotDiagnostics.Boundary.SCHEDULER,
                        if (slot == VehicleWeaponSlot.PRIMARY) "NO_RESOLVED_SELECTION" else "NO_RESOLVED_SECONDARY",
                    )
                    VehicleWeaponShotDiagnostics.record(
                        vehicle, controller,
                        if (slot == VehicleWeaponSlot.PRIMARY) "<selected>" else "<secondary>",
                        ShotResult.rejected(ShotRejectionReason.NO_WEAPON),
                    )
                }
            }, ::activate)
        }
    }

    private fun activate(
        anchor: ResolvedVehicleWeaponSchedule,
        intent: TriggerIntent,
        slot: VehicleWeaponSlot,
    ): Boolean {
        var refusal = ""
        val expanded = VehicleFixedGunBanks.expand(anchor, slot == VehicleWeaponSlot.PRIMARY) { refusal = it }
        if (expanded == null) {
            VehicleWeaponShotDiagnostics.recordBoundary(
                vehicle,
                anchor.selection.controller,
                anchor.selection.weaponName,
                VehicleWeaponShotDiagnostics.Boundary.SCHEDULER,
                "INVALID_FIXED_GUN_BANK: $refusal",
            )
            warnRefusedBank(anchor.selection, refusal)
            return false
        }

        val origin = anchor.selection
        val definition = vehicle.computed()
        val fixedBank = VehicleFixedGunBanks.bankSnapshot(origin)

        for (resolved in expanded) {
            val key = resolved.selection.scheduleKey()
            // PRIMARY is visited first. An overlapping SECONDARY hold cannot double the rate.
            if (activeTriggers.containsKey(key)) continue
            // Every seat holding the weapon is active; they share the weapon's one RuntimeState (tick()).
            activeTriggers[key] = ActiveTrigger(
                resolved, intent, origin, slot, definition, fixedBank,
            )
            val weapon = physicalWeapon(key)
            val state = states[weapon]
            when {
                state == null -> states[weapon] = RuntimeState(resolved.profile)
                // Two seats may resolve the same weapon with different profiles (a fixed bank on one seat, the
                // plain channel on the other). The weapon keeps its credits, cooldown and heat: a handover between
                // seats can never mint a fresh ready shot.
                !state.matches(resolved.profile) -> state.retarget(resolved.profile)
            }
        }
        return true
    }

    /** A refused bank never fires on a held trigger: say why once per vehicle type, weapon and reason. */
    private fun warnRefusedBank(selection: VehicleWeaponSelection, reason: String) {
        val type = net.minecraft.world.entity.EntityType.getKey(vehicle.type)
        if (refusedBanks.add("$type/${selection.seatIndex}/${selection.weaponName}/$reason")) {
            Mod.LOGGER.warn("Fixed gun bank of {} seat {} weapon {} cannot fire: {}",
                type, selection.seatIndex, selection.weaponName, reason)
        }
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

    private fun processTrigger(key: ScheduleKey, trigger: ActiveTrigger, tick: Int, fired: MutableSet<String>) {
        val state = states[physicalWeapon(key)] ?: return
        repeat(trigger.resolved.profile.maxCatchUpEvents) {
            if (!state.canAttempt()) {
                // The cadence slot is spent (or the trigger idle): no other seat may try this tick.
                fired.add(physicalWeapon(key))
                return
            }
            state.consumeCredit()

            val result = execute(trigger)
            if (!result.isAccepted()) {
                state.recordRejected(result)
                // Ammunition, reload and launch admission belong to the weapon: another seat cannot do better.
                if (result.reason != ShotRejectionReason.NO_WEAPON) fired.add(physicalWeapon(key))
                return
            }
            fired.add(physicalWeapon(key))

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
        return states[physicalWeapon(key)]?.snapshot(key, seatHolds(key))
    }

    /** Whether this seat's own trigger is (or within the weapon's release grace was) holding the weapon. */
    private fun seatHolds(key: ScheduleKey): Boolean {
        if (key in activeTriggers) return true
        val state = states[physicalWeapon(key)] ?: return false
        return heldWithinGrace(lastSeatHoldTicks[key], vehicle.tickCount, state.releaseGraceTicks())
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
            AircraftRoundConsolidation.withRequestedWeight(trigger.resolved.profile.roundWeight) {
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
            }
        } finally {
            executingProfile = null
        }
    }

    private fun syncSnapshots() {
        snapshotBuffer.clear()
        // One entry per seat listing each scheduled weapon: the shared cadence and heat, that seat's own trigger.
        val lists = if (states.isEmpty()) emptyList() else List(vehicle.maxPassengers) { vehicle.getWeaponIds(it) }
        for ((weapon, state) in states) {
            for (seat in lists.indices) {
                val index = lists[seat].indexOf(weapon)
                if (index < 0) continue
                val key = ScheduleKey(seat, index, weapon)
                snapshotBuffer.add(state.snapshot(key, seatHolds(key)))
            }
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

    internal companion object {
        /** How long a released seat trigger is remembered for its presentation (longer than any release grace). */
        private const val SEAT_HOLD_MEMORY_TICKS = 40

        private val refusedBanks: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /**
         * The physical weapon a schedule key fires: its channel name. Every seat that lists the channel fires the
         * same gun (one GunData, magazine, reload and heat), so they share one cadence state.
         */
        @JvmStatic
        internal fun physicalWeapon(key: ScheduleKey): String = key.weaponName

        /** False once an earlier holder spent this tick's cadence slot of the same physical weapon. */
        @JvmStatic
        internal fun admitsSharedAttempt(fired: Set<String>, candidate: ScheduleKey): Boolean =
            physicalWeapon(candidate) !in fired

        /** A seat counts as holding the trigger while it holds it, and for [grace] ticks after it let go. */
        @JvmStatic
        internal fun heldWithinGrace(lastHeldTick: Int?, tick: Int, grace: Int): Boolean =
            lastHeldTick != null && tick - lastHeldTick <= grace
    }

    internal class RuntimeState(private var profile: VehicleWeaponScheduleProfile) {
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
            // Consolidated events count every round they stand for, so the scheduled sound keeps
            // the real gun's cadence while half as many events are fired.
            bulletsSinceSound += profile.roundsPerEvent
            recordProjectileHeat(tick)
            if (firstShot || bulletsSinceSound >= profile.soundIntervalProjectiles) {
                bulletsSinceSound = 0
                return true
            }
            return false
        }

        fun snapshot(key: ScheduleKey): VehicleWeaponScheduleSnapshot = snapshot(key, true)

        /**
         * The shared cadence and heat as seen from [key]'s seat; [seatHolding] false shows that seat's trigger as
         * released while another seat fires the weapon.
         */
        fun snapshot(key: ScheduleKey, seatHolding: Boolean): VehicleWeaponScheduleSnapshot {
            val heat = heatFraction()
            val held = triggerHeld && seatHolding
            val spinning = held && overheatTicks <= 0
            return VehicleWeaponScheduleSnapshot(
                profileId = profile.id,
                seatIndex = key.seatIndex,
                weaponIndex = key.weaponIndex,
                triggerHeld = held,
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
            repeat(profile.roundsPerEvent) {
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

        fun releaseGraceTicks(): Int = profile.releaseGraceTicks

        /**
         * Continue under [next] (the same weapon resolved for another seat or bank) keeping the credits, their
         * timestamp, heat and overheat: a seat handover never resets the weapon's cooldown. Credits above the new
         * catch-up bound are dropped.
         */
        fun retarget(next: VehicleWeaponScheduleProfile) {
            if (next == profile) return
            profile = next
            shotCredits = min(shotCredits, max(1.0, next.maxCatchUpEvents.toDouble()))
        }
    }
}
