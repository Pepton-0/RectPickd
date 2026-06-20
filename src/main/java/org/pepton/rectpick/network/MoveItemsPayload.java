package org.pepton.rectpick.network;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;
import org.jetbrains.annotations.NotNull;
import org.pepton.rectpick.Main;

/**
 * Server-bound payload requesting a RectPick inventory transfer.
 *
 * @param containerId menu container id observed by the client when the request was created.
 * @param targetSlotIndex menu slot index whose inventory is used as the destination.
 * @param targetAe2Storage whether the client selected AE2 terminal storage as the destination.
 * @param sourceSlots selected source menu slots and client-visible stacks; copied defensively by the canonical constructor.
 */
public record MoveItemsPayload(
        int containerId,
        int targetSlotIndex,
        boolean targetAe2Storage,
        List<SourceSlot> sourceSlots
) implements CustomPacketPayload {
    private static final int MAX_SOURCE_SLOT_COUNT = 256;

    /**
     * Network payload type id for RectPick move requests.
     */
    public static final Type<MoveItemsPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(Main.MOD_ID, "move_items"));
    /**
     * Codec that serializes menu ids, slot indices, and client-visible source stacks.
     */
    public static final StreamCodec<RegistryFriendlyByteBuf, MoveItemsPayload> STREAM_CODEC = StreamCodec.ofMember(
            MoveItemsPayload::write,
            MoveItemsPayload::read
    );

    /**
     * Creates an immutable move request.
     *
     * @param containerId current menu container id observed by the client.
     * @param targetSlotIndex menu slot index selecting the destination inventory.
     * @param targetAe2Storage whether the client selected AE2 terminal storage as the destination.
     * @param sourceSlots selected source slots; must be small enough for the codec limit.
     */
    public MoveItemsPayload {
        sourceSlots = List.copyOf(sourceSlots);
    }

    @Override
    public @NotNull Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /**
     * Encodes this payload into a registry-friendly buffer.
     *
     * @param buffer writable network buffer supplied by NeoForge; must be positioned for writing this payload body.
     */
    private void write(RegistryFriendlyByteBuf buffer) {
        buffer.writeVarInt(containerId);
        buffer.writeVarInt(targetSlotIndex);
        buffer.writeBoolean(targetAe2Storage);
        buffer.writeVarInt(sourceSlots.size());
        for (SourceSlot sourceSlot : sourceSlots) {
            buffer.writeVarInt(sourceSlot.slotIndex());
            buffer.writeVarLong(sourceSlot.ae2Serial());
            ItemStack.OPTIONAL_STREAM_CODEC.encode(buffer, sourceSlot.itemStack());
        }
    }

    /**
     * Decodes a payload from a registry-friendly buffer.
     *
     * @param buffer readable network buffer supplied by NeoForge; must contain values written by {@link #write(RegistryFriendlyByteBuf)}.
     * @return a payload with an immutable copy of the decoded source slot list.
     */
    private static MoveItemsPayload read(RegistryFriendlyByteBuf buffer) {
        int containerId = buffer.readVarInt();
        int targetSlotIndex = buffer.readVarInt();
        boolean targetAe2Storage = buffer.readBoolean();
        int count = buffer.readVarInt();
        if (count < 0 || count > MAX_SOURCE_SLOT_COUNT) {
            throw new IllegalArgumentException("Invalid RectPick source slot count: " + count);
        }

        List<SourceSlot> sourceSlots = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int sourceSlotIndex = buffer.readVarInt();
            long ae2Serial = buffer.readVarLong();
            ItemStack itemStack = ItemStack.OPTIONAL_STREAM_CODEC.decode(buffer);
            sourceSlots.add(new SourceSlot(sourceSlotIndex, ae2Serial, itemStack));
        }

        return new MoveItemsPayload(containerId, targetSlotIndex, targetAe2Storage, sourceSlots);
    }

    /**
     * Client-visible source slot snapshot for server-side transfer handlers.
     *
     * @param slotIndex menu slot index selected by the client.
     * @param ae2Serial AE2 terminal storage entry serial, or {@code -1} for normal menu slots.
     * @param itemStack stack visible in that slot when the request was sent; empty for unavailable slots.
     */
    public record SourceSlot(int slotIndex, long ae2Serial, ItemStack itemStack) {
        /**
         * Copies the stack so payload consumers cannot mutate caller-owned item stacks.
         *
         * @param slotIndex menu slot index selected by the client.
         * @param ae2Serial AE2 terminal storage entry serial, or {@code -1} for normal menu slots.
         * @param itemStack visible stack to serialize; empty is allowed.
         */
        public SourceSlot {
            itemStack = itemStack.copy();
        }
    }
}
