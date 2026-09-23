package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;

import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderBackend;
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderBackendContext;
import com.atsuishio.superbwarfare.api.performance.ClientRenderPerformanceDiagnostics;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.atsuishio.superbwarfare.api.vehicle.pose.VehiclePoseSnapshot;
import com.atsuishio.superbwarfare.api.vehicle.render.FarVehicleDiagnostics;
import com.atsuishio.superbwarfare.client.FarVehicleClient;
import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderPartSnapshot;
import com.example.sbwmeshloader.core.PolyMeshLoader;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.logging.LogUtils;
import com.mojang.math.Axis;
import com.yourname.berts_vehicle_pack.entity.ArmoredVehicleEntity;
import com.yourname.berts_vehicle_pack.mixin.BvpPolyMeshModelPrewarmAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.resources.ResourceLocation;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.slf4j.Logger;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class BaseVehicleRenderer<T extends GeoVehicleEntity> implements VehicleRenderBackend {
    private final BvpSuspendedStoreRenderer suspendedStores = new BvpSuspendedStoreRenderer();

    protected static long currentResourceGeneration() { return RESOURCE_GENERATION; }
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final boolean DEBUG_MODEL_LOADING = DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.debug.modelLoading");
    private static final int PREWARM_QUEUE_CAPACITY = 8;
    private static final int PREWARM_PACKED_LIGHT = 0x00F000F0;
    private static final int PREWARM_UPLOAD_MESHES_PER_TICK = 8;
    private static final long PREWARM_UPLOAD_NANOS_PER_TICK = 1_000_000L;
    /** Matches TaP's observable first-visible vehicle fade duration without delaying publication. */
    private static final int VEHICLE_FADE_TICKS = 40;
    /** Two independent parsers reduce multi-vehicle queue latency without raising the render-thread upload budget. */
    private static final ExecutorService MODEL_PARSE_EXECUTOR = Executors.newFixedThreadPool(2, task -> {
        Thread thread = new Thread(task, "BVP model parser");
        thread.setDaemon(true);
        return thread;
    });
    private static final ArrayDeque<BaseVehicleRenderer<?>> PREWARM_QUEUE =
            new ArrayDeque<>(PREWARM_QUEUE_CAPACITY);
    private static final Set<BaseVehicleRenderer<?>> INSTANCES =
            Collections.newSetFromMap(new WeakHashMap<BaseVehicleRenderer<?>, Boolean>());
    /** Monotonic client-resource generation; stale parse/upload work may never publish. */
    private static volatile long RESOURCE_GENERATION = 1L;

    private final ResourceLocation modelLocation;
    private final ResourceLocation textureLocation;
    private final ResourceLocation deadTextureLocation;
    private final String debugName;
    /** Atomic render publication: a candidate is visible only after every required mesh upload. */
    private PublishedModel publishedModel;
    private PolyMeshModel pendingModel;
    private CompletableFuture<PolyMeshModel> modelLoadFuture;
    private long modelLoadStartedNanos;
    private long modelLoadGeneration = -1L;
    private boolean modelLoadAttempted;
    private boolean modelLoadQueued;
    private boolean modelLoadFailed;
    private ModelLoadState modelLoadState = ModelLoadState.IDLE;
    private ResourceLocation activeTextureLocation;
    /**
     * One immutable render-part sample is shared by bone animation and debug overlays for the
     * duration of a render transaction.  Keeping this at the renderer boundary prevents armor
     * X-ray from rebuilding turret yaw from a different interpolation phase than the visible
     * turret.
     */
    private VehicleRenderPartSnapshot activeRenderParts;
    private final ProfileWheeledRunningGearAnimator profileWheeledRunningGearAnimator =
            new ProfileWheeledRunningGearAnimator();
    /** Bounded UUID state preserves visibility when vanilla tracking exchanges an entity for a far copy. */
    private final Map<UUID, FadeState> fadeStates = new LinkedHashMap<>(16, 0.75F, true);

    private enum ModelLoadState {
        IDLE,
        QUEUED,
        PARSING,
        UPLOADING,
        READY,
        FAILED
    }

    /** Immutable holder prevents a render from observing a half-published model/generation pair. */
    private record PublishedModel(PolyMeshModel model, long generation) {
    }

    private static final class FadeState {
        private final long generation;
        private final long startTick;
        private final float startPartial;

        private FadeState(long generation, long startTick, float startPartial) {
            this.generation = generation;
            this.startTick = startTick;
            this.startPartial = startPartial;
        }
    }

    protected BaseVehicleRenderer(EntityRendererProvider.Context context, ResourceLocation modelLocation,
                                  ResourceLocation textureLocation,
                                  String debugName) {
        this.modelLocation = modelLocation;
        this.textureLocation = textureLocation;
        this.deadTextureLocation = new ResourceLocation(textureLocation.m_135827_(),
                textureLocation.m_135815_().replace("textures/entity/", "textures/entity_dead/"));
        synchronized (PREWARM_QUEUE) {
            INSTANCES.add(this);
        }
        this.debugName = debugName;
        if (DEBUG_MODEL_LOADING) {
            LOGGER.info("[BERTS_VEHICLE_PACK_DEBUG] {} renderer constructed", debugName);
        }
    }

    protected synchronized PolyMeshModel getOrLoadModel() {
        long generation = RESOURCE_GENERATION;
        PublishedModel published = this.publishedModel;
        if (published != null && published.generation() == generation) {
            this.modelLoadState = ModelLoadState.READY;
            return published.model();
        }
        if (published != null) {
            // A reload can race a render callback on some clients.  Never expose a
            // holder from an older resource generation.
            this.publishedModel = null;
            published.model().close();
        }
        if (this.modelLoadGeneration != generation) {
            discardLoadingState(generation);
        }
        if (this.modelLoadFailed) {
            return null;
        }
        if (!this.modelLoadAttempted && !this.modelLoadQueued) {
            synchronized (PREWARM_QUEUE) {
                if (!this.modelLoadAttempted && !this.modelLoadQueued
                        && PREWARM_QUEUE.size() < PREWARM_QUEUE_CAPACITY) {
                    PREWARM_QUEUE.addLast(this);
                    this.modelLoadQueued = true;
                    this.modelLoadAttempted = true;
                    this.modelLoadGeneration = generation;
                    this.modelLoadState = ModelLoadState.QUEUED;
                }
            }
        }
        return null;
    }

    /**
     * Advances one globally bounded prewarm slice from the client tick. Every renderer that was
     * queued at the start of the slice gets one chance to start/observe its background parse; only
     * completed parses may spend the shared upload-time budget. This removes queue-length latency
     * when several vehicle types enter view together without increasing the render-thread budget.
     */
    public static void prewarmOne(Minecraft minecraft) {
        if (minecraft == null || minecraft.f_91073_ == null) {
            return;
        }
        final int queuedAtStart;
        synchronized (PREWARM_QUEUE) {
            queuedAtStart = PREWARM_QUEUE.size();
        }
        long started = System.nanoTime();
        long deadline = started > Long.MAX_VALUE - PREWARM_UPLOAD_NANOS_PER_TICK
                ? Long.MAX_VALUE : started + PREWARM_UPLOAD_NANOS_PER_TICK;
        for (int index = 0; index < queuedAtStart; index++) {
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0L) {
                break;
            }
            BaseVehicleRenderer<?> renderer;
            synchronized (PREWARM_QUEUE) {
                renderer = PREWARM_QUEUE.pollFirst();
            }
            if (renderer == null) {
                break;
            }
            renderer.markDequeued();
            boolean complete = renderer.advanceQueuedModel(remainingNanos);
            if (!complete) {
                renderer.requeueModelLoad();
            }
        }
    }

    /** Resource reload/context reset entry point; all cached geometry is closed and requeued lazily. */
    public static void onResourceReload() {
        invalidateAllModels();
    }

    /** Level teardown uses the same generation boundary as a resource reload. */
    public static void onLevelUnload() {
        invalidateAllModels();
    }

    /** Retain a visible vehicle's fade while its server-owned far snapshot remains present. */
    public static void onEntityRemoved(GeoVehicleEntity entity) {
        if (entity == null) {
            return;
        }
        final BaseVehicleRenderer<?>[] renderers;
        synchronized (PREWARM_QUEUE) {
            renderers = INSTANCES.toArray(new BaseVehicleRenderer<?>[0]);
        }
        for (BaseVehicleRenderer<?> renderer : renderers) {
            renderer.clearFadeState(entity);
        }
    }

    private static void invalidateAllModels() {
        BvpKomodoBridge.reset();
        AircraftRigAnimator.clear();
        com.atsuishio.superbwarfare.client.renderer.AircraftDetachedWings.clear();
        final long generation;
        final BaseVehicleRenderer<?>[] renderers;
        synchronized (PREWARM_QUEUE) {
            generation = ++RESOURCE_GENERATION;
            PREWARM_QUEUE.clear();
            renderers = INSTANCES.toArray(new BaseVehicleRenderer<?>[0]);
        }
        for (BaseVehicleRenderer<?> renderer : renderers) {
            renderer.resetForGeneration(generation);
        }
    }

    private synchronized void markDequeued() {
        this.modelLoadQueued = false;
    }

    private synchronized void requeueModelLoad() {
        long generation = RESOURCE_GENERATION;
        synchronized (PREWARM_QUEUE) {
            PublishedModel published = this.publishedModel;
            if (this.modelLoadGeneration == generation && !this.modelLoadQueued
                    && (published == null || published.generation() != generation)
                    && !this.modelLoadFailed && this.modelLoadAttempted
                    && PREWARM_QUEUE.size() < PREWARM_QUEUE_CAPACITY) {
                PREWARM_QUEUE.addLast(this);
                this.modelLoadQueued = true;
            }
        }
    }

    private synchronized boolean advanceQueuedModel(long uploadBudgetNanos) {
        long generation = RESOURCE_GENERATION;
        PublishedModel published = this.publishedModel;
        if (published != null && published.generation() == generation) {
            this.modelLoadState = ModelLoadState.READY;
            return true;
        }
        if (this.modelLoadGeneration != generation) {
            discardLoadingState(generation);
            return true;
        }
        if (this.modelLoadFailed || !this.modelLoadAttempted) {
            return true;
        }

        if (this.pendingModel == null && this.modelLoadFuture == null) {
            this.modelLoadStartedNanos = ClientRenderPerformanceDiagnostics.startTimer();
            if (DEBUG_MODEL_LOADING) {
                LOGGER.info("[BERTS_VEHICLE_PACK_DEBUG] {} loading {}", this.debugName, this.modelLocation);
            }
            this.modelLoadFuture = CompletableFuture.supplyAsync(
                    () -> PolyMeshLoader.loadModel(this.modelLocation), MODEL_PARSE_EXECUTOR);
            this.modelLoadState = ModelLoadState.PARSING;
            return false;
        }

        if (this.pendingModel == null) {
            CompletableFuture<PolyMeshModel> future = this.modelLoadFuture;
            if (future == null || !future.isDone()) {
                return false;
            }
            try {
                this.pendingModel = future.join();
            } catch (CancellationException ignored) {
                // Reload/level teardown cancels the worker; a cancelled candidate
                // is never a renderable failure for the new generation.
            } catch (CompletionException exception) {
                LOGGER.warn("[BERTS_VEHICLE_PACK_DEBUG] {} failed to parse {}", this.debugName,
                        this.modelLocation, exception.getCause());
            } catch (RuntimeException exception) {
                LOGGER.warn("[BERTS_VEHICLE_PACK_DEBUG] {} failed to parse {}", this.debugName,
                        this.modelLocation, exception);
            } finally {
                this.modelLoadFuture = null;
            }
            if (generation != RESOURCE_GENERATION) {
                discardLoadingState(RESOURCE_GENERATION);
                return true;
            }
            if (this.pendingModel == null) {
                failModelLoad();
                return true;
            }
            this.modelLoadState = ModelLoadState.UPLOADING;
        }

        if (generation != RESOURCE_GENERATION) {
            discardLoadingState(RESOURCE_GENERATION);
            return true;
        }
        try {
            if (!(this.pendingModel instanceof BvpPolyMeshModelPrewarmAccessor accessor)) {
                LOGGER.warn("[BERTS_VEHICLE_PACK_DEBUG] {} has no bounded upload seam for {}",
                        this.debugName, this.modelLocation);
                this.pendingModel.close();
                this.pendingModel = null;
                failModelLoad();
                return true;
            }
            if (!accessor.bvp$advanceGeometryUpload(PREWARM_PACKED_LIGHT,
                    PREWARM_UPLOAD_MESHES_PER_TICK, Math.max(1L, uploadBudgetNanos))) {
                return false;
            }
        } catch (RuntimeException exception) {
            LOGGER.warn("[BERTS_VEHICLE_PACK_DEBUG] {} failed to upload {}", this.debugName,
                    this.modelLocation, exception);
            this.pendingModel.close();
            this.pendingModel = null;
            failModelLoad();
            return true;
        }

        PolyMeshModel candidate = this.pendingModel;
        this.pendingModel = null;
        if (generation != RESOURCE_GENERATION) {
            candidate.close();
            discardLoadingState(RESOURCE_GENERATION);
            return true;
        }
        PublishedModel previous = this.publishedModel;
        this.publishedModel = new PublishedModel(candidate, generation);
        this.modelLoadState = ModelLoadState.READY;
        this.modelLoadFailed = false;
        if (previous != null && previous.model() != candidate) {
            previous.model().close();
        }
        ClientRenderPerformanceDiagnostics.recordVehicleModelLoad(this.modelLoadStartedNanos, true);
        if (DEBUG_MODEL_LOADING) {
            LOGGER.info("[BERTS_VEHICLE_PACK_DEBUG] {} loaded {}", this.debugName, this.modelLocation);
        }
        return true;
    }

    private synchronized void discardLoadingState(long generation) {
        this.suspendedStores.clear();
        if (this.modelLoadFuture != null) {
            this.modelLoadFuture.cancel(true);
            this.modelLoadFuture = null;
        }
        if (this.pendingModel != null) {
            this.pendingModel.close();
            this.pendingModel = null;
        }
        this.modelLoadGeneration = generation;
        this.modelLoadAttempted = false;
        this.modelLoadQueued = false;
        this.modelLoadFailed = false;
        this.modelLoadState = ModelLoadState.IDLE;
    }

    private synchronized void resetForGeneration(long generation) {
        this.profileWheeledRunningGearAnimator.reset();
        discardLoadingState(generation);
        this.fadeStates.clear();
        PublishedModel previous = this.publishedModel;
        this.publishedModel = null;
        if (previous != null) {
            previous.model().close();
        }
    }

    private synchronized void clearFadeState(GeoVehicleEntity entity) {
        var entry = FarVehicleClient.INSTANCE.getStore().get(entity.getId());
        if (entry == null || !entry.getCurrent().getUuid().equals(entity.getUUID().toString())) {
            this.fadeStates.remove(entity.getUUID());
        }
    }

    private synchronized void failModelLoad() {
        this.modelLoadFailed = true;
        this.modelLoadState = ModelLoadState.FAILED;
        ClientRenderPerformanceDiagnostics.recordVehicleModelLoad(this.modelLoadStartedNanos, false);
        LOGGER.warn("[BERTS_VEHICLE_PACK_DEBUG] {} failed to load {}", this.debugName, this.modelLocation);
    }

    /**
     * Pending models, including those waiting for queue capacity, have no complete mesh to
     * publish. Only a failed load may expose the native fallback geometry.
     */
    private synchronized boolean suppressNativeFallbackDuringLoad() {
        return this.modelLoadGeneration == RESOURCE_GENERATION
                && !this.modelLoadFailed
                && this.modelLoadState != ModelLoadState.READY;
    }

    /**
     * Starts one fade per vehicle only after its complete published holder is visible.
     * The cache is bounded and resource/world generation changes reset every state explicitly.
     */
    private float modelFadeAlpha(GeoVehicleEntity entity, long generation, float partialTicks) {
        float partial = ClientVisualClock.partial(partialTicks);
        long tick = ClientVisualClock.now();
        FadeState state = this.fadeStates.get(entity.getUUID());
        if (state == null || state.generation != generation) {
            this.fadeStates.put(entity.getUUID(), new FadeState(generation, tick, partial));
            if (this.fadeStates.size() > 256) this.fadeStates.remove(this.fadeStates.keySet().iterator().next());
            return 0.0f;
        }
        double age = ClientVisualClock.elapsed(tick, state.startTick, state.startPartial, partial);
        if (!(age > 0.0D)) {
            return 0.0f;
        }
        return Math.min(1.0f, (float) (age / (double) VEHICLE_FADE_TICKS));
    }

    public ResourceLocation m_5478_(T entity) {
        if (this.activeTextureLocation != null) {
            return this.activeTextureLocation;
        }
        if (entity instanceof ArmoredVehicleEntity armored && armored.isWreck()) {
            return this.deadTextureLocation;
        }
        return this.textureLocation;
    }

    @Override
    @SuppressWarnings("unchecked")
    public synchronized boolean render(VehicleRenderBackendContext context) {
        com.atsuishio.superbwarfare.client.overlay.GroundVehicleHullHud.registerModel(
                context.getBackendId(), this.modelLocation);
        if (!(context.getVehicle() instanceof GeoVehicleEntity)) {
            return false;
        }
        T entity = (T) context.getVehicle();
        long performanceStarted = ClientRenderPerformanceDiagnostics.startTimer();
        PolyMeshModel loadedModel = getOrLoadModel();
        if (loadedModel == null) {
            ClientRenderPerformanceDiagnostics.recordVehicleRender(performanceStarted);
            boolean suppressFallback = suppressNativeFallbackDuringLoad();
            recordGeometry(entity, context.getPartialTick(),
                    suppressFallback ? "PENDING_HIDDEN" : "NATIVE_FALLBACK", 0.0F);
            return suppressFallback;
        }

        float entityYaw = context.getEntityYaw();
        float partialTicks = context.getPartialTick();
        PoseStack poseStack = context.getPoseStack();
        MultiBufferSource bufferSource = context.getBufferSource();
        int packedLight = context.getPackedLight();
        PublishedModel activePublished = this.publishedModel;
        float fadeAlpha = activePublished == null
                ? 0.0f
                : modelFadeAlpha(entity, activePublished.generation(), partialTicks);
        ResourceLocation previousTexture = this.activeTextureLocation;
        this.activeTextureLocation = context.getResolvedTexture();
        this.activeRenderParts = VehicleRenderPartSnapshot.capture(entity, entityYaw, partialTicks);
        BvpAircraftBreakupRenderer.Hidden detachedWings = null;
        try {
            applyModelAnimations(entity, entityYaw, loadedModel, partialTicks);
            this.suspendedStores.apply(entity, loadedModel);
            ResourceLocation resolvedTexture = m_5478_(entity);
            detachedWings = BvpAircraftBreakupRenderer.apply(context, loadedModel, this.textureLocation,
                    this.deadTextureLocation, this.suspendedStores);

            // The backend enters after SBW's native vehicleAxis. Restore the
            // renderer-entry matrices so BVP's world-space recoil remains
            // pre-axis and its authored 180-degree frame stays exact.
            restorePreVehicleAxis(context);
            poseStack.m_85836_();
            try {
                applyAdditionalVehicleTransform(entity, partialTicks, poseStack);
                applyVehicleRenderAxis(entity, entityYaw, partialTicks, poseStack,
                        context.getChassisPresentation().getPose());
                boolean instanced = BvpKomodoBridge.submit(context, loadedModel,
                        poseStack.m_85850_().m_252922_(), resolvedTexture, packedLight, fadeAlpha);
                if (!instanced) {
                    loadedModel.renderCutoutOnly(poseStack, bufferSource, resolvedTexture, packedLight,
                            fadeAlpha);
                }
                renderModelSpaceCutout(entity, entityYaw, loadedModel, partialTicks, poseStack,
                        bufferSource, packedLight);
                loadedModel.renderTranslucentOnly(poseStack, bufferSource, resolvedTexture, packedLight,
                        fadeAlpha);
                this.suspendedStores.render(entity, poseStack, bufferSource, packedLight, fadeAlpha);
                recordGeometry(entity, partialTicks, "FULL_MODEL", fadeAlpha);
                if (fadeAlpha > 0.0F) {
                    FarVehicleDiagnostics.modelRendered(entity, partialTicks,
                            context.getChassisPresentation().getAnchor(), fadeAlpha);
                }
                BvpAircraftAfterburnerRenderer.observe(entity);
                renderModelSpaceEffects(entity, entityYaw, loadedModel, partialTicks, poseStack,
                        bufferSource, packedLight);
            } finally {
                poseStack.m_85849_();
            }

            renderDebugOverlays(entity, entityYaw, partialTicks, poseStack, bufferSource,
                    context.getChassisPresentation().getPose());
            return true;
        } finally {
            if (detachedWings != null) detachedWings.restore();
            this.suspendedStores.restore();
            AircraftRigAnimator.restore(loadedModel);
            this.profileWheeledRunningGearAnimator.restoreSteering();
            this.activeRenderParts = null;
            this.activeTextureLocation = previousTexture;
            ClientRenderPerformanceDiagnostics.recordVehicleRender(performanceStarted);
        }
    }

    private void recordGeometry(T entity, float partialTick, String path, float alpha) {
        if (!EliteDiagnostics.isClientEnabled()) return;
        com.yourname.berts_vehicle_pack.diagnostics.BvpVehicleDataDiagnostic.record(entity);
        if (entity.tickCount > 100 && entity.tickCount % 20 != 0) return;
        EliteDiagnostics.recordClient(entity.m_9236_().m_46467_(), "model_lifecycle", "GEOMETRY_DRAW",
                "vehicle", entity.getUUID(), "entity_id", entity.getId(), "entity_age", entity.tickCount,
                "client_tick", ClientVisualClock.now(), "partial_tick", partialTick,
                "path", path, "alpha", alpha, "load_state", this.modelLoadState,
                "resource_generation", RESOURCE_GENERATION, "model", this.modelLocation);
    }

    private static void restorePreVehicleAxis(VehicleRenderBackendContext context) {
        PoseStack.Pose pose = context.getPoseStack().m_85850_();
        Matrix4f poseMatrix = pose.m_252922_();
        Matrix3f normalMatrix = pose.m_252943_();
        poseMatrix.set(context.getPreVehicleAxisPose());
        normalMatrix.set(context.getPreVehicleAxisNormal());
    }

    protected void applyModelAnimations(T entity, float entityYaw, PolyMeshModel loadedModel, float partialTicks) {
        VehicleRenderPartSnapshot renderParts = this.activeRenderParts != null
                ? this.activeRenderParts
                : VehicleRenderPartSnapshot.capture(entity, entityYaw, partialTicks);
        VehicleBonePoseController.apply(entity, entityYaw, loadedModel, partialTicks, renderParts);
        this.profileWheeledRunningGearAnimator.apply(entity, loadedModel, partialTicks);
    }

    protected void renderModelSpaceEffects(T entity, float entityYaw, PolyMeshModel loadedModel, float partialTicks,
                                           PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
        if (entity instanceof ArmoredVehicleEntity armoredEntity) {
            BvpEngineExhaustRenderer.emit(armoredEntity, partialTicks);
        }
    }

    protected void renderModelSpaceCutout(T entity, float entityYaw, PolyMeshModel loadedModel, float partialTicks,
                                          PoseStack poseStack, MultiBufferSource bufferSource, int packedLight) {
    }

    protected void applyAdditionalVehicleTransform(T entity, float partialTicks, PoseStack poseStack) {
    }

    protected void renderDebugOverlays(T entity, float entityYaw, float partialTicks, PoseStack poseStack,
                                       MultiBufferSource bufferSource, VehiclePoseSnapshot presentationPose) {
        if (entity instanceof ArmoredVehicleEntity armoredEntity) {
            BvpMuzzleDebugRenderer.render(armoredEntity, partialTicks, poseStack, bufferSource);
            ArmorDebugRenderer.render(armoredEntity, entityYaw, partialTicks, poseStack, bufferSource,
                    presentationPose, this.activeRenderParts);
        }
    }

    static void applyVehicleRenderAxis(GeoVehicleEntity entity, float entityYaw, float partialTicks,
                                       PoseStack poseStack, VehiclePoseSnapshot extensionPose) {
        float pivotY = (float) entity.getRotateOffsetHeight();
        boolean usesSynchronizedBase = entity.resolveVehiclePoseProvider() != null;
        float basePitch = usesSynchronizedBase
                ? extensionPose.getBasePitchDegrees()
                : entity.getPitch(partialTicks);
        float baseRoll = usesSynchronizedBase
                ? extensionPose.getBaseRollDegrees()
                : entity.getRoll(partialTicks);
        poseStack.m_272245_(Axis.f_252436_.m_252977_(180.0F - entityYaw), 0.0F, pivotY, 0.0F);
        poseStack.m_272245_(Axis.f_252529_.m_252977_(-basePitch), 0.0F, pivotY, 0.0F);
        poseStack.m_272245_(Axis.f_252403_.m_252977_(-baseRoll), 0.0F, pivotY, 0.0F);
        if (!extensionPose.isExtensionIdentity()) {
            poseStack.m_85837_(0.0D, extensionPose.getVerticalOffset(), 0.0D);
            poseStack.m_272245_(Axis.f_252529_.m_252977_(
                    -extensionPose.getPitchDegrees()),
                    0.0F, pivotY, 0.0F);
            poseStack.m_272245_(Axis.f_252403_.m_252977_(
                    -extensionPose.getRollDegrees()),
                    0.0F, pivotY, 0.0F);
        }
    }
}
