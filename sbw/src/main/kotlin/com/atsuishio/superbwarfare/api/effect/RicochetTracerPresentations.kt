package com.atsuishio.superbwarfare.api.effect

import com.atsuishio.superbwarfare.network.message.receive.RicochetTracerMessage

fun interface RicochetTracerEmitter {
    fun emit(message: RicochetTracerMessage)
}

/** Common-side bridge; the client ricochet tracer renderer is installed during particle-provider setup. */
object RicochetTracerPresentations {
    @Volatile
    private var emitter = RicochetTracerEmitter { }

    @JvmStatic
    fun registerClientEmitter(emitter: RicochetTracerEmitter) {
        this.emitter = emitter
    }

    @JvmStatic
    fun emit(message: RicochetTracerMessage) {
        emitter.emit(message)
    }
}
