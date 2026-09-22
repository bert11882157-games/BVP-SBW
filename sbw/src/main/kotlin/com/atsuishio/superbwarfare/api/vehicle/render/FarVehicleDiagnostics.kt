package com.atsuishio.superbwarfare.api.vehicle.render

import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import com.atsuishio.superbwarfare.client.FarVehicleClient
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import java.util.UUID
import java.util.WeakHashMap
import net.minecraft.world.phys.Vec3

/** Bounded probes under the existing server-owned diagnostics session. */
object FarVehicleDiagnostics {
    private val lastModelTick = WeakHashMap<VehicleEntity, Long>()
    private val lastSampleTick = WeakHashMap<VehicleEntity, Double>()
    private val lastLightTick = WeakHashMap<VehicleEntity, Long>()
    private val lastVisibility = WeakHashMap<VehicleEntity, String>()
    private val lastTerrainWait = WeakHashMap<VehicleEntity, Long>()
    private var visibilityTick = Long.MIN_VALUE
    private var visibilityEvents = 0
    private var session: UUID? = null
    private var lastPassTick = Long.MIN_VALUE

    fun terrainWait(vehicle: VehicleEntity, camera: Vec3, bounds: net.minecraft.world.phys.AABB) {
        if (!EliteDiagnostics.isClientEnabled()) return
        refreshSession()
        val tick = vehicle.level().gameTime
        if (lastTerrainWait[vehicle]?.let { tick - it in 0..19 } == true) return
        lastTerrainWait[vehicle] = tick
        val chunks = FarTerrainPolicy.renderCoverage(camera.x, camera.z,
            bounds.minX, bounds.minZ, bounds.maxX, bounds.maxZ)
        val sections = com.atsuishio.superbwarfare.client.FarTerrainClient.sections(camera, bounds)
        EliteDiagnostics.record(vehicle, "far_render", "CLIENT_TERRAIN_WAIT",
            "required_chunks", chunks.size,
            "over_chunk_budget", chunks.size > FarTerrainPolicy.MAX_CHUNKS,
            "missing_terrain", chunks.count { !com.atsuishio.superbwarfare.client.FarTerrainClient.contains(it) },
            "pending_mesh_sections", sections.count {
                !com.atsuishio.superbwarfare.client.renderer.FarTerrainMeshes.ready(it)
            }, "camera_x", camera.x, "camera_y", camera.y, "camera_z", camera.z,
            "vehicle_x", vehicle.x, "vehicle_y", vehicle.y, "vehicle_z", vehicle.z)
    }

    /** Transition evidence catches short flashes that the one-second pass summary misses. */
    fun visibility(vehicle: VehicleEntity, reason: String) {
        if (!EliteDiagnostics.isClientEnabled()) return
        refreshSession()
        if (lastVisibility[vehicle] == reason) return
        val tick = vehicle.level().gameTime
        if (tick != visibilityTick) { visibilityTick = tick; visibilityEvents = 0 }
        if (visibilityEvents >= 64) return
        visibilityEvents++
        lastVisibility[vehicle] = reason
        EliteDiagnostics.record(vehicle, "far_render", "VISIBILITY_CHANGED", "reason", reason,
            "copy", FarVehicleCopies.isCopy(vehicle), "presentation_tick", FarVehicleClient.time())
    }

    @JvmStatic fun lighting(vehicle: VehicleEntity, packedLight: Int) {
        if (!EliteDiagnostics.isClientEnabled()) return
        refreshSession()
        val tick = vehicle.level().gameTime
        if (lastLightTick[vehicle]?.let { tick - it in 0..19 } == true) return
        lastLightTick[vehicle] = tick
        EliteDiagnostics.record(vehicle, "far_render", "LIGHT_SAMPLE", "copy", FarVehicleCopies.isCopy(vehicle),
            "block_light", (packedLight shr 4) and 15, "sky_light", (packedLight shr 20) and 15,
            "terrain_light", com.atsuishio.superbwarfare.client.FarTerrainClient.contains(
                net.minecraft.world.level.ChunkPos.asLong(vehicle.blockPosition())),
            "fog_start", com.mojang.blaze3d.systems.RenderSystem.getShaderFogStart(),
            "fog_end", com.mojang.blaze3d.systems.RenderSystem.getShaderFogEnd())
    }

