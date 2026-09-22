package com.atsuishio.superbwarfare.compat.ffa

import net.minecraft.world.entity.Entity
import net.minecraftforge.fml.ModList
import org.slf4j.LoggerFactory
import java.lang.reflect.Method

/** Optional FFA adapter: standalone SBW has no FFA/Dominions linkage or gameplay dependency. */
object AircraftFfaBridge {
    private data class Api(val levels: Method, val flare: Method, val threat: Method)
    private val logger = LoggerFactory.getLogger(AircraftFfaBridge::class.java)
    private var resolved = false
    private var api: Api? = null

    private fun resolve(): Api? {
        if (resolved) return api
        resolved = true
        if (!ModList.get().isLoaded("ballistics")) return null
        try {
            val hooks = Class.forName("dev.ballistics.CountermeasureHooks")
            api = Api(hooks.getMethod("setLevels", Entity::class.java, Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType), hooks.getMethod("registerFlare", Entity::class.java),
                Class.forName("dev.ballistics.AircraftThreatHooks").getMethod("threatLevel", Entity::class.java))
        } catch (failure: ReflectiveOperationException) {
            logger.warn("FFA aircraft API unavailable; standalone countermeasures remain active", failure)
        } catch (failure: LinkageError) {
            logger.warn("FFA aircraft API linkage failed; standalone countermeasures remain active", failure)
        }
        return api
    }

    private fun invoke(call: (Api) -> Any?): Any? {
        val methods = resolve() ?: return null
        return try { call(methods) } catch (failure: ReflectiveOperationException) {
            api = null
            logger.warn("FFA aircraft bridge disabled after invocation failure", failure)
            null
        } catch (failure: LinkageError) {
            api = null
            logger.warn("FFA aircraft bridge disabled after linkage failure", failure)
            null
        }
    }

    fun levels(vehicle: Entity, chaff: Int, flares: Int) {
        invoke { it.levels.invoke(null, vehicle, chaff, flares) }
    }
    fun registerFlare(flare: Entity) { invoke { it.flare.invoke(null, flare) } }
    fun threatLevel(vehicle: Entity): Int = (invoke { it.threat.invoke(null, vehicle) } as? Number)
        ?.toInt()?.coerceIn(0, 2) ?: 0
}
