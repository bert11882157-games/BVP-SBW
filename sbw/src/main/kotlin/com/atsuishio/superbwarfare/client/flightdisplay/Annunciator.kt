package com.atsuishio.superbwarfare.client.flightdisplay

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource
import com.atsuishio.superbwarfare.resource.vehicle.VehicleResource
import com.mojang.blaze3d.pipeline.RenderTarget
import com.mojang.blaze3d.pipeline.TextureTarget
import com.mojang.blaze3d.platform.GlStateManager
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.Tesselator
import com.mojang.blaze3d.vertex.VertexConsumer
import com.mojang.blaze3d.vertex.VertexSorting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.renderer.LightTexture
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.client.renderer.RenderType
import net.minecraft.client.renderer.texture.AbstractTexture
import net.minecraft.client.renderer.texture.OverlayTexture
import net.minecraft.server.packs.resources.ResourceManager
import org.joml.Matrix4f
import org.joml.Vector3f
import org.lwjgl.opengl.GL11
import kotlin.math.abs

/**
 * Annunciator (caution and warning light) panels: a grid of caption lights on the glareshield or instrument panel,
 * each lit by a [Condition] of the aircraft the local player rides; on every other aircraft the panel is dark.
 *
 * A panel is a "CockpitGauges" entry of kind ANNUNCIATOR with a width, height and its lamps (see
 * [DefaultVehicleResource.CockpitGaugesResource.Lamp]), so each aircraft names its own lights. Captions are painted
 * once into a shared atlas of text cells and drawn full-bright on the lamp face: black on a lit lamp, grey on a dark
 * one, the way a real legend reads.
 */
object Annunciator {
    private val LOGGER = Mod.LOGGER
    private val ATLAS = Mod.loc("dynamic/annunciator_captions")
    private val CASE_TEXTURE = Mod.loc("textures/misc/flight_display_housing.png")
    private const val COLS = 8
    private const val ROWS = 16
    private const val CELL_W = 128
    private const val CELL_H = 64
    private const val SIZE_W = COLS * CELL_W
    private const val SIZE_H = ROWS * CELL_H
    private const val WHITE_CELL = 0                 // a solid white cell: the lamp faces are tinted from it
    private const val GAP = 0.0025f                  // between lamps, blocks
    private const val MARGIN = 0.004f                // panel edge, blocks
    private const val PROUD = 0.004f                 // lamp faces stand this far in front of the panel

    /** What lights a lamp. Masters light when any other lamp of their colour on the same panel is lit. */
    enum class Condition(val flash: Boolean = false) {
        NONE, MASTER_CAUTION, MASTER_WARNING,
        LOW_FUEL, ENGINE_OUT, DAMAGE, FIRE(true), RWR_LOCK, MISSILE_LAUNCH(true),
        GEAR_DOWN, GEAR_UP, GEAR_TRANSIT, STALL(true), OVER_G, OVERSPEED, LOW_ALT, AFTERBURNER, AIRBRAKE,
        ROTOR_RPM_LOW, ROTOR_RPM_HIGH, TORQUE_HIGH, AMMO_LOW, ENGINE_RUN;

        companion object {
            private val byName = values().associateBy { it.name }
            fun of(name: String?): Condition = name?.uppercase()?.let(byName::get) ?: NONE
        }
    }

    private enum class Colour(val lit: Int, val dark: Int) {
        AMBER(0xFFFFB424.toInt(), 0xFF3A2E12.toInt()), RED(0xFFF03A2A.toInt(), 0xFF3A1412.toInt()),
        GREEN(0xFF4CE05A.toInt(), 0xFF143A18.toInt()), WHITE(0xFFF2F2E8.toInt(), 0xFF2E2E2C.toInt()),
        BLUE(0xFF5AA6FF.toInt(), 0xFF14243A.toInt());

        companion object {
            fun of(name: String?): Colour = when (name?.lowercase()) {
                "red" -> RED; "green" -> GREEN; "white" -> WHITE; "blue" -> BLUE; else -> AMBER
            }
        }
    }

    private class Lamp(val text: String, val condition: Condition, val colour: Colour)

