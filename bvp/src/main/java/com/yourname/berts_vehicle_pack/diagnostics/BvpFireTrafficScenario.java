package com.yourname.berts_vehicle_pack.diagnostics;

import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity;
import com.google.gson.GsonBuilder;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.EntityJoinLevelEvent;
import net.minecraftforge.event.entity.EntityLeaveLevelEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.eventbus.api.EventPriority;
import net.minecraftforge.eventbus.api.SubscribeEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.registries.ForgeRegistries;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Private loopback fixture: actual client held input, ordinary scheduler, and aggregate capture. */
@Mod.EventBusSubscriber(modid = BertsVehiclePack.MODID)
public final class BvpFireTrafficScenario {
    private static Run active;
    private BvpFireTrafficScenario() { }

    @SubscribeEvent
    public static void commands(RegisterCommandsEvent event) {
        if (!BvpFireTrafficControl.enabled()) return;
        event.getDispatcher().register(Commands.literal("bvp_fire_traffic")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("label", StringArgumentType.word()).executes(context -> {
                    ServerPlayer player = context.getSource().getPlayerOrException();
                    String label = StringArgumentType.getString(context, "label");
                    if (active != null || !privatePlayer(player) || player.getVehicle() != null
                            || !label.matches("[A-Za-z0-9_-]{1,40}") || EliteDiagnostics.isServerEnabled()) {
                        context.getSource().sendFailure(Component.literal(
                                "Requires an unmounted offline BvpDiagnostics on private 127.0.0.1:25579; Elite must be off."));
                        return 0;
                    }
                    active = new Run(player, label);
                    try { active.prepare(); }
                    catch (RuntimeException failure) { fail(failure.toString()); return 0; }
                    return 1;
                })));
        event.getDispatcher().register(Commands.literal("bvp_fire_traffic_ack")
                .requires(source -> source.hasPermission(2))
                .then(Commands.argument("run", StringArgumentType.word())
                        .then(Commands.argument("phase", StringArgumentType.word()).executes(context -> {
                            ServerPlayer player = context.getSource().getPlayerOrException();
                            if (active == null || active.player != player || !privatePlayer(player)
                                    || !active.id.toString().equals(StringArgumentType.getString(context, "run"))) return 0;
                            String phase = StringArgumentType.getString(context, "phase");
                            if (phase.equals("ABORT")) { fail("Client context/watchdog aborted"); return 1; }
                            if (!phase.equals(active.phase.name()) || active.acknowledged) return 0;
                            active.acknowledged = true;
                            active.record("CLIENT_ACK", "phase", phase);
                            return 1;
                        }))));
    }

    private static boolean privatePlayer(ServerPlayer player) {
        MinecraftServer server = player.server;
        return BvpFireTrafficControl.enabled() && server.isDedicatedServer() && !server.usesAuthentication()
                && "127.0.0.1".equals(server.getLocalIp()) && server.getPort() == BvpFireTrafficControl.PORT
                && server.getPlayerCount() == 1 && player.level().dimension() == Level.OVERWORLD
                && player.isAlive() && !player.isSpectator() && player.getAbilities().instabuild
                && BvpFireTrafficControl.identity(player.getGameProfile().getName(), player.getUUID())
                && BvpFireTrafficControl.loopback(player.connection.connection.getRemoteAddress(), false);
    }

    @SubscribeEvent(priority = EventPriority.LOWEST)
    public static void tick(TickEvent.ServerTickEvent event) {
        if (active == null || event.phase != TickEvent.Phase.END || active.server != event.getServer()) return;
        try { active.tick(); } catch (RuntimeException failure) { fail(failure.toString()); }
    }

    @SubscribeEvent
    public static void join(EntityJoinLevelEvent event) {
        if (active == null || event.isCanceled() || event.getLevel() != active.level
                || !(event.getEntity() instanceof Projectile projectile)) return;
        Entity owner = projectile.getOwner();
        if (owner != active.player && owner != active.vehicle && !active.projectiles.contains(owner)) return;
        if (active.projectiles.size() >= 4096) {
            active.pendingFailure = "Owned projectile cleanup bound exceeded";
            projectile.discard();
            return;
        }
        active.projectiles.add(projectile);
    }

    @SubscribeEvent
    public static void leave(EntityLeaveLevelEvent event) {
        if (active != null && event.getLevel() == active.level) active.projectiles.remove(event.getEntity());
    }

    @SubscribeEvent
    public static void stopping(ServerStoppingEvent event) {
        if (active != null && event.getServer() == active.server) active.finish("STOPPED", "Server stopping");
    }

    private static void fail(String reason) { if (active != null) active.finish("FAIL", reason); }

    private static final class Run {
        final ServerPlayer player;
        final MinecraftServer server;
        final ServerLevel level;
        final String label;
        final UUID id = UUID.randomUUID();
        final Vec3 savedPosition;
        final float savedYaw;
        final float savedPitch;
        final Vec3 origin;
        final Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        final Set<ChunkPos> forced = new LinkedHashSet<>();
        final Set<Projectile> projectiles = Collections.newSetFromMap(new IdentityHashMap<>());
        final List<Map<String, Object>> phases = new ArrayList<>();
        final String startedUtc = Instant.now().toString();
        final long createdNanos = System.nanoTime();
        VehicleEntity vehicle;
        int seat;
        int weapon;
        int ticks;
        int phaseStartTick;
        long phaseStartNanos;
        long phaseStartSequence;
        long releaseTailSequence = -1;
        boolean acknowledged;
        boolean captureOwned;
        boolean finished;
        String pendingFailure;
        BvpFireTrafficControl.Phase phase = BvpFireTrafficControl.Phase.ARM;

        Run(ServerPlayer player, String label) {
            this.player = player;
            server = player.server;
            level = player.serverLevel();
            this.label = label;
            savedPosition = player.position();
            savedYaw = player.getYRot();
            savedPitch = player.getXRot();
            origin = new Vec3(Math.floor(savedPosition.x) + 40, Math.floor(savedPosition.y) + 16,
                    Math.floor(savedPosition.z) + 40);
        }

        void prepare() {
            for (int x = -5; x <= 5; x++) for (int z = -5; z <= 5; z++) {
                put(BlockPos.containing(origin).offset(x, -1, z), Blocks.BEDROCK.defaultBlockState());
            }
            EntityType<?> type = ForgeRegistries.ENTITY_TYPES.getValue(new ResourceLocation(BertsVehiclePack.MODID, "bmpt"));
            Entity candidate = type == null ? null : type.create(level);
            if (!(candidate instanceof VehicleEntity created)) throw new IllegalStateException("BMPT unavailable");
            vehicle = created;
            vehicle.load(new CompoundTag());
            vehicle.moveTo(origin.x, origin.y, origin.z, 0F, 0F);
            vehicle.setNoGravity(true);
            vehicle.setEnergy(vehicle.getMaxEnergy());
            vehicle.addTag("bvp_fire_traffic_fixture");
            if (!level.addFreshEntity(vehicle)) throw new IllegalStateException("Fixture insertion failed");
            player.teleportTo(level, origin.x, origin.y + 2, origin.z, 0F, 0F);
            if (!player.startRiding(vehicle, true)) throw new IllegalStateException("Fixture mount failed");
            seat = vehicle.getSeatIndex(player);
            var seatData = vehicle.getSeat(seat);
            int dualCannon = seatData == null ? -1 : seatData.weapons().indexOf("DualCannon");
            if (dualCannon < 0) throw new IllegalStateException("BMPT seat has no DualCannon");
            vehicle.setWeaponIndex(seat, dualCannon);
            weapon = vehicle.getSelectedWeapon(seat);
            if (!"DualCannon".equals(vehicle.getGunName(seat))) throw new IllegalStateException("BMPT primary is not DualCannon");
            vehicle.modifyGunData("DualCannon", data -> {
                data.resetStatus();
                data.reload.setPendingProgressPercent(0);
                data.ammo.set(850);
                data.virtualAmmo.set(0);
                data.heat.set(0);
                data.overHeat.set(false);
            });
            player.sendSystemMessage(Component.literal("Fire-traffic fixture preparing; keep game focused and controls untouched."));
        }

        void tick() {
            ticks++;
            if (pendingFailure != null) throw new IllegalStateException(pendingFailure);
            if (!privatePlayer(player) || player.serverLevel() != level || vehicle == null || vehicle.isRemoved()
                    || player.getVehicle() != vehicle || vehicle.getSeatIndex(player) != seat
                    || vehicle.getNthEntity(seat) != player || vehicle.getSelectedWeapon(seat) != weapon
                    || vehicle.position().distanceToSqr(origin) > 4D
                    || Math.abs(Mth.wrapDegrees(player.getYRot())) > 3F || Math.abs(player.getXRot()) > 3F) {
                throw new IllegalStateException("Fixture/operator context changed");
            }
            long now = System.nanoTime();
            if (now - createdNanos >= 55_000_000_000L) throw new IllegalStateException("Server watchdog expired");
            if (ticks == 40) {
                var muzzle = vehicle.resolveMuzzleFrame(player, 1F);
                if (muzzle == null) throw new IllegalStateException("Authoritative muzzle unavailable");
                Vec3 direction = muzzle.getDirection();
                if (Math.abs(direction.y) > 0.1D) throw new IllegalStateException("Fixture aim is not level");
                BlockPos center = BlockPos.containing(muzzle.getPosition().add(direction.scale(20D)));
                boolean xWall = Math.abs(direction.x) > Math.abs(direction.z);
                for (int across = -6; across <= 6; across++) for (int up = -5; up <= 5; up++) {
                    put(center.offset(xWall ? 0 : across, up, xWall ? across : 0), Blocks.BEDROCK.defaultBlockState());
                }
                EliteDiagnostics.INSTANCE.start(server);
                captureOwned = true;
                begin(BvpFireTrafficControl.Phase.ARM);
            }
            if (!captureOwned) return;
            if (!EliteDiagnostics.isServerEnabled()) throw new IllegalStateException("Capture was interrupted");
            long phaseNanos = now - phaseStartNanos;
            if (!acknowledged && phaseNanos > 5_000_000_000L) throw new IllegalStateException("Client phase acknowledgement missing");
            if (phase == BvpFireTrafficControl.Phase.ARM) {
                if (acknowledged) begin(BvpFireTrafficControl.Phase.IDLE);
                return;
            }
            if (phase == BvpFireTrafficControl.Phase.RELEASE && phaseNanos >= 3_000_000_000L
                    && releaseTailSequence < 0) releaseTailSequence = acceptedSequence();
            if (phaseNanos < phase.ticks * 50_000_000L) return;
            long accepted = acceptedSequence() - phaseStartSequence;
            phases.add(Map.of("phase", phase.name(), "serverTicks", ticks - phaseStartTick,
                    "durationSeconds", phaseNanos / 1_000_000_000D, "acceptedShots", accepted,
                    "clientAcknowledged", acknowledged, "endServerTick", level.getGameTime()));
            record("PHASE_END", "phase", phase.name(), "accepted", accepted);
            if (!acknowledged || phase == BvpFireTrafficControl.Phase.IDLE && accepted != 0
                    || phase == BvpFireTrafficControl.Phase.HOLD && accepted <= 0
                    || phase == BvpFireTrafficControl.Phase.RELEASE && acceptedSequence() != releaseTailSequence) {
                throw new IllegalStateException("Idle/fire/release acceptance assertion failed");
            }
            if (phase == BvpFireTrafficControl.Phase.RELEASE) finish("PASS", null);
            else begin(BvpFireTrafficControl.Phase.values()[phase.ordinal() + 1]);
        }

        long acceptedSequence() {
            var snapshot = vehicle.getWeaponScheduleSnapshot(seat, weapon);
            return snapshot == null ? 0L : snapshot.getAcceptedSequence();
        }

        void begin(BvpFireTrafficControl.Phase next) {
            phase = next;
            acknowledged = false;
            phaseStartTick = ticks;
            phaseStartNanos = System.nanoTime();
            phaseStartSequence = acceptedSequence();
            record("PHASE_BEGIN", "phase", phase.name(), "accepted_sequence", phaseStartSequence);
            player.sendSystemMessage(Component.literal(BvpFireTrafficControl.message(id, vehicle.getUUID(), vehicle.getId(), phase)));
        }

        void put(BlockPos position, BlockState state) {
            ChunkPos chunk = new ChunkPos(position);
            if (!level.getForcedChunks().contains(chunk.toLong())) {
                level.setChunkForced(chunk.x, chunk.z, true);
                forced.add(chunk);
            }
            level.getChunk(chunk.x, chunk.z);
            if (level.getBlockEntity(position) != null) throw new IllegalStateException("Fixture refuses block entities");
            blocks.putIfAbsent(position.immutable(), level.getBlockState(position));
            level.setBlockAndUpdate(position, state);
        }

        void record(String event, Object... fields) {
            Object[] scoped = new Object[fields.length + 2];
            scoped[0] = "run";
            scoped[1] = id.toString();
            System.arraycopy(fields, 0, scoped, 2, fields.length);
            EliteDiagnostics.record(vehicle, "fire_traffic", event, scoped);
        }

        void finish(String status, String error) {
            if (finished) return;
            finished = true;
            active = null;
            try {
                if (vehicle != null) {
                    player.sendSystemMessage(Component.literal(BvpFireTrafficControl.message(
                            id, vehicle.getUUID(), vehicle.getId(), BvpFireTrafficControl.Phase.STOP)));
                    record("FINISH", "status", status, "error", error);
                }
                Map<String, Object> report = new LinkedHashMap<>();
                report.put("schema", 1);
                report.put("status", status);
                report.put("error", error);
                report.put("run", id.toString());
                report.put("label", label);
                report.put("startedUtc", startedUtc);
                report.put("completedUtc", Instant.now().toString());
                report.put("phases", phases);
                report.put("capture", EliteDiagnostics.INSTANCE.status());
                report.put("notes", List.of("Real ClientEventHandler held-trigger path; no server fire calls.",
                        "Elite all-category logging is enabled: not a clean server-CPU timing benchmark.",
                        "SBW codec counters exclude vanilla, other mods, compression, framing, and socket overhead.",
                        "Ordinary chat commands/system messages synchronize phase boundaries only."));
                Path directory = Path.of("logs", "bvp-fire-traffic");
                Files.createDirectories(directory);
                Files.writeString(directory.resolve(label + "-" + id + ".json"), new GsonBuilder().setPrettyPrinting().create().toJson(report));
            } catch (Exception failure) {
                player.sendSystemMessage(Component.literal("Fire-traffic report failed: " + failure));
            } finally {
                if (captureOwned) EliteDiagnostics.INSTANCE.stop(server);
                for (Projectile projectile : new ArrayList<>(projectiles)) projectile.discard();
                player.stopRiding();
                if (vehicle != null) vehicle.discard();
                blocks.forEach(level::setBlockAndUpdate);
                forced.forEach(chunk -> level.setChunkForced(chunk.x, chunk.z, false));
                if (player.isAlive() && player.connection.connection.isConnected()) {
                    player.teleportTo(level, savedPosition.x, savedPosition.y, savedPosition.z, savedYaw, savedPitch);
                }
                player.sendSystemMessage(Component.literal("Fire-traffic fixture " + status));
            }
        }
    }
}
