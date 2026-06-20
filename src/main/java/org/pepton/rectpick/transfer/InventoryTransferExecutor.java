package org.pepton.rectpick.transfer;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import org.pepton.rectpick.api.Ae2Api;
import org.pepton.rectpick.config.Consts;
import org.pepton.rectpick.network.MoveItemsPayload;
import org.slf4j.Logger;

/**
 * Server/common transfer planner and executor for RectPick.
 * <p>
 * Client-only code may reuse the plan builder, while server code uses
 * {@link #serverTransfer(AbstractContainerMenu, ServerPlayer, int, boolean, List)} to mutate inventories directly.
 */
public final class InventoryTransferExecutor {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String CREATIVE_COPY_SOURCE_SLOT_CLASS_NAME =
            "net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen$CustomCreativeSlot";

    private InventoryTransferExecutor() {
    }

    /**
     * Executes a transfer on the server for the sender's currently open menu.
     *
     * @param menu open menu owned by {@code player}; its container id has already been validated.
     * @param player server player requesting the move; used for pickup checks and slot take callbacks.
     * @param targetSlotIndex menu slot index whose inventory becomes the destination.
     * @param targetAe2Storage whether the client selected AE2 terminal storage as the destination.
     * @param sourceSlots selected source slots; invalid, empty, or destination-inventory slots are skipped.
     * @return total item count moved into the destination inventory.
     */
    public static int serverTransfer(
            AbstractContainerMenu menu,
            ServerPlayer player,
            int targetSlotIndex,
            boolean targetAe2Storage,
            List<MoveItemsPayload.SourceSlot> sourceSlots
    ) {
        List<Integer> sourceSlotIndices = sourceSlots.stream()
                .map(MoveItemsPayload.SourceSlot::slotIndex)
                .toList();

        if (isAe2Loaded() && Ae2Api.shouldHandleServerTransfer(menu, targetSlotIndex, targetAe2Storage, sourceSlots)) {
            return Ae2Api.serverTransfer(menu, player, targetSlotIndex, targetAe2Storage, sourceSlots);
        }

        InventoryTransferPlan plan = createPlan(menu, targetSlotIndex, sourceSlotIndices);
        if (plan == null) {
            return 0;
        }

        int movedTotal = 0;
        for (SourceInventoryTransfer sourceInventory : plan.sourceInventories()) {
            for (Slot sourceSlot : sourceInventory.sourceSlots()) {
                movedTotal += moveServerSlot(player, sourceInventory.sourceInventory(), sourceSlot, plan.destinationInventory(), plan.destinationSlots());
            }
        }

        menu.broadcastChanges();
        debugLog(
                "RectPick server transfer completed: movedItems={}, targetSlot={}, sourceInventories={}, sources={}",
                movedTotal,
                targetSlotIndex,
                plan.sourceInventories().size(),
                sourceSlotIndices
        );
        return movedTotal;
    }

    /**
     * Moves one server-side source slot into a destination inventory.
     *
     * @param player player used for permission checks and callbacks.
     * @param sourceInventory source inventory that owns {@code sourceSlot}.
     * @param sourceSlot source slot; must contain an item, allow pickup, and not belong to the destination inventory.
     * @param destinationInventory inventory selected by the target slot.
     * @param destinationSlots ordered active destination slots belonging to {@code destinationInventory}.
     * @return number of items moved from the source slot.
     */
    private static int moveServerSlot(Player player, Container sourceInventory, Slot sourceSlot, Container destinationInventory, List<Slot> destinationSlots) {
        if (!sourceSlot.hasItem() || !sourceSlot.mayPickup(player) || sourceSlot.container != sourceInventory || sourceInventory == destinationInventory) {
            return 0;
        }

        ItemStack beforeMoveStack = sourceSlot.getItem();
        ItemStack beforeCopy = beforeMoveStack.copy();
        int movedCount = InventoryItemMover.moveItemStack(sourceInventory, destinationInventory, beforeMoveStack, destinationSlots);
        if (movedCount <= 0) {
            return 0;
        }

        if (beforeMoveStack.isEmpty()) {
            sourceSlot.set(ItemStack.EMPTY);
        }

        sourceSlot.onTake(player, beforeCopy.copyWithCount(movedCount));
        sourceSlot.setChanged();
        return movedCount;
    }

    /**
     * Builds a transfer plan from menu slot indices.
     *
     * @param menu menu that owns every slot index; must be the current open menu for the operation.
     * @param targetSlotIndex valid menu slot index used only to select the destination inventory.
     * @param sourceSlotIndices candidate source menu slot indices selected by the client.
     * @return a plan containing one destination inventory and source slots grouped by source inventory, or {@code null} when no move can run.
     */
    public static InventoryTransferPlan createPlan(AbstractContainerMenu menu, int targetSlotIndex, List<Integer> sourceSlotIndices) {
        if (!menu.isValidSlotIndex(targetSlotIndex)) {
            debugLog("RectPick transfer ignored because target slot is invalid: {}", targetSlotIndex);
            return null;
        }

        Slot targetSlot = menu.getSlot(targetSlotIndex);
        Container destinationInventory = targetSlot.container;
        List<Slot> destinationSlots = menu.slots.stream()
                .filter(slot -> slot.isActive() && slot.container == destinationInventory)
                .sorted(Comparator.comparingInt(Slot::getContainerSlot))
                .toList();

        List<Slot> sourceSlots = sourceSlotIndices.stream()
                .filter(menu::isValidSlotIndex)
                .map(menu::getSlot)
                .filter(Slot::hasItem)
                .filter(slot -> slot.container != destinationInventory)
                .distinct()
                .toList();

        List<SourceInventoryTransfer> sourceInventories = groupSourceSlotsByInventory(sourceSlots);
        if (sourceInventories.isEmpty()) {
            debugLog("RectPick transfer ignored because there are no movable source slots");
            return null;
        }

        return new InventoryTransferPlan(destinationInventory, destinationSlots, sourceInventories);
    }

