package com.atsuishio.superbwarfare.api.vehicle.pose

import com.atsuishio.superbwarfare.data.vehicle.subdata.VehicleAttachmentRotationChannel
import org.joml.Matrix4d
import java.util.LinkedHashMap
import java.util.LinkedHashSet

data class VehicleAttachmentNode @JvmOverloads constructor(
    val name: String,
    val parentName: String? = null,
    val rotationChannel: VehicleAttachmentRotationChannel? = null,
) {
    init {
        require(name.isNotBlank()) { "Attachment node name cannot be blank" }
        require(parentName == null || parentName.isNotBlank()) { "Attachment parent name cannot be blank" }
        require(parentName != name) { "Attachment node '$name' cannot parent itself" }
        require(rotationChannel == null || parentName == "Turret") {
            "Articulated attachment '$name' must parent the native Turret frame"
        }
    }
}

/**
 * Validated parent graph. Parents may be other data nodes or an explicitly allowed native base frame.
 * Dynamic local transforms are supplied when an immutable view is resolved.
 */
class VehicleAttachmentGraph(
    nodes: Collection<VehicleAttachmentNode>,
    externalParents: Set<String> = emptySet(),
) {
    private data class CompiledNode(
        val name: String,
        val parentIndex: Int,
        val externalParentIndex: Int,
        val rotationChannel: VehicleAttachmentRotationChannel?,
    )

    private val compiledNodes: List<CompiledNode>
    private val externalParentNames: List<String?>
    private val externalParents = externalParents.toSet()
    private val turretPitchFrames = HashSet<String>()
    private var boundLocalTransforms: Map<String, Matrix4d>? = null
    private var orderedLocalTransforms: Array<Matrix4d> = emptyArray()

    init {
        val copy = LinkedHashMap<String, VehicleAttachmentNode>()
        for (node in nodes) {
            require(copy.put(node.name, node) == null) { "Duplicate attachment node: ${node.name}" }
        }
        for (node in copy.values) {
            val parent = node.parentName
            require(parent == null || copy.containsKey(parent) || this.externalParents.contains(parent)) {
                "Attachment node '${node.name}' references missing parent '$parent'"
            }
            validateNoCycle(node, copy)
        }
        val ordered = ArrayList<VehicleAttachmentNode>(copy.size)
        val appended = LinkedHashSet<String>()
        fun append(node: VehicleAttachmentNode) {
            node.parentName?.let(copy::get)?.let(::append)
            if (appended.add(node.name)) ordered.add(node)
        }
        copy.values.forEach(::append)

        val nodeIndices = HashMap<String, Int>(ordered.size)
        ordered.forEachIndexed { index, node -> nodeIndices[node.name] = index }
        val externalNames = ArrayList<String?>()
        val externalIndices = HashMap<String?, Int>()
        fun externalIndex(name: String?): Int = externalIndices.getOrPut(name) {
            externalNames.add(name)
            externalNames.lastIndex
        }
        compiledNodes = ordered.map { node ->
            if (node.rotationChannel == VehicleAttachmentRotationChannel.TURRET_PITCH ||
                node.parentName in turretPitchFrames
            ) turretPitchFrames.add(node.name)
            val parentIndex = node.parentName?.let(nodeIndices::get) ?: -1
            CompiledNode(
                node.name,
                parentIndex,
                if (parentIndex >= 0) -1 else externalIndex(node.parentName),
                node.rotationChannel,
            )
        }
        externalParentNames = externalNames
    }

    /** True for an opted-in pitch pivot and its authored descendants. */
    fun followsTurretPitch(name: String): Boolean = name in turretPitchFrames

    @JvmOverloads
    fun resolve(
        base: VehicleAttachmentSnapshot,
        localTransforms: Map<String, Matrix4d>,
        turretPitchDegrees: Float? = null,
    ): VehicleAttachmentSnapshot {
        if (boundLocalTransforms !== localTransforms) {
            require(compiledNodes.all { localTransforms.containsKey(it.name) }) {
                "A local transform is required for every attachment node"
            }
            boundLocalTransforms = localTransforms
            orderedLocalTransforms = Array(compiledNodes.size) { index ->
                localTransforms.getValue(compiledNodes[index].name)
            }
        }
        val worldMatrices = arrayOfNulls<Matrix4d>(compiledNodes.size)
        val externalMatrices = arrayOfNulls<Matrix4d>(externalParentNames.size)
        val merged = LinkedHashMap<String, VehicleTransformSnapshot>(base.frameCount + compiledNodes.size)
        base.copyFramesTo(merged)
        for (index in compiledNodes.indices) {
            val node = compiledNodes[index]
            val parent = if (node.parentIndex >= 0) {
                worldMatrices[node.parentIndex]
            } else {
                externalMatrices[node.externalParentIndex] ?: run {
                    val parentName = externalParentNames[node.externalParentIndex]
                    val snapshot = if (parentName == null) {
                        base.transform("Vehicle") ?: base.transform("Default")
                    } else {
                        base.transform(parentName)
                    }
                    snapshot?.matrix()?.also { externalMatrices[node.externalParentIndex] = it }
                }
            } ?: continue
            if (node.rotationChannel != null &&
                (turretPitchDegrees == null || !turretPitchDegrees.isFinite())
            ) continue
            val world = Matrix4d(parent).mul(orderedLocalTransforms[index])
            if (node.rotationChannel == VehicleAttachmentRotationChannel.TURRET_PITCH) {
                world.rotateX(Math.toRadians(turretPitchDegrees!!.toDouble()))
            }
            worldMatrices[index] = world
            merged[node.name] = VehicleTransformSnapshot.fromOwnedMatrix(
                node.name,
                base.sequence,
                base.serverTick,
                world,
            )
        }
        return VehicleAttachmentSnapshot.fromSnapshots(base.sequence, base.serverTick, merged)
    }

    private fun validateNoCycle(
        start: VehicleAttachmentNode,
        allNodes: Map<String, VehicleAttachmentNode>
    ) {
        val seen = LinkedHashSet<String>()
        var current: VehicleAttachmentNode? = start
        while (current != null) {
            require(seen.add(current.name)) {
                "Attachment parent cycle detected at '${current.name}'"
            }
            current = current.parentName?.let(allNodes::get)
        }
    }

    companion object {
        /** Frames supplied by every compatible vehicle implementation or guarded by runtime fallback. */
        @JvmField
        val NATIVE_BASE_FRAMES: Set<String> = linkedSetOf(
            "Default",
            "Vehicle",
            "VehicleCustomPitch",
            "VehicleFlat",
            "Turret",
            "Barrel",
            "RoofCoaxPitch",
            "Ags30Pitch",
            "WeaponStation",
            "WeaponStationBarrel",
        )
    }
}
