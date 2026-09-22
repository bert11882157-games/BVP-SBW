package com.yourname.berts_vehicle_pack.client.renderer;

import com.mojang.blaze3d.vertex.DefaultVertexFormat;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderStateShard;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.client.event.RenderLevelStageEvent;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;

/** Deterministic layered muzzle bloom, core flash, and shrinking forward sparks. */
public final class BvpMuzzleFlashRenderer {
    private static final int FULL_BRIGHT = 0x00F000F0;
    private static final float FLASH_TICKS = 1.0F;
    private static final float SPARK_TICKS = 7.0F;
    private static final int MAX_BURSTS = 256;
    private static final Vec3 WORLD_UP = new Vec3(0.0D, 1.0D, 0.0D);
    private static final Vec3 WORLD_X = new Vec3(1.0D, 0.0D, 0.0D);
    private static final BurstProfile TANK_CANNON =
            new BurstProfile(1.5D, 0.10D, 6, 0.060D, 0.75D, 0.10D);
    private static final BurstProfile AUTOCANNON =
            new BurstProfile(0.45D, 0.08D, 3, 0.040D, 0.55D, 0.08D);
    private static final BurstProfile PASSENGER_HMG =
            new BurstProfile(0.225D, 0.05D, 2, 0.025D, 0.40D, 0.06D);
    private static final BurstProfile COAX =
            new BurstProfile(0.1125D, 0.03D, 1, 0.015D, 0.30D, 0.04D);
    private static final RenderType[] FLASH_RENDER_TYPES = new RenderType[]{
            flashType("muzzle_flash_1"),
            flashType("muzzle_flash_2"),
            flashType("muzzle_flash_3"),
            flashType("muzzle_flash_4")
    };
    private static final RenderType SPARK_RENDER_TYPE =
            MuzzleRenderType.create("bvp_muzzle_spark",
                    new ResourceLocation(BertsVehiclePack.MODID, "textures/particle/fm_flame.png"));
    private static final List<MuzzleBurst> BURSTS = new ArrayList<>();
    private static ClientLevel activeLevel;

    private BvpMuzzleFlashRenderer() {
    }

    public static void tick(Minecraft minecraft) {
        syncLevel(minecraft == null ? null : minecraft.f_91073_);
    }

    public static void enqueueTankCannonBurst(
            Vec3 position, Vec3 direction, long seed, boolean suppressForwardSpray) {
        enqueue(position, direction, seed, TANK_CANNON, suppressForwardSpray);
    }

    public static void enqueueAutocannonBurst(
            Vec3 position, Vec3 direction, long seed, boolean suppressForwardSpray) {
        enqueue(position, direction, seed, AUTOCANNON, suppressForwardSpray);
    }

    public static void enqueuePassengerHmgBurst(
            Vec3 position, Vec3 direction, long seed, boolean suppressForwardSpray) {
        enqueue(position, direction, seed, PASSENGER_HMG, suppressForwardSpray);
    }

    public static void enqueueCoaxBurst(
            Vec3 position, Vec3 direction, long seed, boolean suppressForwardSpray) {
        enqueue(position, direction, seed, COAX, suppressForwardSpray);
    }

    private static void enqueue(Vec3 position, Vec3 direction, long seed, BurstProfile profile,
                                boolean suppressForwardSpray) {
        Minecraft minecraft = Minecraft.m_91087_();
        if (minecraft == null || minecraft.f_91073_ == null || position == null
                || direction == null || direction.m_82556_() <= 1.0E-8D) {
            return;
        }
        syncLevel(minecraft.f_91073_);
        if (BURSTS.size() >= MAX_BURSTS) {
            BURSTS.remove(0);
        }

        Vec3 forward = direction.m_82541_();
        Vec3 origin = position;
        Random random = new Random(seed);
        List<Spark> sparks = new ArrayList<>(profile.sparkCount);
        for (int i = 0; i < profile.sparkCount; i++) {
            double speed = profile.sparkMaxSpeed * (0.2D + 0.8D * random.nextDouble());
            Vec3 velocity = randomConeVector(forward, profile.sparkSpread, random).m_82490_(speed);
            double halfSize = profile.sparkHalfSize * (0.75D + 0.5D * random.nextDouble());
            if (!suppressForwardSpray) {
                sparks.add(new Spark(origin, velocity, halfSize));
            }
        }
        BURSTS.add(new MuzzleBurst(
                origin,
                minecraft.f_91073_.m_46467_(),
                random.nextInt(FLASH_RENDER_TYPES.length),
                random.nextInt(FLASH_RENDER_TYPES.length),
                profile,
                sparks));
    }

