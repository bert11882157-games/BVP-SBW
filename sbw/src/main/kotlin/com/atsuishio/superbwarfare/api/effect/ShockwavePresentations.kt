package com.atsuishio.superbwarfare.api.effect

import com.atsuishio.superbwarfare.network.message.receive.ShockwaveMessage

fun interface ShockwaveEmitter {
    fun emit(message: ShockwaveMessage)
}

/** Common-side bridge; the client shockwave renderer is installed during particle-provider setup. */
object ShockwavePresentations {
    @Volatile
    private var emitter = ShockwaveEmitter { }

    @JvmStatic
    fun registerClientEmitter(emitter: ShockwaveEmitter) {
        this.emitter = emitter
    }

    @JvmStatic
    fun emit(message: ShockwaveMessage) {
        emitter.emit(message)
    }
}
