package com.atsuishio.superbwarfare.resource.vehicle;

import com.atsuishio.superbwarfare.data.IDBasedData;
import com.atsuishio.superbwarfare.data.ObjectToList;
import com.atsuishio.superbwarfare.resource.ModelResource;
import com.google.gson.annotations.SerializedName;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class DefaultVehicleResource implements IDBasedData<DefaultVehicleResource> {

    private transient String id = "";

    @Override
    public @NotNull String getId() {
        return this.id;
    }

    @Override
    public void setId(@NotNull String id) {
        this.id = id;
    }

    @SerializedName("Model")
    private ModelResource model = new ModelResource();

    public ModelResource getModel() {
        return model == null ? new ModelResource() : model;
    }

    @SerializedName("LODDistance")
    public ObjectToList<Double> lodDistance = new ObjectToList<>(48.0, 96.0);

    @SerializedName("RunningGear")
    private RunningGearResource runningGear;

    public @Nullable RunningGearResource getRunningGear() {
        return runningGear;
    }

    @SerializedName("AircraftRig")
    private AircraftRigResource aircraftRig;

    public @Nullable AircraftRigResource getAircraftRig() {
        return aircraftRig;
    }

    @SerializedName("DefensiveStationPresentation")
    private DefensiveStationResource defensiveStationPresentation;

    public @Nullable DefensiveStationResource getDefensiveStationPresentation() {
        return defensiveStationPresentation;
    }

    @SerializedName("FittedGroundRig")
    private FittedGroundRigResource fittedGroundRig;

    public @Nullable FittedGroundRigResource getFittedGroundRig() {
        return fittedGroundRig;
    }

    /** Neutral model pivots/axial vectors, driven only by accepted passenger-station actuals. */
    public static final class DefensiveStationResource {
        @SerializedName("Schema") public Integer schema;
        @SerializedName("Frame") public String frame;
        @SerializedName("Yaw") public AircraftRigResource.Part yaw;
        @SerializedName("Pitch") public AircraftRigResource.Part pitch;
    }

    /** Optional model-pixel rig; the renderer validates the complete graph before applying it. */
    public static final class AircraftRigResource {
        @SerializedName("Schema") public Integer schema;
        @SerializedName("Frame") public String frame;
        @SerializedName("Surfaces") public Surface[] surfaces;
        @SerializedName("Rotors") public Rotor[] rotors;
        @SerializedName("Sweeps") public Sweep[] sweeps;
        @SerializedName("Gear") public Gear[] gear;
        @SerializedName("GearDoors") public GearDoor[] gearDoors;
        @SerializedName("Flaps") public Flap[] flaps;

        /** Whole source subtrees, visibility only; no invented retraction axis or angle. */
        public static final class Gear {
            @SerializedName("Bone") public String bone;
            @SerializedName("Parent") public String parent;
            @SerializedName("VisibleWhen") public String visibleWhen;
        }

        public static class Part {
            @SerializedName("Bone") public String bone;
            @SerializedName("Parent") public String parent;
            @SerializedName("Pivot") public double[] pivot;
            @SerializedName("Axis") public double[] axis;
        }

        /** Source-neutral open door, rotated about its authored hinge as the gear retracts. */
        public static final class GearDoor extends Part {
            @SerializedName("ClosedAngleDegrees") public Double closedAngleDegrees;
        }

        public static final class Surface extends Part {
            @SerializedName("Channel") public String channel;
            @SerializedName("AngleSign") public Integer angleSign;
            @SerializedName("ControlWeights") public ControlWeights controlWeights;
            @SerializedName("MaxDeflectionDegrees") public Double maxDeflectionDegrees;
        }

        /** Presentation derived from the existing authoritative landing-gear travel fraction. */
        public static final class Flap extends Part {
            @SerializedName("Input") public String input;
            @SerializedName("AngleSign") public Integer angleSign;
            @SerializedName("MaxDeflectionDegrees") public Double maxDeflectionDegrees;
        }

        public static final class ControlWeights {
            @SerializedName("ElevatorUp") public Double elevatorUp;
            @SerializedName("RightRoll") public Double rightRoll;
            @SerializedName("RudderRight") public Double rudderRight;
        }

        public static final class Sweep extends Part {
            @SerializedName("AngleSign") public Integer angleSign;
            @SerializedName("MaxDeflectionDegrees") public Double maxDeflectionDegrees;
            @SerializedName("Schedule") public SweepSchedule schedule;
        }

        public static final class SweepSchedule {
            @SerializedName("Input") public String input;
            @SerializedName("Interpolation") public String interpolation;
            @SerializedName("Points") public double[][] points;
        }

        public static final class Rotor extends Part {
            @SerializedName("SpeedChannel") public String speedChannel;
            @SerializedName("Direction") public Integer direction;
            @SerializedName("DegreesPerTickAtFullSpeed") public Double degreesPerTickAtFullSpeed;
        }
    }

    @SerializedName("AfterburnerPresentation")
    private AfterburnerResource afterburnerPresentation;

    public @Nullable AfterburnerResource getAfterburnerPresentation() {
        return afterburnerPresentation;
    }

    /** Resource-loading values; the client validates and copies them before emission. */
    public static final class AfterburnerResource {
        @SerializedName("Schema") public int schema;
        @SerializedName("Frame") public String frame;
        @SerializedName("Outlets") public Outlet[] outlets;
        @SerializedName("Flame") public Stream flame = new Stream();
        @SerializedName("Smoke") public Stream smoke = new Stream();
        @SerializedName("TapEmitters") public TapEmitter[] tapEmitters;

        public static final class TapEmitter {
            @SerializedName("Outlet") public String outlet;
            @SerializedName("Style") public String style;
            @SerializedName("Interval") public int interval = 1;
            @SerializedName("Offset") public double[] offset;
            @SerializedName("Extents") public double[] extents;
            @SerializedName("Velocity") public double[] velocity;
        }

        public static final class Outlet {
            @SerializedName("Id") public String id;
            @SerializedName("Position") public double[] position;
            @SerializedName("Direction") public double[] direction;
            /** Exit radius of the nozzle in blocks (measured from the model); drives the afterburner plume size. */
            @SerializedName("NozzleRadiusBlocks") public Double nozzleRadiusBlocks;
        }

        public static final class Stream {
            @SerializedName("Scale") public Double scale;
            @SerializedName("WidthBlocks") public Double widthBlocks;
            @SerializedName("LengthBlocks") public Double lengthBlocks;
            @SerializedName("ParticlesPerTick") public Double particlesPerTick;
            @SerializedName("LifetimeTicks") public Integer lifetimeTicks;
            @SerializedName("ColorRgb") public double[] colorRgb;
        }
    }

    @SerializedName("FlightDisplays")
    private FlightDisplaysResource flightDisplays;

    /** Glass-cockpit primary flight displays mounted on the instrument panel; null for steam-gauge cockpits. */
    public @Nullable FlightDisplaysResource getFlightDisplays() {
        return flightDisplays;
    }

    /** VEHICLE_LOCAL_BLOCKS (+X left, +Y up, +Z forward) screen placements, one per crew station with a panel. */
    public static final class FlightDisplaysResource {
        @SerializedName("Schema") public int schema;
        @SerializedName("Frame") public String frame;
        @SerializedName("Displays") public Display[] displays;
        /** Top-view outline polylines of the airframe for the stores page: [x, z, x, z, ...] vehicle-local blocks. */
        @SerializedName("Planform") public double[][] planform;

        public static final class Display {
            @SerializedName("Seat") public int seat;
            /** Centre of the screen surface. */
            @SerializedName("Center") public double[] center;
            /** Unit normal of the screen, pointing at the crew member. */
            @SerializedName("Normal") public double[] normal;
            /** Unit screen-up direction, perpendicular to the normal. */
            @SerializedName("Up") public double[] up;
            /** Edge length of the square screen, blocks. */
            @SerializedName("Width") public double width;
            /** Depth of the display housing behind the screen, blocks. */
            @SerializedName("Depth") public double depth;
            /** Page shown: "PFD" (primary flight display, the default), "RADAR" or "STORES". */
            @SerializedName("Kind") public @Nullable String kind;
            /** Screen technology: "LCD" (colour, the default) or "CRT" (green phosphor). */
            @SerializedName("Style") public @Nullable String style;
        }
    }

    @SerializedName("CanopyGlass")
    private CanopyGlassResource canopyGlass;

    /** Tinted canopy glass fitted over the canopy openings (tools/canopy_glass/glass.py). */
    public @Nullable CanopyGlassResource getCanopyGlass() {
        return canopyGlass;
    }

    /**
     * VEHICLE_LOCAL_BLOCKS (+X left, +Y up, +Z forward) canopy glass. Schema 1: loose triangles, three xyz corners
     * each. Schema 2: a dome - the apex and, for each spoke round it, Rings points from the apex out to the rim
     * (neighbouring spokes, wrapping round, are joined into one sheet).
     */
    public static final class CanopyGlassResource {
        @SerializedName("Schema") public int schema;
        @SerializedName("Frame") public String frame;
        @SerializedName("Triangles") public double[] triangles;
        @SerializedName("Apex") public double[] apex;
        @SerializedName("Rings") public int rings;
        @SerializedName("Spokes") public double[][] spokes;
    }

    @SerializedName("CockpitGauges")
    private CockpitGaugesResource cockpitGauges;

    /** Round analogue ("steam") instruments on the panels of cockpits without electronic displays. */
    public @Nullable CockpitGaugesResource getCockpitGauges() {
        return cockpitGauges;
    }

    /** VEHICLE_LOCAL_BLOCKS (+X left, +Y up, +Z forward) gauge placements. */
    public static final class CockpitGaugesResource {
        @SerializedName("Schema") public int schema;
        @SerializedName("Frame") public String frame;
        @SerializedName("Gauges") public Gauge[] gauges;

        public static final class Gauge {
            @SerializedName("Seat") public int seat;
            /** ALTIMETER, ATTITUDE, HEADING, THROTTLE (live for the pilot) or a FILLER_* dial (fixed). */
            @SerializedName("Kind") public String kind;
            /** Centre of the dial face. */
            @SerializedName("Center") public double[] center;
            /** Unit normal of the dial, pointing at the crew member. */
            @SerializedName("Normal") public double[] normal;
            /** Unit dial-up direction, perpendicular to the normal. */
            @SerializedName("Up") public double[] up;
            /** Dial diameter, blocks. */
            @SerializedName("Diameter") public double diameter;
            /** Depth of the case behind the dial, blocks. */
            @SerializedName("Depth") public double depth;
        }
    }

    @SerializedName("EngineExhaust")
    private EngineExhaustResource engineExhaust;

    public @Nullable EngineExhaustResource getEngineExhaust() {
        return engineExhaust;
    }

    /** Optional HULL-local exhaust origins emitted by the camera-slice resource contract. */
    public static final class EngineExhaustResource {
        private static final Origin[] EMPTY_ORIGINS = new Origin[0];

        @SerializedName("Schema")
        private String schema = "";

        @SerializedName("Parent")
        private String parent = "";

        @SerializedName("Origins")
        private Origin[] origins = EMPTY_ORIGINS;

        public String getSchema() {
            return schema == null ? "" : schema;
        }

        public String getParent() {
            return parent == null ? "" : parent;
        }

        public Origin[] getOrigins() {
            return origins == null ? EMPTY_ORIGINS : origins;
        }

        public static final class Origin {
            private static final double[] EMPTY_POSITION = new double[0];

            @SerializedName("Id")
            private String id = "";

            @SerializedName("Position")
            private double[] position = EMPTY_POSITION;

            public String getId() {
                return id == null ? "" : id;
            }

            public double[] getPosition() {
                return position == null ? EMPTY_POSITION : position;
            }
        }
    }
}
