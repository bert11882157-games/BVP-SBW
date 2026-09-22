package com.yourname.berts_vehicle_pack.armor;

import com.atsuishio.superbwarfare.api.projectile.ProjectileProfiles;
import com.atsuishio.superbwarfare.api.projectile.ResolvedProjectileProfile;
import com.atsuishio.superbwarfare.api.projectile.impact.ProjectileImpactContext;
import com.atsuishio.superbwarfare.diagnostics.EliteDiagnostics;
import net.minecraft.world.level.block.SoundType;
import net.minecraft.world.entity.Entity;
import com.yourname.berts_vehicle_pack.BertsVehiclePack;
import java.util.Locale;

/** Material audio consumes one accepted impact; caliber is the projectile's diameter in mm. */
public final class BvpMaterialImpactSounds {
    public enum Material {
        STONE("berts_vehicle_pack:impact_stone"),
        WOOD("minecraft:block.wood.break"),
        DIRT("berts_vehicle_pack:impact_dirt"),
        GLASS("minecraft:block.glass.break"),
        METAL("berts_vehicle_pack:impact_metal");

        final String sound;
        Material(String sound) { this.sound = sound; }
    }

    private BvpMaterialImpactSounds() { }

    static boolean hasPresentation(Entity source) {
        var profile = source == null ? null : ProjectileProfiles.resolve(source);
        return acceptsCombatProfile(profile);
    }

    /** Material feedback is independent of optional tracer and particle-trail authoring. */
    static boolean acceptsCombatProfile(ResolvedProjectileProfile profile) {
        return profile != null && profile.getCombat() != null
                && BertsVehiclePack.MODID.equals(profile.getId().getNamespace());
    }

    public static Material classifySound(String breakSound) {
        String name = breakSound.toLowerCase(Locale.ROOT);
        if (name.contains("glass") || name.contains("amethyst")) return Material.GLASS;
        if (name.contains("metal") || name.contains("anvil") || name.contains("chain")
                || name.contains("copper") || name.contains("netherite") || name.contains("lantern")) return Material.METAL;
        if (name.contains("wood") || name.contains("bamboo") || name.contains("cherry")
                || name.contains("stem") || name.contains("ladder")) return Material.WOOD;
        if (name.contains("gravel") || name.contains("grass") || name.contains("sand")
                || name.contains("mud") || name.contains("root") || name.contains("snow")
                || name.contains("wool") || name.contains("moss") || name.contains("crop")
                || name.contains("vine") || name.contains("wet_grass")) return Material.DIRT;
        return Material.STONE;
    }

    /** Keep gain below the engine's unit-volume clamp so caliber changes remain audible. */
    public static float volume(double caliberMm) {
        double diameter = Double.isFinite(caliberMm) && caliberMm > 0 ? caliberMm : 7.62;
        return (float) (0.22 + 0.72 * (1.0 - Math.exp(-Math.min(diameter, 1000.0) / 45.0)));
    }

    static void play(ProjectileImpactContext context) {
        var projectile = context.getProjectile();
        Material material = Material.METAL;
        if (context.getKind() == ProjectileImpactContext.Kind.BLOCK) {
            SoundType surface = context.getBlockState().getSoundType(projectile.level(),
                    context.getBlockPos(), projectile);
            material = classifySound(surface.getBreakSound().getLocation().toString());
        }
        var combat = ProjectileProfiles.combatDescriptor(projectile);
        double caliber = combat == null ? 7.62 : BvpImpactFragmentCommitter.caliber(context, combat);
        float gain = volume(caliber);
        ArmorSoundService.play(projectile.level(), context.getHitVec(), material.sound, gain, 1.0F);
        EliteDiagnostics.record(projectile, "impact", "MATERIAL_SOUND",
                "material", material, "sound", material.sound, "caliber_mm", caliber,
                "volume", gain, "position", context.getHitVec());
    }
}
