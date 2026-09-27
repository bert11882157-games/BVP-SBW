package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderBackendContext;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.example.sbwmeshloader.core.PolyMesh;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.github.mcmodderanchor.simplebedrockmodel.v1.common.model.BedrockBone;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.norwood.komodo.client.render.kmodo.KmodoConfig;
import com.yourname.berts_vehicle_pack.mixin.BvpPolyMeshModelPrewarmAccessor;
import com.yourname.berts_vehicle_pack.mixin.BvpPolyMeshVertexAccessor;
import dev.engine_room.flywheel.api.backend.BackendManager;
import dev.engine_room.flywheel.api.material.CardinalLightingMode;
import dev.engine_room.flywheel.api.material.Material;
import dev.engine_room.flywheel.api.model.Model;
import dev.engine_room.flywheel.api.visualization.VisualizationContext;
import dev.engine_room.flywheel.lib.instance.InstanceTypes;
import dev.engine_room.flywheel.lib.instance.TransformedInstance;
import dev.engine_room.flywheel.lib.material.Materials;
import dev.engine_room.flywheel.lib.material.SimpleMaterial;
import dev.engine_room.flywheel.lib.memory.MemoryBlock;
import dev.engine_room.flywheel.lib.model.SimpleQuadMesh;
import dev.engine_room.flywheel.lib.model.SingleMeshModel;
import dev.engine_room.flywheel.lib.vertex.FullVertexView;
import dev.engine_room.flywheel.lib.visual.AbstractEntityVisual;
import net.minecraft.core.Vec3i;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.joml.Vector4fc;
import org.slf4j.Logger;

