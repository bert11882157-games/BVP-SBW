package com.atsuishio.superbwarfare.client.renderer

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.vehicle.flight.AircraftWreckBreakup
import com.atsuishio.superbwarfare.api.vehicle.flight.FixedWingFlightStrategy
import com.atsuishio.superbwarfare.client.FarTerrainClient
import com.atsuishio.superbwarfare.client.particle.AircraftCombatParticles
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.vertex.PoseStack
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.MultiBufferSource
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import net.minecraft.world.phys.Vec3
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.client.event.RenderLevelStageEvent
import net.minecraftforge.event.TickEvent
import net.minecraftforge.eventbus.api.SubscribeEvent
import org.joml.Quaternionf
import java.util.UUID

/** Shared near/far debris pass; no server entities, packets, damage or terrain tickets. */
@net.minecraftforge.fml.common.Mod.EventBusSubscriber(modid = Mod.MODID, value = [Dist.CLIENT])
object AircraftDetachedWings {
    fun interface Visual {
        fun render(pose: PoseStack, buffers: MultiBufferSource, blackened: Boolean)
    }
    private data class Key(val id: UUID, val side: Int, val started: Long)
    private data class Part(val motion: AircraftDebrisMotion, val visual: Visual, val radius: Double,
                            val corners: List<Vec3>, val halfExtents: Vec3, val owner: UUID, val fuselage: Boolean)
    private val pieces = LinkedHashMap<Key, Part>()
    private val observed = LinkedHashMap<Key, Long>()
    private var world: Any? = null
    private var clock = 0L
    private var lastRenderDiagnostic = Long.MIN_VALUE
    private val random = java.util.Random()

    @JvmStatic fun clear() {
        pieces.clear(); observed.clear(); world = Minecraft.getInstance().level; clock = 0
        lastRenderDiagnostic = Long.MIN_VALUE
        com.atsuishio.superbwarfare.compat.voxy.ClientDebrisTerrain.clear()
    }
    private fun syncWorld() { if (world !== Minecraft.getInstance().level) clear() }
    @JvmStatic fun needsCapture(vehicle: VehicleEntity, side: Int): Boolean {
        syncWorld()
        if (side >= 4) return vehicle.aircraftWreckImpactTime >= 0 &&
            vehicle.level().gameTime - vehicle.aircraftWreckImpactTime < 200 &&
            Key(vehicle.uuid, side, vehicle.aircraftWreckImpactTime) !in observed
        return vehicle.aircraftWreckStart >= 0 && vehicle.aircraftWreckWings >= 0 &&
            AircraftWreckBreakup.mask(vehicle) and side != 0 &&
            Key(vehicle.uuid, side, vehicle.aircraftWreckStart) !in observed
    }

    /** Center is world-space, orientation maps captured mesh-local coordinates into world space. */
    @JvmStatic fun capture(vehicle: VehicleEntity, side: Int, center: Vec3, orientation: Quaternionf,
                           halfExtents: Vec3, visual: Visual) {
        if (!needsCapture(vehicle, side)) return
        val fuselage = side >= 4
        val key = Key(vehicle.uuid, side, if (fuselage) vehicle.aircraftWreckImpactTime else vehicle.aircraftWreckStart)
        observed[key] = clock
        if (observed.size > 512) observed.remove(observed.keys.first())
        if (pieces.size >= 64) pieces.remove(pieces.keys.first())
        val motion = Vec3(vehicle.aircraftWreckMotionX.toDouble(), vehicle.aircraftWreckMotionY.toDouble(),
            vehicle.aircraftWreckMotionZ.toDouble())
            .takeIf { it.x.isFinite() && it.y.isFinite() && it.z.isFinite() && it.lengthSqr() < 10000 }
            ?: vehicle.deltaMovement
        val gravity = (vehicle.resolveVehicleFlightStrategy() as? FixedWingFlightStrategy)
            ?.handling?.gravityMps2?.div(400.0)?.coerceAtLeast(9.80665 / 400.0) ?: (9.80665 / 400.0)
        val spin = Vec3(random.nextDouble() * .06 - .03, random.nextDouble() * .04 - .02,
            (if (side == AircraftWreckBreakup.LEFT) -1 else 1) * (.025 + random.nextDouble() * .045))
        pieces[key] = Part(AircraftDebrisMotion(center, motion, orientation, spin,
            random.nextDouble() * Math.PI * 2, gravity, 0, halfExtents), visual, halfExtents.length().coerceIn(.25, 48.0),
            listOf(Vec3.ZERO) + (0..7).map { bits -> Vec3(
                halfExtents.x * if (bits and 1 == 0) -1 else 1,
                halfExtents.y * if (bits and 2 == 0) -1 else 1,
                halfExtents.z * if (bits and 4 == 0) -1 else 1) }, halfExtents, vehicle.uuid, fuselage)
    }

