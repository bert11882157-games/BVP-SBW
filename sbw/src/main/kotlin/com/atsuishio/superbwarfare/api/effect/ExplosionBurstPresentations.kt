package com.atsuishio.superbwarfare.api.effect

import com.atsuishio.superbwarfare.network.message.receive.ExplosionBurstMessage

fun interface ExplosionBurstEmitter {
    fun emit(message: ExplosionBurstMessage)
}

/** Common-side bridge whose client implementation is installed during particle-provider setup. */
object ExplosionBurstPresentations {
    @Volatile
    private var emitter = ExplosionBurstEmitter { }

    @JvmStatic
    fun registerClientEmitter(emitter: ExplosionBurstEmitter) {
        this.emitter = emitter
    }

    @JvmStatic
    fun emit(message: ExplosionBurstMessage) {
        emitter.emit(message)
    }
}
