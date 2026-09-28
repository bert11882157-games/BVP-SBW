package com.atsuishio.superbwarfare.data.gun

import com.atsuishio.superbwarfare.api.weapon.ReloadTransitionAction
import com.atsuishio.superbwarfare.api.weapon.ReloadTransitionCause
import com.atsuishio.superbwarfare.api.weapon.ReloadTransitionMode
import com.atsuishio.superbwarfare.api.weapon.ReloadTransitionPolicy
import com.atsuishio.superbwarfare.api.weapon.ReloadTransitionResult
import com.atsuishio.superbwarfare.api.weapon.ShotResult
import com.atsuishio.superbwarfare.data.DefaultDataSupplier
import com.atsuishio.superbwarfare.data.JsonPropertyModifier
import com.atsuishio.superbwarfare.data.PMC
import com.atsuishio.superbwarfare.data.StringOrVec3
import com.atsuishio.superbwarfare.data.gun.GunProp.Companion.AMMO_CONSUMER
import com.atsuishio.superbwarfare.data.gun.GunProp.Companion.AMMO_COST_PER_SHOOT
import com.atsuishio.superbwarfare.data.gun.GunProp.Companion.AVAILABLE_FIRE_MODES
import com.atsuishio.superbwarfare.data.gun.GunProp.Companion.AVAILABLE_PERKS
import com.atsuishio.superbwarfare.data.gun.GunProp.Companion.BOLT_ACTION_TIME
import com.atsuishio.superbwarfare.data.gun.GunProp.Companion.DEFAULT_ZOOM
import com.atsuishio.superbwarfare.data.gun.GunProp.Companion.MAGAZINE
import com.atsuishio.superbwarfare.data.gun.GunProp.Companion.MELEE_DAMAGE
import com.atsuishio.superbwarfare.data.gun.GunProp.Companion.PROJECTILE_AMOUNT
import com.atsuishio.superbwarfare.data.gun.GunProp.Companion.PROJECTILE_BELT_AMMO_TYPE
import com.atsuishio.superbwarfare.data.gun.GunProp.Companion.SHOOT_POS
import com.atsuishio.superbwarfare.data.gun.GunProp.Companion.SHOOT_SHAKE
import com.atsuishio.superbwarfare.data.gun.subdata.*
import com.atsuishio.superbwarfare.data.gun.value.*
import com.atsuishio.superbwarfare.event.GunEventHandler
import com.atsuishio.superbwarfare.item.gun.GunItem
import com.atsuishio.superbwarfare.network.message.receive.ShakeClientMessage
import com.atsuishio.superbwarfare.perk.Perk
import com.atsuishio.superbwarfare.tools.InventoryTool
import com.atsuishio.superbwarfare.tools.sameWith
import com.google.common.cache.CacheBuilder
import com.google.common.cache.CacheLoader
import com.google.common.cache.LoadingCache
import net.minecraft.nbt.CompoundTag
import net.minecraft.server.level.ServerLevel
import net.minecraft.util.Mth
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Item
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3
import net.minecraftforge.common.util.LazyOptional
import net.minecraftforge.energy.IEnergyStorage
import net.minecraftforge.items.IItemHandler
import java.util.*
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.max
import kotlin.math.min

fun ItemStack.isGunItem() = this.item is GunItem
fun ItemStack.toGunData() = if (isGunItem()) GunData.from(this) else null