    public static void render(RenderLevelStageEvent event) {
        if (event.getStage() != RenderLevelStageEvent.Stage.AFTER_PARTICLES) {
            return;
        }
        Minecraft minecraft = Minecraft.m_91087_();
        ClientLevel level = minecraft == null ? null : minecraft.f_91073_;
        syncLevel(level);
        if (level == null || BURSTS.isEmpty()) {
            return;
        }

        float renderTick = level.m_46467_() + event.getPartialTick();
        Camera camera = event.getCamera();
        Vec3 cameraPosition = camera.m_90583_();
        Vector3f right = camera.m_252775_();
        Vector3f up = camera.m_253028_();
        MultiBufferSource.BufferSource bufferSource = minecraft.m_91269_().m_110104_();
        PoseStack poseStack = event.getPoseStack();

        poseStack.m_85836_();
        poseStack.m_252880_(
                (float) -cameraPosition.f_82479_,
                (float) -cameraPosition.f_82480_,
                (float) -cameraPosition.f_82481_);
        try {
            PoseStack.Pose pose = poseStack.m_85850_();
            Iterator<MuzzleBurst> iterator = BURSTS.iterator();
            while (iterator.hasNext()) {
                MuzzleBurst burst = iterator.next();
                float age = renderTick - burst.spawnTick;
                if (age < 0.0F) {
                    continue;
                }

                boolean flashVisible = age < FLASH_TICKS || !burst.flashRendered;
                if (flashVisible) {
                    float flashLife = !burst.flashRendered
                            ? 1.0F
                            : 1.0F - age / FLASH_TICKS;
                    texturedQuad(
                            bufferSource.m_6299_(FLASH_RENDER_TYPES[burst.bloomFrame]),
                            pose,
                            burst.position,
                            right,
                            up,
                            burst.profile.bloomHalfSize,
                            0.68F * flashLife);
                    texturedQuad(
                            bufferSource.m_6299_(FLASH_RENDER_TYPES[burst.coreFrame]),
                            pose,
                            burst.position,
                            right,
                            up,
                            burst.profile.coreHalfSize,
                            flashLife);
                    burst.flashRendered = true;
                }

                if (age < SPARK_TICKS) {
                    float remaining = 1.0F - age / SPARK_TICKS;
                    VertexConsumer sparkConsumer = bufferSource.m_6299_(SPARK_RENDER_TYPE);
                    for (Spark spark : burst.sparks) {
                        Vec3 sparkPosition = spark.position.m_82549_(spark.velocity.m_82490_(age));
                        texturedQuad(
                                sparkConsumer,
                                pose,
                                sparkPosition,
                                right,
                                up,
                                spark.halfSize * remaining,
                                remaining);
                    }
                } else if (burst.flashRendered) {
                    iterator.remove();
                }
            }
        } finally {
            poseStack.m_85849_();
            for (RenderType renderType : FLASH_RENDER_TYPES) {
                bufferSource.m_109912_(renderType);
            }
            bufferSource.m_109912_(SPARK_RENDER_TYPE);
        }
    }

    private static Vec3 randomConeVector(Vec3 forward, double spread, Random random) {
        Vec3 reference = Math.abs(forward.f_82480_) < 0.95D ? WORLD_UP : WORLD_X;
        Vec3 right = reference.m_82537_(forward).m_82541_();
        Vec3 up = forward.m_82537_(right).m_82541_();
        double angle = random.nextDouble() * Math.PI * 2.0D;
        double radius = Math.sqrt(random.nextDouble()) * spread;
        return forward
                .m_82549_(right.m_82490_(Math.cos(angle) * radius))
                .m_82549_(up.m_82490_(Math.sin(angle) * radius))
                .m_82541_();
    }

    private static RenderType flashType(String path) {
        return MuzzleRenderType.create(
                "bvp_" + path,
                new ResourceLocation(BertsVehiclePack.MODID, "textures/particle/" + path + ".png"));
    }

    private static void syncLevel(ClientLevel level) {
        if (activeLevel == level) {
            return;
        }
        BURSTS.clear();
        activeLevel = level;
    }

