package com.atsuishio.superbwarfare.resource;

import com.atsuishio.superbwarfare.data.ObjectToList;
import com.google.gson.annotations.SerializedName;
import net.minecraft.resources.ResourceLocation;
import org.jetbrains.annotations.Nullable;

public class ModelResource {

    /** Optional namespaced client geometry backend; null keeps native Gecko rendering. */
    @SerializedName("GeometryBackend")
    private ResourceLocation geometryBackend;

    public @Nullable ResourceLocation getGeometryBackend() {
        return geometryBackend;
    }

    /** Optional authored wreck texture. When present, no generic wreck-darkening pass is applied. */
    @SerializedName("WreckTexture")
    private ResourceLocation wreckTexture;

    public @Nullable ResourceLocation getWreckTexture() {
        return wreckTexture;
    }

    @SerializedName("Animation")
    public ResourceLocation animation;

    @SerializedName("Model")
    public ResourceLocation model;

    @SerializedName("LODModel")
    protected ObjectToList<ResourceLocation> lodModel = new ObjectToList<>();

    public boolean hasLOD() {
        return lodModel != null && !lodModel.list.isEmpty();
    }

    // LOD的最小等级为1
    public ResourceLocation getLODModel(int level) {
        if (level < 1 || lodModel == null || lodModel.list.isEmpty()) return model;

        var availableLevel = Math.min(level - 1, lodModel.list.size() - 1);
        return lodModel.list.get(availableLevel);
    }

    @SerializedName("Texture")
    public ResourceLocation texture;

    @SerializedName("LODTexture")
    protected ObjectToList<ResourceLocation> lodTexture;

    // LOD的最小等级为1
    public ResourceLocation getLODTexture(int level) {
        if (level < 1 || lodTexture == null || lodTexture.list.isEmpty()) return texture;

        var availableLevel = Math.min(level - 1, lodTexture.list.size() - 1);
        return lodTexture.list.get(availableLevel);
    }
}
