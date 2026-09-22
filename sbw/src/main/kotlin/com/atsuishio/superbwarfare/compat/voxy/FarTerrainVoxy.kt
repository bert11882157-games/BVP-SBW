package com.atsuishio.superbwarfare.compat.voxy

import com.atsuishio.superbwarfare.client.FarTerrainClient
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.lang.reflect.Method

/** Optional public Voxy ingest API; native terrain meshes remain the occlusion authority. */
object FarTerrainVoxy {
    private var resolved = false
    private var world: Method? = null
    private var ingest: Method? = null
    private val logger = LoggerFactory.getLogger(FarTerrainVoxy::class.java)

    fun clear() { resolved = false; world = null; ingest = null }
    fun status(): String = if (!resolved) "unresolved" else if (ingest == null) "native_fallback" else "ingest_available"

    fun ingest(terrain: FarTerrainClient.Terrain) {
        if (!resolved) {
            resolved = true
            try {
                val identifier = Class.forName("me.cortex.voxy.commonImpl.WorldIdentifier")
                val service = Class.forName("me.cortex.voxy.common.world.service.VoxelIngestService")
                world = identifier.methods.firstOrNull { it.name == "of" && it.parameterCount == 1 }
                ingest = service.methods.firstOrNull { it.name == "rawIngest" && it.parameterCount == 7 && it.parameterTypes[0] == identifier }
            } catch (_: ClassNotFoundException) { return }
            catch (failure: LinkageError) { logger.debug("Voxy terrain API unavailable", failure); return }
        }
        val method = ingest ?: return
        try {
            val id = world?.invoke(null, Minecraft.getInstance().level) ?: return
            terrain.chunk.sections.forEachIndexed { index, section ->
                val y = terrain.chunk.getSectionYFromSectionIndex(index)
                method.invoke(null, id, section, terrain.chunk.pos.x, y, terrain.chunk.pos.z, terrain.block[y], terrain.sky[y])
            }
        } catch (failure: ReflectiveOperationException) {
            ingest = null
            logger.warn("Voxy far terrain ingest unavailable; retaining native terrain", failure)
        } catch (failure: LinkageError) {
            ingest = null
            logger.warn("Voxy far terrain API mismatch; retaining native terrain", failure)
        }
    }
}
