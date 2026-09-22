package com.atsuishio.superbwarfare.command

import com.atsuishio.superbwarfare.diagnostics.AamTargetFlight
import com.atsuishio.superbwarfare.diagnostics.AamTestTargets
import com.mojang.brigadier.arguments.IntegerArgumentType
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.network.chat.Component

private fun targetCommand(source: CommandSourceStack, action: () -> String): Int = try {
    val message = action()
    source.sendSuccess({ Component.literal(message) }, false)
    1
} catch (failure: IllegalArgumentException) {
    source.sendFailure(Component.literal(failure.message ?: "Cannot create an AAM target here."))
    0
}

val AAM_TEST_COMMAND = Commands.literal("test")
    .requires { it.hasPermission(2) }
    .executes { targetCommand(it.source) { "/sbw test aircraft [64..512] — airborne AAM target (default160m); status; clear." } }
    .then(Commands.literal("aircraft")
        .executes { targetCommand(it.source) { AamTestTargets.spawn(it.source.playerOrException, AamTargetFlight.DEFAULT_DISTANCE) } }
        .then(Commands.argument("distance", IntegerArgumentType.integer(AamTargetFlight.MIN_DISTANCE, AamTargetFlight.MAX_DISTANCE))
            .executes { targetCommand(it.source) { AamTestTargets.spawn(it.source.playerOrException, IntegerArgumentType.getInteger(it, "distance")) } }))
    .then(Commands.literal("status").executes { targetCommand(it.source) { AamTestTargets.status(it.source.playerOrException) } })
    .then(Commands.literal("clear").executes { targetCommand(it.source) { AamTestTargets.clear(it.source.playerOrException) } })
