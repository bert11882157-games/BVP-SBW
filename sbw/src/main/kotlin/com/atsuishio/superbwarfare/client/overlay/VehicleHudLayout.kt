package com.atsuishio.superbwarfare.client.overlay

import com.atsuishio.superbwarfare.Mod
import com.google.gson.JsonElement
import com.google.gson.JsonNull
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.minecraft.resources.ResourceLocation
import net.minecraft.client.Minecraft
import net.minecraft.server.packs.resources.ResourceManager
import net.minecraft.server.packs.resources.SimplePreparableReloadListener
import net.minecraft.util.profiling.ProfilerFiller
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import net.minecraftforge.client.event.RegisterClientReloadListenersEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import net.minecraftforge.fml.common.Mod.EventBusSubscriber
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.util.Collections
import java.util.LinkedHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Immutable, resource-reloadable layout for the ground vehicle HUD.  The JSON is intentionally
 * normalized to the scaled GUI viewport; renderers only consume the cached [ScaledLayout].
 */
@OnlyIn(Dist.CLIENT)
@EventBusSubscriber(
    modid = Mod.MODID,
    bus = EventBusSubscriber.Bus.MOD,
    value = [Dist.CLIENT]
)
object VehicleHudLayout : SimplePreparableReloadListener<JsonElement>() {
    const val EDITABLE_LAYOUT_PATH = "assets/superbwarfare/hud/vehicle_ground_layout.json"
    private const val RESOURCE_PATH = "hud/vehicle_ground_layout.json"
    private const val SCHEMA = 1
    private const val DEFAULT_MIN_TEXT_SCALE = 0.72f
    private const val DEFAULT_SAFE_PADDING = 0.025f
    /** Shared panel rhythm for the semantic Info1/Info2 anchors. */
    const val INFO_PANEL_TOP_INSET_LINES = 1.15f
    const val INFO_PANEL_LINE_SPACING = 1.10f

    private val layoutResource = ResourceLocation(Mod.MODID, RESOURCE_PATH)
    private val requiredZones = listOf(
        "upper_left", "upper_center", "upper_right",
        "middle_left", "middle_center", "middle_right",
        "lower_left", "lower_center", "lower_right",
    )
    private val requiredElements = listOf(
        // Semantic anchors are explicit so every renderer agrees on the same upper-left and
        // mirrored upper-right regions.  Legacy element names remain accepted as aliases for
        // compatibility with callers that still ask for a particular row/content role.
        "info1", "info2", "module_status", "orientation", "speed", "ammo", "reload",
        "selection", "zero", "selected_ammo", "zoom", "decoy",
    )

    private val defaultSnapshot = Snapshot(
        zones = defaultZones(),
        assignments = defaultAssignments(),
    )

    @Volatile
    private var currentSnapshot: Snapshot = defaultSnapshot

    @Volatile
    private var scaledCache: ScaledLayout? = null

    private var invalidDiagnosticEmitted = false

    override fun prepare(
        resourceManager: ResourceManager,
        profiler: ProfilerFiller,
    ): JsonElement {
        return try {
            val resource = resourceManager.getResource(layoutResource)
            if (resource.isEmpty) {
                JsonNull.INSTANCE
            } else {
                resource.get().open().use { input ->
                    InputStreamReader(input, StandardCharsets.UTF_8).use(JsonParser::parseReader)
                }
            }
        } catch (_: Exception) {
            JsonNull.INSTANCE
        }
    }

    override fun apply(
        prepared: JsonElement,
        resourceManager: ResourceManager,
        profiler: ProfilerFiller,
    ) {
        val next = parse(prepared)
        synchronized(this) {
            currentSnapshot = next ?: run {
                emitInvalidDiagnostic()
                defaultSnapshot
            }
            scaledCache = null
            if (next != null) invalidDiagnosticEmitted = false
        }
    }

    @SubscribeEvent
    @JvmStatic
    fun registerReloadListener(event: RegisterClientReloadListenersEvent) {
        event.registerReloadListener(this)
    }

    /** Returns the immutable current resource snapshot; no parsing or copying occurs here. */
    @JvmStatic
    fun snapshot(): Snapshot = currentSnapshot