    @SubscribeEvent fun tick(event: TickEvent.ClientTickEvent) {
        if (event.phase != TickEvent.Phase.END) return
        syncWorld()
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        if (mc.isPaused) return
        clock++
        val terrain = com.atsuishio.superbwarfare.compat.voxy.ClientDebrisTerrain
        terrain.tick()
        var emissions = 0
        val diagnostics = com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics.isClientEnabled()
        pieces.entries.removeIf { (key, part) ->
            val remove = part.motion.expired || part.motion.position.y < level.minBuildHeight - 32
            if (remove && diagnostics) com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics.recordClient(
                level.gameTime,"aircraft_debris","REMOVE","vehicle",key.id,"part",key.side,"age",part.motion.age)
            remove
        }
        for ((key, part) in pieces) {
            val motion = part.motion
            if (!motion.grounded) {
                terrain.prefetch(motion.position)
                terrain.prefetch(motion.position.add(motion.velocity.scale(12.0)).add(0.0, -2.0, 0.0))
            }
            motion.tick { from, to ->
                part.corners.mapNotNull { local ->
                    val rotated = motion.orientation.transform(org.joml.Vector3f(local.x.toFloat(), local.y.toFloat(), local.z.toFloat()))
                    val offset = Vec3(rotated.x.toDouble(), rotated.y.toDouble(), rotated.z.toDouble())
                    // Lift the sampling rays above the relaxed ground overlap. Otherwise a horizontal
                    // scrape starts inside a block and repeats a zero-distance contact forever.
                    AircraftDebrisContact.relaxedSweep(from.add(offset), to.add(offset)) { start, end ->
                        terrain.clip(ClipContext(start, end, ClipContext.Block.COLLIDER,
                            ClipContext.Fluid.ANY, mc.player)).takeUnless { it.type == HitResult.Type.MISS }
                    }?.let { AircraftDebrisMotion.Contact(it.position.subtract(offset), it.normal) }
                }.minByOrNull { it.position.distanceToSqr(from) }
            }
            if (motion.grounded) {
                fun lowest(rotation: Quaternionf) = part.corners.minOf { local ->
                    rotation.transform(org.joml.Vector3f(local.x.toFloat(),local.y.toFloat(),local.z.toFloat())).y.toDouble()
                }
                val rise = lowest(motion.previousOrientation) - lowest(motion.orientation)
                motion.separate(Vec3(0.0,rise,0.0),Vec3(0.0,1.0,0.0))
            }
            if (diagnostics && clock % 4L == 0L)
                com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics.recordClient(level.gameTime,
                    "aircraft_debris", "STEP", "vehicle", key.id, "part", key.side,
                    "age", motion.age, "x", motion.position.x, "y", motion.position.y, "z", motion.position.z,
                    "vx", motion.velocity.x, "vy", motion.velocity.y, "vz", motion.velocity.z,
                    "grounded", motion.grounded, "impacted", motion.impacted, "bounces", motion.bounces)
            if (part.fuselage && motion.grounded && motion.velocity.horizontalDistanceSqr() > .0004 && emissions < 64) {
                val low = part.corners.minOf { local ->
                    motion.orientation.transform(org.joml.Vector3f(local.x.toFloat(),local.y.toFloat(),local.z.toFloat())).y.toDouble()
                }
                AircraftCombatParticles.grindingSmoke(motion.position.add(0.0, low + .08, 0.0), motion.velocity.horizontalDistance())
                emissions++
            }
            if (emissions < 62 &&
                motion.position.distanceToSqr(mc.gameRenderer.mainCamera.position) < 16384.0 * 16384) {
                val fire = com.atsuishio.superbwarfare.api.vehicle.flight.WreckDebrisPhysics.flameStrength(
                    motion.groundedTicks.toLong(), key.id.hashCode().toLong())
                // Upper exposed face, not the opaque mesh center. Keep points on the moving
                // piece and above the supporting terrain after it settles.
                val up = motion.orientation.conjugate(Quaternionf()).transform(org.joml.Vector3f(0f, 1f, 0f))
                val extents = part.halfExtents
                val direction = Vec3(up.x.toDouble(), up.y.toDouble(), up.z.toDouble())
                val reach = minOf(
                    if (kotlin.math.abs(direction.x) > 1e-6) extents.x / kotlin.math.abs(direction.x) else Double.POSITIVE_INFINITY,
                    if (kotlin.math.abs(direction.y) > 1e-6) extents.y / kotlin.math.abs(direction.y) else Double.POSITIVE_INFINITY,
                    if (kotlin.math.abs(direction.z) > 1e-6) extents.z / kotlin.math.abs(direction.z) else Double.POSITIVE_INFINITY)
                val surface = motion.position.add(0.0, reach.coerceAtLeast(0.0) + .08, 0.0)
                if (clock % 3L == 0L && fire > 0) {
                    AircraftCombatParticles.wreckFlame(surface, fire)
                    emissions++
                }
                if (clock % 2L == 0L) {
                    AircraftCombatParticles.wreckSmoke(surface.add(0.0,.12,0.0), if(motion.grounded) 1.5f else 2.2f); emissions++
                }
            }
        }
        // Resolve sibling solids and the server wreck as an immovable obstacle. The
        // client never changes server motion or allocates physical debris entities.
        val fragments = pieces.values.filter { it.fuselage }
        val owners = if (fragments.isEmpty()) emptyMap() else {
            val ids = fragments.map { it.owner }.toSet()
            val native = level.entitiesForRendering().filterIsInstance<VehicleEntity>().filter { it.uuid in ids }.associateBy { it.uuid }
            ids.associateWith { native[it] ?: com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleCopies.find(it) }
        }
        repeat(4) {
            for (i in fragments.indices) {
                val a = fragments[i]
                for (j in 0 until i) {
                    val b = fragments[j]
                    if (a.owner != b.owner) continue
                    val correction = AircraftDebrisContact.separation(a.motion.position, a.motion.orientation, a.halfExtents,
                        b.motion.position, b.motion.orientation, b.halfExtents) ?: continue
                    val normal = correction.normalize()
                    a.motion.separate(correction.scale(.5), normal)
                    b.motion.separate(correction.scale(-.5), normal.scale(-1.0))
                }
                val vehicle = owners[a.owner]
                if (vehicle != null && vehicle.aircraftWreckImpactTime >= 0) {
                    val section = vehicle.computed().aircraftTerrainContact?.wreckSections?.getOrNull(1)
                    if (section != null) {
                        val frame = vehicle.getVehicleTransform(1f)
                        val local = section.minimum.add(section.maximum).scale(.5)
                        val point = frame.transformPosition(org.joml.Vector3d(local.x, local.y, local.z))
                        val correction = AircraftDebrisContact.separation(a.motion.position, a.motion.orientation, a.halfExtents,
                            Vec3(point.x, point.y, point.z), org.joml.Quaternionf(frame.getNormalizedRotation(org.joml.Quaterniond())),
                            section.maximum.subtract(section.minimum).scale(.5))
                        if (correction != null) a.motion.separate(correction, correction.normalize())
                    }
                }
            }
        }
        pieces.entries.removeIf { (key, part) ->
            if (part.motion.expired && diagnostics) com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics.recordClient(
                level.gameTime,"aircraft_debris","REMOVE","vehicle",key.id,"part",key.side,"age",part.motion.age)
            part.motion.expired
        }
    }

