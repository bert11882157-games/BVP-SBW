package com.yourname.berts_vehicle_pack.client.renderer;

import com.atsuishio.superbwarfare.resource.vehicle.DefaultVehicleResource.AfterburnerResource;
import com.yourname.berts_vehicle_pack.particle.ProjectileEffectParticleOptions;
import com.mojang.blaze3d.vertex.VertexConsumer;
import io.netty.buffer.Unpooled;
import net.minecraft.client.Camera;
import net.minecraft.client.particle.Particle;
import net.minecraft.client.particle.ParticleRenderType;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.world.phys.Vec3;

/** Headless production configuration, density, budget, and compatibility checks. */
public final class BvpAircraftAfterburnerTest {
    public static void main(String[] args) {
        tapEmission();
        var raw = fixture();
        var config = BvpAircraftAfterburnerRenderer.compile(raw);
        near(config.flame().scale(), 0.5, "source particle owns its size");
        near(config.flame().length(), 4.0, "authored plume extent");
        near(config.smoke().length(), 4.0, "smoke extent unchanged");
        check(config.smoke().scale() == 0.35F, "smoke size unchanged");
        firstVisibleInterval(config.flame());
        streamSampling(config.flame());
        check(config.outlets().size() == 2, "two independent outlets");
        check(config.flame().lifetime() == 6 && config.smoke().lifetime() == 12,
                "bounded defaults");
        var state = new BvpAircraftAfterburnerRenderer.State();
        int flame = 0;
        int smoke = 0;
        for (int tick = 0; tick < 20; tick++) {
            state.advance(config);
            flame += state.flameCount;
            smoke += state.smokeCount;
        }
        check(flame == 40 && smoke == 10, "tick-owned density");
        state.stop();
        check(state.flameCount == 0 && state.smokeCount == 0
                && state.flameCarry == 0 && state.smokeCarry == 0, "inactive reset");
        var budget = new BvpAircraftAfterburnerRenderer.Budget();
        for (int i = 0; i < 64; i++) check(budget.take(), "budget admission");
        check(!budget.take(), "global cap");
        budget.reset();
        check(budget.take(), "next-tick recovery");
        raw.outlets[1].id = raw.outlets[0].id;
        rejects(raw, "duplicate outlet");
        raw = fixture();
        raw.outlets[0].direction = new double[] {0, 0, 0};
        rejects(raw, "zero direction");
        raw = fixture();
        raw.flame.scale = Double.NaN;
        rejects(raw, "nonfinite setting");
        raw = fixture();
        raw.smoke.lifetimeTicks = 17;
        rejects(raw, "excess lifetime");
        raw = fixture();
        raw.outlets = new AfterburnerResource.Outlet[0];
        rejects(raw, "missing anchors");
        var original = new ProjectileEffectParticleOptions(true, 1, 1, 6, 0.1F, 0, 0, 0);
        check(original.tintRed() == 1 && original.tintGreen() == 1
                && original.tintBlue() == 1, "ATGM identity tint");
        var buffer = new FriendlyByteBuf(Unpooled.buffer());
        try {
            original.m_7711_(buffer);
            check(buffer.readableBytes() == 29, "unchanged particle wire size");
            var decoded = ProjectileEffectParticleOptions.DESERIALIZER.m_6507_(null, buffer);
            check(decoded.tintRed() == 1 && decoded.tintGreen() == 1
                    && decoded.tintBlue() == 1, "wire compatibility");
        } finally {
            buffer.release();
        }
        System.out.println("Afterburner behavior checks passed");
    }

    private static void firstVisibleInterval(BvpAircraftAfterburnerRenderer.Channel channel) {
        Vec3 direction = new Vec3(0, 0, -1);
        double exhaustSpeed = channel.length() / channel.lifetime();
        for (Vec3 motion : new Vec3[] {new Vec3(0, 0, 0), new Vec3(0, 0, 281.0 / 72),
                new Vec3(2, -0.7, 4), new Vec3(-4, 1.25, -6)}) {
            Vec3 velocity = BvpAircraftAfterburnerRenderer.streamVelocity(direction, motion, channel);
            check(velocity != null, "finite presented motion");
            var particle = new ProbeParticle();
            BvpAircraftAfterburnerRenderer.seedQueuedParticle(particle, velocity);
            for (double partial : new double[] {0, 0.25, 0.5, 0.75, 1}) {
                Vec3 relative = particle.at(partial).m_82546_(motion.m_82490_(partial));
                near(relative.f_82479_, 0, "no lateral platform lag");
                near(relative.f_82480_, 0, "no vertical platform lag");
                near(relative.f_82481_, -exhaustSpeed * partial, "first-frame exhaust extent");
            }
            check(particle.age() == 0 && particle.m_107273_() == 6,
                    "seeding does not advance age or change lifetime");
        }
        // The old END-tick path has xo == x until the following particle update.
        var old = new ProbeParticle();
        near(old.at(0.5).f_82481_ - 281.0 / 72 * 0.5, -281.0 / 144,
                "old first-frame midpoint gap reproduces 281 km/h observation");
        check(BvpAircraftAfterburnerRenderer.streamVelocity(direction,
                new Vec3(Double.NaN, 0, 0), channel) == null, "nonfinite motion rejects");
        check(BvpAircraftAfterburnerRenderer.streamVelocity(direction,
                new Vec3(Double.MAX_VALUE, 0, 0), channel) == null,
                "particle payload float overflow rejects");
        BvpAircraftAfterburnerRenderer.seedQueuedParticle(null, new Vec3(0, 0, 0));
    }

