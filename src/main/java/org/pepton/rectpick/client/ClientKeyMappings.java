package org.pepton.rectpick.client;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.settings.KeyConflictContext;
import org.pepton.rectpick.config.Consts;

/**
 * Client-side key mapping registry and matcher.
 * <p>
 * This utility owns the static PICK_KEY mapping after registration and exposes
 * event-based and polling-based checks for GUI input handling.
 */
public final class ClientKeyMappings {
    private static KeyMapping pickKey;

    private ClientKeyMappings() {
    }

    /**
     * Registers the PICK_KEY mapping with the Minecraft controls screen.
     *
     * @param event key mapping registration event fired on the mod bus; must be a client-side registration event.
     */
    public static void register(RegisterKeyMappingsEvent event) {
        Consts consts = Consts.get();
        pickKey = new KeyMapping(
                Consts.pickKeyTranslationKey,
                KeyConflictContext.GUI,
                InputConstants.Type.KEYSYM,
                consts.defaultPickKey(),
                Consts.keyCategoryTranslationKey
        );

        event.register(pickKey);
    }

    /**
     * Checks whether a key event matches the active PICK_KEY binding.
     *
     * @param keyCode GLFW key code supplied by the screen key event.
     * @param scanCode platform scan code supplied by the screen key event.
     * @return {@code true} when the registered key mapping is active in the current GUI context and matches the event key.
     */
    public static boolean isPickKey(int keyCode, int scanCode) {
        if (pickKey == null) {
            return false;
        }

        InputConstants.Key key = InputConstants.getKey(keyCode, scanCode);
        return pickKey.isActiveAndMatches(key);
    }

    /**
     * Checks whether PICK_KEY is currently physically or logically down.
     *
     * @param windowHandle GLFW window handle from the current Minecraft window; must be valid for key polling.
     * @return {@code true} when the key mapping reports down or GLFW reports its current key as pressed.
     */
    public static boolean isPickKeyDown(long windowHandle) {
        if (pickKey == null) {
            return false;
        }

        InputConstants.Key key = pickKey.getKey();
        boolean physicalKeyDown = key.getType() == InputConstants.Type.KEYSYM && InputConstants.isKeyDown(windowHandle, key.getValue());
        return pickKey.isDown() || physicalKeyDown;
    }
}