    private class Panel(val centre: Vector3f, val normal: Vector3f, val up: Vector3f, val right: Vector3f,
                        val halfW: Float, val halfH: Float, val depth: Float, val columns: Int, val lamps: List<Lamp>) {
        /** The crew seat the panel faces: its lamps light only for the player in that seat. */
        var seat = 0
        fun point(x: Float, y: Float, back: Float): Vector3f = Vector3f(centre)
            .add(Vector3f(right).mul(x)).add(Vector3f(up).mul(y)).sub(Vector3f(normal).mul(back))

        companion object {
            fun of(d: DefaultVehicleResource.CockpitGaugesResource.Gauge): Panel? {
                val c = vec(d.center) ?: return null
                val n = vec(d.normal)?.takeIf { it.lengthSquared() > 1e-6f }?.normalize() ?: return null
                var u = vec(d.up) ?: return null
                u = u.sub(Vector3f(n).mul(u.dot(n))).takeIf { it.lengthSquared() > 1e-6f }?.normalize() ?: return null
                val w = d.width ?: d.diameter; val h = d.height ?: d.diameter
                if (!(w > 0.01 && w < 1.0 && h > 0.01 && h < 1.0)) return null
                val lamps = d.lamps.orEmpty().filterNotNull().filter { !it.text.isNullOrBlank() }
                    .map { Lamp(it.text.trim(), Condition.of(it.condition), Colour.of(it.colour)) }
                if (lamps.isEmpty()) return null
                val r = Vector3f(u).cross(n).normalize()
                return Panel(c, n, u, r, (w / 2).toFloat(), (h / 2).toFloat(), d.depth.toFloat().coerceIn(0.004f, 0.1f),
                    (d.columns ?: 3).coerceIn(1, 8), lamps)
            }

            private fun vec(a: DoubleArray?): Vector3f? = a?.takeIf { it.size == 3 && it.all(Double::isFinite) }
                ?.let { Vector3f(-it[0].toFloat(), it[1].toFloat(), -it[2].toFloat()) }
        }
    }

    private var atlas: RenderTarget? = null
    private val cells = HashMap<String, Int>()
    private val pending = LinkedHashSet<String>()
    private var whitePainted = false
    private var failed = false
    private var liveVehicleId = -1
    private var liveSeat = -1
    private var lit: Set<Condition> = emptySet()

    // ------------------------------------------------------------------ state

    /** Evaluates the lamps of the aircraft the local player rides, at the start of the frame. */
    fun renderTick(partialTick: Float) {
        if (failed) return
        if (pending.isNotEmpty() || !whitePainted) {
            try {
                val target = atlas ?: create().also { atlas = it }
                paint(target) { g ->
                    if (!whitePainted) {
                        cell(g, WHITE_CELL) { g.fill(0, 0, CELL_W, CELL_H, 0xFFFFFFFF.toInt()) }
                        whitePainted = true
                    }
                    for (text in pending) {
                        val index = cells[text] ?: continue
                        cell(g, index) { caption(g, text) }
                    }
                    pending.clear()
                }
            } catch (e: Throwable) {
                failed = true
                LOGGER.error("Annunciator panels failed; disabled for this session", e)
                return
            }
        }
        val mc = Minecraft.getInstance()
        val vehicle = mc.player?.vehicle as? VehicleEntity ?: run { liveVehicleId = -1; return }
        val seat = FlightDisplayState.crewSeat(vehicle)
        val panels = panels(vehicle).filter { it.seat == seat }       // only the local player's own seat's panels
        if (panels.isEmpty() || vehicle.isWreck) { liveVehicleId = -1; return }
        val wanted = panels.flatMap { it.lamps }.map { it.condition }.toSet()
        val state = FlightDisplayState.sample(vehicle, partialTick) ?: run { liveVehicleId = -1; return }
        val rwr = if (Condition.RWR_LOCK in wanted || Condition.MISSILE_LAUNCH in wanted)
            RwrDisplayState.sample(vehicle, partialTick) else RwrDisplayState.STILL
        lit = evaluate(state, rwr)
        liveVehicleId = vehicle.id
        liveSeat = seat
    }

