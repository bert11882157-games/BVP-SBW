package com.atsuishio.superbwarfare.client.sound

import com.atsuishio.superbwarfare.api.internal.OrderedProviderRegistry
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleLoopSoundMode
import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleLoopSoundChannel
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import net.minecraft.client.Minecraft
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import java.util.LinkedHashMap

/** Idempotently updates one custom loop channel. The provider owns the sound instance and its lifetime. */
@OnlyIn(Dist.CLIENT)
fun interface VehicleLoopSoundProvider {
    fun tickLoop(vehicle: VehicleEntity, channel: VehicleLoopSoundChannel, profileId: ResourceLocation)
}

/** One client-END maintenance callback for a registered custom loop owner. */
@OnlyIn(Dist.CLIENT)
fun interface VehicleLoopSoundMaintenance {
    fun tick(minecraft: Minecraft)
}

/**
 * Client-only registry keyed by the namespaced profile id authored in vehicle data.
 *
 * Dispatch is exact-id first, with explicitly registered namespaced path prefixes; it never
 * searches the client level or guesses an addon namespace.
 */
@OnlyIn(Dist.CLIENT)
object VehicleLoopSoundProviderRegistry {
    private val providers = OrderedProviderRegistry<ResourceLocation, VehicleLoopSoundProvider>()
    private data class PrefixKey(val namespace: String, val pathPrefix: String)
    private data class PrefixEntry(
        val key: PrefixKey,
        val provider: VehicleLoopSoundProvider,
    )

    /** Prefix entries are mutation-rare and read from an immutable snapshot on vehicle ticks. */
    @Volatile private var prefixProviders: List<PrefixEntry> = emptyList()
    /** One maintenance owner per custom profile. Hooks run once at client END, never per HUD/render call. */
    private val maintenance = LinkedHashMap<ResourceLocation, VehicleLoopSoundMaintenance>()

    /** Registers or replaces the provider for one custom sound profile. */
    @JvmStatic
    fun register(profileId: ResourceLocation, provider: VehicleLoopSoundProvider) =
        providers.register(profileId, provider)

    /** Removes the provider registered for a custom sound profile. */
    @JvmStatic
    fun unregister(profileId: ResourceLocation): Boolean = providers.unregister(profileId)

    /**
     * Registers one namespaced path-prefix fallback. Exact registrations always win. This is
     * intentionally narrower than a namespace-wide provider so malformed/foreign IDs cannot be
     * routed into an addon sound owner.
     */
    @JvmStatic
    fun registerPathPrefix(namespace: String, pathPrefix: String, provider: VehicleLoopSoundProvider) {
        require(namespace.isNotBlank() && pathPrefix.isNotEmpty())
        synchronized(this) {
            val key = PrefixKey(namespace, pathPrefix)
            prefixProviders = prefixProviders.filterNot { it.key == key } + PrefixEntry(key, provider)
        }
    }

    /** Registers the lifecycle cleanup owned by a provider profile. */
    @JvmStatic
    fun registerMaintenance(profileId: ResourceLocation, hook: VehicleLoopSoundMaintenance) {
        maintenance[profileId] = hook
    }

    /** Runs provider-owned cleanup once per client END tick. */
    @JvmStatic
    fun tick(minecraft: Minecraft) {
        for (hook in maintenance.values) hook.tick(minecraft)
    }

    /** Returns true only when the configured profile resolved to and invoked its per-client-tick provider. */
    @JvmStatic
    fun tickLoop(vehicle: VehicleEntity, channel: VehicleLoopSoundChannel): Boolean {
        val mode = when (channel) {
            VehicleLoopSoundChannel.ENGINE -> vehicle.computed().engineSoundMode
            VehicleLoopSoundChannel.TRACK -> vehicle.computed().trackSoundMode
        }
        if (mode != VehicleLoopSoundMode.CUSTOM) return false

        val profileId = vehicle.computed().customSoundProfileId ?: return false
        val provider = providers[profileId]
            ?: prefixProviders
                .asSequence()
                .filter { it.key.namespace == profileId.namespace && profileId.path.startsWith(it.key.pathPrefix) }
                .sortedByDescending { it.key.pathPrefix.length }
                .map { it.provider }
                .firstOrNull()
            ?: return false

        provider.tickLoop(vehicle, channel, profileId)
        return true
    }
}
