package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.audio.SpatialAudio;
import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.api.projectile.ResolvedProjectileProfile;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactContext;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.registries.ForgeRegistries;

import java.util.Locale;

/**
 * Surface impact audio, one cue per accepted impact: what the round struck (rock, brick and concrete, earth, wood,
 * glass, metal/armor) picks the sample set, the calibre picks how loud and how far it carries. The sets are the
 * Tyrants and Plebeians pack's Flan's impact samples (several variants each, chosen at random by the client).
 *
 * <p>Played through SpatialAudio like gunfire and explosions: distance law, speed-of-sound delay, Doppler and the
 * client voice cap, instead of the vanilla 16-block cut-off the old path had.</p>
 */
public final class BvpMaterialImpactSounds {
    public enum Material {
        ROCK("impact_tap_rock"),
        BRICKS("impact_tap_bricks"),
        DIRT("impact_tap_dirt"),
        WOOD("impact_tap_wood"),
        GLASS("impact_tap_glass"),
        METAL("impact_tap_metal");

        final String sound;

        Material(String event) {
            this.sound = BertsVehiclePack.MODID + ":" + event;
        }
    }

    /** Calibre class: SpatialAudio gain and hearing range (blocks). */
    record Carry(float gain, float range) {
    }

    private BvpMaterialImpactSounds() {
    }

    static boolean hasPresentation(Entity source) {
        var profile = source == null ? null : ProjectileProfiles.resolve(source);
        return acceptsCombatProfile(profile);
    }

    /** Material feedback is independent of optional tracer and particle-trail authoring. */
    static boolean acceptsCombatProfile(ResolvedProjectileProfile profile) {
        return profile != null && profile.getCombat() != null
                && BertsVehiclePack.MODID.equals(profile.getId().getNamespace());
    }

    /** Surface class from the block's sound type name and, for masonry that sounds like stone, its id. */
    public static Material classify(String breakSound, String blockId) {
        String id = blockId == null ? "" : blockId.toLowerCase(Locale.ROOT);
        Material bySound = classifySound(breakSound);
        if (bySound == Material.ROCK && (id.contains("brick") || id.contains("concrete") || id.contains("terracotta")
                || id.contains("tile") || id.contains("smooth") || id.contains("polished") || id.contains("quartz"))) {
            return Material.BRICKS;
        }
        return bySound;
    }

    public static Material classifySound(String breakSound) {
        String name = breakSound.toLowerCase(Locale.ROOT);
        if (name.contains("glass") || name.contains("amethyst") || name.contains("ice")) return Material.GLASS;
        if (name.contains("metal") || name.contains("anvil") || name.contains("chain")
                || name.contains("copper") || name.contains("netherite") || name.contains("lantern")
                || name.contains("iron")) return Material.METAL;
        if (name.contains("wood") || name.contains("bamboo") || name.contains("cherry")
                || name.contains("stem") || name.contains("ladder") || name.contains("scaffold")) return Material.WOOD;
        if (name.contains("brick") || name.contains("decorated_pot")) return Material.BRICKS;
        if (name.contains("gravel") || name.contains("grass") || name.contains("sand")
                || name.contains("mud") || name.contains("root") || name.contains("snow")
                || name.contains("wool") || name.contains("moss") || name.contains("crop")
                || name.contains("vine") || name.contains("soul") || name.contains("fungus")
                || name.contains("nylium") || name.contains("azalea") || name.contains("dirt")) return Material.DIRT;
        return Material.ROCK;
    }

    /**
     * Calibre ladder, under the fire of the same gun (tools/audio/weapon_loudness.py): rifle calibre 0.35 / 24 blocks,
     * HMG 0.55 / 40, autocannon 0.8 / 64, gun 1.1 / 96.
     */
    public static Carry carry(double caliberMm) {
        double d = Double.isFinite(caliberMm) && caliberMm > 0 ? caliberMm : 7.62;
        if (d < 12.0) return new Carry(0.35F, 24.0F);
        if (d < 20.0) return new Carry(0.55F, 40.0F);
        if (d < 60.0) return new Carry(0.8F, 64.0F);
        return new Carry(1.1F, 96.0F);
    }

    /** Legacy linear-volume curve (kept for callers that still play through vanilla audio). */
    public static float volume(double caliberMm) {
        double diameter = Double.isFinite(caliberMm) && caliberMm > 0 ? caliberMm : 7.62;
        return (float) (0.22 + 0.72 * (1.0 - Math.exp(-Math.min(diameter, 1000.0) / 45.0)));
    }

    static void play(ProjectileImpactContext context) {
        var projectile = context.getProjectile();
        Material material = Material.METAL;
        if (context.getKind() == ProjectileImpactContext.Kind.BLOCK) {
            BlockState state = context.getBlockState();
            SoundType surface = state.getSoundType(projectile.level(), context.getBlockPos(), projectile);
            ResourceLocation block = ForgeRegistries.BLOCKS.getKey(state.getBlock());
            material = classify(surface.getBreakSound().getLocation().toString(), block == null ? null : block.toString());
        } else if (context.getKind() == ProjectileImpactContext.Kind.ENTITY
                && !(context.getTarget() instanceof com.atsuishio.superbwarfare.entity.vehicle.base.VehicleEntity)) {
            return; // soft targets: the entity's own hurt sound is the impact
        }
        var combat = ProjectileProfiles.combatDescriptor(projectile);
        double caliber = combat == null ? 7.62 : BvpImpactFragmentCommitter.caliber(context, combat);
        Carry carry = carry(caliber);
        // heavier rounds strike lower; a little random spread so repeated hits are not one sample
        float pitch = (caliber >= 60.0 ? 0.85F : caliber >= 20.0 ? 0.93F : 1.0F)
                * (0.93F + 0.14F * projectile.level().random.nextFloat());
        if (projectile.level() instanceof ServerLevel server) {
            SoundEvent event = SoundEvent.createVariableRangeEvent(new ResourceLocation(material.sound));
            SpatialAudio.Cue cue = new SpatialAudio.Cue(null, event, null, null,
                    Math.min(12.0F, carry.range()), carry.range(), carry.range());
            SpatialAudio.emit(server, context.getHitVec(), cue, carry.gain(), pitch, null, null,
                    SpatialAudio.Category.EXPLOSION, null);
        }
        EliteDiagnostics.record(projectile, "impact", "MATERIAL_SOUND",
                "material", material, "sound", material.sound, "caliber_mm", caliber,
                "volume", carry.gain(), "range", carry.range(), "pitch", pitch, "position", context.getHitVec());
    }
}
