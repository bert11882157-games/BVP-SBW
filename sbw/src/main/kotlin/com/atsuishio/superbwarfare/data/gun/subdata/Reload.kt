package com.atsuishio.superbwarfare.data.gun.subdata

import com.atsuishio.superbwarfare.data.gun.GunData
import com.atsuishio.superbwarfare.data.gun.value.IntValue
import com.atsuishio.superbwarfare.data.gun.value.ReloadState
import com.atsuishio.superbwarfare.data.gun.value.Starter
import com.atsuishio.superbwarfare.data.gun.value.Timer
import net.minecraft.nbt.CompoundTag
import kotlin.math.ceil
import kotlin.math.max

class Reload(private val data: CompoundTag) {
    constructor(data: GunData) : this(data.data())

    @JvmField
    val reloadTimer = Timer(this.data, "Reload")

    @JvmField
    val prepareTimer = Timer(this.data, "Prepare")

    @JvmField
    val prepareLoadTimer = Timer(this.data, "PrepareLoad")

    @JvmField
    val iterativeLoadTimer = Timer(this.data, "IterativeLoad")

    @JvmField
    val finishTimer = Timer(this.data, "Finish")

    @JvmField
    val reloadStarter = Starter(this.data, "Reload")

    @JvmField
    val singleReloadStarter = Starter(this.data, "SingleReload")

    @JvmField
    val stage3Starter = Starter(this.data, "Stage3Forcefully")

    private val pendingProgress = IntValue(this.data, "PendingReloadProgress", 0)

    /** Set by an ammunition switch: the next reload loads a whole new belt, whatever the magazine count says. */
    private val fullChangeValue = IntValue(this.data, "ReloadFullChange", 0)

    fun markFullChange() = fullChangeValue.set(1)

    fun clearFullChange() = fullChangeValue.reset()

    /** Reads and clears the switch marker. */
    fun consumeFullChange(): Boolean = (fullChangeValue.get() != 0).also { fullChangeValue.reset() }

    /** Persistent monotonic presentation revision for exactly-once vehicle reload audio. */
    private val soundCycleRevisionValue = IntValue(this.data, "ReloadSoundRevision", 0)

    fun soundCycleRevision(): Int = soundCycleRevisionValue.get()

    /** Starts one authoritative reload presentation cycle and returns its new revision. */
    fun beginSoundCycle(): Int {
        val current = soundCycleRevisionValue.get()
        val next = if (current == Int.MAX_VALUE) 1 else current + 1
        soundCycleRevisionValue.set(next)
        return next
    }

    fun state() = when (data.getInt("ReloadState")) {
        1 -> ReloadState.NORMAL_RELOADING
        2 -> ReloadState.EMPTY_RELOADING
        else -> ReloadState.NOT_RELOADING
    }

    fun normal() = state() == ReloadState.NORMAL_RELOADING

    fun empty() = state() == ReloadState.EMPTY_RELOADING

    fun setState(state: ReloadState) {
        if (state == ReloadState.NOT_RELOADING) {
            data.remove("ReloadState")
        } else {
            data.putInt("ReloadState", state.ordinal)
        }
    }

    val stage = IntValue(this.data, "ReloadStage", 0)

    fun stage() = stage.get()

    fun setStage(stage: Int) {
        this.stage.set(stage)
    }

    fun time() = reloadTimer.get()

    /** Length of the current reload when it was chosen at the start (a depletion reload), else 0. */
    private val totalValue = IntValue(this.data, "ReloadTotal", 0)

    fun total() = totalValue.get()

    fun setTotal(ticks: Int) {
        if (ticks > 0) totalValue.set(ticks) else totalValue.reset()
    }

    fun setTime(time: Int) {
        reloadTimer.set(time)
    }

    fun reduce() {
        reloadTimer.reduce()
    }

    /** Magazine reloads complete even when a short or credited timer reaches zero in one tick. */
    fun countdownFinished() = state() != ReloadState.NOT_RELOADING && stage() == 0 && time() <= 1

    fun pendingProgressPercent() = pendingProgress.get().coerceIn(0, 99)

    fun setPendingProgressPercent(progressPercent: Int) {
        require(progressPercent in 0..99) { "progressPercent must be between 0 and 99" }
        pendingProgress.set(progressPercent)
    }

    fun clearPendingProgress() {
        pendingProgress.reset()
    }

    /** Applies a persisted partial-reload request once the normal reload timer exists. */
    fun applyPendingProgress(): Int {
        val progressPercent = pendingProgressPercent()
        val remainingTicks = time()
        if (progressPercent <= 0 || remainingTicks <= 0) return 0

        pendingProgress.reset()
        setTime(max(1, ceil(remainingTicks * (100 - progressPercent) / 100.0).toInt()))
        return progressPercent
    }
}
