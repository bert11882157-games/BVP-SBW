package com.atsuishio.superbwarfare.client.renderer;

import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.junit.jupiter.api.Test;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import static org.junit.jupiter.api.Assertions.*;

/** Executes the production projection callback without starting Minecraft or a render context. */
public class FarTerrainProjectionTest {
    public static final class Radius {
        public static int value;
        public static int radius() { return value; }
    }

    private Matrix4f apply(Matrix4f original, int radius) throws Exception {
        String name = "com.atsuishio.superbwarfare.mixins.FarTerrainProjectionMixin";
        ClassNode node = new ClassNode();
        try (var stream = getClass().getClassLoader().getResourceAsStream(name.replace('.', '/') + ".class")) {
            assertNotNull(stream);
            new ClassReader(stream).accept(node, 0);
        }
        node.access &= ~Opcodes.ACC_ABSTRACT;
        int substitutions = 0;
        for (var method : node.methods) for (var instruction : method.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(
                    "com/atsuishio/superbwarfare/client/FarTerrainClient") && call.name.equals("radius")) {
                call.owner = Radius.class.getName().replace('.', '/');
                substitutions++;
            }
        }
        assertEquals(1, substitutions);
        ClassWriter writer = new ClassWriter(0);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        Class<?> type = new ClassLoader(getClass().getClassLoader()) {
            Class<?> define() { return defineClass(name, bytes, 0, bytes.length); }
        }.define();
        var method = type.getDeclaredMethod("sbw$terrainProjection", double.class, CallbackInfoReturnable.class);
        method.setAccessible(true);
        Radius.value = radius;
        CallbackInfoReturnable<Matrix4f> result = new CallbackInfoReturnable<>("getProjectionMatrix", true, original);
        method.invoke(type.getConstructor().newInstance(), 70.0, result);
        return result.getReturnValue();
    }

    @Test void sharedProjectionPreservesNearTerrainDepthInFrontOfDistantVehicles() throws Exception {
        Matrix4f original = new Matrix4f().perspective((float) Math.toRadians(70), 1.7F, 0.05F, 256F);
        Matrix4f shared = apply(original, 768);
        assertEquals(original.m00(), shared.m00());
        assertEquals(original.m11(), shared.m11());
        assertEquals(0.05F, shared.m32() / (shared.m22() - 1F), 0.00001F);
        for (float cover : new float[]{5, 120, 300, 650}) {
            Vector4f a = shared.transform(new Vector4f(0, 0, -cover, 1));
            Vector4f b = shared.transform(new Vector4f(0, 0, -(cover + 50), 1));
            assertTrue(a.z / a.w < b.z / b.w, "Cover must win the shared depth test");
        }
        assertSame(original, apply(original, 0));
    }
}