    private fun evaluate(s: FlightDisplayState, rwr: RwrDisplayState): Set<Condition> {
        val out = HashSet<Condition>()
        fun on(c: Condition, v: Boolean) { if (v) out.add(c) }
        on(Condition.LOW_FUEL, (s.fuel ?: 1.0) < 0.15)
        on(Condition.ENGINE_OUT, !s.engineRunning)
        on(Condition.ENGINE_RUN, s.engineRunning)
        on(Condition.DAMAGE, s.health < 0.5)
        on(Condition.FIRE, s.health < 0.25)
        on(Condition.RWR_LOCK, rwr.locked)
        on(Condition.MISSILE_LAUNCH, rwr.launch)
        val gear = s.gear
        if (gear != null) {
            on(Condition.GEAR_DOWN, gear >= 0.99)
            on(Condition.GEAR_UP, gear <= 0.01)
            on(Condition.GEAR_TRANSIT, gear > 0.01 && gear < 0.99)
        }
        on(Condition.STALL, (s.angleOfAttack ?: 0.0) > 18.0 && s.speedKmh > 60.0)
        on(Condition.OVER_G, abs(s.liftG ?: 1.0) > 8.0)
        on(Condition.OVERSPEED, (s.mach ?: 0.0) > 1.9)
        on(Condition.LOW_ALT, (s.radarAltitude ?: 1000.0) < 60.0 && s.speedKmh > 60.0 && (s.gear ?: 1.0) < 0.99)
        on(Condition.AFTERBURNER, s.afterburner)
        on(Condition.AIRBRAKE, s.airbrake > 0.3)
        val rotor = s.rotorRpm
        if (rotor != null && s.engineRunning) {
            on(Condition.ROTOR_RPM_LOW, rotor < 0.90)
            on(Condition.ROTOR_RPM_HIGH, rotor > 1.10)
        }
        on(Condition.TORQUE_HIGH, (s.torque ?: 0.0) > 1.0)
        on(Condition.AMMO_LOW, s.ammo != null && s.ammo != Int.MAX_VALUE && s.ammo < 50)
        return out
    }

    private fun isLit(panel: Panel, lamp: Lamp, live: Set<Condition>, blink: Boolean): Boolean {
        val on = when (lamp.condition) {
            Condition.NONE -> false
            Condition.MASTER_CAUTION -> panel.lamps.any { it.colour == Colour.AMBER && it.condition != Condition.MASTER_CAUTION
                && it.condition != Condition.MASTER_WARNING && it.condition in live }
            Condition.MASTER_WARNING -> panel.lamps.any { it.colour == Colour.RED && it.condition != Condition.MASTER_CAUTION
                && it.condition != Condition.MASTER_WARNING && it.condition in live }
            else -> lamp.condition in live
        }
        return on && (!lamp.condition.flash || blink)
    }

    private fun panels(vehicle: VehicleEntity): List<Panel> {
        val resource = VehicleResource.getDefault(vehicle).cockpitGauges ?: return emptyList()
        if (resource.frame != "VEHICLE_LOCAL_BLOCKS") return emptyList()
        return resource.gauges.orEmpty().filter { "ANNUNCIATOR".equals(it.kind, ignoreCase = true) }
            .mapNotNull { g -> Panel.of(g)?.also { it.seat = g.seat } }
    }

    // ------------------------------------------------------------------ drawing

    /** Draws [vehicle]'s annunciator panels in the vehicle model frame (see [FlightDisplays.render]). */
    fun render(vehicle: VehicleEntity, poseStack: PoseStack, buffers: MultiBufferSource, packedLight: Int) {
        if (failed) return
        val panels = panels(vehicle)
        if (panels.isEmpty()) return
        val live = if (liveVehicleId == vehicle.id && Minecraft.getInstance().player?.vehicle === vehicle) lit else emptySet()
        val blink = (System.currentTimeMillis() / 300L) % 2L == 0L
        val pose = poseStack.last()
        val faces = buffers.getBuffer(RenderType.text(ATLAS))
        val case = buffers.getBuffer(RenderType.entityCutoutNoCull(CASE_TEXTURE))
        for (panel in panels) {
            housing(case, pose, panel, packedLight)
            val rows = (panel.lamps.size + panel.columns - 1) / panel.columns
            val lw = (2 * panel.halfW - 2 * MARGIN - (panel.columns - 1) * GAP) / panel.columns
            val lh = (2 * panel.halfH - 2 * MARGIN - (rows - 1) * GAP) / rows
            for ((i, lamp) in panel.lamps.withIndex()) {
                val col = i % panel.columns; val row = i / panel.columns
                val x = -panel.halfW + MARGIN + col * (lw + GAP) + lw / 2
                val y = panel.halfH - MARGIN - row * (lh + GAP) - lh / 2
                val on = isLit(panel, lamp, if (panel.seat == liveSeat) live else emptySet(), blink)
                val cell = cells[lamp.text] ?: run {
                    if (whitePainted && cells.size < COLS * ROWS - 1) {
                        val index = cells.size + 1
                        cells[lamp.text] = index
                        pending.add(lamp.text)
                    }
                    null
                }
                quad(faces, pose.pose(), panel, x, y, lw / 2, lh / 2, PROUD, WHITE_CELL,
                    if (on) lamp.colour.lit else lamp.colour.dark)
                if (cell != null) {
                    quad(faces, pose.pose(), panel, x, y, lw / 2, lh / 2, PROUD + 0.0006f, cell,
                        if (on) 0xFF101008.toInt() else 0xFF8A8A80.toInt())
                }
            }
        }
    }

