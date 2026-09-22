package com.atsuishio.superbwarfare.entity.projectile;

import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.*;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.jar.JarFile;

/**
 * Runs the installed addon's compiled display helper with the compiled SBW bridge.
 * Minecraft construction is replaced by minimal type shells; only the named/SRG age field
 * is normalized. No game, world, Mixin transformation or rendering pipeline is started.
 * Arguments: SBW jar or class directory, original addon jar.
 */
public final class DroneAmmoAddonCompatibilityTest implements Opcodes {
    private static final String ENTITY = "net/minecraft/world/entity/Entity";
    private static final String THROWABLE = "net/minecraft/world/entity/projectile/ThrowableItemProjectile";
    private static final String FAST = "com/atsuishio/superbwarfare/entity/projectile/FastThrowableProjectile";
    private static final String GEO = "com/atsuishio/superbwarfare/entity/projectile/BasicGeoProjectileEntity";
    private static final String MIXIN = "com/senkos/droneammofix/mixin/DroneAttachmentMixin";
    private static final String PROBE = "compat/GeoFast";
    private static final String GEO_ONLY = "compat/GeoOnly";

    private static ClassNode read(Path location, String name) throws Exception {
        byte[] bytes;
        if (Files.isDirectory(location)) {
            bytes = Files.readAllBytes(location.resolve(name + ".class"));
        } else {
            try (JarFile jar = new JarFile(location.toFile())) {
                var entry = jar.getJarEntry(name + ".class");
                require(entry != null, "Missing class: " + name);
                try (var stream = jar.getInputStream(entry)) {
                    bytes = stream.readAllBytes();
                }
            }
        }
        ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        return node;
    }

