package com.atsuishio.superbwarfare.mixins;

import net.minecraft.client.resources.sounds.SoundInstance;
import net.minecraft.client.resources.sounds.Sound;
import net.minecraft.client.sounds.SoundManager;
import net.minecraft.client.sounds.WeighedSoundEvents;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundSource;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Objects;

/** Executes the compiled production callback, substituting only the diagnostic sink; no world. */
public final class SoundEngineMixinCancellationTest {
    /** Opaque identity only: direct implementation avoids Proxy initializing SoundManager. */
    private static final class ProbeSound implements SoundInstance {
        private AssertionError accessed() { return new AssertionError("Observer accessed sound playback"); }
        public ResourceLocation getLocation() { throw accessed(); }
        public WeighedSoundEvents resolve(SoundManager manager) { throw accessed(); }
        public Sound getSound() { throw accessed(); }
        public SoundSource getSource() { throw accessed(); }
        public boolean isLooping() { throw accessed(); }
        public boolean isRelative() { throw accessed(); }
        public int getDelay() { throw accessed(); }
        public float getVolume() { throw accessed(); }
        public float getPitch() { throw accessed(); }
        public double getX() { throw accessed(); }
        public double getY() { throw accessed(); }
        public double getZ() { throw accessed(); }
        public Attenuation getAttenuation() { throw accessed(); }
    }

    public static final class Observer {
        static boolean enabled;
        static int calls;
        static int recorded;
        static SoundInstance last;
        public static void resolved(SoundInstance instance) {
            // Mirrors Kotlin's non-null parameter check BEFORE the disabled-session return.
            Objects.requireNonNull(instance, "instance");
            calls++; last = instance;
            if (enabled) recorded++;
        }
    }

    private record Callback(Object owner, Method method) {
        void invoke(SoundInstance instance, CallbackInfo info) throws Exception { method.invoke(owner, instance, info); }
    }

    private static Callback callback(boolean oldUnguardedControl) throws Exception {
        String name = "com.atsuishio.superbwarfare.mixins.SoundEngineMixin";
        ClassNode node = new ClassNode();
        try (var stream = SoundEngineMixinCancellationTest.class.getClassLoader()
                .getResourceAsStream(name.replace('.', '/') + ".class")) {
            if (stream == null) throw new AssertionError("Compiled production mixin missing");
            new ClassReader(stream).accept(node, ClassReader.EXPAND_FRAMES);
        }
        node.access &= ~Opcodes.ACC_ABSTRACT; // Only to instantiate the mixin callback in isolation.
        String observer = Observer.class.getName().replace('.', '/');
        int calls = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("superbWarfare$resolvedSound")) continue;
            for (var instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call && call.name.equals("resolved")) {
                    call.owner = observer;
                    calls++;
                }
            }
            if (oldUnguardedControl) {
                method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
                method.instructions.add(new VarInsnNode(Opcodes.ALOAD, 1));
                method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, observer, "resolved",
                        "(Lnet/minecraft/client/resources/sounds/SoundInstance;)V", false));
                method.instructions.add(new InsnNode(Opcodes.RETURN));
            }
        }
        if (calls != 1) throw new AssertionError("Expected exactly one production resolved observer call");
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_FRAMES | ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        byte[] bytes = writer.toByteArray();
        Class<?> type = new ClassLoader(SoundEngineMixinCancellationTest.class.getClassLoader()) {
            Class<?> define() { return defineClass(name, bytes, 0, bytes.length); }
        }.define();
        Method method = type.getDeclaredMethod("superbWarfare$resolvedSound", SoundInstance.class, CallbackInfo.class);
        method.setAccessible(true);
        return new Callback(type.getConstructor().newInstance(), method);
    }

    public static void main(String[] args) throws Exception {
        Callback fixed = callback(false);
        Callback old = callback(true);
        SoundInstance sound = new ProbeSound();
        int checks = 0;
        for (boolean enabled : new boolean[]{false, true}) {
            Observer.enabled = enabled; Observer.calls = 0; Observer.recorded = 0; Observer.last = null;
            CallbackInfo info = new CallbackInfo("play", false);
            fixed.invoke(null, info);
            if (Observer.calls != 0 || Observer.last != null || info.isCancelled()) throw new AssertionError("Cancelled sound reached observer");
            checks++;
            fixed.invoke(sound, info);
            if (Observer.calls != 1 || Observer.last != sound || Observer.recorded != (enabled ? 1 : 0)
                    || info.isCancelled()) throw new AssertionError("Normal playback observation changed");
            checks++;
            for (int i = 0; i < 100; i++) fixed.invoke(null, info);
            if (Observer.calls != 1) throw new AssertionError("Repeated cancellation was observed");
            checks++;
            try {
                old.invoke(null, info);
                throw new AssertionError("Old callback must reproduce the null failure before enabled check");
            } catch (InvocationTargetException expected) {
                if (!(expected.getCause() instanceof NullPointerException)) throw expected;
                checks++;
            }
        }
        System.out.println("PASS " + checks + " callback controls + 200 repeated cancellations; disabled/enabled, unchanged identity, old-failure reproduction");
    }
}