    private static void streamSampling(BvpAircraftAfterburnerRenderer.Channel channel) {
        near(BvpAircraftAfterburnerRenderer.streamOffset(channel, 0, 2), 0,
                "first sample remains at the authored outlet");
        // The fixture's four-block plume advances 4/6 blocks per tick; the second of two samples is halfway.
        near(BvpAircraftAfterburnerRenderer.streamOffset(channel, 1, 2), 1.0 / 3,
                "second sample is distinct without adding particles");
        for (int count = 1; count <= 4; count++) {
            double previous = -1;
            for (int index = 0; index < count; index++) {
                double offset = BvpAircraftAfterburnerRenderer.streamOffset(channel, index, count);
                check(offset > previous && offset < channel.length() / channel.lifetime(),
                        "ordered samples stay inside one exhaust-relative tick");
                previous = offset;
            }
        }
    }

    private static final class ProbeParticle extends Particle {
        ProbeParticle() {
            super(null, 0, 0, 0);
            this.f_107219_ = false;
            this.f_107225_ = 6;
        }

        Vec3 at(double partial) {
            return new Vec3(f_107209_ + (f_107212_ - f_107209_) * partial,
                    f_107210_ + (f_107213_ - f_107210_) * partial,
                    f_107211_ + (f_107214_ - f_107211_) * partial);
        }

        int age() { return f_107224_; }

        @Override
        public void m_5744_(VertexConsumer vertices, Camera camera, float partialTick) {}

        @Override
        public ParticleRenderType m_7556_() { return null; }
    }

    private static void near(double actual, double expected, String name) {
        if (Math.abs(actual - expected) > 1.0E-9) throw new AssertionError(name + ": " + actual);
    }

    private static AfterburnerResource fixture() {
        var data = new AfterburnerResource();
        data.schema = 1;
        data.frame = "VEHICLE_LOCAL_BLOCKS";
        data.outlets = new AfterburnerResource.Outlet[2];
        for (int i = 0; i < 2; i++) {
            var outlet = new AfterburnerResource.Outlet();
            outlet.id = "fixture_" + i;
            outlet.position = new double[] {i, 0, 0};
            outlet.direction = new double[] {0, 0, -1};
            data.outlets[i] = outlet;
        }
        return data;
    }

    private static void tapEmission() {
        var raw = fixture();
        raw.schema = 2;
        var emitter = new AfterburnerResource.TapEmitter();
        emitter.outlet = "fixture_1";
        emitter.style = "FM_FLAME";
        emitter.offset = new double[] {0, 0.1, -4};
        emitter.extents = new double[] {0.2, 0.2, 0.2};
        emitter.velocity = new double[] {0, 0, 0.1875};
        raw.tapEmitters = new AfterburnerResource.TapEmitter[] {emitter};
        var config = BvpAircraftAfterburnerRenderer.compile(raw);
        var compiled = config.tapEmitters().get(0);
        check(compiled.flame(), "TaP FMFlame owns its smoke companion");
        near(compiled.velocity().f_82481_, 0.1875, "source world velocity without aircraft inheritance");
        var center = BvpAircraftAfterburnerRenderer.tapSample(compiled, 0.5F, 0.5F, 0.5F);
        near(center.f_82479_, 1, "bound to second nozzle");
        near(center.f_82481_, -4, "source extended plume offset");
        var edge = BvpAircraftAfterburnerRenderer.tapSample(compiled, 0, 1, 0);
        near(edge.f_82479_, 0.9, "source random extent minimum");
        near(edge.f_82480_, 0.2, "source random extent maximum");
        emitter.outlet = "missing";
        rejects(raw, "unbound nozzle rejected");
        emitter.outlet = "fixture_1";
        emitter.extents[0] = -1;
        rejects(raw, "negative source extent rejected");
        emitter.extents[0] = 0.2;
        emitter.velocity[0] = Double.NaN;
        rejects(raw, "nonfinite source velocity rejected");
    }

    private static void rejects(AfterburnerResource data, String name) {
        try {
            BvpAircraftAfterburnerRenderer.compile(data);
        } catch (IllegalArgumentException expected) {
            return;
        }
        throw new AssertionError(name);
    }

    private static void check(boolean condition, String name) {
        if (!condition) throw new AssertionError(name);
    }
}
