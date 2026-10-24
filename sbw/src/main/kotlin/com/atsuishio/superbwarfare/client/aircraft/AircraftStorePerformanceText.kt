package com.atsuishio.superbwarfare.client.aircraft

import com.atsuishio.superbwarfare.api.aircraft.AircraftStorePerformance
import java.util.Locale

/** Tooltip wording for [AircraftStorePerformance] figures, in metric units. */
internal object AircraftStorePerformanceText {
    private const val STANDARD_GRAVITY = 9.80665
    private const val TICKS = AircraftStorePerformance.TICKS_PER_SECOND

    data class Line(val label: String, val value: String, val known: Boolean) {
        override fun toString() = "$label: $value"
    }

    fun lines(stats: AircraftStorePerformance.Stats): List<Line> = listOf(
        line("Turn rate", stats.turnRateDegreesPerSecond) { "${number(it)}°/s" },
        line("Explosive mass", stats.explosiveKgTnt) { "${number(it)} kg TNT" },
        line("Acceleration", stats.accelerationBlocksPerTickSquared) {
            val metresPerSecondSquared = it * TICKS * TICKS
            "${number(metresPerSecondSquared)} m/s² (${number(metresPerSecondSquared / STANDARD_GRAVITY)} g)"
        },
        line("Top speed", stats.topSpeedBlocksPerTick) {
            "${number(it * TICKS)} m/s (${number(it * TICKS * 3.6)} km/h)"
        },
    )

    private fun line(label: String, figure: AircraftStorePerformance.Figure, format: (Double) -> String): Line =
        figure.value?.let { Line(label, format(it), true) } ?: Line(label, figure.reason ?: "Unknown", false)

    /** Three significant figures below 100, whole numbers above. */
    fun number(value: Double): String = when {
        value >= 100.0 -> String.format(Locale.ROOT, "%.0f", value)
        value >= 10.0 -> String.format(Locale.ROOT, "%.1f", value).removeSuffix(".0")
        else -> String.format(Locale.ROOT, "%.2f", value).trimEnd('0').removeSuffix(".")
    }
}
