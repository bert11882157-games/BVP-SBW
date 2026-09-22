package com.atsuishio.superbwarfare.diagnostics

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.tree.ArgumentCommandNode
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.resources.ResourceLocation
import net.minecraftforge.event.RegisterCommandsEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test

class FarTerrainTestCommandTest {
    @Test
    fun registeredEntityArgumentConsumesNamespacedVehicleIds() {
        val dispatcher = CommandDispatcher<CommandSourceStack>()
        FarTerrainTestScenario.register(RegisterCommandsEvent(
            dispatcher, Commands.CommandSelection.DEDICATED, null,
        ))
        val argument = dispatcher.root.getChild("sbw_far_test")
            .getChild("prepare").getChild("entity") as ArgumentCommandNode<*, *>
        for (id in listOf("berts_vehicle_pack:m1_abrams_elite", "superbwarfare:t_90a")) {
            val reader = StringReader(id)
            assertEquals(ResourceLocation(id), argument.type.parse(reader))
            assertFalse(reader.canRead(), "The registered parser must consume the namespace and path")
        }
    }
}