    /**
     * Returns a cached viewport projection.  A new object is made only when the viewport, font
     * line height, or immutable resource snapshot changes, never once per render call.
     */
    @JvmStatic
    fun scaled(width: Int, height: Int, lineHeight: Int): ScaledLayout {
        val snapshot = currentSnapshot
        val cached = scaledCache
        if (cached != null && cached.snapshot === snapshot && cached.width == width &&
            cached.height == height && cached.lineHeight == lineHeight
        ) {
            return cached
        }
        synchronized(this) {
            val existing = scaledCache
            if (existing != null && existing.snapshot === snapshot && existing.width == width &&
                existing.height == height && existing.lineHeight == lineHeight
            ) {
                return existing
            }
            return ScaledLayout(snapshot, width, height, lineHeight).also { scaledCache = it }
        }
    }

    @JvmStatic
    fun scaled(width: Int, height: Int): ScaledLayout =
        scaled(width, height, Minecraft.getInstance().font.lineHeight)

    private fun parse(root: JsonElement?): Snapshot? {
        return try {
            val objectRoot = root?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            if (number(objectRoot, "schema") != SCHEMA.toDouble()) return null

            val zoneArray = objectRoot.get("zones")?.takeIf { it.isJsonArray }?.asJsonArray ?: return null
            val zones = LinkedHashMap<String, ZoneSpec>(requiredZones.size)
            for (element in zoneArray) {
                val zone = element.takeIf { it.isJsonObject }?.asJsonObject ?: return null
                val id = zone.get("id")?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
                    ?: return null
                if (id !in requiredZones || zones.containsKey(id)) return null
                val x = number(zone, "x")?.toFloat() ?: return null
                val y = number(zone, "y")?.toFloat() ?: return null
                val width = number(zone, "width")?.toFloat() ?: return null
                val height = number(zone, "height")?.toFloat() ?: return null
                val padding = number(zone, "safePadding")?.toFloat() ?: return null
                val minimumScale = if (zone.has("minTextScale")) {
                    number(zone, "minTextScale")?.toFloat() ?: return null
                } else {
                    DEFAULT_MIN_TEXT_SCALE
                }
                val spec = ZoneSpec(id, x, y, width, height, padding, minimumScale)
                if (!spec.isValid()) return null
                zones[id] = spec
            }
            if (zones.keys != requiredZones.toSet()) return null
            if (zones.values.any { it.x + it.width > 1f || it.y + it.height > 1f }) return null
            if (zones.values.any { it.x + it.safePadding < 0f || it.y + it.safePadding < 0f ||
                    it.x + it.width - it.safePadding > 1f || it.y + it.height - it.safePadding > 1f }) return null
            if (zones.values.any { it.contentWidth <= 0f || it.contentHeight <= 0f }) return null

            val zoneValues = zones.values.toList()
            for (i in zoneValues.indices) {
                for (j in i + 1 until zoneValues.size) {
                    if (zoneValues[i].overlaps(zoneValues[j])) return null
                }
            }

            val elementObject = objectRoot.get("elements")?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
            val assignments = LinkedHashMap<String, String>(requiredElements.size)
            for (element in requiredElements) {
                val assigned = elementObject.get(element)
                    ?.takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString ?: return null
                if (assigned !in zones) return null
                assignments[element] = assigned
            }
            if (elementObject.entrySet().map { it.key }.toSet() != requiredElements.toSet()) return null
            Snapshot(zones.toMap(), assignments.toMap())
        } catch (_: RuntimeException) {
            null
        }
    }

    private fun number(objectRoot: JsonObject, name: String): Double? {
        val value = objectRoot.get(name) ?: return null
        if (!value.isJsonPrimitive || !value.asJsonPrimitive.isNumber) return null
        val number = value.asDouble
        return number.takeIf { it.isFinite() }
    }

    private fun emitInvalidDiagnostic() {
        if (!invalidDiagnosticEmitted) {
            invalidDiagnosticEmitted = true
            Mod.LOGGER.warn("Invalid {}; using built-in ground HUD layout", EDITABLE_LAYOUT_PATH)
        }
    }

    private fun defaultZones(): Map<String, ZoneSpec> {
        val zones = LinkedHashMap<String, ZoneSpec>(requiredZones.size)
        for (row in 0..2) {
            for (column in 0..2) {
                val id = when (row) {
                    0 -> listOf("upper_left", "upper_center", "upper_right")[column]
                    1 -> listOf("middle_left", "middle_center", "middle_right")[column]
                    else -> listOf("lower_left", "lower_center", "lower_right")[column]
                }
                zones[id] = ZoneSpec(
                    id,
                    column / 3f,
                    row / 3f,
                    1f / 3f,
                    1f / 3f,
                    DEFAULT_SAFE_PADDING,
                    DEFAULT_MIN_TEXT_SCALE,
                )
            }
        }
        return zones
    }

