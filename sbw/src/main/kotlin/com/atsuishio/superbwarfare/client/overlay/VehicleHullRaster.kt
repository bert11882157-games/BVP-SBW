package com.atsuishio.superbwarfare.client.overlay

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import org.joml.Matrix4d
import org.joml.Vector3d
import kotlin.math.*

/** Orthographic hull-only coverage from the authored Bedrock cube or polygon geometry. */
object VehicleHullRaster {
    data class Image(val size: Int, val alpha: ByteArray, val polygonCount: Int,
                     val centerX: Double, val centerZ: Double, val span: Double) {
        fun hudX(modelX: Double): Double = (centerX - modelX) * (size - 4) / span * 36 / size
        fun hudY(modelZ: Double): Double = (modelZ - centerZ) * (size - 4) / span * 36 / size
    }

    fun create(root: JsonObject, size: Int = 192): Image? {
        require(size in 32..256)
        val geometry = root.getAsJsonArray("minecraft:geometry")?.firstOrNull()?.asJsonObject ?: return null
        val bones = geometry.getAsJsonArray("bones")?.map { it.asJsonObject } ?: return null
        if (bones.size > 4096) return null
        val byName = bones.associateBy { it.get("name").asString }
        val hull = listOf("hull", "body", "chassis", "vehicle", "root")
            .firstNotNullOfOrNull { name -> bones.firstOrNull { it.get("name").asString.equals(name, true) } }
            ?: bones.firstOrNull { !it.has("parent") } ?: return null
        val hullName = hull.get("name").asString
        val excluded = Regex("turret|barrel|barell|cannon|gun|wheel|track|weapon|rotor|propeller|missile|rocket|broken|wreck", RegexOption.IGNORE_CASE)
        fun belongs(bone: JsonObject, visited: MutableSet<String> = HashSet()): Boolean {
            val name = bone.get("name").asString
            if (!visited.add(name)) return false
            if (name == hullName) return true
            if (excluded.containsMatchIn(name)) return false
            return bone.get("parent")?.asString?.let { byName[it] }?.let { belongs(it, visited) } ?: false
        }
        fun vector(array: JsonArray?, fallback: Vector3d = Vector3d()): Vector3d =
            if (array == null || array.size() != 3) fallback else
                Vector3d(array[0].asDouble, array[1].asDouble, array[2].asDouble)
        fun rotation(pivot: Vector3d, angles: Vector3d): Matrix4d = Matrix4d().translate(pivot)
            .rotateZYX(Math.toRadians(angles.z), Math.toRadians(angles.y), Math.toRadians(angles.x))
            .translate(-pivot.x, -pivot.y, -pivot.z)
        val transforms = HashMap<String, Matrix4d>()
        fun transform(bone: JsonObject, visited: MutableSet<String> = HashSet()): Matrix4d {
            val name = bone.get("name").asString
            transforms[name]?.let { return Matrix4d(it) }
            require(visited.add(name)) { "Cyclic hull bone hierarchy" }
            val parent = bone.get("parent")?.asString?.let { byName[it] }
            val result = if (parent == null) Matrix4d() else transform(parent, visited)
            result.mul(rotation(vector(bone.getAsJsonArray("pivot")), vector(bone.getAsJsonArray("rotation"))))
            transforms[name] = Matrix4d(result)
            return result
        }
        val polygons = ArrayList<List<Vector3d>>()
        for (bone in bones.filter { belongs(it) }) {
            val matrix = transform(bone)
            val mesh = bone.getAsJsonObject("poly_mesh")
            if (mesh != null) {
                val raw = mesh.getAsJsonArray("positions")
                require(raw.size() <= 1_000_000) { "Hull vertex limit exceeded" }
                val positions = raw.map { matrix.transformPosition(vector(it.asJsonArray)) }
                for (face in mesh.getAsJsonArray("polys")) {
                    val points = face.asJsonArray.map { indices -> positions[indices.asJsonArray[0].asInt] }
                    if (points.size in 3..16) polygons.add(points)
                }
            }
            for (cubeElement in bone.getAsJsonArray("cubes") ?: JsonArray()) {
                val cube = cubeElement.asJsonObject
                val origin = vector(cube.getAsJsonArray("origin"))
                val extent = vector(cube.getAsJsonArray("size"))
                val inflate = cube.get("inflate")?.asDouble ?: 0.0
                val cubeMatrix = Matrix4d(matrix).mul(rotation(vector(cube.getAsJsonArray("pivot")),
                    vector(cube.getAsJsonArray("rotation"))))
                val corners = (0..7).map { index -> cubeMatrix.transformPosition(Vector3d(
                    origin.x + if (index and 1 == 0) -inflate else extent.x + inflate,
                    origin.y + if (index and 2 == 0) -inflate else extent.y + inflate,
                    origin.z + if (index and 4 == 0) -inflate else extent.z + inflate)) }
                for (face in listOf(intArrayOf(0,1,3,2),intArrayOf(4,6,7,5),intArrayOf(0,4,5,1),
                    intArrayOf(2,3,7,6),intArrayOf(0,2,6,4),intArrayOf(1,5,7,3)))
                    polygons.add(face.map { corners[it] })
            }
            require(polygons.size <= 100_000) { "Hull polygon limit exceeded" }
        }
        val points = polygons.flatten()
        if (points.isEmpty() || points.any { !it.isFinite }) return null
        val minX = points.minOf { it.x }; val maxX = points.maxOf { it.x }
        val minZ = points.minOf { it.z }; val maxZ = points.maxOf { it.z }
        val span = max(maxX-minX, maxZ-minZ)
        if (span < 1e-6) return null
        val scale = (size-4)/span
        val cx = (minX+maxX)/2; val cz = (minZ+maxZ)/2
        val mask = ByteArray(size*size)
        // Bedrock renderers reflect authored X; retain that handedness in the overhead view.
        fun project(point: Vector3d) = Pair((cx-point.x)*scale+size/2.0, (point.z-cz)*scale+size/2.0)
        fun edge(a: Pair<Double,Double>, b: Pair<Double,Double>, x: Double, y: Double) =
            (b.first-a.first)*(y-a.second)-(b.second-a.second)*(x-a.first)
        for (polygon in polygons) {
            val projected = polygon.map { project(it) }
            for (i in 1 until projected.size-1) {
                val a=projected[0]; val b=projected[i]; val c=projected[i+1]
                val area=edge(a,b,c.first,c.second)
                if (abs(area)<1e-8) continue
                val left=floor(minOf(a.first,b.first,c.first)).toInt().coerceIn(0,size-1)
                val right=ceil(maxOf(a.first,b.first,c.first)).toInt().coerceIn(0,size-1)
                val top=floor(minOf(a.second,b.second,c.second)).toInt().coerceIn(0,size-1)
                val bottom=ceil(maxOf(a.second,b.second,c.second)).toInt().coerceIn(0,size-1)
                for (y in top..bottom) for (x in left..right) {
                    val xx=x+0.5; val yy=y+0.5
                    if (edge(a,b,xx,yy)*area>=0 && edge(b,c,xx,yy)*area>=0 && edge(c,a,xx,yy)*area>=0)
                        mask[y*size+x]=255.toByte()
                }
            }
        }
        return Image(size,mask,polygons.size,cx,cz,span)
    }
}
