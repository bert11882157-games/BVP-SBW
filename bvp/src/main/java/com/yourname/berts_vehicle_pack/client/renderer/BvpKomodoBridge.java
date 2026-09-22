package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.client.renderer.vehicle.VehicleRenderBackendContext;
import com.atsuishio.superbwarfare.entity.vehicle.base.GeoVehicleEntity;
import com.example.sbwmeshloader.core.PolyMeshModel;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.resources.ResourceLocation;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import org.joml.Matrix4f;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** Optional instance submission boundary; the normal renderer owns animation and presentation. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID, value = Dist.CLIENT)
public final class BvpKomodoBridge {
    private static final Map<GeoVehicleEntity, Receiver> RECEIVERS = new ConcurrentHashMap<>();
    private static long frame;
    private static Runnable geometryReset = () -> { };

    private BvpKomodoBridge() { }

    public interface Receiver {
        boolean submit(long frame, VehicleRenderBackendContext context, PolyMeshModel model,
                       Matrix4f modelPose, ResourceLocation texture, int light, float alpha);
        void flush(long frame);
        void reset();
    }

    public static void attach(GeoVehicleEntity entity, Receiver receiver) {
        Receiver previous = RECEIVERS.put(entity, receiver);
        if (previous != null && previous != receiver) previous.reset();
    }

    public static void detach(GeoVehicleEntity entity, Receiver receiver) {
        RECEIVERS.remove(entity, receiver);
    }

    public static void geometryReset(Runnable reset) {
        geometryReset = reset;
    }

    @SubscribeEvent
    public static void renderTick(TickEvent.RenderTickEvent event) {
        if (event.phase == TickEvent.Phase.START) frame++;
    }

    static boolean submit(VehicleRenderBackendContext context, PolyMeshModel model,
                          Matrix4f modelPose, ResourceLocation texture, int light, float alpha) {
        Receiver receiver = RECEIVERS.get(context.getVehicle());
        return receiver != null && receiver.submit(frame, context, model, modelPose, texture, light, alpha);
    }

    /** Called after Flywheel's parallel frame work completes and before its instance upload. */
    public static void flush() {
        for (Receiver receiver : RECEIVERS.values()) receiver.flush(frame);
    }

    public static void reset() {
        for (Receiver receiver : RECEIVERS.values()) receiver.reset();
        geometryReset.run();
    }
}
