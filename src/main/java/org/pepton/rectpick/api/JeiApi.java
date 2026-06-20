package org.pepton.rectpick.api;

import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.runtime.IIngredientListOverlay;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.resources.ResourceLocation;
import org.pepton.rectpick.Main;

/**
 * JEI integration point for RectPick.
 * <p>
 * JEI creates this plugin when JEI is installed, then supplies its runtime API.
 */
@JeiPlugin
public final class JeiApi implements IModPlugin {
    private static IJeiRuntime runtime;

    /**
     * Returns this JEI plugin's stable identifier.
     *
     * @return RectPick-owned JEI plugin id.
     */
    @Override
    public ResourceLocation getPluginUid() {
        return ResourceLocation.fromNamespaceAndPath(Main.MOD_ID, "jei_api");
    }

    /**
     * Stores JEI's runtime API after JEI has finished loading.
     *
     * @param jeiRuntime active JEI runtime supplied by JEI; must not be {@code null}.
     */
    @Override
    public void onRuntimeAvailable(IJeiRuntime jeiRuntime) {
        runtime = jeiRuntime;
    }

    /**
     * Clears the stored JEI runtime when JEI unloads it.
     */
    @Override
    public void onRuntimeUnavailable() {
        runtime = null;
    }

    /**
     * Checks whether JEI's ingredient-list search box currently owns keyboard input.
     *
     * @return {@code true} when JEI is installed, its ingredient list is visible, and the search field is focused.
     */
    public static boolean isSearchFieldFocused() {
        if (runtime == null) {
            return false;
        }

        IIngredientListOverlay ingredientListOverlay = runtime.getIngredientListOverlay();
        return ingredientListOverlay.hasKeyboardFocus();
    }
}
