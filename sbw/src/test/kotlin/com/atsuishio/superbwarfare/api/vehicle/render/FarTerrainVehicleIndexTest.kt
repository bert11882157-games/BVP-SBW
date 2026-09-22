package com.atsuishio.superbwarfare.api.vehicle.render

import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.DoubleTag
import net.minecraft.nbt.ListTag
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.util.UUID

class FarTerrainVehicleIndexTest {
    @Test fun `unloaded vehicle positions survive reload and moves replace old positions`() {
        val index = FarVehicleIndex()
        val id = UUID.randomUUID()
        index.put(id, -80.5, 900.0)
        index.put(id, -120.5, 950.0)
        val loaded = FarVehicleIndex.load(index.save(CompoundTag()))
        assertEquals(FarVehicleIndex.Position(-120.5, 950.0), loaded.positions[id])
        loaded.remove(id)
        assertTrue(FarVehicleIndex.load(loaded.save(CompoundTag())).positions.isEmpty())
        loaded.put(id, Double.NaN, 0.0)
        loaded.put(id, 30_000_001.0, 0.0)
        assertTrue(loaded.positions.isEmpty())
    }

    @Test fun `saved vehicle module tags bootstrap old worlds without overwriting newer live state`() {
        val id = UUID.randomUUID()
        val vehicle = CompoundTag().also {
            it.putUUID("UUID", id)
            it.putFloat("TurretHealth", 100F)
            it.putFloat("LeftWheelHealth", 100F)
            it.put("Pos", ListTag().also { p ->
                p.add(DoubleTag.valueOf(200.0)); p.add(DoubleTag.valueOf(64.0)); p.add(DoubleTag.valueOf(-300.0))
            })
        }
        val passengerContainer = CompoundTag().also {
            it.putUUID("UUID", UUID.randomUUID())
            it.put("Passengers", ListTag().also { passengers -> passengers.add(vehicle) })
        }
        val disk = ListTag().also { it.add(passengerContainer) }
        val index = FarVehicleIndex()
        index.discover(disk)
        assertEquals(mapOf(id to FarVehicleIndex.Position(200.0, -300.0)), index.positions)
        index.put(id, 400.0, -500.0)
        index.discover(disk)
        assertEquals(FarVehicleIndex.Position(400.0, -500.0), index.positions[id])
    }
}
