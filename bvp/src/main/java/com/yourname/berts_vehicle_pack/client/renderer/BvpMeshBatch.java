package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.api.performance.ClientRenderPerformanceDiagnostics;
import com.example.sbwmeshloader.core.PolyMesh;
import com.mojang.blaze3d.platform.Window;
import com.mojang.blaze3d.shaders.Uniform;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferUploader;
import com.mojang.blaze3d.vertex.VertexBuffer;
import com.yourname.berts_vehicle_pack.mixin.BvpPolyMeshDrawAccessor;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.ShaderInstance;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;

/**
 * Batched direct VBO drawing for the mesh loader's opaque pass.
 *
 * <p>The loader drew every bone mesh with {@code VertexBuffer.drawWithShader}: twelve sampler lookups (each
 * building a "Sampler"+i string), every uniform set, the program and all samplers bound ({@code apply}), the draw,
 * then everything unbound again ({@code clear}), plus a VAO query and vertex-attribute save/restore. A tracked
 * vehicle has a few hundred bone meshes (links, wheels, ERA, hatches) and every hung store is its own model, so a
 * busy scene paid that setup thousands of times per frame on the render thread.</p>
 *
 * <p>Within one pass nothing of that changes except the model-view matrix, the mesh-space light directions
 * ({@link BvpVboLighting}) and the packed light. {@link #begin} does the shared part once, {@link #draw} uploads just
 * those three and draws, {@link #end} unbinds and restores the VAO and light attribute once. The pixels are the
 * same as the unbatched path. {@code -Dbvp.render.unbatched=true} (or {@link #setEnabled}) restores the old path,
 * for comparisons.</p>
 */
public final class BvpMeshBatch {
    private static final String[] SAMPLERS = new String[12];
    private static final int LIGHT_ATTRIBUTE = 4;
    private static final int[] SAVED_LIGHT = new int[4];
    private static boolean enabled = !Boolean.getBoolean("bvp.render.unbatched");

    private static ShaderInstance shader;
    private static Uniform modelView;
    private static Uniform light0;
    private static Uniform light1;
    private static int savedVao;
    private static int lastLight;

    /** Counters for the performance probe: passes opened and meshes drawn batched. */
    public static long passes;
    public static long draws;

    static {
        for (int i = 0; i < SAMPLERS.length; i++) SAMPLERS[i] = "Sampler" + i;
    }

    private BvpMeshBatch() { }

    public static boolean isEnabled() { return enabled; }

    public static void setEnabled(boolean value) { enabled = value; }

    /** After the pass's render state is set up, before the bone walk. */
    public static void begin() {
        end();
        if (!enabled || ClientRenderPerformanceDiagnostics.isBatchingDisabled()) return;
        ShaderInstance current = RenderSystem.getShader();
        if (current == null) return;
        RenderSystem.assertOnRenderThread();
        savedVao = GL11.glGetInteger(GL30.GL_VERTEX_ARRAY_BINDING);
        GL30.glGetVertexAttribIiv(LIGHT_ATTRIBUTE, GL20.GL_CURRENT_VERTEX_ATTRIB, SAVED_LIGHT);
        // Everything VertexBuffer.drawWithShader sets, except the model-view matrix and lights (per mesh).
        for (int i = 0; i < SAMPLERS.length; i++) current.setSampler(SAMPLERS[i], RenderSystem.getShaderTexture(i));
        if (current.PROJECTION_MATRIX != null) current.PROJECTION_MATRIX.set(RenderSystem.getProjectionMatrix());
        if (current.INVERSE_VIEW_ROTATION_MATRIX != null)
            current.INVERSE_VIEW_ROTATION_MATRIX.set(RenderSystem.getInverseViewRotationMatrix());
        if (current.COLOR_MODULATOR != null) current.COLOR_MODULATOR.set(RenderSystem.getShaderColor());
        if (current.GLINT_ALPHA != null) current.GLINT_ALPHA.set(RenderSystem.getShaderGlintAlpha());
        if (current.FOG_START != null) current.FOG_START.set(RenderSystem.getShaderFogStart());
        if (current.FOG_END != null) current.FOG_END.set(RenderSystem.getShaderFogEnd());
        if (current.FOG_COLOR != null) current.FOG_COLOR.set(RenderSystem.getShaderFogColor());
        if (current.FOG_SHAPE != null) current.FOG_SHAPE.set(RenderSystem.getShaderFogShape().getIndex());
        if (current.TEXTURE_MATRIX != null) current.TEXTURE_MATRIX.set(RenderSystem.getTextureMatrix());
        if (current.GAME_TIME != null) current.GAME_TIME.set(RenderSystem.getShaderGameTime());
        if (current.SCREEN_SIZE != null) {
            Window window = Minecraft.getInstance().getWindow();
            current.SCREEN_SIZE.set((float) window.getWidth(), (float) window.getHeight());
        }
        RenderSystem.setupShaderLights(current);
        current.apply();
        shader = current;
        modelView = current.MODEL_VIEW_MATRIX;
        light0 = current.LIGHT0_DIRECTION;
        light1 = current.LIGHT1_DIRECTION;
        lastLight = Integer.MIN_VALUE;
        passes++;
        ClientRenderPerformanceDiagnostics.recordBatchPass();
    }

    /** One bone mesh with its full pose; outside a batch this is the loader's own draw. */
    public static void draw(PolyMesh mesh, Matrix4f pose, int packedLight) {
        ShaderInstance current = shader;
        if (current == null) {
            mesh.drawVBO(pose, packedLight);
            return;
        }
        VertexBuffer vbo = ((BvpPolyMeshDrawAccessor) mesh).bvp$geometryVbo();
        if (vbo == null || vbo.isInvalid()) return;
        BvpVboLighting.enter(pose);
        RenderSystem.setupShaderLights(current);
        if (light0 != null) light0.upload();
        if (light1 != null) light1.upload();
        if (modelView != null) {
            modelView.set(pose);
            modelView.upload();
        }
        vbo.bind();
        if (packedLight != lastLight) {
            GL30.glVertexAttribI2i(LIGHT_ATTRIBUTE, packedLight & 0xFFFF, (packedLight >>> 16) & 0xFFFF);
            lastLight = packedLight;
        }
        vbo.draw();
        draws++;
        ClientRenderPerformanceDiagnostics.recordPolyMeshDraw();
    }

    /** Before the pass clears its render state (normal and exceptional exit). */
    public static void end() {
        ShaderInstance current = shader;
        if (current == null) return;
        shader = null;
        modelView = null;
        light0 = null;
        light1 = null;
        try {
            current.clear();
        } finally {
            GL30.glVertexAttribI4i(LIGHT_ATTRIBUTE, SAVED_LIGHT[0], SAVED_LIGHT[1], SAVED_LIGHT[2], SAVED_LIGHT[3]);
            GL30.glBindVertexArray(savedVao);
            BufferUploader.invalidate();
            BvpVboLighting.exit();
        }
    }
}
