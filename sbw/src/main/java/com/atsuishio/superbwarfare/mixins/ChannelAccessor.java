package com.atsuishio.superbwarfare.mixins;

import com.mojang.blaze3d.audio.Channel;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** The OpenAL source name behind a playing channel (for per-source velocity: Doppler). */
@Mixin(Channel.class)
public interface ChannelAccessor {
    @Accessor("source")
    int superbwarfare$source();
}
