package com.atsuishio.superbwarfare.client.renderer.vehicle

enum class TrackVisualState {
    INTACT,
    BROKEN,
    FALLBACK,
}

/** Chooses only an actually renderable per-side visual; null means no track asset is ready. */
object RunningGearVisualSelector {
    @JvmStatic
    fun select(
        brokenRequested: Boolean,
        intactReady: Boolean,
        brokenReady: Boolean,
        fallbackReady: Boolean,
    ): TrackVisualState? {
        if (brokenRequested) {
            if (brokenReady) return TrackVisualState.BROKEN
            if (fallbackReady) return TrackVisualState.FALLBACK
            if (intactReady) return TrackVisualState.INTACT
            return null
        }
        if (intactReady) return TrackVisualState.INTACT
        if (fallbackReady) return TrackVisualState.FALLBACK
        return null
    }
}
