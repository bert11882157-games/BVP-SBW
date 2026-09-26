package com.yourname.berts_vehicle_pack.client.renderer;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import org.joml.Matrix3f;
import org.joml.Matrix4f;
import org.joml.Vector3f;

/**
 * Diffuse lighting for the mesh loader's direct VBO draws.
 *
 * <p>The loader's VBOs hold model-local normals and are drawn with the vanilla entity shader, which lights the
 * normal attribute against view-space light directions (the buffered path multiplies normals by the pose normal
 * matrix first). Uncorrected, whole sides of a vehicle go dark or black and the shading shifts with the camera;
 * this showed while a model faded in after loading and whenever the instanced path was not used. Around each VBO
 * draw the two level lights are expressed in that mesh's own frame, then restored.</p>
 */
public final class BvpVboLighting {
    private static final Vector3f LEVEL_0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    private static final Vector3f LEVEL_1 = new Vector3f(-0.2F, 1.0F, 0.7F).normalize();
    private static final Vector3f NETHER_0 = new Vector3f(0.2F, 1.0F, -0.7F).normalize();
    private static final Vector3f NETHER_1 = new Vector3f(-0.2F, -1.0F, 0.7F).normalize();

    private static final Vector3f VIEW_0 = new Vector3f();
    private static final Vector3f VIEW_1 = new Vector3f();
    private static final Vector3f MODEL_0 = new Vector3f();
    private static final Vector3f MODEL_1 = new Vector3f();
    private static final Matrix3f VIEW = new Matrix3f();
    private static final Matrix3f MODEL = new Matrix3f();
    private static boolean active;
    /** 0 = normal level lighting; toward 1 the lights swing round to the viewer, flattening the shading. */
    private static float flat;
    private static final Vector3f VIEWER = new Vector3f();

    /**
     * Munition models (low-poly Blockbench stores and projectiles) band badly under directional lighting: while
     * {@code amount} is set, the lights point mostly at the viewer so faces the camera sees are evenly lit
     * (0.9 removes about 90% of the shading). Returns the previous value for {@link #setFlat} to restore.
     */
    public static float setFlat(float amount) {
        float previous = flat;
        flat = Math.max(0F, Math.min(1F, amount));
        return previous;
    }

    private BvpVboLighting() { }

    /** Before a VBO draw with {@code modelView} (the full pose the loader passes as ModelViewMat). */
    public static void enter(Matrix4f modelView) {
        if (modelView == null) return; // re-entry after a failed draw simply recomputes
        Minecraft minecraft = Minecraft.m_91087_();
        if (minecraft.f_91073_ == null) return;
        boolean nether = minecraft.f_91073_.m_104583_().m_108885_();
        // Level lights in view space, as Lighting.setupLevel/setupNetherLevel put them there this frame.
        VIEW.set(RenderSystem.getInverseViewRotationMatrix()).transpose();
        VIEW_0.set(nether ? NETHER_0 : LEVEL_0).mul(VIEW);
        VIEW_1.set(nether ? NETHER_1 : LEVEL_1).mul(VIEW);
        // Into the mesh frame: transpose of the pose's rotation part (uniform scale is removed by normalising).
        modelView.get3x3(MODEL).transpose();
        MODEL_0.set(VIEW_0).mul(MODEL);
        MODEL_1.set(VIEW_1).mul(MODEL);
        if (flat > 0F) {
            // View space looks down -Z: toward the viewer is +Z, carried into the mesh frame like the lights.
            VIEWER.set(0F, 0F, 1F).mul(MODEL);
            if (VIEWER.lengthSquared() > 1.0E-12F) {
                VIEWER.normalize();
                MODEL_0.normalize().lerp(VIEWER, flat);
                MODEL_1.normalize().lerp(VIEWER, flat);
            }
        }
        if (!(MODEL_0.lengthSquared() > 1.0E-12F) || !(MODEL_1.lengthSquared() > 1.0E-12F)) return;
        MODEL_0.normalize();
        MODEL_1.normalize();
        RenderSystem.setShaderLights(MODEL_0, MODEL_1);
        active = true;
    }

    /** After the draw: back to the level's view-space lights for everything drawn next. */
    public static void exit() {
        if (!active) return;
        active = false;
        RenderSystem.setShaderLights(VIEW_0, VIEW_1);
    }
}