    private static MethodNode method(ClassNode node, String name, String descriptor) {
        return node.methods.stream().filter(m -> m.name.equals(name) && m.desc.equals(descriptor))
                .findFirst().orElseThrow(() -> new AssertionError("Missing ABI: " + name + descriptor));
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static boolean ageField(FieldInsnNode field) {
        return List.of(ENTITY, THROWABLE, FAST).contains(field.owner) && field.desc.equals("I")
                && (field.name.equals("tickCount") || field.name.equals("f_19797_"));
    }

    private static void checkBridge(MethodNode bridge) {
        require((bridge.access & (ACC_PUBLIC | ACC_STATIC)) == ACC_PUBLIC,
                "Bridge must remain public and non-static");
        var code = java.util.stream.StreamSupport.stream(bridge.instructions.spliterator(), false)
                .filter(i -> i.getOpcode() >= 0).toList();
        require(code.size() == 4
                && code.get(0) instanceof VarInsnNode self && self.getOpcode() == ALOAD && self.var == 0
                && code.get(1) instanceof VarInsnNode value && value.getOpcode() == ILOAD && value.var == 1
                && code.get(2) instanceof FieldInsnNode field && field.getOpcode() == PUTFIELD && ageField(field)
                && code.get(3).getOpcode() == RETURN,
                "Bridge must only assign the supplied age: no network, lifecycle or gameplay side effects");
    }

    private static ClassNode shell(String name, String parent, String... interfaces) {
        ClassNode node = new ClassNode();
        node.version = V17;
        node.access = ACC_PUBLIC;
        node.name = name;
        node.superName = parent;
        node.interfaces.addAll(List.of(interfaces));
        MethodNode constructor = new MethodNode(ACC_PUBLIC, "<init>", "()V", null, null);
        constructor.instructions.add(new VarInsnNode(ALOAD, 0));
        constructor.instructions.add(new MethodInsnNode(INVOKESPECIAL, parent, "<init>", "()V", false));
        constructor.instructions.add(new InsnNode(RETURN));
        node.methods.add(constructor);
        return node;
    }

    private static void normalizeAge(MethodNode method) {
        for (var instruction : method.instructions) {
            if (instruction instanceof FieldInsnNode field && ageField(field)) field.name = "tickCount";
        }
    }

    private static void addGeoImplementation(ClassNode node) {
        MethodNode hidden = new MethodNode(ACC_PUBLIC, "getHiddenTicks", "()I", null, null);
        hidden.instructions.add(new VarInsnNode(ALOAD, 0));
        hidden.instructions.add(new FieldInsnNode(GETFIELD, ENTITY, "hidden", "I"));
        hidden.instructions.add(new InsnNode(IRETURN));
        node.methods.add(hidden);
    }

    private static ClassLoader harness(Path sbw, Path addon, boolean missingBridge, boolean noOp)
            throws Exception {
        MethodNode bridge = method(read(sbw, FAST), "setSyncedTick", "(I)V");
        checkBridge(bridge);
        MethodNode helper = method(read(addon, MIXIN), "prepareDisplayProjectile",
                "(L" + ENTITY + ";)L" + ENTITY + ";");
        int calls = 0;
        for (var instruction : helper.instructions) {
            if (instruction instanceof MethodInsnNode call && call.owner.equals(FAST)
                    && call.name.equals("setSyncedTick") && call.desc.equals("(I)V")
                    && call.getOpcode() == INVOKEVIRTUAL) calls++;
        }
        require(calls == 1, "Expected the original addon's exact invokevirtual ABI");
        normalizeAge(helper);
        normalizeAge(bridge);
        ClassNode entity = shell(ENTITY, "java/lang/Object");
        entity.fields.add(new FieldNode(ACC_PUBLIC, "tickCount", "I", null, null));
        entity.fields.add(new FieldNode(ACC_PUBLIC, "hidden", "I", null, null));
        ClassNode geo = new ClassNode();
        geo.version = V17;
        geo.access = ACC_PUBLIC | ACC_INTERFACE | ACC_ABSTRACT;
        geo.name = GEO;
        geo.superName = "java/lang/Object";
        geo.methods.add(new MethodNode(ACC_PUBLIC | ACC_ABSTRACT, "getHiddenTicks", "()I", null, null));
        ClassNode throwable = shell(THROWABLE, ENTITY);
        ClassNode fast = shell(FAST, THROWABLE);
        if (noOp) {
            bridge.instructions.clear();
            bridge.localVariables = null;
            bridge.instructions.add(new InsnNode(RETURN));
        }
        if (!missingBridge) fast.methods.add(bridge);
        ClassNode probe = shell(PROBE, FAST, GEO);
        addGeoImplementation(probe);
        ClassNode geoOnly = shell(GEO_ONLY, ENTITY, GEO);
        addGeoImplementation(geoOnly);
        ClassNode mixin = shell(MIXIN, "java/lang/Object");
        mixin.methods.add(helper);
        Map<String, byte[]> classes = new HashMap<>();
        for (ClassNode node : List.of(entity, throwable, geo, fast, probe, geoOnly, mixin)) {
            ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
            node.accept(writer);
            classes.put(node.name.replace('/', '.'), writer.toByteArray());
        }
        return new ClassLoader(null) {
            @Override protected Class<?> findClass(String name) throws ClassNotFoundException {
                byte[] bytes = classes.get(name);
                if (bytes == null) throw new ClassNotFoundException(name);
                return defineClass(name, bytes, 0, bytes.length);
            }
        };
    }

    private static Class<?> load(ClassLoader loader, String name) throws Exception {
        return loader.loadClass(name.replace('/', '.'));
    }

    private static void displayCase(ClassLoader loader, String type, int age, int hidden, int expected)
            throws Exception {
        Class<?> entity = load(loader, ENTITY);
        Class<?> mixin = load(loader, MIXIN);
        Object projectile = load(loader, type).getConstructor().newInstance();
        entity.getField("tickCount").setInt(projectile, age);
        entity.getField("hidden").setInt(projectile, hidden);
        Method helper = mixin.getDeclaredMethod("prepareDisplayProjectile", entity);
        helper.setAccessible(true);
        Object instance = mixin.getConstructor().newInstance();
        require(helper.invoke(instance, projectile) == projectile, "Display entity identity changed");
        require(entity.getField("tickCount").getInt(projectile) == expected, "Incorrect display age");
        require(helper.invoke(instance, projectile) == projectile, "Repeat changed entity identity");
        require(entity.getField("tickCount").getInt(projectile) == expected, "Repeat changed age");
        require(helper.invoke(instance, new Object[]{null}) == null, "Null display changed");
    }

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "Usage: DroneAmmoAddonCompatibilityTest <sbw jar/classes> <addon jar>");
        Path sbw = Path.of(args[0]);
        Path addon = Path.of(args[1]);
        ClassLoader fixed = harness(sbw, addon, false, false);
        for (int[] values : new int[][]{{0, 0, 1}, {0, 1, 2}, {1, 1, 2}, {25, 1, 25},
                {0, 40, 41}, {Integer.MAX_VALUE, 1, Integer.MAX_VALUE}}) {
            displayCase(fixed, PROBE, values[0], values[1], values[2]);
        }
        for (String type : List.of(ENTITY, FAST, GEO_ONLY)) displayCase(fixed, type, 0, 5, 0);
        Class<?> fast = load(fixed, FAST);
        Object projectile = fast.getConstructor().newInstance();
        Method setter = fast.getMethod("setSyncedTick", int.class);
        for (int value : new int[]{0, 7, -1, Integer.MIN_VALUE, Integer.MAX_VALUE}) {
            setter.invoke(projectile, value);
            require(fast.getField("tickCount").getInt(projectile) == value, "Setter changed supplied value");
        }
        try {
            displayCase(harness(sbw, addon, true, false), PROBE, 0, 1, 2);
            throw new AssertionError("Missing-method control did not fail");
        } catch (InvocationTargetException expected) {
            require(expected.getCause() instanceof NoSuchMethodError, "Wrong missing-method failure");
        }
        try {
            displayCase(harness(sbw, addon, false, true), PROBE, 0, 1, 2);
            throw new AssertionError("No-op control did not fail");
        } catch (AssertionError expected) {
            require(expected.getMessage().equals("Incorrect display age"), "Wrong no-op failure");
        }
        System.out.println("PASS: original addon helper + compiled bridge; 9 display cases/repeats/null, "
                + "5 exact setter values; missing-method and no-op controls; age-only bytecode contract");
    }
}