    /** A lamp-sized quad of the atlas cell [cell], tinted [argb], full-bright, wound like the gauge faces. */
    private fun quad(consumer: VertexConsumer, matrix: Matrix4f, p: Panel, cx: Float, cy: Float, hw: Float, hh: Float,
                     lift: Float, cell: Int, argb: Int) {
        val u0 = (cell % COLS).toFloat() / COLS; val v0 = (cell / COLS).toFloat() / ROWS
        val du = 1f / COLS; val dv = 1f / ROWS
        val r = (argb shr 16) and 0xFF; val g = (argb shr 8) and 0xFF; val b = argb and 0xFF
        val corners = arrayOf(floatArrayOf(-hw, -hh), floatArrayOf(hw, -hh), floatArrayOf(hw, hh), floatArrayOf(-hw, hh))
        for (c in corners) {
            val x = cx + c[0]; val y = cy + c[1]
            val q = p.point(x, y, -lift)
            // Render targets store their top row at v = 1; the cell's painted y grows downward.
            val tu = u0 + (c[0] / hw * 0.5f + 0.5f) * du
            val tv = 1f - (v0 + (0.5f - c[1] / hh * 0.5f) * dv)
            consumer.vertex(matrix, q.x, q.y, q.z).color(r, g, b, 255).uv(tu, tv).uv2(LightTexture.FULL_BRIGHT).endVertex()
        }
    }

    private fun housing(consumer: VertexConsumer, pose: PoseStack.Pose, p: Panel, light: Int) {
        val w = p.halfW; val h = p.halfH; val d = p.depth
        // front face (the lamps sit on it), four sides and the back
        box(consumer, pose, p, -w, -h, w, h, -0.001f, d, light)
    }

    private fun box(consumer: VertexConsumer, pose: PoseStack.Pose, p: Panel, x0: Float, y0: Float, x1: Float, y1: Float,
                    front: Float, back: Float, light: Int) {
        val n = p.normal
        quad(consumer, pose, p.point(x0, y0, front), p.point(x1, y0, front), p.point(x1, y1, front), p.point(x0, y1, front),
            Vector3f(n), light, 0.07f)
        quad(consumer, pose, p.point(x0, y1, back), p.point(x1, y1, back), p.point(x1, y0, back), p.point(x0, y0, back),
            Vector3f(n).negate(), light, 0.06f)
        quad(consumer, pose, p.point(x0, y1, front), p.point(x1, y1, front), p.point(x1, y1, back), p.point(x0, y1, back),
            Vector3f(p.up), light, 0.09f)
        quad(consumer, pose, p.point(x1, y0, front), p.point(x0, y0, front), p.point(x0, y0, back), p.point(x1, y0, back),
            Vector3f(p.up).negate(), light, 0.05f)
        quad(consumer, pose, p.point(x1, y1, front), p.point(x1, y0, front), p.point(x1, y0, back), p.point(x1, y1, back),
            Vector3f(p.right), light, 0.08f)
        quad(consumer, pose, p.point(x0, y0, front), p.point(x0, y1, front), p.point(x0, y1, back), p.point(x0, y0, back),
            Vector3f(p.right).negate(), light, 0.08f)
    }

