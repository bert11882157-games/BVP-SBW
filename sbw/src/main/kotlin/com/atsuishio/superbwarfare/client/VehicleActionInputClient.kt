package com.atsuishio.superbwarfare.client

import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity
import com.atsuishio.superbwarfare.network.message.send.VehicleActionInputMessage
import com.atsuishio.superbwarfare.tools.sendPacketToServer
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.api.distmarker.Dist
import net.minecraftforge.api.distmarker.OnlyIn
import java.util.LinkedHashMap
import java.util.UUID

/** Client edge helper shared by addon key mappings; the server remains the action authority. */
@OnlyIn(Dist.CLIENT)
object VehicleActionInputClient {
    private data class Context(
        val levelIdentity: Int,
        val dimension: String,
        val vehicleId: Int,
        val vehicleUuid: UUID,
        val actionId: ResourceLocation,
        val contextKey: String,
    )

    private val contexts = LinkedHashMap<ResourceLocation, ContextState>()
    private var sequence = 0

    private data class ContextState(
        val context: Context,
        var lastHeld: Boolean,
    )

    @JvmStatic
    @JvmOverloads
    fun sync(
        vehicle: VehicleEntity,
        actionId: ResourceLocation,
        held: Boolean,
        contextKey: String = "",
    ) {
        val levelIdentity = System.identityHashCode(vehicle.level())
        val stale = contexts.filterValues {
            it.context.levelIdentity != levelIdentity ||
                it.context.dimension != vehicle.level().dimension().location().toString() ||
                it.context.vehicleId != vehicle.id ||
                it.context.vehicleUuid != vehicle.uuid
        }.keys.toList()
        stale.forEach(::clear)

        val nextContext = Context(
            levelIdentity,
            vehicle.level().dimension().location().toString(),
            vehicle.id,
            vehicle.uuid,
            actionId,
            contextKey,
        )
        val previous = contexts[actionId]
        if (previous != null && previous.context != nextContext) {
            contexts.remove(actionId)
            if (previous.lastHeld) send(previous.context, false)
        }
        val state = contexts.getOrPut(actionId) {
            ContextState(nextContext, false)
        }
        if (state.lastHeld == held) return
        send(state.context, held)
        state.lastHeld = held
    }

    @JvmStatic
    fun clear() {
        contexts.keys.toList().forEach(::clear)
    }

    @JvmStatic
    fun clear(actionId: ResourceLocation) {
        val state = contexts.remove(actionId) ?: return
        if (state.lastHeld) send(state.context, false)
    }

    private fun send(context: Context, held: Boolean) {
        sequence += 1
        sendPacketToServer(
            VehicleActionInputMessage(
                context.vehicleId,
                context.actionId.toString(),
                sequence,
                held,
            )
        )
    }
}
