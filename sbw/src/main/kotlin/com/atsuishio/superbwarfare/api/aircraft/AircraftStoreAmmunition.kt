package com.atsuishio.superbwarfare.api.aircraft

import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraftforge.items.IItemHandler

/** Server-side reservation: rejected releases restore the item before returning to the caller. */
object AircraftStoreAmmunition {
    fun fire(inventory: IItemHandler, item: Item?, creative: Boolean,
             overflow: (ItemStack) -> Unit, launch: () -> Boolean): Boolean {
        // Legacy third-party stores without AmmoItem retain their original inventory contract.
        if (creative || item == null) return launch()
        var reserved = ItemStack.EMPTY
        var sourceSlot = -1
        for (slot in 0 until inventory.slots) {
            if (!inventory.getStackInSlot(slot).`is`(item)) continue
            reserved = inventory.extractItem(slot, 1, false)
            if (!reserved.isEmpty) { sourceSlot = slot; break }
        }
        require(sourceSlot >= 0) { "Load ${item.description.string} into the aircraft inventory." }
        var accepted = false
        try {
            accepted = launch()
            return accepted
        } finally {
            if (!accepted) {
                var remaining = inventory.insertItem(sourceSlot, reserved, false)
                for (slot in 0 until inventory.slots) {
                    if (remaining.isEmpty) break
                    if (slot != sourceSlot) remaining = inventory.insertItem(slot, remaining, false)
                }
                if (!remaining.isEmpty) overflow(remaining)
            }
        }
    }
}