    /**
     * Checks whether a slot is a client-only creative tab source slot that should be copied instead of moved.
     *
     * @param slot menu slot to inspect.
     * @return {@code true} when the slot is the creative item-list slot implementation.
     */
    public static boolean isCreativeCopySourceSlot(Slot slot) {
        return slot != null && CREATIVE_COPY_SOURCE_SLOT_CLASS_NAME.equals(slot.getClass().getName());
    }

    /**
     * Checks whether AE2 is loaded before touching classes that directly depend on AE2's API.
     *
     * @return {@code true} when the AE2 mod is present in the current runtime.
     */
    private static boolean isAe2Loaded() {
        return ModList.get().isLoaded("ae2");
    }

    /**
     * Groups source slots by their backing inventory while preserving first-seen inventory order.
     *
     * @param sourceSlots source slots already filtered to movable non-destination slots.
     * @return source inventory groups, each containing slots from exactly one backing container.
     */
    private static List<SourceInventoryTransfer> groupSourceSlotsByInventory(List<Slot> sourceSlots) {
        List<SourceInventoryTransferBuilder> builders = new ArrayList<>();

        for (Slot sourceSlot : sourceSlots) {
            SourceInventoryTransferBuilder builder = findSourceInventoryBuilder(builders, sourceSlot.container);
            if (builder == null) {
                builder = new SourceInventoryTransferBuilder(sourceSlot.container);
                builders.add(builder);
            }

            builder.sourceSlots.add(sourceSlot);
        }

        return builders.stream()
                .map(builder -> new SourceInventoryTransfer(builder.sourceInventory, builder.sourceSlots))
                .toList();
    }

    /**
     * Finds an existing source-inventory builder by container identity.
     *
     * @param builders current grouped source builders.
     * @param sourceInventory source container to locate.
     * @return matching builder, or {@code null} when this source inventory has not been seen.
     */
    private static SourceInventoryTransferBuilder findSourceInventoryBuilder(List<SourceInventoryTransferBuilder> builders, Container sourceInventory) {
        for (SourceInventoryTransferBuilder builder : builders) {
            if (builder.sourceInventory == sourceInventory) {
                return builder;
            }
        }

        return null;
    }

    /**
     * Immutable transfer plan derived from a menu and target slot.
     *
     * @param destinationInventory inventory that should receive moved stacks.
     * @param destinationSlots active slots belonging to {@code destinationInventory}, sorted by container slot index.
     * @param sourceInventories source inventory groups that do not belong to {@code destinationInventory}.
     */
    public record InventoryTransferPlan(Container destinationInventory, List<Slot> destinationSlots, List<SourceInventoryTransfer> sourceInventories) {
        /**
         * Copies slot lists so later caller mutations cannot alter this plan.
         *
         * @param destinationInventory inventory that should receive moved stacks.
         * @param destinationSlots active destination slots already sorted by insertion priority.
         * @param sourceInventories movable source inventory groups filtered from the original selection.
         */
        public InventoryTransferPlan {
            destinationSlots = List.copyOf(Objects.requireNonNull(destinationSlots));
            sourceInventories = List.copyOf(Objects.requireNonNull(sourceInventories));
        }

        /**
         * Checks whether this plan includes creative tab source slots that require copy semantics.
         *
         * @return {@code true} when at least one source slot is a creative item-list slot.
         */
        public boolean containsCreativeSourceSlots() {
            for (SourceInventoryTransfer sourceInventory : sourceInventories) {
                for (Slot sourceSlot : sourceInventory.sourceSlots()) {
                    if (isCreativeCopySourceSlot(sourceSlot)) {
                        return true;
                    }
                }
            }

            return false;
        }
    }

    /**
     * Source-side transfer group for one backing inventory.
     *
     * @param sourceInventory source inventory that owns every slot in {@code sourceSlots}.
     * @param sourceSlots non-empty source slots from {@code sourceInventory}.
     */
    public record SourceInventoryTransfer(Container sourceInventory, List<Slot> sourceSlots) {
        /**
         * Copies source slots so later caller mutations cannot alter this group.
         *
         * @param sourceInventory source inventory that owns every source slot.
         * @param sourceSlots movable source slots from this inventory.
         */
        public SourceInventoryTransfer {
            sourceInventory = Objects.requireNonNull(sourceInventory);
            sourceSlots = List.copyOf(Objects.requireNonNull(sourceSlots));
        }
    }

    /**
     * Mutable builder used while grouping selected source slots by container identity.
     */
    private static final class SourceInventoryTransferBuilder {
        private final Container sourceInventory;
        private final List<Slot> sourceSlots = new ArrayList<>();

        /**
         * Creates a builder for one source inventory.
         *
         * @param sourceInventory source container represented by this builder.
         */
        private SourceInventoryTransferBuilder(Container sourceInventory) {
            this.sourceInventory = sourceInventory;
        }
    }

    /**
     * Emits a RectPick inventory operation debug log when debug logging is enabled.
     *
     * @param message SLF4J message pattern describing the operation.
     * @param args pattern arguments passed through without additional processing.
     */
    private static void debugLog(String message, Object... args) {
        if (Consts.debugLog) {
            LOGGER.info(message, args);
        }
    }
}
