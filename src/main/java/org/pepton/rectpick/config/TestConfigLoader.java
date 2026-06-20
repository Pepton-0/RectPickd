package org.pepton.rectpick.config;

import org.lwjgl.glfw.GLFW;

/**
 * Temporary hard-coded config loader used until a real config file is introduced.
 */
public final class TestConfigLoader implements IConfigLoader {
    @Override
    public int getDefaultPickKey() {
        return GLFW.GLFW_KEY_C;
    }

    @Override
    public double getMoveOperationMaxDragDistance() {
        return 20;
    }

    @Override
    public int getSelectionOutlineColor() {
        return 0xCC3399FF;
    }

    @Override
    public int getSelectionFillColor() {
        return 0x333399FF;
    }

    @Override
    public int getSelectedSlotFillColor() {
        return 0x993399FF;
    }

    @Override
    public int getMovedSlotFillColor() {
        return 0x993399FF;
    }

    @Override
    public double getSelectedSlotFadeInSeconds() {
        return 0.3;
    }

    @Override
    public double getMovedSlotHoldSeconds() {
        return 0.1;
    }

    @Override
    public double getMovedSlotFadeOutSeconds() {
        return 0.3;
    }
}
