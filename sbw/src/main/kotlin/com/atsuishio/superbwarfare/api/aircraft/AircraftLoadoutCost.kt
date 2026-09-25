package com.atsuishio.superbwarfare.api.aircraft

import com.google.gson.JsonObject
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraftforge.items.IItemHandler
import kotlin.math.max
import kotlin.math.min

/**
 * Store munitions are bought when they are fitted in the pylon menu and simply spent when fired.
 *
 * Every fitted hardpoint is a [Rack] holding a number of munitions of one ammunition item. An edit
 * (APPLY or a loaded preset) refits every hardpoint, so it is priced hardpoint by hardpoint:
 * - same store: pay for the munitions that are missing, or get the removed ones back;
 * - another store, or emptied: get the old rack's munitions back and pay for the new rack.
 * Charges and refunds are then netted per ammunition item and settled all-or-nothing.
 *
 * Only munitions that were actually paid for are refunded ([Rack.paid]). Loadouts fitted before
 * this rule, or fitted with a creative exemption, hold unpaid munitions: they still fire, but
 * emptying them returns nothing, so a creative fit can never be turned into items later.
 */
object AircraftLoadoutCost {
    /** Released-store categories whose [fire][AircraftArmamentManager] path spends a rack munition. */
    private val guidedCategories = setOf("AIR_TO_AIR", "AIR_TO_GROUND", "ANTI_RADIATION", "CRUISE")

    /**
     * A hardpoint's munitions. [ammo] is the AmmoItem bought per munition, or null for a free store.
     * [rounds] counts only munitions still on an attached station; [paid] how many of them were bought.
     */
    data class Rack(val store: String, val ammo: String?, val rounds: Int, val paid: Int = rounds) {
        init { require(rounds >= 0 && paid in 0..rounds) { "Invalid rack contents." } }
    }

    /** One hardpoint's share of an edit, with the paid munitions the refitted rack will hold. */
    data class Change(val charge: Map<String, Int>, val refund: Map<String, Int>, val paid: Int)

    /** Whole-edit price: [net] is positive for items to pay and negative for items given back. */
    data class Plan(val changes: Map<String, Change>, val net: Map<String, Int>) {
        val charges: Map<String, Int> get() = net.filterValues { it > 0 }
        val refunds: Map<String, Int> get() = net.filterValues { it < 0 }.mapValues { -it.value }
        fun paid(mount: String): Int = changes[mount]?.paid ?: 0
        companion object { val FREE = Plan(emptyMap(), emptyMap()) }
    }

    data class Shortfall(val ammo: String, val need: Int, val have: Int)

    /** Mirrors the store-release branch of fire(): pods and visual stores never use rack munitions. */
    @JvmStatic fun releasedStore(store: JsonObject): Boolean {
        val category = store["Category"]?.takeIf { it.isJsonPrimitive }?.asString ?: return false
        return category == "LASER_GUIDED" || category == "COMMAND_GUIDED" ||
            category in guidedCategories && store.has("Guidance") ||
            category == "BOMB" && store.has("Bomb") ||
            category == "CRUISE" && store.has("Flight")
    }

    /** The item bought for each munition of [store], or null when fitting it is free. */
    @JvmStatic fun ammoId(store: JsonObject): String? {
        if (!releasedStore(store)) return null
        val value = store["AmmoItem"]?.takeIf { it.isJsonPrimitive }?.asString ?: return null
        return value.takeIf { it.isNotBlank() }
    }

    /** Creative mode or a creative ammo box (carried, in the aircraft, or with a passenger) fits for free. */
    @JvmStatic fun exempt(creative: Boolean, playerBox: Boolean, vehicleBox: Boolean): Boolean =
        creative || playerBox || vehicleBox

    /** Munitions a rack holds: per-position capacity × positions (pair = 2) × rack copies. */
    @JvmStatic fun rounds(perPosition: Int, positions: Int, copies: Int): Int =
        max(perPosition, 1) * max(positions, 0) * max(copies, 1)

    /** Unfired munitions ([fired] until [capacity]) whose station has not been torn off with a wing. */
    @JvmStatic fun attached(capacity: Int, fired: Int, detached: (Int) -> Boolean): Int =
        (fired.coerceIn(0, max(capacity, 0)) until max(capacity, 0)).count { !detached(it) }

