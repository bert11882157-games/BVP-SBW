package com.atsuishio.superbwarfare.api.aircraft

/** Equipment admission and accepted launch ordering, independent of vehicle/world adapters. */
internal object AircraftStoreLaunchTransaction {
    fun execute(used: Int, capacity: Int, lastFireTick: Long?, now: Long,
                launch: () -> Unit, commit: () -> Unit) {
        require(used >= 0 && used < capacity) { "Hardpoint is empty; refit on the ground." }
        require(lastFireTick == null || now - lastFireTick >= 10) { "Launcher is cycling." }
        // A missing target, failed profile, or rejected entity insertion throws before mutation.
        launch()
        commit()
    }
}