    private fun quad(consumer: VertexConsumer, pose: PoseStack.Pose, a: Vector3f, b: Vector3f, c: Vector3f, e: Vector3f,
                     normal: Vector3f, light: Int, grey: Float) {
        val n = pose.normal().transform(Vector3f(normal).normalize())
        val uv = arrayOf(floatArrayOf(0f, 0f), floatArrayOf(0f, 1f), floatArrayOf(1f, 1f), floatArrayOf(1f, 0f))
        val g = (grey * 255 / 0.2f).toInt().coerceIn(0, 255)
        for ((i, p) in arrayOf(a, b, c, e).withIndex()) {
            consumer.vertex(pose.pose(), p.x, p.y, p.z).color(g, g, g, 255).uv(uv[i][0], uv[i][1])
                .overlayCoords(OverlayTexture.NO_OVERLAY).uv2(light).normal(n.x, n.y, n.z).endVertex()
        }
    }

    // ------------------------------------------------------------------ the caption atlas

    /** Paints [text] (one or two lines, split at "|") in white, centred in a cell, as large as the cell allows. */
    private fun caption(g: GuiGraphics, text: String) {
        val lines = text.split('|').map { it.trim() }.filter { it.isNotEmpty() }.take(2)
        if (lines.isEmpty()) return
        val font = DisplayDraw.font
        val widest = lines.maxOf { font.width(it) }.coerceAtLeast(1)
        val scale = minOf(2.6f, (CELL_W - 10f) / widest, (CELL_H - 8f) / (lines.size * 11f))
        val step = 11f * scale
        val y0 = CELL_H / 2f - (lines.size - 1) * step / 2
        for ((k, line) in lines.withIndex()) {
            DisplayDraw.text(g, line, CELL_W / 2f, y0 + k * step, scale, 0xFFFFFFFF.toInt(), DisplayDraw.Align.CENTER)
        }
    }

    private fun create(): RenderTarget {
        val target = TextureTarget(SIZE_W, SIZE_H, false, Minecraft.ON_OSX)
        target.setClearColor(0f, 0f, 0f, 0f)
        target.setFilterMode(GL11.GL_LINEAR)
        target.clear(Minecraft.ON_OSX)
        Minecraft.getInstance().textureManager.register(ATLAS, TargetTexture(target))
        return target
    }

    private inline fun cell(g: GuiGraphics, index: Int, body: () -> Unit) {
        val x = (index % COLS) * CELL_W
        val y = (index / COLS) * CELL_H
        RenderSystem.enableScissor(x, SIZE_H - y - CELL_H, CELL_W, CELL_H)
        g.pose().pushPose()
        g.pose().translate(x.toFloat(), y.toFloat(), 0f)
        body()
        g.flush()
        g.pose().popPose()
        RenderSystem.disableScissor()
    }

    private fun paint(target: RenderTarget, body: (GuiGraphics) -> Unit) {
        val mc = Minecraft.getInstance()
        RenderSystem.assertOnRenderThread()
        RenderSystem.backupProjectionMatrix()
        val modelView = RenderSystem.getModelViewStack()
        modelView.pushPose()
        val previousScissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST)
        try {
            target.bindWrite(true)
            RenderSystem.setProjectionMatrix(Matrix4f().setOrtho(0f, SIZE_W.toFloat(), SIZE_H.toFloat(), 0f, 1000f, 3000f),
                VertexSorting.ORTHOGRAPHIC_Z)
            modelView.setIdentity()
            modelView.translate(0f, 0f, -2000f)
            RenderSystem.applyModelViewMatrix()
            RenderSystem.disableDepthTest()
            RenderSystem.enableBlend()
            RenderSystem.defaultBlendFunc()
            val buffers = MultiBufferSource.immediate(Tesselator.getInstance().builder)
            val graphics = GuiGraphics(mc, buffers)
            body(graphics)
            graphics.flush()
        } finally {
            RenderSystem.disableScissor()
            if (previousScissor) GL11.glEnable(GL11.GL_SCISSOR_TEST)
            modelView.popPose()
            RenderSystem.applyModelViewMatrix()
            RenderSystem.restoreProjectionMatrix()
            RenderSystem.enableDepthTest()
            mc.mainRenderTarget.bindWrite(true)
        }
    }

    private class TargetTexture(private val target: RenderTarget) : AbstractTexture() {
        override fun getId(): Int = target.colorTextureId
        override fun load(manager: ResourceManager) {}
        override fun releaseId() {}
        override fun setFilter(blur: Boolean, mipmap: Boolean) {
            GlStateManager._bindTexture(getId())
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR)
            GlStateManager._texParameter(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR)
        }
    }
}
