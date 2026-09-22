package com.atsuishio.superbwarfare.data.gun

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Authored ordered belt. Ammo references own ballistics; Family and labels are presentation
 * metadata, never a second hardcoded balance table or a condition for firing.
 */
@Serializable
data class ProjectileBeltProfile(
    @SerialName("Rounds") val rounds: List<ProjectileBeltRound> = emptyList(),
    @SerialName("Name") val name: String = "",
    @SerialName("Family") val family: ProjectileBeltFamily = ProjectileBeltFamily.GENERIC,
) {
    // Compile at construction/decode, not at first shot. The caller cannot change a live cycle
    // by mutating its source list before the first read. This derived cache is never serialized.
    @Transient
    private val compiled: CompiledProjectileBelt? = CompiledProjectileBelt.compile(rounds, isNamed())

    fun isNamed(): Boolean = name.isNotEmpty()
    fun isKpvtTyped(): Boolean = family == ProjectileBeltFamily.KPVT
    fun isValid(): Boolean = compiled != null
    fun cycleLength(): Int = compiled?.cycleLength ?: 0
    fun roundAt(phase: Int): ProjectileBeltRound? = compiled?.roundAt(phase)
}

/** Bounded immutable random access. Position and round identity are intentionally independent. */
internal class CompiledProjectileBelt private constructor(
    private val orderedShots: List<ProjectileBeltRound>,
) {
    val cycleLength: Int get() = orderedShots.size
    fun roundAt(phase: Int): ProjectileBeltRound = orderedShots[Math.floorMod(phase, cycleLength)]

    companion object {
        fun compile(rounds: List<ProjectileBeltRound>, named: Boolean): CompiledProjectileBelt? {
            if (rounds.isEmpty() || rounds.size > 32) return null
            var length = 0L
            for (round in rounds) {
                if (round.shots !in 1..1024) return null
                if (named && round.ammo == null) return null
                if (round.ammo != null && !validReference(round.ammo)) return null
                length += round.shots.toLong()
                if (length > 4096) return null
            }
            val compiled = ArrayList<ProjectileBeltRound>(length.toInt())
            for (round in rounds) {
                val immutableRound = round.copy()
                repeat(round.shots) { compiled.add(immutableRound) }
            }
            return CompiledProjectileBelt(java.util.Collections.unmodifiableList(compiled))
        }

        private fun validReference(value: String): Boolean =
            value.isNotBlank() && value.length <= 256 && value.none { it.isISOControl() }
    }
}

@Serializable
enum class ProjectileBeltFamily {
    GENERIC,
    M242,
    TWO_A42,
    RH202,
    ZU23,
    WESTERN_127,
    WESTERN_COAX_762,
    RUSSIAN_COAX_762,
    RUSSIAN_127,
    KPVT,
    NR30,
}

@Serializable
data class ProjectileBeltRound(
    @SerialName("Shots")
    val shots: Int = 1,

    /**
     * Exact AmmoConsumer.ammo identity; named belts must provide it so speed, mass/explosion,
     * and projectile profile/penetration are owned by this round rather than a shared gun
     * default.  Anonymous legacy belts may omit it and use the selected consumer.
     */
    @SerialName("Ammo")
    val ammo: String? = null,

    @SerialName("Tracer")
    val tracer: ProjectileBeltTracer = ProjectileBeltTracer.INHERIT,

    /** Stable semantic identity for a round inside a named belt (for diagnostics/authoring). */
    @SerialName("Round")
    val roundId: String? = null,

    /** Optional human-facing label; Russian AP-I(c) uses the explicit API(cermet) label. */
    @SerialName("DisplayLabel")
    val displayLabel: String? = null,
)

@Serializable
enum class ProjectileBeltTracer {
    /** Preserve the referenced projectile profile's tracer presentation. */
    INHERIT,

    /** Explicitly disable both synchronized and native/fallback tracer presentation. */
    NONE,

    /** Disable the synchronized tracer extension for this round only. */
    SUPPRESS,

    /** Enable the synchronized tracer extension and force its RGB to red. */
    RED,

    /** Enable the synchronized tracer extension and force its RGB to green. */
    GREEN,
}

enum class ProjectileBeltResolutionStatus {
    NONE,
    INVALID,
    READY,
}

/** Exact fail-closed reason retained for bounded live diagnostics; never changes belt behavior. */
enum class ProjectileBeltResolutionFailure {
    INVALID_PROFILE,
    INVALID_PHASE,
    EMPTY_CONSUMER_SET,
    BLANK_CONSUMER_IDENTITY,
    DUPLICATE_CONSUMER_IDENTITY,
    MISSING_COMPONENT_TARGET,
}

/** Immutable result of resolving one accepted-shot candidate from a belt phase. */
data class ProjectileBeltResolution(
    val status: ProjectileBeltResolutionStatus,
    val round: ProjectileBeltRound? = null,
    val projectileData: GunData? = null,
    val beltName: String? = null,
    val failure: ProjectileBeltResolutionFailure? = null,
)
