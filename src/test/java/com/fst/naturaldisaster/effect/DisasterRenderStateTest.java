package com.fst.naturaldisaster.effect;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class DisasterRenderStateTest {
    @Test
    void fadesInAndOutOverFortyTicksWithoutJumpingAtTheFlagChange() {
        var state = new DisasterRenderState();
        assertFalse(state.isRendering());
        assertEquals(0f, state.intensity(1f));
        state.setRendering(true);
        assertTrue(state.isRendering());
        assertEquals(0f, state.intensity(1f));
        for (int i = 0; i < 20; i++) state.tick();
        assertEquals(0.5f, state.intensity(1f));
        for (int i = 0; i < 20; i++) state.tick();
        assertEquals(1f, state.intensity(1f));
        state.setRendering(false);
        assertFalse(state.isRendering());
        assertEquals(1f, state.intensity(1f));
        for (int i = 0; i < 40; i++) state.tick();
        assertEquals(0f, state.intensity(1f));
        state.tick();
        assertEquals(0f, state.intensity(0f));
    }

    @Test
    void reversalsAndDuplicateFlagsContinueFromCurrentIntensity() {
        var state = new DisasterRenderState();
        state.setRendering(true);
        for (int i = 0; i < 24; i++) state.tick();
        float current = state.intensity(1f);
        state.setRendering(false);
        assertEquals(current, state.intensity(1f));
        state.tick();
        assertEquals(23f / 40f, state.intensity(1f));
        state.setRendering(false);
        state.tick();
        assertEquals(22f / 40f, state.intensity(1f));
        state.setRendering(true);
        state.tick();
        assertEquals(23f / 40f, state.intensity(1f));
        assertEquals(22.5f / 40f, state.intensity(0.5f));
    }

    @Test
    void resetDropsTheOldWorldStateAndPlayersHaveIndependentTransitions() {
        var first = new DisasterRenderState();
        var second = new DisasterRenderState();
        first.setRendering(true);
        first.tick();
        assertEquals(0f, second.intensity(1f));
        assertFalse(second.isRendering());
        first.reset();
        assertFalse(first.isRendering());
        assertEquals(0f, first.intensity(0f));
        assertEquals(0f, first.intensity(1f));
    }
}
