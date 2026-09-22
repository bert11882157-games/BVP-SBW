package com.atsuishio.superbwarfare.client.camera;

import java.io.*;
import java.lang.reflect.*;
import java.net.URL;
import java.nio.file.*;
import java.util.*;
import org.objectweb.asm.*;
import org.objectweb.asm.tree.*;
import org.spongepowered.asm.launch.MixinBootstrap;
import org.spongepowered.asm.launch.platform.container.*;
import org.spongepowered.asm.mixin.*;
import org.spongepowered.asm.mixin.transformer.*;
import org.spongepowered.asm.service.*;

/** No-world diagnostic: applies real Mixin 0.8.5 to the installed foreign target. */
public final class CameraOverhaulTransformationTest {
    public static final class Service extends MixinServiceAbstract
            implements IClassProvider, IClassBytecodeProvider, ITransformerProvider, IClassTracker {
        final List<String> applied = new ArrayList<>();
        public String getName() { return "NoWorldCameraTransformation"; }
        public boolean isValid() { return true; }
        public MixinEnvironment.Phase getInitialPhase() { return MixinEnvironment.Phase.DEFAULT; }
        public IClassProvider getClassProvider() { return this; }
        public IClassBytecodeProvider getBytecodeProvider() { return this; }
        public ITransformerProvider getTransformerProvider() { return this; }
        public IClassTracker getClassTracker() { return this; }
        public IMixinAuditTrail getAuditTrail() {
            return new IMixinAuditTrail() {
                public void onApply(String name, String mixin) { applied.add(name + ":" + mixin); }
                public void onPostProcess(String name) { }
                public void onGenerate(String name, String generator) { }
            };
        }
        public Collection<String> getPlatformAgents() { return List.of(); }
        public IContainerHandle getPrimaryContainer() { return new ContainerHandleVirtual("no-world-probe"); }
        public InputStream getResourceAsStream(String name) { return getClass().getClassLoader().getResourceAsStream(name); }
        public URL[] getClassPath() { return new URL[0]; }
        public Class<?> findClass(String name) throws ClassNotFoundException { return findClass(name, false); }
        public Class<?> findClass(String name, boolean init) throws ClassNotFoundException { return Class.forName(name, init, getClass().getClassLoader()); }
        public Class<?> findAgentClass(String name, boolean init) throws ClassNotFoundException { return findClass(name, init); }
        public ClassNode getClassNode(String name) throws ClassNotFoundException, IOException { return getClassNode(name, true); }
        public ClassNode getClassNode(String name, boolean transforms) throws ClassNotFoundException, IOException {
            try (InputStream in = getResourceAsStream(name.replace('.', '/') + ".class")) {
                if (in == null) throw new ClassNotFoundException(name);
                ClassNode node = new ClassNode();
                new ClassReader(in).accept(node, ClassReader.EXPAND_FRAMES);
                if (name.endsWith(".CameraOverhaulCameraSystemMixin")
                        && System.getProperty("camera.probe.priority") != null) {
                    for (AnnotationNode annotation : node.invisibleAnnotations) {
                        if (!annotation.desc.equals("Lorg/spongepowered/asm/mixin/Mixin;")) continue;
                        for (int i = 0; i < annotation.values.size(); i += 2) {
                            if (annotation.values.get(i).equals("priority"))
                                annotation.values.set(i + 1, Integer.getInteger("camera.probe.priority"));
                        }
                    }
                }
                return node;
            }
        }
        public Collection<ITransformer> getTransformers() { return List.of(); }
        public Collection<ITransformer> getDelegatedTransformers() { return List.of(); }
        public void addTransformerExclusion(String name) { }
        public void registerInvalidClass(String name) { }
        public boolean isClassLoaded(String name) { return false; }
        public String getClassRestrictions(String name) { return ""; }
        IMixinTransformer transformer() { return getInternal(IMixinTransformerFactory.class).createTransformer(); }
    }

    public static final class Properties implements IGlobalPropertyService {
        private record Key(String name) implements IPropertyKey { }
        private final Map<IPropertyKey, Object> values = new HashMap<>();
        public IPropertyKey resolveKey(String name) { return new Key(name); }
        @SuppressWarnings("unchecked") public <T> T getProperty(IPropertyKey key) { return (T) values.get(key); }
        public void setProperty(IPropertyKey key, Object value) { values.put(key, value); }
        public <T> T getProperty(IPropertyKey key, T fallback) { T value = getProperty(key); return value == null ? fallback : value; }
        public String getPropertyString(IPropertyKey key, String fallback) { Object value = values.get(key); return value == null ? fallback : value.toString(); }
    }

