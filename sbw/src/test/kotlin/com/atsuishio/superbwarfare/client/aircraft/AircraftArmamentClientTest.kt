package com.atsuishio.superbwarfare.client.aircraft

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlin.math.abs
import net.minecraft.world.phys.Vec3
import org.joml.Matrix4d
import org.joml.Vector3d
import kotlin.math.cos
import kotlin.math.sin

object AircraftArmamentClientTest {
    private var checks = 0
    @JvmStatic fun main(args: Array<String>) {
        val raw = JsonParser.parseString(Files.readString(Path.of(args[0]))).asJsonObject
        val snapshot = AircraftArmamentSnapshot.decode(raw) ?: error("valid fixture rejected")
        check(snapshot.definition.pairs.size == 2, "independent mirrored pairs")
        check(snapshot.selections.size == 1, "one symmetric selection, not two side values")
        check(snapshot.visibleBones() == setOf("suspended_inner_left", "suspended_inner_right"),
            "global inventory never activates the other pair")
        check(snapshot.allStoreBones().size == 4, "complete neutral inventory")
        check(snapshot.stores.getValue("private_fixture:visual").categoryLabel == "Visual only", "truthful non-AAM label")
        check(snapshot.stores.getValue("private_fixture:aam").visualOnly, "AAM combat absent")
        check(snapshot.stores.values.all { it.model == null }, "no invented fixture model")
        check(snapshot.stores.getValue("private_fixture:aam").item.toString() == "minecraft:firework_rocket", "explicit diagnostic AAM item")
        check(snapshot.stores.getValue("private_fixture:laser").item.toString() == "minecraft:arrow", "explicit diagnostic laser item")
        fun rejects(change: (JsonObject) -> Unit) {
            val malformed = raw.deepCopy(); change(malformed)
            check(AircraftArmamentSnapshot.decode(malformed) == null, "malformed receipt fails atomically")
        }
        rejects { it.remove("Definition") }
        rejects { it.getAsJsonObject("Definition").getAsJsonArray("Pairs")[0].asJsonObject.remove("Name") }
        rejects { it.getAsJsonObject("Presets").add("X".repeat(33), JsonObject()) }
        rejects { it.addProperty("Revision", 1.5) }
        rejects { it.getAsJsonObject("Selections").addProperty("left_only", "private_fixture:aam") }
        rejects { it.getAsJsonObject("Selections").addProperty("inner", "unassigned:store") }
        rejects { it.getAsJsonObject("Stores").getAsJsonObject("private_fixture:aam").addProperty("Category", "INVENTED") }
        rejects { it.getAsJsonObject("Definition").getAsJsonObject("Pod").addProperty("YawLimit", Double.NaN) }
        rejects { it.getAsJsonObject("Definition").getAsJsonArray("Pairs").add(it.getAsJsonObject("Definition").getAsJsonArray("Pairs")[0].deepCopy()) }
        val cache = AircraftArmamentStateCache()
        val id = snapshot.vehicle
        check(cache.receive(raw) != null, "full bootstrap")
        for (key in listOf("CatalogueRevision", "PointRevision")) {
            check(cache.receive(raw.deepCopy().apply { addProperty(key, 1.5) }) == null, "fractional revision rejects")
            check(cache.receive(raw.deepCopy().apply { addProperty(key, "1") }) == null, "string revision rejects")
        }
        val thin = JsonObject().apply {
            addProperty("Vehicle", id.toString()); addProperty("EntityId", 1); addProperty("PointRevision", 2)
            addProperty("ClearPoint", true)
        }
        check(cache.receive(thin)?.snapshot?.point == null, "clear point")
        check(cache.receive(raw)?.snapshot?.point == null, "old full receipt cannot revive point")
        check(cache.get(id)?.pointRevision == 2L, "point revision preserved")
        val public = raw.deepCopy().apply { remove("Epoch") }
        check(cache.receive(public)?.json?.get("Epoch")?.asLong == 99L, "public receipt preserves private epoch")
        check(cache.receive(thin) == null, "duplicate thin is inert")
        check(cache.receive(thin.deepCopy().apply { addProperty("PointRevision", 2.5) }) == null, "fractional thin revision rejects")
        check(cache.receive(thin.deepCopy().apply { addProperty("PointRevision", 3); addProperty("EntityId", 1.5) }) == null,
            "fractional entity identity rejects")
        check(cache.receive(thin.deepCopy().apply { addProperty("PointRevision", 3); addProperty("EntityId", 2) }) == null,
            "wrong entity ID rejects")
        check(cache.get(id)?.snapshot?.point == null, "invalid receipt leaves previous coherent state")
        for (index in 2..AircraftArmamentStateCache.MAX_ENTRIES + 12) {
            val next = raw.deepCopy().apply { addProperty("Vehicle", UUID(0, index.toLong()).toString()) }
            check(cache.receive(next) != null, "bounded cache admission")
        }
        check(cache.size == AircraftArmamentStateCache.MAX_ENTRIES, "hard snapshot cap")
        cache.clear(); check(cache.size == 0, "world/disconnect clear")
        val pod = snapshot.definition.pod!!
        val entryAim = AircraftPodAim()
        for ((min, max, expected) in listOf(Triple(-20F, 90F, 30F), Triple(-90F, 20F, 20F),
                Triple(-60F, -10F, -10F), Triple(45F, 80F, 45F))) {
            entryAim.begin(pod.copy(pitchMin = min, pitchMax = max))
            check(entryAim.yaw == 0F && entryAim.pitch == expected, "initial downward pitch clamps authored limits")
            entryAim.sample(10000.0, 10000.0, 0.5, pod.copy(pitchMin = min, pitchMax = max))
            check(entryAim.pitch == expected, "entry resets cursor without a first-frame jump")
        }
        entryAim.begin(pod.copy(pitchMin = -20F, pitchMax = 90F))
        check(Vec3.directionFromRotation(entryAim.pitch, entryAim.yaw).y < 0, "positive entry pitch looks downward")

        // Native pod frame is T(world+pivot) Ry(-yaw) Rx(pitch) Rz(roll) T(-pivot).
        // Legacy client orbit frame does not cancel pivot or apply roll; it must not position a HULL pod.
        val world = Vector3d(8799.31599, -59.98, 5049.3324)
        val origin = Vector3d(0.0, 1.016941875, 3.462603125)
        val pivot = 2.63569
        for (yaw in doubleArrayOf(0.0, 35.0, -90.0, 180.0)) {
            for (pitch in doubleArrayOf(0.0, -20.0, 30.0)) for (roll in doubleArrayOf(0.0, -45.0, 45.0)) {
                val y = Math.toRadians(yaw); val p = Math.toRadians(pitch); val r = Math.toRadians(roll)
                val native = Matrix4d().translate(world.x, world.y + pivot, world.z)
                    .rotateY(-y).rotateX(p).rotateZ(r).translate(0.0, -pivot, 0.0)
                val body = Matrix4d().translate(world).translate(0.0, pivot, 0.0)
                    .rotateY(Math.PI - y).rotateX(-p).rotateZ(-r).translate(0.0, -pivot, 0.0)
                val cameraPoint = native.transformPosition(Vector3d(origin))
                val renderedPoint = body.transformPosition(Vector3d(-origin.x, origin.y, -origin.z))
                check(cameraPoint.distance(renderedPoint) < 1.0E-9, "pod/native model HULL frame agrees through yaw pitch roll")
                val local = Vec3.directionFromRotation(30F, 0F)
                val clientRay = native.transformDirection(Vector3d(local.x, local.y, local.z)).normalize()
                val serverRay = native.transformDirection(Vector3d(-sin(0.0) * cos(Math.PI / 6),
                    -sin(Math.PI / 6), cos(0.0) * cos(Math.PI / 6))).normalize()
                // Vec3.directionFromRotation uses Minecraft's float lookup-table trig; the server uses doubles.
                check(clientRay.distance(serverRay) < 1.0E-4, "client/server pod ray agrees within native float-trig precision")
            }
        }
        val wrong = Matrix4d().translate(world.x, world.y + pivot, world.z).transformPosition(Vector3d(origin))
        val correct = Matrix4d().translate(world).transformPosition(Vector3d(origin))
        check(abs(wrong.y - correct.y - pivot) < 1.0E-9, "regression witness: legacy orbit frame adds 2.63569 blocks")
        for (fps in intArrayOf(30, 60, 120)) {
            val aim = AircraftPodAim()
            for (frame in 0..fps) aim.sample(frame * 120.0 / fps, frame * 20.0 / fps, 0.5, pod)
            check(abs(aim.yaw - 60F) < 0.001 && abs(aim.pitch - 10F) < 0.001, "frame-rate-independent pod mouse")
            aim.resetCursor(); aim.sample(10000.0, 10000.0, 0.5, pod)
            check(abs(aim.yaw - 60F) < 0.001, "resume never replays missed mouse movement")
            repeat(10) { aim.sample(11000.0 + it * 256, 11000.0 + it * 256, 2.0, pod) }
            check(aim.yaw == 170F && aim.pitch == 20F, "authored gimbal caps")
            aim.reset(); check(aim.yaw == 0F && aim.pitch == 0F, "pod ownership reset")
        }
        for (mask in 0..15) for (scale in doubleArrayOf(0.0, 0.2, 1.0, 10.0, Double.NaN)) {
            val axes = AircraftPodAim.legacyAxes(mask, scale)
            check(axes.x.isFinite() && axes.y.isFinite() && abs(axes.x) <= 2 && abs(axes.y) <= 2,
                "helicopter keyboard axes hard bound")
            if (mask == 0 || mask == 15) check(axes.x == 0F && axes.y == 0F, "release/opposed-key neutral")
        }
        check(AircraftPodAim.legacyAxes(4, 1.0).x == -1F, "left keyboard native axis")
        check(AircraftPodAim.legacyAxes(1, 1.0).y == 1F, "pitch-down keyboard native axis")
        println("PASS $checks aircraft armament parser/cache/pod/input checks")
    }

    private fun check(value: Boolean, label: String) { checks++; if (!value) error(label) }
}
