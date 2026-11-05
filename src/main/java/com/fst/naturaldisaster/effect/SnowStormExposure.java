package com.fst.naturaldisaster.effect;

import net.minecraft.world.level.Level;

/** Short-lived player exposure, not an event timer; never serialized or copied on respawn. */
public final class SnowStormExposure {
    private Level level;
    private long refreshedAt;

    public void refresh(Level level) {
        this.level = level;
        refreshedAt = level.getGameTime();
    }

    public boolean isActive(Level level) {
        long elapsed = level.getGameTime() - refreshedAt;
        return this.level == level && elapsed >= 0 && elapsed <= 1;
    }

    public void clear() {
        level = null;
    }
}