    public static void main(String[] args) throws Exception {
        MixinService.boot();
        Method getInstance = MixinService.class.getDeclaredMethod("getInstance");
        getInstance.setAccessible(true);
        Object bootstrap = getInstance.invoke(null);
        Service service = new Service();
        for (String name : new String[]{"service", "propertyService"}) {
            Field field = MixinService.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(bootstrap, name.equals("service") ? service : new Properties());
        }
        MixinBootstrap.init();
        MixinEnvironment environment = MixinEnvironment.getDefaultEnvironment().setSide(MixinEnvironment.Side.CLIENT);
        Mixins.addConfiguration("camera-probe-sbw.json");
        if (args.length > 1 && args[1].equals("optimizer")) Mixins.addConfiguration("camera-probe-optimizer.json");
        String target = "mirsario.cameraoverhaul.CameraSystem";
        ClassNode node = service.getClassNode(target);
        boolean changed = service.transformer().transformClass(environment, target, node);
        if (!changed) throw new AssertionError("CameraSystem was not transformed");
        ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS | ClassWriter.COMPUTE_FRAMES);
        node.accept(writer);
        Files.write(Path.of(args[0]), writer.toByteArray());
        int ownershipCalls = 0;
        for (MethodNode method : node.methods) {
            if (!method.name.equals("onCameraUpdate") && !method.name.equals("modifyCameraTransform")) continue;
            List<String> calls = new ArrayList<>();
            for (AbstractInsnNode instruction : method.instructions) {
                if (instruction instanceof MethodInsnNode call) {
                    calls.add(call.name);
                    if (call.name.contains("sbw$")) ownershipCalls++;
                }
            }
            System.out.println(method.name + " call order=" + calls);
            int owner = -1, optimizer = -1;
            for (int i = 0; i < calls.size(); i++) {
                if (calls.get(i).contains("sbw$")) owner = i;
                if (calls.get(i).contains("senkosCameraOptimizer$optimizedUpdate")) optimizer = i;
            }
            if (owner < 0 || (optimizer >= 0 && owner > optimizer))
                throw new AssertionError("Ownership must run before a cancelling foreign updater: " + calls);
        }
        if (ownershipCalls != 2) throw new AssertionError("Expected both ownership handlers, got " + ownershipCalls);
        exerciseTransformedDispatch(writer.toByteArray(), service);
        System.out.println("Applied=" + service.applied);
        System.out.println("PASS actual foreign-target Mixin transformation (not a world/runtime activation proof)");
    }

    private static void exerciseTransformedDispatch(byte[] target, Service service) throws Exception {
        String ownerName = "com.atsuishio.superbwarfare.client.camera.VehicleCameraEffectOwnership";
        ClassNode owner = service.getClassNode(ownerName);
        for (MethodNode method : owner.methods) {
            if (!method.name.equals("ownsMountedCamera")) continue;
            // Only the Minecraft admission predicate is substituted. Execute the production
            // injected handlers, ownership State and installed foreign target with no world.
            method.instructions.clear(); method.tryCatchBlocks.clear(); method.localVariables = null;
            method.instructions.add(new LdcInsnNode("camera.probe.mounted"));
            method.instructions.add(new MethodInsnNode(Opcodes.INVOKESTATIC, "java/lang/Boolean",
                    "getBoolean", "(Ljava/lang/String;)Z", false));
            method.instructions.add(new InsnNode(Opcodes.IRETURN));
            method.maxStack = 1; method.maxLocals = 0;
        }
        ClassWriter ownerWriter = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        owner.accept(ownerWriter);
        ClassLoader loader = new ClassLoader(CameraOverhaulTransformationTest.class.getClassLoader()) {
            @Override protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
                if (!name.equals("mirsario.cameraoverhaul.CameraSystem") && !name.equals(ownerName))
                    return super.loadClass(name, resolve);
                Class<?> found = findLoadedClass(name);
                if (found == null) {
                    byte[] bytes = name.equals(ownerName) ? ownerWriter.toByteArray() : target;
                    found = defineClass(name, bytes, 0, bytes.length);
                }
                if (resolve) resolveClass(found);
                return found;
            }
        };
        Class<?> system = loader.loadClass("mirsario.cameraoverhaul.CameraSystem");
        Class<?> transform = Class.forName("mirsario.cameraoverhaul.utilities.Transform");
        Class<?> context = Class.forName("mirsario.cameraoverhaul.CameraContext");
        Object camera = system.getConstructor().newInstance();
        Field offsetField = system.getDeclaredField("offsetTransform"); offsetField.setAccessible(true);
        Field eulerField = transform.getField("eulerRot");
        org.joml.Vector3d offset = (org.joml.Vector3d) eulerField.get(offsetField.get(camera));
        Object output = transform.getConstructor().newInstance();
        org.joml.Vector3d shown = (org.joml.Vector3d) eulerField.get(output);
        Method apply = system.getMethod("modifyCameraTransform", transform);
        Method update = system.getMethod("onCameraUpdate", context, double.class);
        System.clearProperty("camera.probe.mounted");
        offset.z = 26.8;
        apply.invoke(camera, output);
        if (Math.abs(shown.z - 26.8) > 1e-9) throw new AssertionError("Unowned foreign transform changed");
        System.setProperty("camera.probe.mounted", "true");
        for (int frame = 0; frame < 180; frame++) {
            // Null context would fail in either foreign updater. Successful return proves
            // the actual injected cancellation runs before both normal and optimized work.
            update.invoke(camera, null, 1.0 / 60.0);
            shown.set(0, 0, frame - 90);
            apply.invoke(camera, output);
            if (shown.z != frame - 90) throw new AssertionError("Mounted foreign roll modified body bank");
        }
        System.clearProperty("camera.probe.mounted");
        shown.zero(); apply.invoke(camera, output);
        if (shown.z != 0) throw new AssertionError("Exit exposed a retained transform before rebase");
        System.out.println("PASS transformed dispatch: unowned control, 180 mounted frames, real updater cancellation, exit guard");
    }
}
