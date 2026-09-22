package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.api.diagnostics.DebugFeaturePolicy;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.atsuishio.superbwarfare.init.ModKeyMappings;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWKeyCallback;
import org.lwjgl.glfw.GLFWKeyCallbackI;

import java.nio.file.Files;
import java.nio.file.Path;

/** Bounded private-fixture keyboard edges through the installed Minecraft GLFW callback. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID, value = Dist.CLIENT)
public final class BvpGroundHudInputDriver {
    private static long seenSequence = -1, seenModification = -1, pressedAt, releaseAt, nextRepeat;
    private static int heldKey = -1;
    private static long heldWindow;
    private static VehicleEntity heldVehicle;
    private static boolean repeats;
    private BvpGroundHudInputDriver() { }

    private static VehicleEntity admitted(Minecraft mc) {
        if (!DebugFeaturePolicy.isDiagnosticPropertyEnabled("bvp.diagnostics.scenarios")
                || !EliteDiagnostics.isClientEnabled() || mc.player == null || mc.level == null
                || mc.getConnection() == null || mc.screen != null || !mc.isWindowActive()
                || !"0".equals(mc.getUser().getAccessToken())
                || !BvpFireTrafficControl.identity(mc.player.getGameProfile().getName(),mc.player.getUUID())
                || !BvpFireTrafficControl.loopback(mc.getConnection().getConnection().getRemoteAddress(),true)) return null;
        return mc.player.getVehicle() instanceof VehicleEntity vehicle && !vehicle.isRemoved()
                && vehicle.hasCustomName() && "camera_probe:0:hud:hold".equals(vehicle.getName().getString())
                ? vehicle : null;
    }

    @SubscribeEvent public static void tick(TickEvent.ClientTickEvent event) {
        if(event.phase != TickEvent.Phase.START) return;
        Minecraft mc = Minecraft.getInstance();
        VehicleEntity vehicle = admitted(mc);
        long now = System.nanoTime();
        if(heldKey >= 0) {
            if(vehicle != heldVehicle || mc.getWindow().getWindow() != heldWindow || now >= releaseAt) {
                edge(GLFW.GLFW_RELEASE,vehicle == heldVehicle ? "duration" : "context_loss");
                heldKey = -1; heldVehicle = null;
            } else if(repeats && now >= nextRepeat) {
                edge(GLFW.GLFW_REPEAT,"repeat"); nextRepeat = now + 200_000_000L;
            }
            return;
        }
        if(vehicle == null || mc.player.tickCount % 2 != 0) return;
        Path plan = mc.gameDirectory.toPath().resolve("logs/ground-hud-input.json");
        try {
            if(!Files.isRegularFile(plan) || Files.size(plan) > 4096) return;
            long modified = Files.getLastModifiedTime(plan).toMillis();
            if(modified == seenModification) return;
            seenModification = modified;
            var json = JsonParser.parseString(Files.readString(plan)).getAsJsonObject();
            long sequence = json.get("sequence").getAsLong();
            if(sequence <= seenSequence) return;
            seenSequence = sequence;
            int duration = json.get("holdMillis").getAsInt();
            String key = json.get("key").getAsString();
            int code;
            if("secondary".equals(key)) {
                var binding = ModKeyMappings.VEHICLE_SWITCH_SECONDARY.getKey();
                if(binding.getType() != InputConstants.Type.KEYSYM) return;
                code = binding.getValue();
            } else if(key.matches("[1-9]")) code = GLFW.GLFW_KEY_1 + key.charAt(0)-'1';
            else return;
            if(duration < 50 || duration > 2500 || code < 32 || code > GLFW.GLFW_KEY_LAST) return;
            heldKey = code; heldVehicle = vehicle; heldWindow = mc.getWindow().getWindow();
            pressedAt = now; releaseAt = now + duration*1_000_000L; nextRepeat = now+350_000_000L;
            repeats = !json.has("repeat") || json.get("repeat").getAsBoolean();
            edge(GLFW.GLFW_PRESS,"start");
        } catch(Exception failure) {
            EliteDiagnostics.record(vehicle,"ground_hud_input","PLAN_REJECTED","error",failure.toString());
            if(heldKey >= 0) { edge(GLFW.GLFW_RELEASE,"error"); heldKey = -1; heldVehicle = null; }
        }
    }

    private static void edge(int action,String reason) {
        if(heldKey < 0) return;
        // Obtain and immediately restore the exact existing callback; never replace its behavior.
        GLFWKeyCallback callback = GLFW.glfwSetKeyCallback(heldWindow,(GLFWKeyCallbackI)null);
        if(callback == null) throw new IllegalStateException("Minecraft keyboard callback unavailable");
        GLFW.glfwSetKeyCallback(heldWindow,callback);
        EliteDiagnostics.record(heldVehicle,"ground_hud_input","KEY_EDGE","sequence",seenSequence,
                "key",heldKey,"action",action,"reason",reason,
                "elapsedMillis",(System.nanoTime()-pressedAt)/1_000_000.0);
        callback.invoke(heldWindow,heldKey,GLFW.glfwGetKeyScancode(heldKey),action,0);
    }
}