    /** Prices one hardpoint. Exempt edits neither pay nor refund, and add only unpaid munitions. */
    @JvmStatic fun change(old: Rack?, new: Rack?, exempt: Boolean): Change {
        if (old != null && new != null && old.store == new.store) {
            val ammo = new.ammo ?: return Change(emptyMap(), emptyMap(), 0)
            val kept = min(old.paid, old.rounds)
            if (new.rounds >= old.rounds) {
                val missing = new.rounds - old.rounds
                return if (exempt || missing == 0) Change(emptyMap(), emptyMap(), kept)
                    else Change(mapOf(ammo to missing), emptyMap(), kept + missing)
            }
            // Fewer munitions (a smaller bay load): unpaid munitions come off first.
            val removedPaid = max(0, kept - new.rounds)
            val refund = if (exempt) emptyMap() else positive(ammo, removedPaid)
            return Change(emptyMap(), refund, kept - removedPaid)
        }
        val refund = if (exempt || old?.ammo == null) emptyMap() else positive(old.ammo, min(old.paid, old.rounds))
        val charge = if (exempt || new?.ammo == null) emptyMap() else positive(new.ammo, new.rounds)
        return Change(charge, refund, charge.values.sum())
    }

    /** Prices a whole refit: [old] is what is fitted now, [new] every hardpoint after the edit. */
    @JvmStatic fun plan(old: Map<String, Rack>, new: Map<String, Rack>, exempt: Boolean): Plan {
        val changes = (old.keys + new.keys).associateWith { change(old[it], new[it], exempt) }
        val net = sortedMapOf<String, Int>()
        for (change in changes.values) {
            change.charge.forEach { (ammo, count) -> net.merge(ammo, count, Int::plus) }
            change.refund.forEach { (ammo, count) -> net.merge(ammo, -count, Int::plus) }
        }
        return Plan(changes, net.filterValues { it != 0 })
    }

    /** Items the player lacks for [net]; empty when the edit is affordable. */
    @JvmStatic fun shortfalls(net: Map<String, Int>, have: (String) -> Int): List<Shortfall> =
        net.filterValues { it > 0 }.mapNotNull { (ammo, need) ->
            val owned = have(ammo)
            if (owned >= need) null else Shortfall(ammo, need, owned)
        }

    /** "Need 2 × Small Air-to-Air Missile (have 1)", one clause per missing item. */
    @JvmStatic fun describe(shortfalls: List<Shortfall>, name: (String) -> String): String =
        shortfalls.joinToString("; ") { "Need ${it.need} × ${name(it.ammo)} (have ${it.have})" }

    @JvmStatic fun list(counts: Map<String, Int>, name: (String) -> String): String =
        counts.entries.joinToString(", ") { "${it.value} × ${name(it.key)}" }

    /** "Cost 2 × A · Refund 1 × B", or "No ammunition change". */
    @JvmStatic fun summary(net: Map<String, Int>, name: (String) -> String): String {
        val plan = Plan(emptyMap(), net)
        val parts = listOfNotNull(
            plan.charges.takeIf { it.isNotEmpty() }?.let { "Cost ${list(it, name)}" },
            plan.refunds.takeIf { it.isNotEmpty() }?.let { "Refund ${list(it, name)}" })
        return if (parts.isEmpty()) "No ammunition change" else parts.joinToString(" · ")
    }

    @JvmStatic fun count(inventory: IItemHandler, item: Item): Int =
        (0 until inventory.slots).sumOf { slot ->
            inventory.getStackInSlot(slot).takeIf { it.`is`(item) }?.count ?: 0
        }

    /**
     * Takes every positive entry of [net] from [inventory], all or nothing, then hands every negative
     * entry back through [give] (which drops what does not fit). Throws with [describe] text when short.
     */
    @JvmStatic fun settle(inventory: IItemHandler, net: Map<Item, Int>, name: (Item) -> String,
                          give: (Item, Int) -> Unit) {
        val charges = net.filterValues { it > 0 }
        val missing = charges.mapNotNull { (item, need) ->
            val owned = count(inventory, item)
            if (owned >= need) null else Triple(item, need, owned)
        }
        require(missing.isEmpty()) {
            missing.joinToString("; ") { (item, need, owned) -> "Need $need × ${name(item)} (have $owned)" }
        }
        val taken = LinkedHashMap<Item, Int>()
        for ((item, need) in charges) {
            var remaining = need
            for (slot in 0 until inventory.slots) {
                if (remaining == 0) break
                if (!inventory.getStackInSlot(slot).`is`(item)) continue
                val extracted: ItemStack = inventory.extractItem(slot, remaining, false)
                if (extracted.isEmpty) continue
                remaining -= extracted.count
                taken.merge(item, extracted.count, Int::plus)
            }
            if (remaining > 0) {
                // The handler refused part of a counted stack: nothing may be spent.
                taken.forEach { (spent, count) -> give(spent, count) }
                throw IllegalArgumentException("Need $need × ${name(item)} (have ${need - remaining})")
            }
        }
        net.forEach { (item, delta) -> if (delta < 0) give(item, -delta) }
    }

    private fun positive(ammo: String, count: Int): Map<String, Int> =
        if (count > 0) mapOf(ammo to count) else emptyMap()
}