import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Shares real polygon meshes through Komodo while consuming BVP's current render pose. */
public final class BvpKomodoVehicleVisual extends AbstractEntityVisual<GeoVehicleEntity>
        implements BvpKomodoBridge.Receiver {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final int MAX_CACHED_MODELS = 256;
    private static final int BAKE_PARTS_PER_FRAME = 32;
    private static final long BAKE_NANOS_PER_FRAME = 1_000_000L;
    private static final Map<GeometryKey, Geometry> GEOMETRY = new HashMap<>();
    private static final ArrayDeque<Geometry> BAKING = new ArrayDeque<>();
    private static long budgetFrame = Long.MIN_VALUE;

    static {
        BvpKomodoBridge.geometryReset(BvpKomodoVehicleVisual::clearGeometry);
    }

    private Geometry pendingGeometry;
    private Geometry instanceGeometry;
    private Matrix4f[] transforms = new Matrix4f[0];
    private boolean[] visible = new boolean[0];
    private int[] lights = new int[0];
    private final List<TransformedInstance> instances = new ArrayList<>();
    private boolean[] instanceVisible = new boolean[0];
    private long submittedFrame = Long.MIN_VALUE;
    private long reportedBakeTick = Long.MIN_VALUE;
    private boolean failed;

    public BvpKomodoVehicleVisual(VisualizationContext context, GeoVehicleEntity entity, float partialTick) {
        super(context, entity, partialTick);
        BvpKomodoBridge.attach(entity, this);
        record("VISUAL_CREATED", "adapter", "custom_polymesh");
    }

    @Override
    public synchronized boolean submit(long frame, VehicleRenderBackendContext context, PolyMeshModel model,
                                       Matrix4f modelPose, ResourceLocation texture, int light, float alpha) {
        if (deleted || failed || !enabled()) return false;
        try {
            GeometryKey key = new GeometryKey(model, texture);
            Geometry geometry = GEOMETRY.get(key);
            if (geometry == null) {
                if (GEOMETRY.size() >= MAX_CACHED_MODELS) return false;
                geometry = new Geometry(model, texture);
                GEOMETRY.put(key, geometry);
                if (!geometry.ready()) BAKING.addLast(geometry);
            }
            advanceBakes(frame);
            if (geometry.failure != null) throw new IllegalStateException("Vehicle mesh bake failed", geometry.failure);
            // The existing complete-mesh fade and sorted transparent pass remain authoritative.
            if (!geometry.ready()) {
                long tick = ClientVisualClock.now();
                if (entity.tickCount % 20 == 0 && reportedBakeTick != tick) {
                    reportedBakeTick = tick;
                    record("BAKE_PENDING", "completed_parts", geometry.cursor, "total_parts", geometry.parts.size());
                }
                return false;
            }
            if (alpha < 1.0F) return false;
            if (pendingGeometry != geometry) {
                pendingGeometry = geometry;
                int count = geometry.parts.size();
                transforms = new Matrix4f[count];
                for (int index = 0; index < count; index++) transforms[index] = new Matrix4f();
                visible = new boolean[count];
                lights = new int[count];
            }
            Arrays.fill(visible, false);
            Vec3 anchor = context.getChassisPresentation().getAnchor();
            Vec3i origin = renderOrigin();
            Matrix4f relative = new Matrix4f(context.getPreVehicleAxisPose()).invert().mul(modelPose);
            PoseStack pose = new PoseStack();
            Matrix4f root = pose.m_85850_().m_252922_();
            root.translation((float) (anchor.x - origin.getX()), (float) (anchor.y - origin.getY()),
                    (float) (anchor.z - origin.getZ())).mul(relative);
            pose.m_85850_().m_252943_().set(new Matrix3f(root).invert().transpose());
            for (Node node : geometry.tree) collect(node, pose, light);
            submittedFrame = frame;
            return true;
        } catch (RuntimeException | LinkageError exception) {
            failed = true;
            LOGGER.warn("BVP Komodo mesh submission failed for {}", entity.getUUID(), exception);
            record("ADAPTER_FAILED", "error", exception.toString());
            return false;
        }
    }

    private void collect(Node node, PoseStack pose, int light) {
        BedrockBone bone = node.bone;
        if (!bone.visible) return;
        pose.m_85836_();
        try {
            bone.translateAndRotateAndScale(pose);
            int[] parts = node.parts;
            if (parts.length > 0) {
                Matrix4f current = pose.m_85850_().m_252922_();
                int boneLight = bone.illuminated ? 0x00F000F0 : light;
                for (int index : parts) {
                    transforms[index].set(current);
                    visible[index] = true;
                    lights[index] = boneLight;
                }
            }
            for (Node child : node.children) collect(child, pose, light);
        } finally {
            pose.m_85849_();
        }
    }

    @Override
    public synchronized void flush(long frame) {
        boolean draw = !deleted && !failed && submittedFrame == frame && enabled();
        if (!draw) {
            hideInstances();
            return;
        }
        if (instanceGeometry != pendingGeometry) {
            deleteInstances();
            instanceGeometry = pendingGeometry;
            for (Part part : instanceGeometry.parts) {
                TransformedInstance instance = instancerProvider()
                        .instancer(InstanceTypes.TRANSFORMED, part.mesh.model).createInstance();
                instance.setVisible(false);
                instances.add(instance);
            }
            instanceVisible = new boolean[instances.size()];
        }
        int drawn = 0;
        int changed = 0;
        int poseHash = 1;
        for (int index = 0; index < instances.size(); index++) {
            TransformedInstance instance = instances.get(index);
            boolean show = visible[index];
            if (instanceVisible[index] != show) {
                instance.setVisible(show);
                instanceVisible[index] = show;
            }
            if (!show) continue;
            drawn++;
            Matrix4f transform = transforms[index];
            poseHash = 31 * poseHash + transform.hashCode();
            if (!instance.pose.equals(transform, 0.000001F) || instance.light != lights[index]) {
                instance.setTransform(transform);
                instance.light(lights[index]);
                instance.setChanged();
                changed++;
            }
        }
        if (entity.tickCount <= 100 || entity.tickCount % 20 == 0) {
            record("INSTANCE_FRAME", "frame", frame, "instances", drawn,
                    "unique_meshes", instanceGeometry.meshes.size(), "changed_instances", changed,
                    "vertices", instanceGeometry.vertices, "pose_hash", poseHash);
        }
    }

    private static boolean enabled() {
        return KmodoConfig.flywheelEnabled() && KmodoConfig.rawDrawAllowed()
                && BackendManager.isBackendOn();
    }

    private void hideInstances() {
        for (int index = 0; index < instances.size(); index++) {
            if (instanceVisible[index]) {
                instances.get(index).setVisible(false);
                instanceVisible[index] = false;
            }
        }
    }

    private void deleteInstances() {
        for (TransformedInstance instance : instances) instance.delete();
        instances.clear();
        instanceVisible = new boolean[0];
        instanceGeometry = null;
    }

    @Override
    public synchronized void reset() {
        deleteInstances();
        pendingGeometry = null;
        transforms = new Matrix4f[0];
        visible = new boolean[0];
        lights = new int[0];
        submittedFrame = Long.MIN_VALUE;
        reportedBakeTick = Long.MIN_VALUE;
        failed = false;
    }

    @Override
    protected synchronized void _delete() {
        reset();
        BvpKomodoBridge.detach(entity, this);
    }

    private static void clearGeometry() {
        for (Geometry geometry : GEOMETRY.values()) geometry.close();
        GEOMETRY.clear();
        BAKING.clear();
        budgetFrame = Long.MIN_VALUE;
    }

    /** Render-thread queue: one part per model per round, with a shared time and work bound. */
    private static void advanceBakes(long frame) {
        if (budgetFrame == frame) return;
        budgetFrame = frame;
        long deadline = System.nanoTime() + BAKE_NANOS_PER_FRAME;
        int parts = 0;
        while (!BAKING.isEmpty() && parts < BAKE_PARTS_PER_FRAME
                && (parts == 0 || System.nanoTime() < deadline)) {
            Geometry geometry = BAKING.removeFirst();
            try {
                geometry.bakeNext();
                if (!geometry.ready()) BAKING.addLast(geometry);
            } catch (RuntimeException | LinkageError exception) {
                geometry.failure = exception;
                LOGGER.warn("BVP Komodo polygon mesh could not be prepared", exception);
            }
            parts++;
        }
    }

    private void record(String event, Object... fields) {
        if (!EliteDiagnostics.isClientEnabled()) return;
        Object[] tagged = new Object[fields.length + 4];
        tagged[0] = "vehicle";
        tagged[1] = entity.getUUID();
        tagged[2] = "entity_id";
        tagged[3] = entity.getId();
        System.arraycopy(fields, 0, tagged, 4, fields.length);
        EliteDiagnostics.recordClient(entity.m_9236_().m_46467_(), "komodo_geometry", event, tagged);
    }

    private record GeometryKey(PolyMeshModel model, ResourceLocation texture) { }

    private static final class Geometry {
        final List<BedrockBone> roots = new ArrayList<>();
        final List<Part> parts = new ArrayList<>();
        final Map<BedrockBone, List<Integer>> boneParts = new IdentityHashMap<>();
        /** The bone hierarchy pruned to bones with an opaque mesh in their subtree, part indices resolved. */
        final List<Node> tree = new ArrayList<>();
        final Map<VertexKey, BakedMesh> meshes = new HashMap<>();
        final Material material;
        int cursor;
        long vertices;
        Throwable failure;
        /**
         * One bounding sphere for every part of the vehicle, in the parts' shared mesh space. Flywheel's indirect
         * backend frustum- and occlusion-culls every instance on the GPU by its model's sphere; with each small part
         * (a wheel, a link group, a turret) tested on its own tight sphere, moving running gear and traversing turrets
         * winked out for single frames at close range. A part is now culled only when the whole vehicle would be.
         * Any rigid (or uniformly scaled) pose keeps each part inside its transformed sphere.
         */
        final Vector4f sphere;

        Geometry(PolyMeshModel model, ResourceLocation texture) {
            material = new SimpleMaterial.Builder().copyFrom(Materials.CUTOUT_MIPPED_BLOCK)
                    .cardinalLightingMode(CardinalLightingMode.ENTITY).diffuse(true)
                    .backfaceCulling(false).mipmap(false).texture(texture).build();
            var accessor = (BvpPolyMeshModelPrewarmAccessor) (Object) model;
            var seenRoots = new IdentityHashMap<BedrockBone, Boolean>();
            for (var entry : new TreeMap<>(model.getBoneMap()).entrySet()) {
                BedrockBone bone = entry.getValue();
                BedrockBone root = bone;
                while (root.parent != null) root = root.parent;
                if (seenRoots.put(root, true) == null) roots.add(root);
                if (accessor.bvp$translucentBones().contains(bone)) continue;
                List<PolyMesh> boneMeshes = accessor.bvp$meshes().get(bone);
                if (boneMeshes == null) continue;
                List<Integer> indices = boneParts.computeIfAbsent(bone, ignored -> new ArrayList<>());
                for (PolyMesh mesh : boneMeshes) {
                    indices.add(parts.size());
                    parts.add(new Part(entry.getKey(), mesh));
                    vertices += mesh.getVertexCount();
                }
            }
            sphere = vehicleSphere(parts);
            var live = new IdentityHashMap<BedrockBone, Boolean>();
            for (BedrockBone bone : boneParts.keySet())
                for (BedrockBone b = bone; b != null && live.put(b, Boolean.TRUE) == null; b = b.parent) { }
            for (BedrockBone root : roots) {
                Node node = Node.build(root, live, boneParts);
                if (node != null) tree.add(node);
            }
        }

        void bakeNext() {
            Part part = parts.get(cursor);
            VertexKey key = new VertexKey(part.source);
            part.mesh = meshes.get(key);
            if (part.mesh == null) {
                part.mesh = bake(key, material, part.name, sphere);
                meshes.put(key, part.mesh);
            }
            cursor++;
        }

        boolean ready() { return cursor == parts.size(); }

        void close() {
            for (BakedMesh mesh : meshes.values()) mesh.memory.free();
            meshes.clear();
        }
    }

    private static final class Node {
        final BedrockBone bone;
        final int[] parts;
        final Node[] children;

        private Node(BedrockBone bone, int[] parts, Node[] children) {
            this.bone = bone;
            this.parts = parts;
            this.children = children;
        }

        static Node build(BedrockBone bone, Map<BedrockBone, Boolean> live, Map<BedrockBone, List<Integer>> boneParts) {
            if (!live.containsKey(bone)) return null;
            List<Node> kids = new ArrayList<>();
            for (BedrockBone child : bone.getChildren()) {
                Node node = build(child, live, boneParts);
                if (node != null) kids.add(node);
            }
            List<Integer> indices = boneParts.get(bone);
            int[] parts = indices == null ? new int[0] : indices.stream().mapToInt(Integer::intValue).toArray();
            return new Node(bone, parts, kids.toArray(new Node[0]));
        }
    }

    private static final class Part {
        final String name;
        final PolyMesh source;
        BakedMesh mesh;

        Part(String name, PolyMesh source) {
            this.name = name;
            this.source = source;
        }
    }

    private record BakedMesh(Model model, MemoryBlock memory) { }

    /** Exact vertex equality shares repeated links without merging merely similar geometry. */
    private static final class VertexKey {
        final float[][] values;
        final int hash;

        VertexKey(PolyMesh source) {
            var vertices = (BvpPolyMeshVertexAccessor) (Object) source;
            values = new float[][]{vertices.bvp$positionsX(), vertices.bvp$positionsY(),
                    vertices.bvp$positionsZ(), vertices.bvp$normalsX(), vertices.bvp$normalsY(),
                    vertices.bvp$normalsZ(), vertices.bvp$textureU(), vertices.bvp$textureV()};
            hash = Arrays.deepHashCode(values);
        }

        @Override
        public int hashCode() { return hash; }

        @Override
        public boolean equals(Object other) {
            return other instanceof VertexKey key && Arrays.deepEquals(values, key.values);
        }
    }

    private static Vector4f vehicleSphere(List<Part> parts) {
        float minX = Float.POSITIVE_INFINITY, minY = Float.POSITIVE_INFINITY, minZ = Float.POSITIVE_INFINITY;
        float maxX = Float.NEGATIVE_INFINITY, maxY = Float.NEGATIVE_INFINITY, maxZ = Float.NEGATIVE_INFINITY;
        for (Part part : parts) {
            var v = (BvpPolyMeshVertexAccessor) (Object) part.source;
            float[] xs = v.bvp$positionsX(), ys = v.bvp$positionsY(), zs = v.bvp$positionsZ();
            for (int i = 0; i < xs.length; i++) {
                minX = Math.min(minX, xs[i]); maxX = Math.max(maxX, xs[i]);
                minY = Math.min(minY, ys[i]); maxY = Math.max(maxY, ys[i]);
                minZ = Math.min(minZ, zs[i]); maxZ = Math.max(maxZ, zs[i]);
            }
        }
        if (!(minX <= maxX)) return null;
        float cx = (minX + maxX) * 0.5F, cy = (minY + maxY) * 0.5F, cz = (minZ + maxZ) * 0.5F;
        float r2 = 0.0F;
        for (Part part : parts) {
            var v = (BvpPolyMeshVertexAccessor) (Object) part.source;
            float[] xs = v.bvp$positionsX(), ys = v.bvp$positionsY(), zs = v.bvp$positionsZ();
            for (int i = 0; i < xs.length; i++) {
                float dx = xs[i] - cx, dy = ys[i] - cy, dz = zs[i] - cz;
                r2 = Math.max(r2, dx * dx + dy * dy + dz * dz);
            }
        }
        return new Vector4f(cx, cy, cz, (float) Math.sqrt(r2) * 1.02F + 1.0E-3F);
    }

    /** A single-mesh model whose culling sphere is the whole vehicle's (see {@link Geometry#sphere}). */
    private record VehicleModel(List<Model.ConfiguredMesh> meshes, Vector4fc boundingSphere) implements Model { }

    private static BakedMesh bake(VertexKey key, Material material, String name, Vector4f sphere) {
        int count = key.values[0].length;
        MemoryBlock memory = MemoryBlock.mallocTracked((long) FullVertexView.STRIDE * count);
        try {
            FullVertexView view = new FullVertexView();
            view.ptr(memory.ptr());
            view.vertexCount(count);
            for (int index = 0; index < count; index++) {
                view.x(index, key.values[0][index]);
                view.y(index, key.values[1][index]);
                view.z(index, key.values[2][index]);
                view.normalX(index, key.values[3][index]);
                view.normalY(index, key.values[4][index]);
                view.normalZ(index, key.values[5][index]);
                view.u(index, key.values[6][index]);
                view.v(index, key.values[7][index]);
                view.r(index, 1.0F);
                view.g(index, 1.0F);
                view.b(index, 1.0F);
                view.a(index, 1.0F);
                view.overlay(index, OverlayTexture.f_118083_);
                view.light(index, 0);
            }
            SimpleQuadMesh mesh = new SimpleQuadMesh(view, "bvp_vehicle:" + name);
            Model model = sphere == null ? new SingleMeshModel(mesh, material)
                    : new VehicleModel(List.of(new Model.ConfiguredMesh(material, mesh)), new Vector4f(sphere));
            return new BakedMesh(model, memory);
        } catch (RuntimeException | Error exception) {
            memory.free();
            throw exception;
        }
    }
}
