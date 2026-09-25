package com.atsuishio.superbwarfare.api.effect

import com.atsuishio.superbwarfare.network.message.receive.FireballMessage

fun interface FireballEmitter {
    fun emit(message: FireballMessage)
}

/** Common-side bridge; the client fireball renderer is installed during particle-provider setup. */
object FireballPresentations {
    @Volatile
    private var emitter = FireballEmitter { }

    @JvmStatic
    fun registerClientEmitter(emitter: FireballEmitter) {
        this.emitter = emitter
    }

    @JvmStatic
    fun emit(message: FireballMessage) {
        emitter.emit(message)
    }
}
