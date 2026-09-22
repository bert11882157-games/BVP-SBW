package com.atsuishio.superbwarfare.client.aircraft

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy

import com.google.gson.JsonObject
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.EditBox
import net.minecraft.client.gui.components.Tooltip
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/** A revisioned draft editor. Mirrored controls edit the same pair key, never two independent sides. */
class AircraftLoadoutScreen(private var state: AircraftArmamentSnapshot) : Screen(Component.literal("Aircraft loadout")) {
    private val draft = LinkedHashMap(state.selections)
    private var pairPage = 0
    private var showPresets = false
    private var presetIndex = 0
    private var presetName = "Preset 1"
    private lateinit var presetInput: EditBox
    private val editingButtons = ArrayList<Button>()
    private val unavailableButtons = HashSet<Button>()
    private val submissionButtons = HashSet<Button>()
    private val diagnosticPreviewEnabled = DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")
    private var previewOnly = false
    private var pending = false
    private var left = 0
    private var top = 0
    private var panelWidth = 0
    private var panelHeight = 0
    private var columnWidth = 0
    private var rows = 1

    override fun isPauseScreen() = false

    fun accept(next: AircraftArmamentSnapshot) {
        if (previewOnly) return
        if (next.vehicle != state.vehicle) return
        if (next.revision != state.revision) { draft.clear(); draft.putAll(next.selections) }
        state = next
        pending = false
        rebuild()
    }

    private fun rebuild() {
        if (::presetInput.isInitialized) presetName = presetInput.value
        clearWidgets()
        init()
    }

    private fun button(label: String, x: Int, y: Int, w: Int, edit: Boolean = false,
                       serverAction: Boolean = false, action: () -> Unit): Button {
        val button = Button.builder(Component.literal(font.plainSubstrByWidth(label, (w - 10).coerceAtLeast(1)))) { action() }
            .bounds(x, y, w.coerceAtLeast(12), 20).tooltip(Tooltip.create(Component.literal(label))).build()
        addRenderableWidget(button)
        if (edit) editingButtons += button
        if (serverAction) submissionButtons += button
        return button
    }

    override fun init() {
        editingButtons.clear(); unavailableButtons.clear(); submissionButtons.clear()
        panelWidth = (width - 16).coerceAtMost(560).coerceAtLeast(120)
        panelHeight = (height - 16).coerceAtMost(420).coerceAtLeast(120)
        left = (width - panelWidth) / 2; top = (height - panelHeight) / 2
        columnWidth = panelWidth - 24
        rows = ((panelHeight - 116) / 26).coerceIn(1, 16)
        val mounts = state.definition.mounts
        pairPage = pairPage.coerceIn(0, (mounts.size - 1).coerceAtLeast(0) / rows)
        mounts.drop(pairPage * rows).take(if (showPresets) 0 else rows).forEachIndexed { index, mount ->
            val options = listOf<String?>(null) + mount.allowed.filter { state.stores.containsKey(it) }
            val current = draft[mount.id]
            val store = current?.let { state.stores[it] }
            val name = store?.let { "${it.name} (${(it.massKg * (it.capacity ?: 1) * mount.positions.size).toInt()} kg)" } ?: "Empty"
            val label = if (mount.allowed.isEmpty()) "${mount.name} · unavailable" else
                "${mount.name} · $name${if (mount.positions.size == 2) " · both wings" else ""}"
            val choice = button(label, left + 12, top + 56 + index * 26, columnWidth, true) {
                val start = options.indexOf(current).coerceAtLeast(0)
                val next = (1..options.size).asSequence().map { options[(start + it) % options.size] }
                    .firstOrNull { candidate ->
                        val proposed = LinkedHashMap(draft)
                        if (candidate == null) proposed.remove(mount.id) else proposed[mount.id] = candidate
                        state.payloadKg(proposed) <= state.definition.maxPayloadKg + 1.0e-6
                    }
                if (next == null) draft.remove(mount.id) else draft[mount.id] = next
                rebuild()
            }
            if (mount.allowed.isEmpty()) unavailableButtons += choice
        }
        val navigationY = top + panelHeight - 55
        if (!showPresets && mounts.size > rows) {
            button("<", left + 12, navigationY, 24) { pairPage--; rebuild() }
            button(">", left + 40, navigationY, 24) { pairPage++; rebuild() }
        }
        if (!showPresets) button("Clear all", left + panelWidth - 92, navigationY, 80, true) { draft.clear(); rebuild() }
        if (showPresets) {
            val y = top + panelHeight - 81
            val half = (columnWidth - 4) / 2
            presetInput = addRenderableWidget(EditBox(font, left + 12, y, half, 20, Component.literal("Preset name")))
            presetInput.setMaxLength(32); presetInput.value = presetName
            button("Save current draft", left + 16 + half, y, half, true, true) {
                val name = presetInput.value.trim()
                if (name.isNotEmpty()) submit("SAVE_PRESET", name)
            }
            val names = state.presets.keys.sorted()
            presetIndex = presetIndex.coerceIn(0, (names.size - 1).coerceAtLeast(0))
            val preset = names.getOrNull(presetIndex)
            button(preset ?: "No saved presets", left + 12, y + 24, half) {
                if (names.isNotEmpty()) presetIndex = (presetIndex + 1) % names.size
                rebuild()
            }
            val small = (half - 4) / 2
            button("Load", left + 16 + half, y + 24, small, true, true) { if (preset != null) submit("LOAD_PRESET", preset) }
            button("Delete", left + 20 + half + small, y + 24, small, true, true) { if (preset != null) submit("DELETE_PRESET", preset) }
        }
        val third = (columnWidth - 8) / 3
        val bottom = top + panelHeight - 28
        button("Apply", left + 12, bottom, third, true, true) { submit("APPLY") }
        button(if (showPresets) "Hide presets" else "Presets…", left + 16 + third, bottom, third) { showPresets = !showPresets; rebuild() }
        button("Close", left + 20 + third * 2, bottom, third) { onClose() }
        if (diagnosticPreviewEnabled && !previewOnly) {
            button("Freeze preview", left + panelWidth - 116, top + 7, 104) { previewOnly = true; pending = false; rebuild() }
        }
    }

