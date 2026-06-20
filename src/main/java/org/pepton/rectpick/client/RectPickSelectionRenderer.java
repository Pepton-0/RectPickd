package org.pepton.rectpick.client;

import com.mojang.blaze3d.platform.Window;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.world.inventory.Slot;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ScreenEvent;
import org.pepton.rectpick.config.Consts;

/**
 * Client-side renderer for RectPick visual feedback.
 * <p>
 * This class intentionally does not decide which slots are selected or moved.
 * It only stores renderable state pushed by {@link InventoryRangeSelectionHandler}
 * and draws that state during screen rendering.
 */
public final class RectPickSelectionRenderer {
    private static final int SLOT_SIZE = 16;

    private GuiPoint startPos;
    private GuiPoint endPos;
    private boolean selecting;

    private int selectedMenuId = -1;
    private List<Integer> selectedSlotIndices = List.of();
    private long selectedStartedNanos;

    private int movedMenuId = -1;
    private List<Integer> movedSlotIndices = List.of();
    private long movedStartedNanos;

    /**
     * Starts rendering an active rectangle selection.
     *
     * @param startPos GUI-scaled selection start point; must not be {@code null}.
     */
    public void beginSelection(GuiPoint startPos) {
        this.selecting = true;
        this.startPos = startPos;
        this.endPos = startPos;
    }

    /**
     * Stops rendering the active rectangle selection without touching stored slot highlights.
     */
    public void endSelection() {
        selecting = false;
        startPos = null;
        endPos = null;
    }

    /**
     * Clears all visual state owned by this renderer.
     */
    public void clearAll() {
        endSelection();
        clearSelectedSlots();
        clearMovedSlots();
    }

    /**
     * Starts or refreshes the selected-source-slot highlight animation.
     *
     * @param menuId menu id that owns the highlighted slot indices.
     * @param slotIndices menu slot indices to render.
     */
    public void showSelectedSlots(int menuId, List<Integer> slotIndices) {
        selectedMenuId = menuId;
        selectedSlotIndices = List.copyOf(slotIndices);
        selectedStartedNanos = System.nanoTime();
    }

    /**
     * Clears only selected-source-slot highlights.
     */
    public void clearSelectedSlots() {
        selectedMenuId = -1;
        selectedSlotIndices = List.of();
        selectedStartedNanos = 0L;
    }

    /**
     * Starts or refreshes the moved-slot highlight animation.
     *
     * @param menuId menu id that owns the highlighted slot indices.
     * @param slotIndices menu slot indices to render.
     */
    public void showMovedSlots(int menuId, List<Integer> slotIndices) {
        movedMenuId = menuId;
        movedSlotIndices = List.copyOf(slotIndices);
        movedStartedNanos = System.nanoTime();
    }

    /**
     * Clears only moved-slot highlights.
     */
    public void clearMovedSlots() {
        movedMenuId = -1;
        movedSlotIndices = List.of();
        movedStartedNanos = 0L;
    }

    /**
     * Draws RectPick visual feedback after the current screen has rendered.
     *
     * @param event screen render post event.
     */
    @SubscribeEvent
    public void onScreenRenderPost(ScreenEvent.Render.Post event) {
        if (!(event.getScreen() instanceof AbstractContainerScreen<?> screen)) {
            return;
        }

        GuiGraphics guiGraphics = event.getGuiGraphics();
        updateActiveSelectionEndFromMouse();
        drawSelectedSlotHighlights(guiGraphics, screen);
        drawMovedSlotHighlights(guiGraphics, screen);
        drawActiveSelectionOutline(guiGraphics);
    }

    /**
     * Updates the visual-only selection end point from the current mouse position.
     */
    private void updateActiveSelectionEndFromMouse() {
        if (!selecting) {
            return;
        }

        endPos = currentGuiMousePosition();
    }

    /**
     * Draws selected source slot backgrounds with a short sine fade-in.
     */
    private void drawSelectedSlotHighlights(GuiGraphics guiGraphics, AbstractContainerScreen<?> screen) {
        if (selectedSlotIndices.isEmpty() || selectedMenuId != screen.getMenu().containerId) {
            return;
        }

        double elapsedSeconds = elapsedSecondsSince(selectedStartedNanos);
        double progress = clamp(elapsedSeconds / Consts.get().selectedSlotFadeInSeconds(), 0.0, 1.0);
        double alphaFactor = Math.sin(progress * Math.PI * 0.5);
        int color = withAlphaFactor(Consts.get().selectedSlotFillColor(), alphaFactor);

        drawSlotHighlights(guiGraphics, screen, selectedSlotIndices, color);
    }

