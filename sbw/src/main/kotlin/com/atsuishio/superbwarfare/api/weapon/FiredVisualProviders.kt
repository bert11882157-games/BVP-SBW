package com.atsuishio.superbwarfare.api.weapon

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.api.internal.OrderedProviderRegistry
import net.minecraft.resources.ResourceLocation
import java.util.LinkedHashSet
import java.util.UUID

/** Client-dispatched registry for optional per-shot presentation providers. */
object FiredVisualProviders {
    private const val MAX_DELIVERED_RECORDS = 4096

    private val providers = OrderedProviderRegistry<ResourceLocation, FiredVisualProvider>()
    private val deliveredRecords = LinkedHashSet<RecordAddress>()

    @JvmStatic
    fun register(id: ResourceLocation, provider: FiredVisualProvider) = providers.register(id, provider)

    @JvmStatic
    fun unregister(id: ResourceLocation): Boolean = providers.unregister(id)

    /**
     * Delivers a server record at most once on this client. A false result leaves
     * native SBW presentation untouched and available as the fallback behavior.
     */
    @JvmStatic
    fun dispatch(record: FiredVisualRecord): Boolean {
        synchronized(deliveredRecords) {
            if (!deliveredRecords.add(RecordAddress(record.serverSessionId, record.sequence))) {
                return true
            }
            while (deliveredRecords.size > MAX_DELIVERED_RECORDS) {
                val iterator = deliveredRecords.iterator()
                iterator.next()
                iterator.remove()
            }
        }

        for ((id, provider) in providers.snapshot()) {
            try {
                if (provider.handle(record)) return true
            } catch (exception: RuntimeException) {
                Mod.LOGGER.warn("Fired visual provider {} failed for sequence {}", id, record.sequence, exception)
            }
        }
        return false
    }

    private data class RecordAddress(val serverSessionId: UUID, val sequence: Long)
}
