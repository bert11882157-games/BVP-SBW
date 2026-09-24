package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonParser
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test

class AircraftMountConflictTest {
    @Test fun ventralMissileAndSharedBayCannotCoexistRegardlessOfSelectionOrder() {
        val definition = JsonParser.parseString("""{"Singles":[
            {"Id":"ventral","Name":"Ventral missile","ExclusiveWith":["bay"]},
            {"Id":"bay","Name":"Internal bay"}, {"Id":"wing","Name":"Wing"}]}""").asJsonObject
        for (selection in listOf(setOf("ventral","bay"),setOf("bay","ventral")))
            assertThrows(IllegalArgumentException::class.java) {
                AircraftArmamentRegistry.validateMountConflicts(definition,selection)
            }
        for (selection in listOf(emptySet(),setOf("ventral","wing"),setOf("bay","wing")))
            assertDoesNotThrow { AircraftArmamentRegistry.validateMountConflicts(definition,selection) }
    }
}
