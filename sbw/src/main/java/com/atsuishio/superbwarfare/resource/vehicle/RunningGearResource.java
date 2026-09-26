package com.atsuishio.superbwarfare.resource.vehicle;

import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.Nullable;

/** Raw client-resource schema. Use the validated client profile API at runtime. */
public final class RunningGearResource {

    private static final String[] EMPTY_NAMES = new String[0];
    private static final TrackSide[] EMPTY_SIDES = new TrackSide[0];
    private static final float[] EMPTY_FLOATS = new float[0];
    private static final float[][] EMPTY_CURVES = new float[0][];
    private static final WheelBones EMPTY_WHEEL_BONES = new WheelBones();

    @SerializedName("Type")
    private String type = "NONE";

    @SerializedName("RoadWheelCount")
    private int roadWheelCount;

    @SerializedName("WheelBones")
    private WheelBones wheelBones = EMPTY_WHEEL_BONES;

    @SerializedName("TrackRender")
    private TrackRender trackRender;

    @SerializedName("Steering")
    private SteeringRig steering;

    public String getType() {
        return type;
    }

    public int getRoadWheelCount() {
        return roadWheelCount;
    }

    public WheelBones getWheelBones() {
        return wheelBones == null ? EMPTY_WHEEL_BONES : wheelBones;
    }

    public @Nullable TrackRender getTrackRender() {
        return trackRender;
    }

    public @Nullable SteeringRig getSteering() {
        return steering;
    }

    /** Optional yaw parents; the existing wheel bones remain the exclusive spin children. */
    public static final class SteeringRig {
        @SerializedName("Schema")
        private Integer schema;

        @SerializedName("Wheels")
        private SteeringWheel[] wheels;

        public @Nullable Integer getSchema() { return schema; }

        public @Nullable SteeringWheel[] getWheels() { return wheels; }
    }

    public static final class SteeringWheel {
        @SerializedName("SteeringBone")
        private String steeringBone;

        @SerializedName("WheelBone")
        private String wheelBone;

        @SerializedName("MaxAngleDegrees")
        private Float maxAngleDegrees;

        @SerializedName("Sign")
        private Integer sign;

        public @Nullable String getSteeringBone() { return steeringBone; }

        public @Nullable String getWheelBone() { return wheelBone; }

        public @Nullable Float getMaxAngleDegrees() { return maxAngleDegrees; }

        public @Nullable Integer getSign() { return sign; }
    }

    public static final class WheelBones {
        @SerializedName("Left")
        private String[] left = EMPTY_NAMES;

        @SerializedName("Right")
        private String[] right = EMPTY_NAMES;

        public String[] getLeft() {
            return left == null ? EMPTY_NAMES : left;
        }

        public String[] getRight() {
            return right == null ? EMPTY_NAMES : right;
        }
    }

    public static final class TrackRender {
        @SerializedName("Mode")
        private String mode = "LINKS";

        @SerializedName("LinkCount")
        private int linkCount;

        @SerializedName("PhaseDistance")
        private float phaseDistance;

        @SerializedName("TravelScale")
        private float travelScale = 1.0F;

        @SerializedName("LinkHalfThickness")
        private float linkHalfThickness;

        @SerializedName("LinkFit")
        private String linkFit = "CONTACT_INTERVAL";

        @SerializedName("EvaluationLayout")
        private TrackBounds evaluationLayout;

        @SerializedName("Sides")
        private TrackSide[] sides = EMPTY_SIDES;

        @SerializedName("Path")
        private TrackPath path;

        @SerializedName("Auto")
        private TrackAuto auto;

        /** Runtime belt from the wheels; absent means the default automatic belt. */
        public @Nullable TrackAuto getAuto() {
            return auto;
        }

        public String getMode() {
            return mode;
        }

        public int getLinkCount() {
            return linkCount;
        }

        public float getPhaseDistance() {
            return phaseDistance;
        }

        public float getTravelScale() {
            return travelScale;
        }

        public float getLinkHalfThickness() {
            return linkHalfThickness;
        }

        public String getLinkFit() {
            return linkFit;
        }

        public @Nullable TrackBounds getEvaluationLayout() {
            return evaluationLayout;
        }

        public TrackSide[] getSides() {
            return sides == null ? EMPTY_SIDES : sides;
        }

        public @Nullable TrackPath getPath() {
            return path;
        }

    }

    /** Belt traced at runtime around the side's wheels (see TrackBeltPath). */
    public static final class TrackAuto {
        @SerializedName("Enabled")
        private boolean enabled = true;