    @JvmStatic
    fun modelRendered(vehicle: VehicleEntity) {
        if (!EliteDiagnostics.isClientEnabled()) return
        refreshSession()
        val tick = vehicle.level().gameTime
        val previous = lastModelTick[vehicle]
        if (previous != null && tick - previous in 0..19) return
        lastModelTick[vehicle] = tick
        EliteDiagnostics.record(vehicle, "far_render", "MODEL_DRAWN",
            "copy", FarVehicleCopies.isCopy(vehicle), "x", vehicle.x, "y", vehicle.y, "z", vehicle.z,
            "wreck", vehicle.isWreck)
    }

    /** Samples the anchor actually submitted to rendering, including sub-tick motion and fade. */
    @JvmStatic
    fun modelRendered(vehicle: VehicleEntity, partialTick: Float, anchor: Vec3, fade: Float) {
        modelRendered(vehicle)
        if (!EliteDiagnostics.isClientEnabled() || !java.lang.Boolean.getBoolean("bvp.diagnostics.scenarios")) return
        val tick = vehicle.level().gameTime + partialTick.toDouble()
        val presentationTick = FarVehicleClient.time(partialTick)
        val previous = lastSampleTick[vehicle]
        if (previous != null && presentationTick >= previous && presentationTick - previous < 0.25) return
        lastSampleTick[vehicle] = presentationTick
        val entry = FarVehicleClient.store.get(vehicle.id)?.takeIf {
            it.current.uuid == vehicle.stringUUID && it.current.type == vehicle.encodeId &&
                it.current.overrideData == vehicle.override && FarVehicleClient.currentLevel() === vehicle.level()
        }
        val segment = entry?.let { FarVehicleStore.segment(it, presentationTick) }
        EliteDiagnostics.record(vehicle, "far_render", "MODEL_SAMPLE",
            "copy", FarVehicleCopies.isCopy(vehicle), "render_tick", tick, "partial_tick", partialTick,
            "x", anchor.x, "y", anchor.y, "z", anchor.z, "fade", fade,
            "presentation_tick", presentationTick, "receipt_tick", entry?.receivedTick,
            "interval", entry?.interval, "alpha", segment?.let { FarVehicleStore.alpha(it, presentationTick) },
            "segment_start_tick", segment?.startTick, "segment_end_tick", segment?.endTick,
            "previous_x", segment?.previous?.x, "previous_y", segment?.previous?.y,
            "previous_z", segment?.previous?.z, "current_x", segment?.current?.x,
            "current_y", segment?.current?.y, "current_z", segment?.current?.z)
    }

    @JvmOverloads
    fun renderPass(tick: Long, cached: Int, requested: Int, copies: Int, range: Int,
                   gate: String = "DRAW_ATTEMPTED", terrainWaiting: Int = 0) {
        if (!EliteDiagnostics.isClientEnabled()) return
        refreshSession()
        if (lastPassTick != Long.MIN_VALUE && tick - lastPassTick in 0..19) return
        lastPassTick = tick
        EliteDiagnostics.recordClient(tick, "far_render", "RENDER_PASS",
            "cached", cached, "requested", requested, "copies", copies, "range", range,
            "gate", gate, "terrain_waiting", terrainWaiting)
    }

    private fun refreshSession() {
        val current = EliteDiagnostics.clientSessionId()
        if (current != session) {
            session = current
            lastModelTick.clear()
            lastSampleTick.clear()
            lastLightTick.clear()
            lastVisibility.clear()
            lastTerrainWait.clear()
            visibilityTick = Long.MIN_VALUE
            visibilityEvents = 0
            lastPassTick = Long.MIN_VALUE
        }
    }
}