    private fun defaultAssignments(): Map<String, String> = linkedMapOf(
        "info1" to "upper_left",
        "info2" to "upper_right",
        "module_status" to "upper_left",
        // Keep the compact hull/bearing glyph in the upper-center slot directly below the
        // compass.  Renderers still clamp its top edge to this authored zone.
        "orientation" to "upper_center",
        "speed" to "upper_left",
        "ammo" to "lower_left",
        "reload" to "upper_right",
        "selection" to "upper_right",
        "zero" to "upper_left",
        "selected_ammo" to "upper_right",
        "zoom" to "upper_left",
        "decoy" to "middle_center",
    )

    class Snapshot internal constructor(
        zones: Map<String, ZoneSpec>,
        assignments: Map<String, String>,
    ) {
        val zones: Map<String, ZoneSpec> = Collections.unmodifiableMap(LinkedHashMap(zones))
        val assignments: Map<String, String> = Collections.unmodifiableMap(LinkedHashMap(assignments))

        fun zone(id: String): ZoneSpec? = zones[id]
        fun elementZone(element: String): ZoneSpec? = assignments[element]?.let(zones::get)
    }

    class ZoneSpec(
        @JvmField val id: String,
        @JvmField val x: Float,
        @JvmField val y: Float,
        @JvmField val width: Float,
        @JvmField val height: Float,
        @JvmField val safePadding: Float,
        @JvmField val minTextScale: Float,
    ) {
        val contentWidth: Float get() = width - safePadding * 2f
        val contentHeight: Float get() = height - safePadding * 2f

        fun isValid(): Boolean = x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() &&
            safePadding.isFinite() && minTextScale.isFinite() && width > 0f && height > 0f &&
            safePadding >= 0f && minTextScale > 0f && minTextScale <= 1f &&
            contentWidth > 0f && contentHeight > 0f

        fun overlaps(other: ZoneSpec): Boolean =
            x < other.x + other.width && other.x < x + width &&
                y < other.y + other.height && other.y < y + height
    }

    class ScaledLayout internal constructor(
        @JvmField val snapshot: Snapshot,
        @JvmField val width: Int,
        @JvmField val height: Int,
        @JvmField val lineHeight: Int,
    ) {
        private val scaledZones: Map<String, ScaledZone> = Collections.unmodifiableMap(
            snapshot.zones.mapValues { (_, spec) -> ScaledZone(spec, width, height, lineHeight) }
        )

        fun zone(id: String): ScaledZone = scaledZones[id] ?: scaledZones.getValue("middle_center")
        fun element(element: String): ScaledZone = zone(snapshot.assignments[element] ?: "middle_center")
    }

    class ScaledZone internal constructor(
        spec: ZoneSpec,
        viewportWidth: Int,
        viewportHeight: Int,
        @JvmField val lineHeight: Int,
    ) {
        @JvmField val left: Int = (spec.x + spec.safePadding).times(viewportWidth).roundToInt()
        @JvmField val top: Int = (spec.y + spec.safePadding).times(viewportHeight).roundToInt()
        @JvmField val right: Int = (spec.x + spec.width - spec.safePadding).times(viewportWidth).roundToInt()
        @JvmField val bottom: Int = (spec.y + spec.height - spec.safePadding).times(viewportHeight).roundToInt()
        @JvmField val width: Int = max(1, right - left)
        @JvmField val height: Int = max(1, bottom - top)
        @JvmField val centerX: Int = (left + right) / 2
        @JvmField val centerY: Int = (top + bottom) / 2
        @JvmField val maxTextWidth: Int = width
        @JvmField val minTextScale: Float = spec.minTextScale
        private val baselineLimit: Int = max(top, bottom - lineHeight)
        @JvmField val baseline: Int = (top + max(2, lineHeight / 3)).coerceAtMost(baselineLimit)

        fun baseline(offset: Int): Int =
            (baseline + offset * lineHeight).coerceAtMost(baselineLimit).coerceAtLeast(top)

        /**
         * Baseline used by the normalized Info1/Info2 panels.  The small top inset and shared
         * spacing keep both panels readable while remaining clipped to the authored cell.
         */
        fun panelBaseline(offset: Int): Int =
            (baseline + (INFO_PANEL_TOP_INSET_LINES * lineHeight).roundToInt() +
                (offset * lineHeight * INFO_PANEL_LINE_SPACING).roundToInt())
                .coerceAtMost(baselineLimit)
                .coerceAtLeast(top)
    }
}
