package org.pepton.rectpick.config;

/**
 * Singleton holder for global RectPick settings.
 * <p>
 * The singleton must be initialized exactly once through {@link #initialize(IConfigLoader)}
 * before any caller uses {@link #get()}.
 */
public final class Consts {
    /**
     * Enables RectPick operation logs used while checking inventory selection and transfer behavior.
     */
    public static final boolean debugLog = true;

    /**
     * Maximum distance in GUI-scaled pixels for snapping a transfer target to the nearest inventory.
     */
    public static final double transferTargetSnapDistance = 10;

    /**
     * Language key used by the pick key mapping.
     */
    public static final String pickKeyTranslationKey = "key.rectpick.pick";

    /**
     * Language key used for the RectPick controls category.
     */
    public static final String keyCategoryTranslationKey = "key.categories.rectpick";

    /**
     * Client ticks to wait for delayed transfer feedback after a server-side inventory sync.
     */
    public static final int transferFeedbackWaitTicks = 10;

    /**
     * Forces client-side transfer fallback for debugging instead of sending RectPick server payloads.
     */
    public static final boolean disableServerTransferForDebug = false;

    private static Consts instance;

    private final int defaultPickKey;
    private final double moveOperationMaxDragDistance;
    private final int selectionOutlineColor;
    private final int selectionFillColor;
    private final int selectedSlotFillColor;
    private final int movedSlotFillColor;
    private final double selectedSlotFadeInSeconds;
    private final double movedSlotHoldSeconds;
    private final double movedSlotFadeOutSeconds;

    /**
     * Loads immutable settings from the supplied loader.
     *
     * @param loader config loader that must return valid, non-null string values and sane numeric values.
     */
    private Consts(IConfigLoader loader) {
        this.defaultPickKey = loader.getDefaultPickKey();
        this.moveOperationMaxDragDistance = loader.getMoveOperationMaxDragDistance();
        this.selectionOutlineColor = loader.getSelectionOutlineColor();
        this.selectionFillColor = loader.getSelectionFillColor();
        this.selectedSlotFillColor = loader.getSelectedSlotFillColor();
        this.movedSlotFillColor = loader.getMovedSlotFillColor();
        this.selectedSlotFadeInSeconds = loader.getSelectedSlotFadeInSeconds();
        this.movedSlotHoldSeconds = loader.getMovedSlotHoldSeconds();
        this.movedSlotFadeOutSeconds = loader.getMovedSlotFadeOutSeconds();
    }

    /**
     * Initializes the singleton settings instance.
     *
     * @param loader config loader to read from; must not be {@code null} and must not be reused after another initialization.
     * @throws IllegalStateException if the singleton has already been initialized.
     */
    public static void initialize(IConfigLoader loader) {
        if (instance != null) {
            throw new IllegalStateException("Consts is already initialized");
        }

        instance = new Consts(loader);
    }

    /**
     * Returns the initialized singleton settings instance.
     *
     * @return the singleton instance containing immutable settings loaded at startup.
     * @throws IllegalStateException if called before {@link #initialize(IConfigLoader)}.
     */
    public static Consts get() {
        if (instance == null) {
            throw new IllegalStateException("Consts has not been initialized");
        }

        return instance;
    }

    /**
     * Returns the default GLFW key code for the pick key.
     *
     * @return the configured GLFW key code without remapping.
     */
    public int defaultPickKey() {
        return defaultPickKey;
    }

    /**
     * Returns the allowed drag distance for treating a small drag as a transfer gesture.
     *
     * @return the configured tolerance in GUI-scaled pixels.
     */
    public double moveOperationMaxDragDistance() {
        return moveOperationMaxDragDistance;
    }

    /**
     * Returns the ARGB color used for the active selection rectangle outline.
     *
     * @return configured ARGB color value.
     */
    public int selectionOutlineColor() {
        return selectionOutlineColor;
    }

    /**
     * Returns the ARGB color used for the active selection rectangle fill.
     *
     * @return configured ARGB color value.
     */
    public int selectionFillColor() {
        return selectionFillColor;
    }

    /**
     * Returns the ARGB color used for selected source slot backgrounds.
     *
     * @return configured ARGB color value.
     */
    public int selectedSlotFillColor() {
        return selectedSlotFillColor;
    }

    /**
     * Returns the ARGB color used for moved slot backgrounds.
     *
     * @return configured ARGB color value.
     */
    public int movedSlotFillColor() {
        return movedSlotFillColor;
    }

    /**
     * Returns the fade-in duration for selected source slot highlights.
     *
     * @return duration in seconds.
     */
    public double selectedSlotFadeInSeconds() {
        return selectedSlotFadeInSeconds;
    }

    /**
     * Returns the hold duration before moved slot highlights start fading out.
     *
     * @return duration in seconds.
     */
    public double movedSlotHoldSeconds() {
        return movedSlotHoldSeconds;
    }

    /**
     * Returns the fade-out duration for moved slot highlights.
     *
     * @return duration in seconds.
     */
    public double movedSlotFadeOutSeconds() {
        return movedSlotFadeOutSeconds;
    }
}
