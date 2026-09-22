package com.atsuishio.superbwarfare.entity.vehicle.base

import com.atsuishio.superbwarfare.Mod
import com.atsuishio.superbwarfare.capability.energy.SyncedEntityEnergyStorage
import com.atsuishio.superbwarfare.capability.energy.VehicleEnergyStorage
import com.atsuishio.superbwarfare.inventory.handler.VehicleContainerHandler
import net.minecraft.core.NonNullList
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.IntTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.item.ItemStack
import net.minecraftforge.common.capabilities.ForgeCapabilities
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.energy.IEnergyStorage
import kotlin.math.min

/** Owns vehicle inventory storage, mutation and capability lifetime behind VehicleEntity's ABI. */
internal class VehicleInventoryEnergyService(
    private val vehicle: VehicleEntity,
) {
    val inventory = VehicleContainerHandler(6 * 17, vehicle)
    private var itemHandler = LazyOptional.of { inventory }

    // Keep the supplier lazy: Entity.defineSynchedData runs before VehicleEntity initializers,
    // while VehicleEnergyStorage requires the already-defined ENERGY accessor.
    private lateinit var energyStorage: SyncedEntityEnergyStorage
    private var energyHandler: LazyOptional<IEnergyStorage> = LazyOptional.of { energyStorage }

    fun initializeEnergyStorage() {
        if (vehicle.hasEnergyStorage()) {
            energyStorage = VehicleEnergyStorage(vehicle)
        }
    }

    fun syncedEnergyStorage(): SyncedEntityEnergyStorage = energyStorage

    fun replaceEnergyStorage(storage: SyncedEntityEnergyStorage) {
        energyStorage = storage
    }

    fun items(): NonNullList<ItemStack> = inventory.getItems()

    fun resizeItems() {
        val newSize = vehicle.getContainerSize()
        val oldSize = inventory.slots
        if (newSize == oldSize) return

        val oldStacks = NonNullList.withSize(oldSize, ItemStack.EMPTY)
        for (i in 0 until oldSize) {
            oldStacks[i] = inventory.getStackInSlot(i)
        }

        inventory.setSize(newSize)

        for (i in 0 until min(oldSize, newSize)) {
            inventory.setStackInSlot(i, oldStacks[i])
        }

        if (newSize < oldSize) {
            for (i in newSize until oldSize) {
                val stack = oldStacks[i]
                if (!stack.isEmpty) {
                    vehicle.spawnAtLocation(stack, 0.5f)
                }
            }
        }
    }

    fun getItem(slot: Int): ItemStack {
        if (!vehicle.hasContainer() || slot !in 0 until vehicle.getContainerSize()) return ItemStack.EMPTY
        return inventory.getStackInSlot(slot)
    }

    fun removeItem(slot: Int, amount: Int): ItemStack {
        if (!vehicle.hasContainer() || slot !in 0 until vehicle.getContainerSize()) return ItemStack.EMPTY
        return inventory.extractItem(slot, amount, false)
    }

    fun setItem(slot: Int, stack: ItemStack) {
        if (!vehicle.hasContainer() || slot !in 0 until vehicle.getContainerSize()) return

        val limit = min(vehicle.maxStackSize, stack.maxStackSize)
        if (!stack.isEmpty && stack.count > limit) {
            Mod.LOGGER.warn(
                "try inserting ItemStack {} exceeding the maximum stack size: {}, clamped to {}",
                stack.item,
                limit,
                limit,
            )
            stack.count = limit
        }
        inventory.setStackInSlot(slot, stack)
    }

    fun publishChanged() {
        if (vehicle.level().isClientSide) return
        val item = itemHandler.resolve().orElse(null) ?: return
        vehicle.publishVehicleInventorySnapshot(item.serializeNBT())
    }

    fun clear() = inventory.clear()

    fun canPlaceItem(slot: Int, stack: ItemStack): Boolean {
        if (!vehicle.hasContainer() || slot !in 0 until vehicle.getContainerSize()) return false

        val currentStack = inventory.getStackInSlot(slot)
        if (!currentStack.isEmpty && currentStack.item !== stack.item) return false

        val combinedCount = currentStack.count + stack.count
        return combinedCount <= vehicle.maxStackSize && combinedCount <= stack.maxStackSize
    }

    fun canTakeItem(slot: Int): Boolean =
        vehicle.hasContainer() && slot in 0 until vehicle.getContainerSize()

    fun dropContentsOnRemoval() {
        for (i in 0 until inventory.slots) {
            val stack = inventory.getStackInSlot(i)
            if (!stack.isEmpty) {
                vehicle.spawnAtLocation(stack, 0.5f)
            }
        }
    }

    fun itemCapability(): LazyOptional<VehicleContainerHandler> = itemHandler

    fun invalidateItemCapability() {
        itemHandler.invalidate()
    }

    fun reviveItemCapability() {
        itemHandler = LazyOptional.of { inventory }
    }

    fun consumeEnergy(amount: Int) {
        if (!vehicle.hasEnergyStorage()) {
            Mod.LOGGER.warn("Trying to consume energy of vehicle {}, but it has no energy storage", vehicle.name)
            return
        }
        if (vehicle.level() is ServerLevel) {
            energyStorage.extractEnergy(amount, false)
        }
    }

    fun canConsume(amount: Int): Boolean {
        if (!vehicle.hasEnergyStorage()) {
            Mod.LOGGER.warn(
                "Trying to check if can consume energy of vehicle {}, but it has no energy storage",
                vehicle.name,
            )
            return false
        }
        return vehicle.energy >= amount
    }

    fun energy(): Int {
        if (!vehicle.hasEnergyStorage()) {
            Mod.LOGGER.warn(
                "Trying to get energy of vehicle {}, but it has no energy storage",
                vehicle.name,
            )
            return Int.MAX_VALUE
        }
        return energyStorage.energyStored
    }

    fun setEnergy(energy: Int) {
        if (!vehicle.hasEnergyStorage()) {
            Mod.LOGGER.warn(
                "Trying to set energy of vehicle {}, but it has no energy storage",
                vehicle.name,
            )
            return
        }
        val targetEnergy = Mth.clamp(energy, 0, vehicle.maxEnergy)

        if (targetEnergy > energyStorage.energyStored) {
            energyStorage.receiveEnergy(targetEnergy - energyStorage.energyStored, false)
        } else {
            energyStorage.extractEnergy(energyStorage.energyStored - targetEnergy, false)
        }
    }

    fun getEnergyStorage(): IEnergyStorage? {
        if (!vehicle.hasEnergyStorage()) {
            Mod.LOGGER.warn("Trying to get energy storage of vehicle {}, but it has no energy storage", vehicle.name)
        }
        return energyStorage
    }

    fun maxEnergy(): Int = if (!vehicle.hasEnergyStorage()) {
        Mod.LOGGER.warn(
            "Trying to get max energy of vehicle {}, but it has no energy storage",
            vehicle.name,
        )
        Int.MAX_VALUE
    } else {
        vehicle.computed().maxEnergy
    }

    fun hasEnergyStorage(): Boolean = vehicle.computed().maxEnergy > 0

    fun readEnergy(tag: net.minecraft.nbt.Tag?) {
        if (vehicle.hasEnergyStorage() && tag is IntTag) {
            energyStorage.deserializeNBT(tag)
        }
    }

    fun writeEnergy(compound: CompoundTag) {
        if (vehicle.hasEnergyStorage()) {
            compound.put("Energy", energyStorage.serializeNBT())
        }
    }

    fun chargeFromInventory() {
        if (!vehicle.hasEnergyStorage() || vehicle.tickCount % 20 != 0) return

        for (stack in inventory.getItems()) {
            val neededEnergy = vehicle.maxEnergy - vehicle.energy
            if (neededEnergy <= 0) break

            val energyCap = stack.getCapability(ForgeCapabilities.ENERGY).resolve()
            if (energyCap.isEmpty) continue

            val stackEnergy = energyCap.get()
            val stored = stackEnergy.energyStored
            if (stored <= 0) continue

            val energyToExtract = Math.min(stored, neededEnergy)
            stackEnergy.extractEnergy(energyToExtract, false)
            vehicle.energy += energyToExtract
        }
    }

    fun energyCapability(): LazyOptional<IEnergyStorage> = energyHandler

    fun replaceEnergyCapability(capability: LazyOptional<IEnergyStorage>) {
        energyHandler = capability
    }

    fun invalidateEnergyCapability() {
        energyHandler.invalidate()
    }

    fun reviveEnergyCapability() {
        energyHandler = LazyOptional.of { energyStorage }
    }
}