        /** Model pixels outward from each side's links (the same for both sides). */
        @SerializedName("OffsetX")
        private float offsetX;

        /** Model pixels up for the whole belt (both sides). */
        @SerializedName("OffsetY")
        private float offsetY;

        /** Wheel radius overrides in model pixels, by bone name. */
        @SerializedName("Radii")
        private java.util.Map<String, Float> radii;

        /** Optional explicit wheel order per side (lets a belt sag onto rollers); default is the convex hull. */
        @SerializedName("Order")
        private WheelBones order;

        /** Beyond this many blocks from the camera the static fallback track is drawn instead of the links. */
        @SerializedName("FarLinksBlocks")
        private float farLinksBlocks = 96.0F;

        public boolean isEnabled() { return enabled; }

        public float getOffsetX() { return offsetX; }

        public float getOffsetY() { return offsetY; }

        public java.util.Map<String, Float> getRadii() { return radii == null ? java.util.Map.of() : radii; }

        public @Nullable WheelBones getOrder() { return order; }

        public float getFarLinksBlocks() { return farLinksBlocks; }
    }

    public static class TrackBounds {
        @SerializedName("YCenter")
        private float yCenter;

        @SerializedName("Radius")
        private float radius;

        @SerializedName("ZRear")
        private float zRear;

        @SerializedName("ZFront")
        private float zFront;

        public float getYCenter() {
            return yCenter;
        }

        public float getRadius() {
            return radius;
        }

        public float getZRear() {
            return zRear;
        }

        public float getZFront() {
            return zFront;
        }
    }

    public static final class TrackSide extends TrackBounds {
        @SerializedName("Side")
        private String side = "";

        /** Optional side-specific path. When omitted, TrackRender.Path is used. */
        @SerializedName("Path")
        private TrackPath path;

        @SerializedName("Direction")
        private int direction;

        @SerializedName("XCenter")
        private float xCenter;

        @SerializedName("YBottom")
        private float yBottom;

        @SerializedName("YTop")
        private float yTop;

        @SerializedName("RoadCenters")
        private float[] roadCenters = EMPTY_FLOATS;

        @SerializedName("TrackBone")
        private String trackBone = "";

        @SerializedName("BrokenBone")
        private String brokenBone = "";

        @SerializedName("FallbackBone")
        private String fallbackBone = "";

        @SerializedName("LinkMovePrefix")
        private String linkMovePrefix = "";

        @SerializedName("LinkRotationPrefix")
        private String linkRotationPrefix = "";

        @SerializedName("LegacyFramePrefix")
        private String legacyFramePrefix = "";

        public String getSide() {
            return side;
        }

        public @Nullable TrackPath getPath() {
            return path;
        }

        public int getDirection() {
            return direction;
        }

        public float getXCenter() {
            return xCenter;
        }

        public float getYBottom() {
            return yBottom;
        }

        public float getYTop() {
            return yTop;
        }

        public float[] getRoadCenters() {
            return roadCenters == null ? EMPTY_FLOATS : roadCenters;
        }

        public String getTrackBone() {
            return trackBone;
        }

        public String getBrokenBone() {
            return brokenBone;
        }

        public String getFallbackBone() {
            return fallbackBone;
        }

        public String getLinkMovePrefix() {
            return linkMovePrefix;
        }

        public String getLinkRotationPrefix() {
            return linkRotationPrefix;
        }

        public String getLegacyFramePrefix() {
            return legacyFramePrefix;
        }
    }

    public static final class TrackPath {
        @SerializedName("SourceYMin")
        private float sourceYMin;

        @SerializedName("SourceYMax")
        private float sourceYMax;

        @SerializedName("SourceZMin")
        private float sourceZMin;

        @SerializedName("SourceZMax")
        private float sourceZMax;

        @SerializedName("MoveY")
        private float[][] moveY = EMPTY_CURVES;

        @SerializedName("MoveZ")
        private float[][] moveZ = EMPTY_CURVES;

        @SerializedName("RotationX")
        private float[][] rotationX = EMPTY_CURVES;

        public float getSourceYMin() {
            return sourceYMin;
        }

        public float getSourceYMax() {
            return sourceYMax;
        }

        public float getSourceZMin() {
            return sourceZMin;
        }

        public float getSourceZMax() {
            return sourceZMax;
        }

        public float[][] getMoveY() {
            return moveY == null ? EMPTY_CURVES : moveY;
        }

        public float[][] getMoveZ() {
            return moveZ == null ? EMPTY_CURVES : moveZ;
        }

        public float[][] getRotationX() {
            return rotationX == null ? EMPTY_CURVES : rotationX;
        }
    }

}