    @SubscribeEvent fun render(event: RenderLevelStageEvent) {
        if (event.stage != RenderLevelStageEvent.Stage.AFTER_ENTITIES || pieces.isEmpty()) return
        val mc = Minecraft.getInstance()
        val camera = event.camera.position
        val buffers = mc.renderBuffers().bufferSource()
        val pose = event.poseStack
        val oldStart = RenderSystem.getShaderFogStart()
        val oldEnd = RenderSystem.getShaderFogEnd()
        // A fog change alone cannot bypass the native camera's short far clipping plane.
        val originalProjection = org.joml.Matrix4f(RenderSystem.getProjectionMatrix())
        val projection = org.joml.Matrix4f(originalProjection)
        val near = originalProjection.m32() / (originalProjection.m22() - 1)
        val far = 20000f
        projection.m22(-(far + near) / (far - near)).m32(-2 * far * near / (far - near))
        val frustum = net.minecraft.client.renderer.culling.Frustum(event.poseStack.last().pose(), projection)
        frustum.prepare(camera.x, camera.y, camera.z)
        buffers.endBatch()
        var drawn = 0
        var distant = 0
        try {
            RenderSystem.setProjectionMatrix(projection, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN)
            RenderSystem.setShaderFogStart(18000f)
            RenderSystem.setShaderFogEnd(far)
            for (part in pieces.values) {
                val motion = part.motion
                val position = motion.previousPosition.lerp(motion.position, event.partialTick.toDouble())
                if (position.distanceToSqr(camera) > 16384.0 * 16384) continue
                val bounds = net.minecraft.world.phys.AABB(position, position).inflate(part.radius)
                if (!frustum.isVisible(bounds)) continue
                pose.pushPose()
                try {
                    pose.translate(position.x - camera.x, position.y - camera.y, position.z - camera.z)
                    pose.mulPose(Quaternionf(motion.previousOrientation).slerp(motion.orientation, event.partialTick))
                    part.visual.render(pose, buffers, motion.impacted)
                    drawn++
                    val normalRange = mc.options.effectiveRenderDistance * 16.0
                    if (position.distanceToSqr(camera) > normalRange * normalRange) distant++
                } finally { pose.popPose() }
            }
            buffers.endBatch()
        } finally {
            RenderSystem.setProjectionMatrix(originalProjection, com.mojang.blaze3d.vertex.VertexSorting.DISTANCE_TO_ORIGIN)
            RenderSystem.setShaderFogStart(oldStart); RenderSystem.setShaderFogEnd(oldEnd)
        }
        val gameTime = mc.level?.gameTime ?: return
        if (gameTime != lastRenderDiagnostic && gameTime % 20L == 0L &&
            com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics.isClientEnabled()) {
            lastRenderDiagnostic = gameTime
            com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics.recordClient(gameTime,
                "aircraft_debris", "RENDER", "retained", pieces.size, "drawn", drawn,
                "beyond_view_range", distant)
        }
    }
}