class GunData private constructor(
    stack: ItemStack, initialDefaultDataSupplier: (() -> DefaultGunData)? = null
) : DefaultDataSupplier<DefaultGunData> {
    @JvmField
    val stack: ItemStack

    @JvmField
    val item: GunItem

    @JvmField
    val tag: CompoundTag

    @JvmField
    val gunDataTag: CompoundTag

    @JvmField
    val perkTag: CompoundTag

    @JvmField
    val attachmentTag: CompoundTag

    @JvmField
    val propertyOverrideString: StringValue

    @JvmField
    val id: String

    private var defaultDataSupplier: () -> DefaultGunData
    var lastTimeStack: ItemStack? = null

    private fun getOrPut(name: String): CompoundTag {
        if (!this.tag.contains(name)) {
            this.tag.put(name, CompoundTag())
        }
        return this.tag.getCompound(name)
    }

    fun initialized(): Boolean {
        return item.isInitialized(this)
    }

    fun initialize() {
        item.init(this)
    }

    fun item() = item
    fun stack() = stack

    fun tag() = tag
    fun data() = gunDataTag
    fun perk() = perkTag
    fun attachment() = attachmentTag

    override fun getDefault() = this.defaultDataSupplier()

    private val jsonPropModifier = JsonPropertyModifier(GunProp.entries)

    private var pmc: PMC<GunData, DefaultGunData>? = null
    private var resolvedDataRevision = -1

    /** Resolver-only component selected for one belt round; never serialized or consumed. */
    @Transient
    private var projectileBeltComponentOverride: AmmoConsumer? = null

    /**
     * Transient identity of a vehicle weapon entry (the key in VehicleEntity.gunDataMap).
     *
     * Vehicle guns all use the same vehicle_gun ItemStack, so the registry id/stack UUID cannot
     * distinguish a coax, roof station, or main gun.  Runtime policies which are scoped to one
     * vehicle weapon (for example heatless tank HMGs and reload audio claims) use this value;
     * it is deliberately not serialized and is reattached whenever a vehicle rebuilds/copies
     * its GunData map.
     */
    @Transient
    @JvmField
    var vehicleWeaponIdentity: String? = null

    /**
     * What the property pipeline of a perk-less vehicle gun reads from the stack: the override, the selected ammo
     * type and fire mode, and the attachments. Everything else in its tag is runtime state (ammo, heat, timers,
     * reload, belt phase) that changes every tick a weapon fires or cools, and each such change used to rebuild the
     * whole property map (deep NBT compare, stack copy, override and ammo-type deserialisation).
     */
    private var vehiclePropertyKey: Any? = null

    private fun vehiclePropertyKey(): Any? {
        if (item !is com.atsuishio.superbwarfare.item.gun.vehicle.VehicleGun || !perkTag.isEmpty) return null
        return listOf(gunDataTag.getString("Override"), gunDataTag.getInt("SelectedAmmoType"),
            gunDataTag.getInt("SelectedFireMode"), attachmentTag.hashCode(), attachmentTag.size())
    }

    @Suppress("unchecked_cast")
    fun <T> get(prop: GunProp<*, T>): T {
        val revision = dataRevision.get()
        val cached = pmc
        val key = vehiclePropertyKey()
        val unchanged = if (key != null) key == vehiclePropertyKey else stack sameWith lastTimeStack
        if (unchanged && resolvedDataRevision == revision && cached != null) return cached[prop]

        val pmc = PMC(this)
        this.pmc = pmc
        vehiclePropertyKey = key
        lastTimeStack = if (key == null) stack.copy() else null
        resolvedDataRevision = revision

        // property override tag
        jsonPropModifier.update(propertyOverrideString.get())
        jsonPropModifier.modifyProperty(pmc)

        // gun modifiers
        item.modifyProperty(pmc)

        // FireMode
        selectedFireModeInfo(pmc[AVAILABLE_FIRE_MODES]).modifyProperty(pmc)

        // AmmoConsumer
        selectedAmmoConsumer(pmc[AMMO_CONSUMER]).modifyProperty(pmc)

        // A belt component may contribute projectile semantics only.  It is applied after the
        // selected AmmoType and never participates in ammo selection/consumption/reload state.
        projectileBeltComponentOverride?.modifyProjectileBeltProperties(pmc)

        // perk
        for (type in Perk.Type.entries.toTypedArray()) {
            val list = perk.getInstances(type)
            for (instance in list) {
                instance.perk.modifyProperty(pmc)
            }
        }

        // limit
        GunProp.modifyProperty(pmc)

        if (item is com.atsuishio.superbwarfare.item.gun.vehicle.VehicleGun) {
            val installation = getDefault()
            // A saved property override or selected belt round cannot restore obsolete heat or
            // an infinite magazine. These are installation policies, not ammunition properties.
            if (!installation.overheatEnabled) {
                pmc[GunProp.OVERHEAT_ENABLED] = false
                pmc[GunProp.HEAT_PER_SHOOT] = 0.0
            }
            if (installation.beltFed && installation.magazine > 0) {
                pmc[GunProp.BELT_FED] = true
                pmc[MAGAZINE] = installation.magazine
                pmc[GunProp.NORMAL_RELOAD_TIME] = installation.normalReloadTime
                pmc[GunProp.EMPTY_RELOAD_TIME] = installation.emptyReloadTime
                pmc[AMMO_COST_PER_SHOOT] = installation.ammoCostPerShoot
                pmc[PROJECTILE_AMOUNT] = installation.projectileAmount
            }
        }

        return pmc[prop]
    }

    fun hasInfiniteBackupAmmo(shooter: Entity?): Boolean {
        return shooter is Player && shooter.isCreative
                || selectedAmmoConsumer().type == AmmoConsumer.AmmoConsumeType.INFINITE
                || meleeOnly()
                || InventoryTool.hasCreativeAmmoBox(shooter)
    }

    /**
     * 武器是否直接使用背包内弹药
     */
    fun useBackpackAmmo(): Boolean {
        return get(MAGAZINE) <= 0
    }

    // TODO 这什么b scope判断
    fun minZoom(): Double {
        val scopeType = this.attachment.get(AttachmentType.SCOPE)
        return if (scopeType == 3) max(getDefault().minZoom, 1.25) else 1.25
    }

    // TODO 这什么b scope判断
    fun maxZoom(): Double {
        val scopeType = this.attachment.get(AttachmentType.SCOPE)
        return if (scopeType == 3) getDefault().maxZoom else 114514.0
    }

    fun zoom(): Double {
        if (minZoom() >= maxZoom()) return get(DEFAULT_ZOOM)
        return Mth.clamp(get(DEFAULT_ZOOM), minZoom(), maxZoom())
    }

    @JvmOverloads
    fun selectedAmmoConsumer(consumers: List<AmmoConsumer>? = get(AMMO_CONSUMER)): AmmoConsumer {
        if (consumers.isNullOrEmpty()) {
            return AmmoConsumer.INVALID
        }
        return consumers[this.selectedAmmoType.get().coerceIn(consumers.indices)]
    }

    /**
     * Resolves one belt round without mutating selected ammunition or any firing state.  An
     * exact AmmoConsumer.ammo identity may borrow that consumer's effective projectile data;
     * the returned copy is never saved and therefore cannot consume the borrowed ammunition.
     */
    fun resolveProjectileBelt(): ProjectileBeltResolution {
        val belt = get(GunProp.PROJECTILE_BELT) ?: return ProjectileBeltResolution(
            ProjectileBeltResolutionStatus.NONE,
        )
        if (!belt.isValid()) {
            return ProjectileBeltResolution(
                ProjectileBeltResolutionStatus.INVALID,
                failure = ProjectileBeltResolutionFailure.INVALID_PROFILE,
            )
        }
        val round = belt.roundAt(projectileBeltPhase.get())
            ?: return ProjectileBeltResolution(
                ProjectileBeltResolutionStatus.INVALID,
                failure = ProjectileBeltResolutionFailure.INVALID_PHASE,
            )
        val ammoResolution = round.ammo?.let { ammoIdentity ->
            copyForProjectileBeltAmmo(ammoIdentity)
        }
        if (ammoResolution?.failure != null) {
            return ProjectileBeltResolution(
                ProjectileBeltResolutionStatus.INVALID,
                round = round,
                beltName = belt.name.ifBlank { null },
                failure = ammoResolution.failure,
            )
        }
        val resolvedProjectileData = ammoResolution?.data ?: this
        // The exact consumer is authoritative. ProjectileFactory validates its combat profile;
        // missing/changed tracer presentation must never reject an otherwise valid shot.
        return ProjectileBeltResolution(
            ProjectileBeltResolutionStatus.READY,
            round,
            resolvedProjectileData,
            belt.name.ifBlank { null },
        )
    }

    /** Advances only after an accepted logical shot; reload, repress, and ammo switching do not reset it. */
    fun advanceProjectileBelt() {
        val belt = get(GunProp.PROJECTILE_BELT) ?: return
        val cycle = belt.cycleLength()
        if (cycle <= 0) {
            projectileBeltPhase.reset()
            return
        }
        projectileBeltPhase.set(Math.floorMod(projectileBeltPhase.get().toLong() + 1L, cycle.toLong()).toInt())
    }

    private data class ProjectileBeltAmmoResolution(
        val data: GunData? = null,
        val failure: ProjectileBeltResolutionFailure? = null,
    )

    private fun copyForProjectileBeltAmmo(ammoIdentity: String): ProjectileBeltAmmoResolution {
        val consumers = get(AMMO_CONSUMER)
        val componentConsumers = get(PROJECTILE_BELT_AMMO_TYPE)
        if (consumers.isEmpty() && componentConsumers.isEmpty()) {
            return ProjectileBeltAmmoResolution(
                failure = ProjectileBeltResolutionFailure.EMPTY_CONSUMER_SET,
            )
        }

        // Belt lookup deliberately uses the union.  Every resolver identity must be present and
        // unique across both lists; otherwise a round could silently borrow the wrong projectile
        // profile or accidentally become a selectable/consumable AmmoType.
        val seen = HashSet<String>(consumers.size + componentConsumers.size)
        var target: AmmoConsumer? = null
        var targetIndex = -1
        var targetIsComponent = false
        for (index in consumers.indices) {
            val consumer = consumers[index]
            val identity = consumer.ammo?.takeIf { it.isNotBlank() }
                ?: return ProjectileBeltAmmoResolution(
                    failure = ProjectileBeltResolutionFailure.BLANK_CONSUMER_IDENTITY,
                )
            if (!seen.add(identity)) {
                return ProjectileBeltAmmoResolution(
                    failure = ProjectileBeltResolutionFailure.DUPLICATE_CONSUMER_IDENTITY,
                )
            }
            if (identity == ammoIdentity) {
                target = consumer
                targetIndex = index
            }
        }
        for (consumer in componentConsumers) {
            val identity = consumer.ammo?.takeIf { it.isNotBlank() }
                ?: return ProjectileBeltAmmoResolution(
                    failure = ProjectileBeltResolutionFailure.BLANK_CONSUMER_IDENTITY,
                )
            if (!seen.add(identity)) {
                return ProjectileBeltAmmoResolution(
                    failure = ProjectileBeltResolutionFailure.DUPLICATE_CONSUMER_IDENTITY,
                )
            }
            if (identity == ammoIdentity) {
                if (target != null) {
                    return ProjectileBeltAmmoResolution(
                        failure = ProjectileBeltResolutionFailure.DUPLICATE_CONSUMER_IDENTITY,
                    )
                }
                target = consumer
                targetIsComponent = true
            }
        }
        if (target == null) {
            return ProjectileBeltAmmoResolution(
                failure = ProjectileBeltResolutionFailure.MISSING_COMPONENT_TARGET,
            )
        }
        if (targetIsComponent) {
            val resolved = copy().also {
                it.projectileBeltComponentOverride = target
                // GunData construction resolves the default fire mode and therefore warms PMC
                // before this transient resolver-only override can be attached.  Invalidate that
                // warmed snapshot so the first projectile read applies the exact component.
                it.pmc = null
                it.resolvedDataRevision = -1
            }
            return ProjectileBeltAmmoResolution(data = resolved)
        }
        val selectedIndex = selectedAmmoType.get().coerceIn(consumers.indices)
        if (targetIndex == selectedIndex) return ProjectileBeltAmmoResolution(data = this)
        return ProjectileBeltAmmoResolution(
            data = copy().also { it.selectedAmmoType.set(targetIndex) },
        )
    }

    fun changeAmmoConsumer(index: Int, ammoSupplier: Entity?) {
        changeAmmoConsumerWithResult(index, ammoSupplier)
    }

    @JvmOverloads
    fun changeAmmoConsumerWithResult(
        index: Int,
        ammoSupplier: Entity?,
        policy: ReloadTransitionPolicy = ReloadTransitionPolicy.LEGACY,
    ): ReloadTransitionResult {
        val consumers = get(AMMO_CONSUMER)
        val previousIndex = selectedAmmoType.get()
        val previousState = reload.state()
        val previousRemainingTicks = reload.time()
        if (consumers.isEmpty()) {
            return unchangedAmmoConsumerResult(
                previousState,
                previousRemainingTicks,
                previousIndex,
            )
        }

        val targetIndex = index.coerceIn(consumers.indices)
        if (targetIndex == previousIndex) {
            return unchangedAmmoConsumerResult(
                previousState,
                previousRemainingTicks,
                previousIndex,
            )
        }

        val wasReloading = reloading()
        val hadMagazineAmmo = ammo.get() > 0

        if (!(ammoSupplier is Player && ammoSupplier.isCreative)) {
            val currentConsumer = selectedAmmoConsumer(consumers)
            val targetConsumer = consumers[targetIndex]

            val currentSlot = currentConsumer.ammoSlot
            val targetSlot = targetConsumer.ammoSlot

            if (currentSlot == targetSlot && targetConsumer.shouldUnload) {
                if (ammoSupplier != null) {
                    this.withdrawAmmoWithResult(ammoSupplier)
                }
                // Remaining rounds still belong to the current consumer; switching here would convert that ledger.
                if (storedAmmoRounds() > 0L) {
                    return unchangedAmmoConsumerResult(
                        previousState,
                        previousRemainingTicks,
                        previousIndex,
                    )
                }
            } else {
                val ammo = this.ammo.get()
                val virtualAmmo = this.virtualAmmo.get()
                this.ammoSlot.set(currentSlot, ammo, virtualAmmo)

                this.ammo.set(this.ammoSlot.getAmmo(targetSlot))
                this.virtualAmmo.set(this.ammoSlot.getVirtualAmmo(targetSlot))
                this.ammoSlot.reset(targetSlot)
            }
        }

        this.selectedAmmoType.set(targetIndex)

        if (ammoSupplier is Player && ammoSupplier.isCreative) {
            this.ammo.set(get(MAGAZINE))
        }

        this.item.whenNoAmmo(this)
        this.closeHammer.set(false)
        this.fireIndex.reset()

        return applyReloadTransition(
            ReloadTransitionCause.AMMO_CONSUMER_CHANGE,
            policy.forConsumerSwitch(wasReloading, hadMagazineAmmo),
            true,
            previousState,
            previousRemainingTicks,
            previousIndex,
            targetIndex,
        )
    }

    private fun unchangedAmmoConsumerResult(
        previousState: ReloadState,
        previousRemainingTicks: Int,
        previousIndex: Int,
    ) = ReloadTransitionResult(
        ReloadTransitionCause.AMMO_CONSUMER_CHANGE,
        ReloadTransitionMode.NO_CHANGE,
        previousState,
        previousRemainingTicks,
        0,
        previousIndex,
        previousIndex,
    )

    fun resetStatus() {
        resetStatusWithResult()
    }

    fun resetStatusWithResult(): ReloadTransitionResult {
        val previousState = reload.state()
        val previousRemainingTicks = reload.time()
        return applyReloadTransition(
            ReloadTransitionCause.STATUS_RESET,
            ReloadTransitionAction.RESET,
            false,
            previousState,
            previousRemainingTicks,
        )
    }

    private fun resetStatusInternal() {
        this.reload.stage.reset()
        this.reload.setState(ReloadState.NOT_RELOADING)
        this.reload.iterativeLoadTimer.reset()
        this.reload.reloadTimer.reset()
        this.reload.finishTimer.reset()
        this.reload.prepareTimer.reset()
        this.reload.prepareLoadTimer.reset()
        this.reload.reloadStarter.finish()
        this.reload.singleReloadStarter.finish()
        this.reload.clearPendingProgress()
        this.bolt.actionTimer.reset()
        this.bolt.needed.reset()
        this.charge.starter.finish()
        this.charge.timer.reset()
    }

    private fun applyReloadTransition(
        cause: ReloadTransitionCause,
        action: ReloadTransitionAction,
        resetBeforeStart: Boolean,
        previousState: ReloadState,
        previousRemainingTicks: Int,
        previousAmmoConsumerIndex: Int? = null,
        selectedAmmoConsumerIndex: Int? = null,
    ): ReloadTransitionResult {
        when (action.mode) {
            ReloadTransitionMode.NO_CHANGE -> Unit
            ReloadTransitionMode.RESET -> resetStatusInternal()
            ReloadTransitionMode.START_RELOAD -> {
                if (resetBeforeStart) resetStatusInternal()
                reload.setPendingProgressPercent(action.progressPercent)
                reload.reloadStarter.markStart()
            }
        }

        return ReloadTransitionResult(
            cause,
            action.mode,
            previousState,
            previousRemainingTicks,
            action.progressPercent,
            previousAmmoConsumerIndex,
            selectedAmmoConsumerIndex,
        )
    }

    @JvmOverloads
    fun selectedFireModeInfo(fireModes: List<FireModeInfo>? = get(AVAILABLE_FIRE_MODES)): FireModeInfo {
        if (fireModes.isNullOrEmpty()) {
            return FireModeInfo()
        }
        return fireModes[this.selectedFireMode.get().coerceIn(fireModes.indices)]
    }

    // 开火相关流程开始
    /*
     * 开火相关流程描述
     * 1. 调用shouldStartReloading和shouldStartBolt查看当前状态是否应该开始换弹或拉栓，是则调用startReloading或startBolt开始换弹/拉栓流程
     * 2. 调用canShoot(@Nullable Entity shooter)查看当前状态是否能够开火，如果能够开火则调用shootBullet进行开火
     * 3. 调用tick(@Nullable Entity shooter)执行枪械tick任务，包括换弹流程、过热计算、拉栓等
     *
     * 可选项：
     * 1. 使用GunData.virtualAmmo.set来设置虚拟弹药数量
     * 2. 传入带有IItemHandler能力的任意Entity来提供额外弹药
     *
     */
    /**
     * 是否应该开始换弹
     */
    fun shouldStartReloading(entity: Entity?): Boolean {
        return !reloading() && !useBackpackAmmo() && !hasEnoughAmmoToShoot(entity) && hasBackupAmmo(entity)
    }

    /**
     * 是否应该开始换弹
     */
    fun shouldStartBolt(): Boolean {
        return this.bolt.actionTimer.get() == 0 && this.bolt.needed.get()
    }

    /**
     * 开始换弹流程，换弹将在tick内被执行
     */
    fun startReload() {
        startReloadWithResult()
    }

    fun startReloadWithResult(): ReloadTransitionResult {
        val previousState = reload.state()
        val previousRemainingTicks = reload.time()
        return applyReloadTransition(
            ReloadTransitionCause.DIRECT_REQUEST,
            ReloadTransitionAction.startAtProgress(reload.pendingProgressPercent()),
            false,
            previousState,
            previousRemainingTicks,
        )
    }

    /**
     * 开始拉栓流程，换弹将在tick内被执行
     */
    fun startBolt() {
        this.bolt.actionTimer.set(get(BOLT_ACTION_TIME) + 1)
    }

    /**
     * 是否还有剩余弹药（不考虑枪内弹药）
     */
    fun hasBackupAmmo(entity: Entity?): Boolean {
        return countBackupAmmo(entity) > 0
    }

    /**
     * 计算剩余弹药数量（不考虑枪内弹药）
     */
    fun countBackupAmmo(entity: Entity?): Int {
        if (entity == null) return virtualAmmo.get()
        if (entity is Player && entity.isCreative || InventoryTool.hasCreativeAmmoBox(entity)) return Int.MAX_VALUE
        return countBackupAmmoWithoutCreative(entity)
    }

    /** [countBackupAmmo] for a supplier already known to have no creative source (checked once for all weapons). */
    fun countBackupAmmoWithoutCreative(entity: Entity): Int {

        return Math.toIntExact(
            min(
                countBackupAmmoItem(entity).toLong() * this.selectedAmmoConsumer().loadAmount + this.virtualAmmo.get(),
                Int.MAX_VALUE.toLong()
            )
        )
    }

    /**
     * 计算剩余弹药数量（不考虑枪内弹药）
     */
    fun countBackupAmmo(handler: IItemHandler?): Int {
        if (handler == null) return virtualAmmo.get()
        if (InventoryTool.hasCreativeAmmoBox(handler)) return Int.MAX_VALUE

        return Math.toIntExact(
            min(
                countBackupAmmoItem(handler).toLong() * this.selectedAmmoConsumer().loadAmount + this.virtualAmmo.get(),
                Int.MAX_VALUE.toLong()
            )
        )
    }

    fun countBackupAmmoItem(entity: Entity?): Int {
        return this.selectedAmmoConsumer().count(this, entity)
    }

    fun countBackupAmmoItem(handler: IItemHandler?): Int {
        return this.selectedAmmoConsumer().count(this, handler)
    }

    /**
     * 消耗额外弹药（不影响枪内弹药）
     */
    fun consumeBackupAmmo(entity: Entity?, count: Int) {
        val remaining = consumeVirtualBackupAmmo(
            count,
            entity is Player && entity.isCreative || InventoryTool.hasCreativeAmmoBox(entity),
        )
        val ammoSupplier = entity ?: return
        if (remaining <= 0) return

        consumeSourceAmmo(remaining) { consumer, itemAmount ->
            consumer.consume(this, ammoSupplier, itemAmount)
        }
    }

    /**
     * 消耗额外弹药（不影响枪内弹药）
     */
    fun consumeBackupAmmo(handler: IItemHandler?, count: Int) {
        val remaining = consumeVirtualBackupAmmo(count, InventoryTool.hasCreativeAmmoBox(handler))
        val ammoHandler = handler ?: return
        if (remaining <= 0) return

        consumeSourceAmmo(remaining) { consumer, itemAmount ->
            consumer.consume(this, ammoHandler, itemAmount)
        }
    }

    private fun consumeVirtualBackupAmmo(count: Int, hasInfiniteAmmo: Boolean): Int {
        if (count <= 0 || hasInfiniteAmmo) return 0

        val consumed = min(virtualAmmo.get().coerceAtLeast(0), count)
        if (consumed > 0) {
            virtualAmmo.add(-consumed)
            save()
        }
        return count - consumed
    }

    private inline fun consumeSourceAmmo(
        count: Int,
        consumeItems: (AmmoConsumer, Int) -> Int,
    ) {
        val consumer = selectedAmmoConsumer()
        val loadAmount = consumer.loadAmount
        val remainder = count % loadAmount
        if (remainder == 0) {
            consumeItems(consumer, count / loadAmount)
            return
        }

        val consumed = consumeItems(consumer, count / loadAmount + 1)
        val excessRounds = consumed * loadAmount - count
        if (excessRounds >= 0) {
            // 迫真过载装填
            virtualAmmo.add(excessRounds)
        }
    }

    /**
     * 当前状态在换弹前的可用射击次数
     */
    fun currentAvailableShots(entity: Entity?): Int {
        val ammoCost = get(AMMO_COST_PER_SHOOT)
        if (ammoCost <= 0) return Int.MAX_VALUE

        return currentAvailableAmmo(entity) / ammoCost
    }

    /**
     * 当前枪内可用弹药数量
     */
    fun currentAvailableAmmo(entity: Entity?): Int {
        return if (useBackpackAmmo()) countBackupAmmo(entity) else this.ammo.get()
    }

    /**
     * 当前状态枪内是否拥有足够的弹药进行开火
     */
    fun hasEnoughAmmoToShoot(entity: Entity?): Boolean {
        return get(AMMO_COST_PER_SHOOT) <= currentAvailableAmmo(entity)
    }

    /**
     * 换弹完成后装填弹药，在换弹流程完成后调用
     */
    @JvmOverloads
    fun reloadAmmo(entity: Entity?, extraOne: Boolean = false) {
        if (useBackpackAmmo()) return

        val mag = get(MAGAZINE)
        val ammo = this.ammo.get()
        val ammoNeeded = mag - ammo + (if (extraOne) 1 else 0)

        // 空仓换弹的栓动武器应该在换弹后取消待上膛标记
        if (ammo == 0 && get(BOLT_ACTION_TIME) > 0) {
            bolt.needed.set(false)
        }

        val available = countBackupAmmo(entity)
        val ammoToAdd = min(ammoNeeded, available)

        consumeBackupAmmo(entity, ammoToAdd)
        this.ammo.set(ammo + ammoToAdd)

        reload.setState(ReloadState.NOT_RELOADING)
        this.fireIndex.reset()
    }

    /**
     * 当前状态能否开火
     */
    fun canShoot(shooter: Entity?): Boolean {
        return item.canShoot(this, shooter)
    }

    /**
     * 无实体情况下开火
     */
    fun shoot(level: ServerLevel, shootPosition: Vec3, shootDirection: Vec3, spread: Double, zoom: Boolean) {
        this.item.shoot(level, shootPosition, shootDirection, this, spread, zoom, null)
    }

    /**
     * 有实体情况下开火
     */
    fun shoot(entity: Entity, spread: Double, zoom: Boolean, uuid: UUID?) {
        this.item.shoot(this, entity, spread, zoom, uuid)
    }

    fun shoot(entity: Entity, spread: Double, zoom: Boolean, uuid: UUID?, targetPos: Vec3?) {
        this.item.shoot(this, entity, spread, zoom, uuid, targetPos)
    }

    fun shoot(parameters: ShootParameters) {
        this.item.shoot(parameters)
    }

    fun shootWithResult(parameters: ShootParameters): ShotResult {
        return this.item.shootWithResult(parameters)
    }

    /**
     * 执行tick更新枪械数据
     * <br></br>
     * 在玩家背包里时会使用GunItem.inventoryTick自动执行
     * <br></br>
     * 若需要在其他地方使用，请手动调用该方法
     *
     * @param inMainHand 枪械是否在主手上，用于控制部分tick流程是否执行
     */
    fun tick(shooter: Entity?, inMainHand: Boolean) {
        GunEventHandler.gunTick(shooter, this, inMainHand)
        reload.applyPendingProgress()
    }

    // 开火相关流程结束
    /**
     * 返还弹匣内弹药，在换弹和切换弹匣配件时调用
     */
    fun withdrawAmmo(ammoSupplier: Entity) {
        withdrawAmmoWithResult(ammoSupplier)
    }

    fun withdrawAmmoCount(): Int {
        val loadAmount = selectedAmmoConsumer().loadAmount.coerceAtLeast(1)
        return min(storedAmmoRounds() / loadAmount, Int.MAX_VALUE.toLong()).toInt()
    }

    /**
     * 返还弹匣内弹药，在换弹和切换弹匣配件时调用
     */
    fun withdrawAmmo(handler: IItemHandler) {
        withdrawRepresentableAmmo(Long.MAX_VALUE) { consumer, itemAmount ->
            consumer.withdraw(handler, itemAmount)
        }
    }

    /**
     * Returns up to [maxRounds] as whole source-ammo units and removes only rounds actually delivered.
     */
    fun withdrawAmmoRounds(ammoSupplier: Entity, maxRounds: Int): Int {
        return withdrawRepresentableAmmo(maxRounds.coerceAtLeast(0).toLong()) { consumer, itemAmount ->
            consumer.withdraw(ammoSupplier, itemAmount)
        }.toInt()
    }

    private fun withdrawAmmoWithResult(ammoSupplier: Entity): Long {
        return withdrawRepresentableAmmo(Long.MAX_VALUE) { consumer, itemAmount ->
            consumer.withdraw(ammoSupplier, itemAmount)
        }
    }

    private inline fun withdrawRepresentableAmmo(
        maxRounds: Long,
        withdrawItems: (AmmoConsumer, Int) -> Int,
    ): Long {
        val consumer = selectedAmmoConsumer()
        val loadAmount = consumer.loadAmount.coerceAtLeast(1)
        val roundsToReturn = min(storedAmmoRounds(), maxRounds.coerceAtLeast(0))
        val itemAmount = min(roundsToReturn / loadAmount, Int.MAX_VALUE.toLong()).toInt()
        if (itemAmount <= 0) return 0

        val returnedItems = withdrawItems(consumer, itemAmount).coerceIn(0, itemAmount)
        val returnedRounds = returnedItems.toLong() * loadAmount
        removeStoredAmmoRounds(returnedRounds)
        return returnedRounds
    }

    private fun storedAmmoRounds(): Long {
        return ammo.get().coerceAtLeast(0).toLong() + virtualAmmo.get().coerceAtLeast(0).toLong()
    }

    private fun removeStoredAmmoRounds(rounds: Long) {
        var remaining = min(rounds.coerceAtLeast(0), storedAmmoRounds())

        val magazineRounds = min(ammo.get().coerceAtLeast(0).toLong(), remaining)
        if (magazineRounds > 0) {
            ammo.add(-magazineRounds.toInt())
            remaining -= magazineRounds
        }

        val virtualRounds = min(virtualAmmo.get().coerceAtLeast(0).toLong(), remaining)
        if (virtualRounds > 0) {
            virtualAmmo.add(-virtualRounds.toInt())
        }
    }

    fun availablePerks() = get(AVAILABLE_PERKS)

    fun canApplyPerk(perk: Perk) = availablePerks().contains(perk)

    val rawDamageReduce: DamageReduce
        get() = getDefault().damageReduce

    val damageReduceRate: Double
        get() {
            for (type in Perk.Type.entries.toTypedArray()) {
                return this.perk.getInstances(type)
                    .minOfOrNull { it.perk.getModifiedDamageReduceRate(this.rawDamageReduce) } ?: continue
            }
            return this.rawDamageReduce.rate
        }

    val damageReduceMinDistance: Double
        get() {
            for (type in Perk.Type.entries.toTypedArray()) {
                return this.perk.getInstances(type)
                    .minOfOrNull { it.perk.getModifiedDamageReduceMinDistance(this.rawDamageReduce) } ?: continue
            }
            return this.rawDamageReduce.minDistance
        }

    fun meleeOnly(): Boolean {
        return get(PROJECTILE_AMOUNT) <= 0 && get(MELEE_DAMAGE) > 0
    }

    val isShotgun: Boolean
        get() = get(PROJECTILE_AMOUNT) > 1

    fun firePosition(): Vec3 {
        val shootPos = get(SHOOT_POS)
        return selectedMuzzleValue(shootPos.positions, shootPos.positions.size, shootPos.boundUpWithAmmoAmount)
            ?: Vec3.ZERO
    }

    fun firePositionForHud(): Vec3 {
        return get(SHOOT_POS).shootPositionForHud ?: firePosition()
    }

    /** Selects a native muzzle frame with the same ammo/fire-index rules as [firePosition]. */
    fun firePositionAttachment(): String? =
        get(SHOOT_POS).let { positionAttachment(it, it.muzzleAttachments) }

    /** Captures the position selection before shooting advances ammo or fire-index state. */
    fun firePositionSlot(): Int? =
        get(SHOOT_POS).let { selectedMuzzleIndex(it.positions.size, it.boundUpWithAmmoAmount) }

    fun fireDirection(): StringOrVec3 {
        val shootPos = get(SHOOT_POS)
        return selectedMuzzleValue(shootPos.directions, shootPos.directions.size, false)
            ?: StringOrVec3("Default")
    }

    /** Selects a native muzzle direction frame with the same fire-index rule as [fireDirection]. */
    fun fireDirectionAttachment(): String? =
        get(SHOOT_POS).let { directionAttachment(it, it.muzzleDirectionAttachments) }

    /** Captures the direction selection independently because its cardinality may differ. */
    fun fireDirectionSlot(): Int? =
        get(SHOOT_POS).let { selectedMuzzleIndex(it.directions.size, false) }

    /** Presentation can intentionally originate somewhere other than the ballistic muzzle. */
    fun fireEffectAttachment(): String? =
        get(SHOOT_POS).let { positionAttachment(it, it.effectAttachments) }

    fun fireEffectDirectionAttachment(): String? =
        get(SHOOT_POS).let { directionAttachment(it, it.effectDirectionAttachments) }

    fun fireDirectionForHud(): StringOrVec3? {
        return get(SHOOT_POS).shootDirectionForHud
    }

    private fun positionAttachment(shootPos: ShootPos, list: List<String>): String? {
        return selectedMuzzleValue(list, shootPos.positions.size, shootPos.boundUpWithAmmoAmount)
    }

    private fun directionAttachment(shootPos: ShootPos, list: List<String>): String? {
        return selectedMuzzleValue(list, shootPos.directions.size, false)
    }

    private fun <T> selectedMuzzleValue(values: List<T>, sourceSize: Int, boundToAmmo: Boolean): T? {
        if (values.isEmpty()) return null
        val index = selectedMuzzleIndex(sourceSize, boundToAmmo) ?: return null
        return values.getOrNull(index)
    }

    private fun selectedMuzzleIndex(sourceSize: Int, boundToAmmo: Boolean): Int? {
        if (sourceSize <= 0) return null
        return if (boundToAmmo) {
            (ammo.get() - 1).coerceIn(0, sourceSize - 1)
        } else {
            Math.floorMod(fireIndex.get(), sourceSize)
        }
    }

    fun getEnergyProvider(ammoSupplier: Entity?): LazyOptional<IEnergyStorage> {
        return this.item.getEnergyProvider(this, ammoSupplier)
    }

    fun shakePlayers(source: Entity?) {
        if (source == null) return

        val shootShake = get(SHOOT_SHAKE) ?: return

        ShakeClientMessage.sendToNearbyPlayers(source, shootShake.x, shootShake.y, shootShake.z)
    }

    // 可持久化属性开始
    @JvmField
    val selectedAmmoType: IntValue

    @JvmField
    val ammo: IntValue

    @JvmField
    val virtualAmmo: IntValue

    // backup ammo count override
    @JvmField
    val backupAmmoCount: IntValue

    @JvmField
    val ammoSlot: AmmoSlot

    @JvmField
    val burstAmount: IntValue

    @JvmField
    val selectedFireMode: IntValue

    @JvmField
    val fireIndex: IntValue

    /** Persistent accepted-shot phase; kept across reload and selection changes. */
    @JvmField
    val projectileBeltPhase: IntValue

    @JvmField
    val level: IntValue

    @JvmField
    val exp: DoubleValue

    // Max: 100
    @JvmField
    val heat: DoubleValue

    @JvmField
    val shootAnimationTimer: IntValue

    @JvmField
    val shootTimer: IntValue

    @JvmField
    val overHeat: BooleanValue

    fun canAdjustZoom() = item.canAdjustZoom(this)

    fun canSwitchScope() = item.canSwitchScope(this)

    @JvmField
    val reload: Reload

    /**
     * 是否正在换弹
     */
    fun reloading() = reload.state() != ReloadState.NOT_RELOADING

    @JvmField
    val charge: Charge

    fun charging() = charge.time() > 0

    @JvmField
    val isEmpty: BooleanValue

    @JvmField
    val closeHammer: BooleanValue

    @JvmField
    val closeStrike: BooleanValue

    @JvmField
    val stopped: BooleanValue

    @JvmField
    val forceStop: BooleanValue

    @JvmField
    val loadIndex: IntValue

    @JvmField
    val holdOpen: BooleanValue

    @JvmField
    val hideBulletChain: BooleanValue

    @JvmField
    val sensitivity: IntValue

    @JvmField
    val zooming: BooleanValue

    // 其他子级属性
    @JvmField
    val bolt: Bolt

    @JvmField
    val attachment: Attachment

    @JvmField
    val perk: Perks

    fun save() = Unit

    override fun equals(other: Any?): Boolean {
        if (other !is GunData) return false

        return other.stack sameWith this.stack
    }

    fun copy(): GunData = from(stack.copy(), defaultDataSupplier).also {
        it.vehicleWeaponIdentity = vehicleWeaponIdentity
    }

    init {
        require(stack.item is GunItem) { "stack is not GunItem!" }

        val gunItem = stack.item as GunItem
        this.item = gunItem
        this.stack = stack
        this.id = getRegistryId(stack.item)

        this.defaultDataSupplier = initialDefaultDataSupplier ?: { gunItem.getDefaultData(this) }

        this.tag = stack.getOrCreateTag()

        gunDataTag = getOrPut("GunData")
        perkTag = getOrPut("Perks")
        attachmentTag = getOrPut("Attachments")
        propertyOverrideString = StringValue(this.gunDataTag, "Override")

        selectedAmmoType = IntValue(gunDataTag, "SelectedAmmoType")
        selectedFireMode = IntValue(gunDataTag, "SelectedFireMode", 0)
        fireIndex = IntValue(gunDataTag, "FireIndex", 0)
        projectileBeltPhase = IntValue(gunDataTag, "ProjectileBeltPhase")

        // 可持久化属性
        reload = Reload(this)
        charge = Charge(this)
        bolt = Bolt(this)
        attachment = Attachment(this)
        perk = Perks(this)

        ammo = IntValue(gunDataTag, "Ammo")
        virtualAmmo = IntValue(gunDataTag, "VirtualAmmo")
        backupAmmoCount = IntValue(gunDataTag, "BackupAmmoCount")
        ammoSlot = AmmoSlot(gunDataTag)
        burstAmount = IntValue(gunDataTag, "BurstAmount")

        level = IntValue(gunDataTag, "Level")
        exp = DoubleValue(gunDataTag, "Exp")

        isEmpty = BooleanValue(gunDataTag, "IsEmpty")
        closeHammer = BooleanValue(gunDataTag, "CloseHammer")
        closeStrike = BooleanValue(gunDataTag, "CloseStrike")
        stopped = BooleanValue(gunDataTag, "Stopped")
        forceStop = BooleanValue(gunDataTag, "ForceStop")
        loadIndex = IntValue(gunDataTag, "LoadIndex")
        holdOpen = BooleanValue(gunDataTag, "HoldOpen")
        hideBulletChain = BooleanValue(gunDataTag, "HideBulletChain")
        sensitivity = IntValue(gunDataTag, "Sensitivity")
        heat = DoubleValue(gunDataTag, "Heat")
        shootAnimationTimer = IntValue(gunDataTag, "ShootAnimationTimer")
        shootTimer = IntValue(gunDataTag, "ShootTimer")
        overHeat = BooleanValue(gunDataTag, "OverHeat")
        zooming = BooleanValue(gunDataTag, "Zooming")

        var defaultFireMode = get(GunProp.DEFAULT_FIRE_MODE)

        val fireModes = get(AVAILABLE_FIRE_MODES)
        for (i in fireModes.indices) {
            if (fireModes[i].name == defaultFireMode) {
                selectedFireMode.defaultValue = i
                break
            }
        }
    }

    companion object {
        private val dataRevision = AtomicInteger()

        @JvmField
        val DATA_CACHE: LoadingCache<ItemStack, GunData> = CacheBuilder.newBuilder()
            .weakKeys()
            .weakValues()
            .build(object : CacheLoader<ItemStack, GunData>() {
                override fun load(stack: ItemStack) = GunData(stack)
            })

        fun invalidateCache() {
            dataRevision.incrementAndGet()
            DATA_CACHE.invalidateAll()
        }

        fun create(item: Item): GunData {
            return from(ItemStack(item))
        }

        @JvmStatic
        @JvmOverloads
        fun from(stack: ItemStack, defaultDataSupplier: (() -> DefaultGunData)? = null): GunData {
            return defaultDataSupplier?.let { GunData(stack, it) } ?: DATA_CACHE.getUnchecked(stack)
        }

        @JvmOverloads
        @JvmStatic
        fun <T> get(stack: ItemStack, prop: GunProp<*, T>, useCache: Boolean = true): T {
            return from(stack).get(prop)
        }

        @JvmStatic
        fun getDefault(id: String): DefaultGunData {
            val isDefault = !com.atsuishio.superbwarfare.data.CustomData.GUN_DATA.containsKey(id)
            val data = com.atsuishio.superbwarfare.data.CustomData.GUN_DATA.getOrElseGet(id) { DefaultGunData() }
            data.isDefaultData = isDefault
            return data
        }

        fun getDefault(stack: ItemStack): DefaultGunData {
            return getDefault(stack.item)
        }

        fun getDefault(item: Item): DefaultGunData {
            return getDefault(getRegistryId(item))
        }

        fun getRegistryId(item: Item): String {
            var id = item.descriptionId
            id = id.substring(id.indexOf(".") + 1).replace('.', ':')
            return id
        }

        fun getPerkPriority(s: String): Int {
            if (s.isEmpty()) return 2

            return when (s[0]) {
                '@' -> 0
                '!' -> 2
                else -> 1
            }
        }
    }

    override fun hashCode() = 31 * stack.item.hashCode() +
        (stack.tag?.takeUnless { it.isEmpty }?.hashCode() ?: 0)
}