    private fun submit(operation: String, name: String? = null) {
        if (previewOnly) return
        val payload = JsonObject().apply {
            addProperty("Revision", state.revision)
            if (name != null) addProperty("Name", name)
            if (operation == "APPLY" || operation == "SAVE_PRESET") {
                add("Selections", JsonObject().apply { draft.forEach { (pair, store) -> addProperty(pair, store) } })
            }
        }
        pending = AircraftArmamentClient.request(operation, payload)
    }

    override fun tick() {
        val mc = Minecraft.getInstance()
        val vehicle = mc.player?.vehicle
        if (!previewOnly && (vehicle == null || vehicle.uuid != state.vehicle || !vehicle.isAlive || mc.player?.isAlive != true)) {
            onClose(); return
        }
        val editable = previewOnly || (vehicle?.onGround() == true && !pending)
        editingButtons.forEach {
            it.active = editable && it !in unavailableButtons && (!previewOnly || it !in submissionButtons)
        }
    }

    override fun render(graphics: GuiGraphics, mouseX: Int, mouseY: Int, partialTick: Float) {
        renderBackground(graphics)
        graphics.fill(left, top, left + panelWidth, top + panelHeight, 0xF0141C27.toInt())
        graphics.fill(left, top, left + panelWidth, top + 3, 0xFFE2B66D.toInt())
        val headerWidth = if (diagnosticPreviewEnabled && !previewOnly)
            panelWidth - 148 else panelWidth - 24
        val mass = "${state.payloadKg(draft).toInt()} / ${state.definition.maxPayloadKg.toInt()} kg"
        graphics.drawString(font, mass, left + panelWidth - 12 - font.width(mass), top + 12, 0xFFF1E8D8.toInt(), false)
        graphics.drawString(font, font.plainSubstrByWidth(state.definition.name, headerWidth - font.width(mass) - 8),
            left + 12, top + 12, 0xFFF1E8D8.toInt(), false)
        val status = when {
            previewOnly -> "PRIVATE UI PREVIEW · server actions disabled · Esc to close"
            pending -> "Awaiting authoritative result…"
            Minecraft.getInstance().player?.vehicle?.onGround() != true -> "GROUND ONLY · Land before changing equipment"
            state.message.isNotBlank() -> state.message
            draft != state.selections -> "UNAPPLIED DRAFT · Apply to equip this loadout"
            else -> "Click a pylon to cycle its equipment; Apply to fit"
        }
        graphics.drawString(font, font.plainSubstrByWidth(status, panelWidth - 24), left + 12, top + 28,
            0xFFE2B66D.toInt(), false)
        super.render(graphics, mouseX, mouseY, partialTick)
    }
}
