package org.pepton.rectpick.config;

/**
 * Abstraction for reading RectPick configuration values.
 * <p>
 * Implementations may use hard-coded values, files, or another config system,
 * but must return values that are safe to use during mod initialization.
 */
public interface IConfigLoader {
    /**
     * Returns the default GLFW key code for the pick key.
     *
     * @return a GLFW keyboard key code compatible with {@code InputConstants.Type.KEYSYM}.
     */
    int getDefaultPickKey();

    /**
     * Returns the maximum DOWN-to-UP drag distance that still counts as a transfer gesture.
     *
     * @return a non-negative distance in GUI-scaled pixels.
     */
    double getMoveOperationMaxDragDistance();

    /**
     * Returns the ARGB color for the active selection rectangle outline.
     *
     * @return ARGB color value.
     */
    int getSelectionOutlineColor();

    /**
     * Returns the ARGB fill color for the active selection rectangle.
     *
     * @return ARGB color value.
     */
    int getSelectionFillColor();

    /**
     * Returns the ARGB fill color for selected source slot backgrounds.
     *
     * @return ARGB color value.
     */
    int getSelectedSlotFillColor();

    /**
     * Returns the ARGB fill color for moved slot backgrounds.
     *
     * @return ARGB color value.
     */
    int getMovedSlotFillColor();

    /**
     * Returns the fade-in duration for selected source slot highlights.
     *
     * @return duration in seconds.
     */
    double getSelectedSlotFadeInSeconds();

    /**
     * Returns the hold duration before moved slot highlights start fading out.
     *
     * @return duration in seconds.
     */
    double getMovedSlotHoldSeconds();

    /**
     * Returns the fade-out duration for moved slot highlights.
     *
     * @return duration in seconds.
     */
    double getMovedSlotFadeOutSeconds();
}
