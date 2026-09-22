package com.atsuishio.superbwarfare.command

import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

val ELITE_DIAGNOSTICS_COMMAND = Commands.literal("elite")
    .requires { it.hasPermission(2) }
    .then(Commands.literal("diagnostics")
        .then(Commands.literal("on").executes {
            val message = runCatching { EliteDiagnostics.start(it.source.server) }
                .getOrElse { error -> "Elite diagnostics could not start: ${error.message}" }
            it.source.sendSuccess({ Component.literal(message) }, false)
            1
        })
        .then(Commands.literal("off").executes {
            val message = EliteDiagnostics.stop(it.source.server)
            it.source.sendSuccess({ Component.literal(message) }, false)
            1
        })
        .then(Commands.literal("status").executes {
            it.source.sendSuccess({ Component.literal(EliteDiagnostics.status()) }, false)
            1
        }))
