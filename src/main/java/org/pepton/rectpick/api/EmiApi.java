package org.pepton.rectpick.api;

/**
 * EMI integration point for RectPick.
 * <p>
 * This class is only queried when EMI is installed and delegates to EMI's public client API.
 */
public final class EmiApi {
    private EmiApi() {
    }

    /**
     * Checks whether EMI's item search box currently owns keyboard input.
     *
     * @return {@code true} when EMI reports that its search field is focused; {@code false} if EMI is unavailable or not ready.
     */
    public static boolean isSearchFieldFocused() {
        try {
            return dev.emi.emi.api.EmiApi.isSearchFocused();
        } catch (RuntimeException | LinkageError exception) {
            return false;
        }
    }
}
