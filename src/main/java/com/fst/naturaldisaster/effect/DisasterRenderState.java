package com.fst.naturaldisaster.effect;

/** Per-player visual target and client transition only; never an event/lifecycle state. */
public final class DisasterRenderState {
    public static final int FADE_TICKS = 40;
    private boolean rendering;
    private int previous;
    private int current;

    public boolean isRendering() {
        return rendering;
    }

    /** Changing direction preserves the current intensity, including repeated boundary crossings. */
    public void setRendering(boolean rendering) {
        this.rendering = rendering;
    }

    /** Client tick only. Integer progress guarantees exact endpoints with no accumulated error. */
    public void tick() {
        previous = current;
        current = rendering ? Math.min(FADE_TICKS, current + 1) : Math.max(0, current - 1);
    }

    public float intensity(float partialTick) {
        float partial = Math.max(0f, Math.min(1f, partialTick));
        return (previous + (current - previous) * partial) / FADE_TICKS;
    }

    public void reset() {
        rendering = false;
        previous = 0;
        current = 0;
    }
}
