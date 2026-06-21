package org.pepton.rectpick.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvent;
import org.pepton.rectpick.sound.RectPickSoundEvents;

/**
 * Client-only helper for playing short feedback sounds for RectPick operations.
 */
public final class ClientOperationSounds {
    private static final float DEFAULT_VOLUME = 0.45F;

    private ClientOperationSounds() {
    }

    /**
     * Plays feedback when PICK_KEY starts a valid inventory gesture.
     */
    public static void playBeginPick() {
        play(RectPickSoundEvents.BEGIN_PICK.get(), 1.1F);
    }

    /**
     * Plays feedback for a successful source-slot selection.
     */
    public static void playSelectionStored() {
        play(RectPickSoundEvents.SELECTION_STORED.get(), 1.25F);
    }

    /**
     * Plays feedback for a dispatched transfer.
     */
    public static void playTransferDone() {
        play(RectPickSoundEvents.TRANSFER_DONE.get(), 0.7F);
    }

    /**
     * Plays feedback for an empty selection or missing transfer target.
     */
    public static void playOperationIgnored() {
        play(RectPickSoundEvents.OPERATION_IGNORED.get(), 1.0F);
    }

    /**
     * Plays one sound as GUI feedback through the client sound manager.
     *
     * @param sound sound event to play; must already be registered.
     * @param pitch playback pitch used to distinguish operation types.
     */
    private static void play(SoundEvent sound, float pitch) {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, DEFAULT_VOLUME));
    }
}