    private static void texturedQuad(VertexConsumer consumer, PoseStack.Pose pose, Vec3 center,
                                     Vector3f right, Vector3f up, double halfSize, float alpha) {
        if (!(halfSize > 0.0D) || !(alpha > 0.0F)) {
            return;
        }
        double rightX = (double) right.x() * -halfSize;
        double rightY = (double) right.y() * -halfSize;
        double rightZ = (double) right.z() * -halfSize;
        double upX = (double) up.x() * halfSize;
        double upY = (double) up.y() * halfSize;
        double upZ = (double) up.z() * halfSize;
        Matrix4f matrix = pose.m_252922_();
        Matrix3f normalMatrix = pose.m_252943_();
        texturedVertex(consumer, matrix, normalMatrix,
                center.f_82479_ - rightX - upX, center.f_82480_ - rightY - upY,
                center.f_82481_ - rightZ - upZ, 0.0F, 1.0F, alpha);
        texturedVertex(consumer, matrix, normalMatrix,
                center.f_82479_ + rightX - upX, center.f_82480_ + rightY - upY,
                center.f_82481_ + rightZ - upZ, 1.0F, 1.0F, alpha);
        texturedVertex(consumer, matrix, normalMatrix,
                center.f_82479_ + rightX + upX, center.f_82480_ + rightY + upY,
                center.f_82481_ + rightZ + upZ, 1.0F, 0.0F, alpha);
        texturedVertex(consumer, matrix, normalMatrix,
                center.f_82479_ - rightX + upX, center.f_82480_ - rightY + upY,
                center.f_82481_ - rightZ + upZ, 0.0F, 0.0F, alpha);
    }

    private static void texturedVertex(VertexConsumer consumer, Matrix4f matrix, Matrix3f normalMatrix,
                                       double x, double y, double z, float u, float v, float alpha) {
        consumer.m_252986_(matrix, (float) x, (float) y, (float) z)
                .m_85950_(1.0F, 1.0F, 1.0F, alpha)
                .m_7421_(u, v)
                .m_86008_(OverlayTexture.f_118083_)
                .m_85969_(FULL_BRIGHT)
                .m_252939_(normalMatrix, 0.0F, 1.0F, 0.0F)
                .m_5752_();
    }

    private record BurstProfile(
            double bloomHalfSize,
            double coreHalfSize,
            int sparkCount,
            double sparkHalfSize,
            double sparkMaxSpeed,
            double sparkSpread) {
    }

    private record Spark(Vec3 position, Vec3 velocity, double halfSize) {
    }

    private static final class MuzzleBurst {
        final Vec3 position;
        final float spawnTick;
        final int bloomFrame;
        final int coreFrame;
        final BurstProfile profile;
        final List<Spark> sparks;
        boolean flashRendered;

        MuzzleBurst(Vec3 position, float spawnTick, int bloomFrame, int coreFrame,
                    BurstProfile profile, List<Spark> sparks) {
            this.position = position;
            this.spawnTick = spawnTick;
            this.bloomFrame = bloomFrame;
            this.coreFrame = coreFrame;
            this.profile = profile;
            this.sparks = sparks;
        }
    }

    /** Textured additive render type with alpha-aware blending and no depth writes. */
    private static final class MuzzleRenderType extends RenderType {
        private MuzzleRenderType(String name, VertexFormat format, VertexFormat.Mode mode, int bufferSize,
                                 boolean affectsCrumbling, boolean sortOnUpload,
                                 Runnable setupState, Runnable clearState) {
            super(name, format, mode, bufferSize, affectsCrumbling, sortOnUpload, setupState, clearState);
        }

        private static RenderType create(String name, ResourceLocation texture) {
            return RenderType.m_173215_(
                    name,
                    DefaultVertexFormat.f_85812_,
                    VertexFormat.Mode.QUADS,
                    512,
                    false,
                    false,
                    RenderType.CompositeState.m_110628_()
                            .m_173290_(new RenderStateShard.TextureStateShard(texture, false, false))
                            .m_173292_(f_234323_)
                            .m_110685_(f_110136_)
                            .m_110663_(f_110113_)
                            .m_110661_(f_110110_)
                            .m_110671_(f_110153_)
                            .m_110677_(f_110155_)
                            .m_110675_(f_110126_)
                            .m_110687_(f_110115_)
                            .m_110691_(false));
        }
    }
}