    /**
     * Draws moved slot backgrounds with hold and sine fade-out.
     */
    private void drawMovedSlotHighlights(GuiGraphics guiGraphics, AbstractContainerScreen<?> screen) {
        if (movedSlotIndices.isEmpty() || movedMenuId != screen.getMenu().containerId) {
            return;
        }

        double elapsedSeconds = elapsedSecondsSince(movedStartedNanos);
        double holdSeconds = Consts.get().movedSlotHoldSeconds();
        double fadeOutSeconds = Consts.get().movedSlotFadeOutSeconds();

        double alphaFactor;
        if (elapsedSeconds <= holdSeconds) {
            alphaFactor = 1.0;
        } else {
            double fadeProgress = clamp((elapsedSeconds - holdSeconds) / fadeOutSeconds, 0.0, 1.0);
            alphaFactor = Math.cos(fadeProgress * Math.PI * 0.5);
            if (fadeProgress >= 1.0) {
                clearMovedSlots();
                return;
            }
        }

        int color = withAlphaFactor(Consts.get().movedSlotFillColor(), alphaFactor);
        drawSlotHighlights(guiGraphics, screen, movedSlotIndices, color);
    }

    /**
     * Draws one filled rectangle over every valid menu slot index.
     */
    private static void drawSlotHighlights(
            GuiGraphics guiGraphics,
            AbstractContainerScreen<?> screen,
            List<Integer> slotIndices,
            int color
    ) {
        for (int slotIndex : slotIndices) {
            if (!screen.getMenu().isValidSlotIndex(slotIndex)) {
                continue;
            }

            Slot slot = screen.getMenu().getSlot(slotIndex);
            if (!slot.isActive()) {
                continue;
            }

            int left = screen.getGuiLeft() + slot.x;
            int top = screen.getGuiTop() + slot.y;
            guiGraphics.fill(left, top, left + SLOT_SIZE, top + SLOT_SIZE, color);
        }
    }

    /**
     * Draws the active drag-selection outline if a selection gesture is in progress.
     */
    private void drawActiveSelectionOutline(GuiGraphics guiGraphics) {
        if (!selecting || startPos == null || endPos == null) {
            return;
        }

        GuiRect rect = GuiRect.fromTwoPoints(startPos, endPos);
        drawFill(guiGraphics, rect, Consts.get().selectionFillColor());
        drawOutline(guiGraphics, rect, Consts.get().selectionOutlineColor(), 1);
    }

    /**
     * Draws a filled rectangle.
     */
    private static void drawFill(GuiGraphics guiGraphics, GuiRect rect, int color) {
        int left = floorToInt(rect.left());
        int top = floorToInt(rect.top());
        int right = ceilToInt(rect.right());
        int bottom = ceilToInt(rect.bottom());

        if (right <= left || bottom <= top) {
            return;
        }

        guiGraphics.fill(left, top, right, bottom, color);
    }

    /**
     * Draws an outline rectangle using four filled strips.
     */
    private static void drawOutline(GuiGraphics guiGraphics, GuiRect rect, int color, int thickness) {
        int left = floorToInt(rect.left());
        int top = floorToInt(rect.top());
        int right = ceilToInt(rect.right());
        int bottom = ceilToInt(rect.bottom());

        if (right <= left || bottom <= top) {
            return;
        }

        guiGraphics.fill(left, top, right, top + thickness, color);
        guiGraphics.fill(left, bottom - thickness, right, bottom, color);
        guiGraphics.fill(left, top, left + thickness, bottom, color);
        guiGraphics.fill(right - thickness, top, right, bottom, color);
    }

    /**
     * Applies a multiplier to the alpha channel of an ARGB color.
     */
    private static int withAlphaFactor(int argb, double factor) {
        int originalAlpha = (argb >>> 24) & 0xFF;
        int newAlpha = (int) Math.round(originalAlpha * clamp(factor, 0.0, 1.0));
        return (argb & 0x00FFFFFF) | (newAlpha << 24);
    }

    private static double elapsedSecondsSince(long startNanos) {
        if (startNanos <= 0L) {
            return 0.0;
        }

        return (System.nanoTime() - startNanos) / 1_000_000_000.0;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int floorToInt(double value) {
        return (int) Math.floor(value);
    }

    private static int ceilToInt(double value) {
        return (int) Math.ceil(value);
    }

    /**
     * Reads the current raw mouse position and converts it to GUI-scaled coordinates.
     *
     * @return current mouse position in GUI-scaled screen space.
     */
    private static GuiPoint currentGuiMousePosition() {
        Minecraft minecraft = Minecraft.getInstance();
        Window window = minecraft.getWindow();
        double rawX = minecraft.mouseHandler.xpos();
        double rawY = minecraft.mouseHandler.ypos();
        double guiX = rawX * window.getGuiScaledWidth() / window.getScreenWidth();
        double guiY = rawY * window.getGuiScaledHeight() / window.getScreenHeight();
        return new GuiPoint(guiX, guiY);
    }
}
