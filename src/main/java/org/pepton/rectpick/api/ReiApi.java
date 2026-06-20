package org.pepton.rectpick.api;

import me.shedaniel.rei.api.client.REIRuntime;
import me.shedaniel.rei.api.client.gui.widgets.TextField;

/**
 * REI integration point for RectPick.
 * <p>
 * This class is only queried when REI is installed and reads REI's public runtime search field.
 */
public final class ReiApi {
    private ReiApi() {
    }

    /**
     * Checks whether REI's item search box currently owns keyboard input.
     *
     * @return {@code true} when REI exposes a focused search field; {@code false} if REI is unavailable or not ready.
     */
    public static boolean isSearchFieldFocused() {
        try {
            TextField searchTextField = REIRuntime.getInstance().getSearchTextField();
            return searchTextField != null && searchTextField.isFocused();
        } catch (RuntimeException | LinkageError exception) {
            return false;
        }
    }
}
