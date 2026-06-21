package org.pepton.rectpick.sound;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvent;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.pepton.rectpick.Main;

/**
 * Registry holder for RectPick operation sound events.
 * <p>
 * Sound definitions live in {@code assets/rectpick/sounds.json}; bundled audio
 * assets are stored under {@code assets/rectpick/sounds}.
 */
public final class RectPickSoundEvents {
    private static final DeferredRegister<SoundEvent> SOUND_EVENTS = DeferredRegister.create(Registries.SOUND_EVENT, Main.MOD_ID);

    /**
     * Sound played when PICK_KEY starts a valid inventory gesture.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> BEGIN_PICK = register("begin_pick");

    /**
     * Sound played when a range selection stores at least one source slot.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> SELECTION_STORED = register("selection_stored");

    /**
     * Sound played when a transfer request is dispatched or client fallback starts.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> TRANSFER_DONE = register("transfer_done");

    /**
     * Sound played when a selection is empty or no transfer target is found.
     */
    public static final DeferredHolder<SoundEvent, SoundEvent> OPERATION_IGNORED = register("operation_ignored");

    private RectPickSoundEvents() {
    }

    /**
     * Registers RectPick sound events on the mod event bus.
     *
     * @param modEventBus mod event bus for this mod; must be available during mod construction.
     */
    public static void register(IEventBus modEventBus) {
        SOUND_EVENTS.register(modEventBus);
    }

    /**
     * Creates one variable-range sound event entry under the RectPick namespace.
     *
     * @param path resource path used for both the registry name and sounds.json key.
     * @return deferred sound holder resolved after registry setup.
     */
    private static DeferredHolder<SoundEvent, SoundEvent> register(String path) {
        ResourceLocation id = ResourceLocation.fromNamespaceAndPath(Main.MOD_ID, path);
        return SOUND_EVENTS.register(path, () -> SoundEvent.createVariableRangeEvent(id));
    }
}
