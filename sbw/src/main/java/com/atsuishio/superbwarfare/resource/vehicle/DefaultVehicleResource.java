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
