package com.yourname.berts_vehicle_pack.armor;

import java.util.Collections;
import java.util.List;

/**
 * Immutable server-owned armor impact classification and shooter presentation payload.
 *
 * <p>The payload is deliberately local to the BVP armor boundary: the existing private
 * ArmorEventStatus packet carries its rendered lines, while this object keeps the authoritative
 * classification, incidence diagnostics, module state, and any shooter-only notification in one
 * place.  It is never constructed from client input.</p>
 */
public final class ArmorImpactFeedback {
    public enum Classification {
        MISS,
        RICOCHET,
        NON_PENETRATION,
        PENETRATION,
        /** A projectile struck an exposed track module; this is not plate penetration. */
        TRACK_HIT,
        /** A projectile struck an exposed non-track module; this is not plate penetration. */
        MODULE_HIT
    }

    private final Classification classification;
    private final double incidenceAngleDegrees;
    private final double effectiveArmorMm;
    private final String moduleName;
    private final double moduleRemainingHp;
    private final List<String> shooterNotifications;

    private ArmorImpactFeedback(Classification classification, double incidenceAngleDegrees,
                                double effectiveArmorMm, String moduleName,
                                double moduleRemainingHp, List<String> shooterNotifications) {
        this.classification = classification;
        this.incidenceAngleDegrees = finiteOrNaN(incidenceAngleDegrees);
        this.effectiveArmorMm = finiteOrNaN(effectiveArmorMm);
        this.moduleName = moduleName == null ? "" : moduleName;
        this.moduleRemainingHp = finiteOrNaN(moduleRemainingHp);
        this.shooterNotifications = shooterNotifications == null || shooterNotifications.isEmpty()
                ? List.of()
                : Collections.unmodifiableList(List.copyOf(shooterNotifications));
    }

    public static ArmorImpactFeedback missed() {
        return new ArmorImpactFeedback(Classification.MISS, Double.NaN, Double.NaN,
                "", Double.NaN, List.of());
    }

    public static ArmorImpactFeedback plate(Classification classification, double incidenceAngleDegrees,
                                             double effectiveArmorMm, String moduleName,
                                             double moduleRemainingHp, List<String> notifications) {
        if (classification != Classification.RICOCHET
                && classification != Classification.NON_PENETRATION
                && classification != Classification.PENETRATION
                && classification != Classification.TRACK_HIT
                && classification != Classification.MODULE_HIT) {
            throw new IllegalArgumentException("plate classification must be impact status");
        }
        return new ArmorImpactFeedback(classification, incidenceAngleDegrees, effectiveArmorMm,
                moduleName, moduleRemainingHp, notifications);
    }

    public Classification classification() {
        return classification;
    }

    public double incidenceAngleDegrees() {
        return incidenceAngleDegrees;
    }

    public double effectiveArmorMm() {
        return effectiveArmorMm;
    }

    public String moduleName() {
        return moduleName;
    }

    public double moduleRemainingHp() {
        return moduleRemainingHp;
    }

    public List<String> shooterNotifications() {
        return shooterNotifications;
    }

    public String primaryLine() {
        return switch (classification) {
            case MISS -> "Shot missed!";
            case RICOCHET -> "Ricochet!";
            case PENETRATION -> "Successful penetration!";
            case TRACK_HIT -> "Track hit!";
            case MODULE_HIT -> "Module hit!";
            case NON_PENETRATION -> Double.isFinite(incidenceAngleDegrees)
                    && Double.isFinite(effectiveArmorMm)
                    ? String.format(java.util.Locale.ROOT,
                    "Non-penetration! Angle: %.0f\u00B0 Effective armor: %.0fmm",
                    incidenceAngleDegrees, effectiveArmorMm)
                    : "Non-penetration!";
        };
    }

    public String moduleLine() {
        if (moduleName.isBlank() || !Double.isFinite(moduleRemainingHp)) {
            return "";
        }
        return String.format(java.util.Locale.ROOT, "%s damaged! Remaining HP: %.1f",
                moduleName, Math.max(0.0D, moduleRemainingHp));
    }

    private static double finiteOrNaN(double value) {
        return Double.isFinite(value) ? value : Double.NaN;
    }
}
