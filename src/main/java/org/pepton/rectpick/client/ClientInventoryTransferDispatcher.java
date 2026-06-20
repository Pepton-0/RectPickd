package org.pepton.rectpick.client;

import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.neoforged.fml.ModList;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.registration.NetworkRegistry;
import org.pepton.rectpick.api.Ae2Api;
import org.pepton.rectpick.config.Consts;
import org.pepton.rectpick.network.MoveItemsPayload;
import org.pepton.rectpick.transfer.InventoryItemMover;
import org.pepton.rectpick.transfer.InventoryTransferExecutor;
import org.slf4j.Logger;

/**
 * Client-side dispatcher for inventory transfer requests.
 * <p>
 * It first tries the negotiated RectPick server payload channel. If the server
 * cannot receive the payload, it falls back to vanilla client click operations.
 */
public final class ClientInventoryTransferDispatcher {
    private static final Logger LOGGER = LogUtils.getLogger();

    private ClientInventoryTransferDispatcher() {
    }

    /**
     * Transfers selected source slots into the inventory containing the target slot.
     *
     * @param menu currently open menu; must be the menu that owns both target and source slot indices.
     * @param targetSlotIndex menu slot index used to choose the destination inventory.
     * @param sourceSlotIndices menu slot indices selected by range selection; invalid or same-inventory slots are skipped by the plan.
     * @param stackUnitTransfer {@code true} to copy full creative stacks instead of the visible creative stack count.
     * @return result containing whether transfer work started and destination slot indices expected to receive items.
     */
    public static TransferDispatchResult transfer(AbstractContainerMenu menu, int targetSlotIndex, List<Integer> sourceSlotIndices, boolean stackUnitTransfer) {
        TransferDispatchResult ae2TransferResult = tryAe2TerminalTransfer(menu, targetSlotIndex, sourceSlotIndices);
        if (ae2TransferResult.transferred()) {
            return ae2TransferResult;
        }

        InventoryTransferExecutor.InventoryTransferPlan plan = InventoryTransferExecutor.createPlan(menu, targetSlotIndex, sourceSlotIndices);
        if (plan == null) {
            return TransferDispatchResult.notTransferred();
        }

        List<DestinationSlotSnapshot> beforeDestinationSnapshots = snapshotDestinationSlots(menu, plan.destinationSlots());
        List<Integer> movedDestinationSlotIndices = estimateMovedDestinationSlotIndices(menu, plan, stackUnitTransfer);
        if (movedDestinationSlotIndices.isEmpty()) {
            debugLog("RectPick transfer skipped highlight because no destination slot can accept selected stacks");
        }

        if (plan.containsCreativeSourceSlots()) {
            if (clientFallbackTransfer(menu, targetSlotIndex, sourceSlotIndices, plan, stackUnitTransfer)) {
                List<Integer> changedDestinationSlotIndices = changedDestinationSlotIndices(menu, beforeDestinationSnapshots);
                return new TransferDispatchResult(true, !changedDestinationSlotIndices.isEmpty(), false, changedDestinationSlotIndices, beforeDestinationSnapshots);
            }

            return TransferDispatchResult.notTransferred();
        }

        if (tryServerTransfer(menu, targetSlotIndex, false, sourceSlotIndices)) {
            List<Integer> changedDestinationSlotIndices = changedDestinationSlotIndices(menu, beforeDestinationSnapshots);
            return new TransferDispatchResult(true, !changedDestinationSlotIndices.isEmpty(), true, changedDestinationSlotIndices, beforeDestinationSnapshots);
        }

        if (clientFallbackTransfer(menu, targetSlotIndex, sourceSlotIndices, plan, stackUnitTransfer)) {
            List<Integer> changedDestinationSlotIndices = changedDestinationSlotIndices(menu, beforeDestinationSnapshots);
            return new TransferDispatchResult(true, !changedDestinationSlotIndices.isEmpty(), false, changedDestinationSlotIndices, beforeDestinationSnapshots);
        }

        return TransferDispatchResult.notTransferred();
    }

    /**
     * Dispatches AE2 terminal transfers before normal slot-inventory planning.
     *
     * @param menu current menu whose client slots may include AE2 terminal storage entries.
     * @param targetSlotIndex menu slot index selected as transfer destination.
     * @param sourceSlotIndices selected source slot indices.
     * @return transferred result when an AE2 server request was sent, otherwise a failed result.
     */
    private static TransferDispatchResult tryAe2TerminalTransfer(AbstractContainerMenu menu, int targetSlotIndex, List<Integer> sourceSlotIndices) {
        if (!ModList.get().isLoaded("ae2") || !Ae2Api.isTerminalMenu(menu)) {
            return TransferDispatchResult.notTransferred();
        }

        boolean targetAe2Storage = Ae2Api.isStorageTarget(menu, targetSlotIndex);
        boolean sourceAe2Storage = sourceSlotIndices.stream()
                .anyMatch(sourceSlotIndex -> Ae2Api.isStorageTarget(menu, sourceSlotIndex));
        debugLog(
                "RectPick AE2 terminal candidate: target={}, targetStorage={}, sourceStorage={}, sources={}",
                targetSlotIndex,
                targetAe2Storage,
                sourceAe2Storage,
                sourceSlotIndices
        );
        if (!targetAe2Storage && !sourceAe2Storage) {
            return TransferDispatchResult.notTransferred();
        }

        List<DestinationSlotSnapshot> beforeSnapshots = targetAe2Storage
                ? snapshotSlots(menu, sourceSlotIndices)
                : snapshotTargetInventorySlots(menu, targetSlotIndex);
        if (Consts.disableServerTransferForDebug) {
            if (clientFallbackAe2TerminalTransfer(menu, targetSlotIndex, sourceSlotIndices, targetAe2Storage)) {
                debugLog(
                        "RectPick AE2 client fallback transfer started: target={}, targetStorage={}, sources={}",
                        targetSlotIndex,
                        targetAe2Storage,
                        sourceSlotIndices
                );
                return new TransferDispatchResult(true, false, true, List.of(), beforeSnapshots);
            }

            return TransferDispatchResult.notTransferred();
        }

        if (tryServerTransfer(menu, targetSlotIndex, targetAe2Storage, sourceSlotIndices)) {
            debugLog(
                    "RectPick sent AE2 terminal transfer request: target={}, targetStorage={}, sources={}",
                    targetSlotIndex,
                    targetAe2Storage,
                    sourceSlotIndices
            );
            return new TransferDispatchResult(true, false, true, List.of(), beforeSnapshots);
        }

        return TransferDispatchResult.notTransferred();
    }

    /**
     * Uses normal client click operations for AE2 terminal transfers while RectPick server transfer is disabled.
     *
     * @param menu current AE2 terminal menu.
     * @param targetSlotIndex target menu slot selected by the user.
     * @param sourceSlotIndices selected source menu slot indices.
     * @param targetAe2Storage {@code true} to insert normal source slots into AE2 storage.
     * @return {@code true} when at least one click sequence was attempted.
     */
    private static boolean clientFallbackAe2TerminalTransfer(
            AbstractContainerMenu menu,
            int targetSlotIndex,
            List<Integer> sourceSlotIndices,
            boolean targetAe2Storage
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode == null || minecraft.player == null || !menu.isValidSlotIndex(targetSlotIndex)) {
            debugLog("RectPick AE2 client fallback skipped because game mode, player, or target slot is unavailable");
            return false;
        }

        if (targetAe2Storage) {
            return clickNormalSlotsIntoAe2Storage(minecraft, menu, targetSlotIndex, sourceSlotIndices);
        }

        return clickAe2StorageIntoTargetInventory(menu, sourceSlotIndices);
    }

    /**
     * Quick-moves normal source slots into AE2 storage.
     *
     * @param minecraft active client instance.
     * @param menu current AE2 terminal menu.
     * @param targetSlotIndex AE2 storage target slot index, used only to ensure the target is still valid.
     * @param sourceSlotIndices selected source slot indices.
     * @return {@code true} when at least one source slot was clicked.
     */
    private static boolean clickNormalSlotsIntoAe2Storage(Minecraft minecraft, AbstractContainerMenu menu, int targetSlotIndex, List<Integer> sourceSlotIndices) {
        if (!menu.isValidSlotIndex(targetSlotIndex)) {
            return false;
        }

        boolean clickedAny = false;
        for (int sourceSlotIndex : sourceSlotIndices) {
            if (!menu.isValidSlotIndex(sourceSlotIndex) || Ae2Api.isStorageTarget(menu, sourceSlotIndex)) {
                continue;
            }

            Slot sourceSlot = menu.getSlot(sourceSlotIndex);
            if (!sourceSlot.hasItem() || !sourceSlot.mayPickup(minecraft.player)) {
                continue;
            }

            minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlotIndex, 0, ClickType.QUICK_MOVE, minecraft.player);
            clickedAny = true;
        }

        return clickedAny;
    }

    /**
     * Invokes AE2 storage interactions for source entries so AE2 moves them into the player inventory.
     *
     * @param menu current AE2 terminal menu.
     * @param sourceSlotIndices selected AE2 storage source slot indices.
     * @return {@code true} when at least one source storage entry was clicked.
     */
    private static boolean clickAe2StorageIntoTargetInventory(AbstractContainerMenu menu, List<Integer> sourceSlotIndices) {
        boolean clickedAny = false;

        for (int sourceSlotIndex : sourceSlotIndices) {
            long serial = Ae2Api.getStorageEntrySerial(menu, sourceSlotIndex);
            if (serial < 0) {
                continue;
            }

            if (Ae2Api.handleStorageInteraction(menu, serial, "SHIFT_CLICK")) {
                clickedAny = true;
            }
        }

        return clickedAny;
    }

    /**
     * Captures current item counts for destination slots before an operation runs.
     *
     * @param destinationSlots destination slots selected by the transfer plan.
     * @return immutable snapshots keyed by menu slot index.
     */
    private static List<DestinationSlotSnapshot> snapshotDestinationSlots(AbstractContainerMenu menu, List<Slot> destinationSlots) {
        return destinationSlots.stream()
                .map(slot -> new DestinationSlotSnapshot(menuIndexOfSlot(menu, slot), slot.getItem().getCount()))
                .toList();
    }

    /**
     * Captures current item counts for arbitrary valid menu slots.
     *
     * @param menu menu that owns the slot indices.
     * @param slotIndices menu slot indices to capture.
     * @return immutable snapshots for valid slot indices only.
     */
    private static List<DestinationSlotSnapshot> snapshotSlots(AbstractContainerMenu menu, List<Integer> slotIndices) {
        return slotIndices.stream()
                .filter(menu::isValidSlotIndex)
                .map(menu::getSlot)
                .map(slot -> new DestinationSlotSnapshot(menuIndexOfSlot(menu, slot), slot.getItem().getCount()))
                .toList();
    }

    /**
     * Captures the inventory represented by one target slot.
     *
     * @param menu menu that owns the target slot.
     * @param targetSlotIndex valid menu slot index selecting one inventory.
     * @return immutable count snapshots for active slots in that inventory.
     */
    private static List<DestinationSlotSnapshot> snapshotTargetInventorySlots(AbstractContainerMenu menu, int targetSlotIndex) {
        if (!menu.isValidSlotIndex(targetSlotIndex)) {
            return List.of();
        }

        Container destinationInventory = menu.getSlot(targetSlotIndex).container;
        return menu.slots.stream()
                .filter(slot -> slot.isActive() && slot.container == destinationInventory)
                .map(slot -> new DestinationSlotSnapshot(menuIndexOfSlot(menu, slot), slot.getItem().getCount()))
                .toList();
    }

    /**
     * Finds destination slots whose item count differs from a previous snapshot.
     *
     * @param menu menu that still owns the destination slot indices.
     * @param beforeDestinationSnapshots snapshots captured before the transfer.
     * @return menu slot indices whose item count changed.
     */
    public static List<Integer> changedDestinationSlotIndices(AbstractContainerMenu menu, List<DestinationSlotSnapshot> beforeDestinationSnapshots) {
        List<Integer> changedSlotIndices = new ArrayList<>();
        for (DestinationSlotSnapshot snapshot : beforeDestinationSnapshots) {
            if (!menu.isValidSlotIndex(snapshot.slotIndex())) {
                continue;
            }

            if (menu.getSlot(snapshot.slotIndex()).getItem().getCount() != snapshot.itemCount()) {
                changedSlotIndices.add(snapshot.slotIndex());
            }
        }

        return List.copyOf(changedSlotIndices);
    }

    /**
     * Sends a server transfer request when the remote side has the RectPick channel.
     *
     * @param menu current menu whose container id will be serialized.
     * @param targetSlotIndex valid menu slot index intended as the destination inventory selector.
     * @param targetAe2Storage {@code true} when the payload should be interpreted as an AE2 storage target.
     * @param sourceSlotIndices source slot indices to serialize; should come from a stored range selection.
     * @return {@code true} when a server payload was sent, {@code false} when fallback should be used.
     */
    private static boolean tryServerTransfer(AbstractContainerMenu menu, int targetSlotIndex, boolean targetAe2Storage, List<Integer> sourceSlotIndices) {
        if (Consts.disableServerTransferForDebug) {
            debugLog("RectPick server transfer skipped because debug client-side transfer mode is enabled");
            return false;
        }

        ClientPacketListener connection = Minecraft.getInstance().getConnection();
        if (connection == null || !NetworkRegistry.hasChannel(connection.getConnection(), ConnectionProtocol.PLAY, MoveItemsPayload.TYPE.id())) {
            debugLog("RectPick server transfer channel is not available; using client click fallback");
            return false;
        }

        PacketDistributor.sendToServer(new MoveItemsPayload(
                menu.containerId,
                targetSlotIndex,
                targetAe2Storage,
                createSourceSlots(menu, sourceSlotIndices)
        ));
        debugLog("RectPick sent server transfer request: target={}, sources={}", targetSlotIndex, sourceSlotIndices);
        return true;
    }

    /**
     * Creates source slot snapshots for a server transfer payload.
     *
     * @param menu menu that owns the selected source slot indices.
     * @param sourceSlotIndices selected source slot indices.
     * @return immutable source snapshots preserving input order.
     */
    private static List<MoveItemsPayload.SourceSlot> createSourceSlots(AbstractContainerMenu menu, List<Integer> sourceSlotIndices) {
        return sourceSlotIndices.stream()
                .map(sourceSlotIndex -> new MoveItemsPayload.SourceSlot(
                        sourceSlotIndex,
                        Ae2Api.getStorageEntrySerial(menu, sourceSlotIndex),
                        getSourceStack(menu, sourceSlotIndex)
                ))
                .toList();
    }

    /**
     * Reads the current client-visible item stack for one source slot.
     *
     * @param menu menu that should contain the source slot.
     * @param sourceSlotIndex selected source menu slot index.
     * @return copied visible stack, or empty when the slot is invalid.
     */
    private static ItemStack getSourceStack(AbstractContainerMenu menu, int sourceSlotIndex) {
        if (!menu.isValidSlotIndex(sourceSlotIndex)) {
            return ItemStack.EMPTY;
        }

        return menu.getSlot(sourceSlotIndex).getItem().copy();
    }

    /**
     * Resolves a slot to its actual position in the menu slot list.
     *
     * @param menu menu that owns the slot list.
     * @param slot slot object to locate.
     * @return index in {@code menu.slots}, or {@code slot.index} when the slot is not present in the list.
     */
    private static int menuIndexOfSlot(AbstractContainerMenu menu, Slot slot) {
        int menuIndex = menu.slots.indexOf(slot);
        return menuIndex >= 0 ? menuIndex : slot.index;
    }

    /**
     * Performs transfer by issuing vanilla click operations from the client.
     *
     * @param menu current menu; must still match the screen being operated on.
     * @param targetSlotIndex menu slot index selecting the destination inventory.
     * @param sourceSlotIndices selected source slot indices; same-destination slots are removed by the transfer plan.
     * @param plan transfer plan already built for these arguments.
     * @param stackUnitTransfer {@code true} to copy full creative stacks instead of the visible creative stack count.
     * @return {@code true} when a transfer plan was available and source slots were processed.
     */
    private static boolean clientFallbackTransfer(
            AbstractContainerMenu menu,
            int targetSlotIndex,
            List<Integer> sourceSlotIndices,
            InventoryTransferExecutor.InventoryTransferPlan plan,
            boolean stackUnitTransfer
    ) {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.gameMode == null || minecraft.player == null) {
            debugLog("RectPick client fallback transfer skipped because game mode or player is unavailable");
            return false;
        }

        for (InventoryTransferExecutor.SourceInventoryTransfer sourceInventory : plan.sourceInventories()) {
            for (Slot sourceSlot : sourceInventory.sourceSlots()) {
                if (InventoryTransferExecutor.isCreativeCopySourceSlot(sourceSlot)) {
                    copyCreativeClientSlot(minecraft, sourceSlot, plan.destinationInventory(), plan.destinationSlots(), stackUnitTransfer);
                } else {
                    moveClientSlot(minecraft, menu, sourceInventory.sourceInventory(), sourceSlot, plan.destinationInventory(), plan.destinationSlots());
                }
            }
        }

        debugLog("RectPick client fallback transfer completed: targetSlot={}, sources={}", targetSlotIndex, sourceSlotIndices);
        return true;
    }

    /**
     * Estimates destination slots that will receive items if the transfer plan is executed.
     *
     * @param menu menu that owns the planned slots.
     * @param plan transfer plan containing source groups and one destination inventory.
     * @param stackUnitTransfer {@code true} to estimate full creative stack copies.
     * @return menu slot indices for destination slots expected to receive at least one item.
     */
    private static List<Integer> estimateMovedDestinationSlotIndices(AbstractContainerMenu menu, InventoryTransferExecutor.InventoryTransferPlan plan, boolean stackUnitTransfer) {
        List<ItemStack> simulatedDestinationStacks = plan.destinationSlots().stream()
                .map(slot -> slot.getItem().copy())
                .collect(ArrayList::new, ArrayList::add, ArrayList::addAll);
        List<Integer> movedSlotIndices = new ArrayList<>();

        for (InventoryTransferExecutor.SourceInventoryTransfer sourceInventory : plan.sourceInventories()) {
            for (Slot sourceSlot : sourceInventory.sourceSlots()) {
                ItemStack movingStack = createTransferStack(sourceSlot, stackUnitTransfer);
                estimateMoveIntoExistingStacks(menu, plan.destinationInventory(), movingStack, plan.destinationSlots(), simulatedDestinationStacks, movedSlotIndices);
                estimateMoveIntoEmptySlots(menu, plan.destinationInventory(), movingStack, plan.destinationSlots(), simulatedDestinationStacks, movedSlotIndices);
            }
        }

        return List.copyOf(movedSlotIndices);
    }

    /**
     * Creates the mutable stack used by transfer simulation or creative copying.
     *
     * @param sourceSlot source slot to read.
     * @param stackUnitTransfer {@code true} to expand creative source stacks to their max stack size.
     * @return copied stack that callers may shrink while inserting into destinations.
     */
    private static ItemStack createTransferStack(Slot sourceSlot, boolean stackUnitTransfer) {
        ItemStack sourceStack = sourceSlot.getItem();
        if (sourceStack.isEmpty()) {
            return ItemStack.EMPTY;
        }

        if (stackUnitTransfer && InventoryTransferExecutor.isCreativeCopySourceSlot(sourceSlot)) {
            return sourceStack.copyWithCount(sourceStack.getMaxStackSize());
        }

        return sourceStack.copy();
    }

    /**
     * Simulates merging one moving stack into compatible existing destination stacks.
     *
     * @param menu menu that owns the destination slots.
     * @param destinationInventory inventory that should receive items.
     * @param movingStack mutable simulated source stack.
     * @param destinationSlots destination slots ordered by insertion priority.
     * @param simulatedDestinationStacks mutable simulated stacks matching {@code destinationSlots} by index.
     * @param movedSlotIndices output menu slot indices that accepted simulated items.
     */
    private static void estimateMoveIntoExistingStacks(
            AbstractContainerMenu menu,
            Container destinationInventory,
            ItemStack movingStack,
            List<Slot> destinationSlots,
            List<ItemStack> simulatedDestinationStacks,
            List<Integer> movedSlotIndices
    ) {
        for (int i = 0; i < destinationSlots.size(); i++) {
            if (movingStack.isEmpty()) {
                return;
            }

            ItemStack destinationStack = simulatedDestinationStacks.get(i);
            if (destinationStack.isEmpty() || !ItemStack.isSameItemSameComponents(destinationStack, movingStack)) {
                continue;
            }

            estimateMoveIntoSlot(menu, destinationInventory, movingStack, destinationSlots.get(i), destinationStack, movedSlotIndices);
        }
    }

    /**
     * Simulates inserting one moving stack into empty destination slots.
     *
     * @param menu menu that owns the destination slots.
     * @param destinationInventory inventory that should receive items.
     * @param movingStack mutable simulated source stack.
     * @param destinationSlots destination slots ordered by insertion priority.
     * @param simulatedDestinationStacks mutable simulated stacks matching {@code destinationSlots} by index.
     * @param movedSlotIndices output menu slot indices that accepted simulated items.
     */
    private static void estimateMoveIntoEmptySlots(
            AbstractContainerMenu menu,
            Container destinationInventory,
            ItemStack movingStack,
            List<Slot> destinationSlots,
            List<ItemStack> simulatedDestinationStacks,
            List<Integer> movedSlotIndices
    ) {
        for (int i = 0; i < destinationSlots.size(); i++) {
            if (movingStack.isEmpty()) {
                return;
            }

            ItemStack destinationStack = simulatedDestinationStacks.get(i);
            if (!destinationStack.isEmpty()) {
                continue;
            }

            Slot destinationSlot = destinationSlots.get(i);
            if (!InventoryItemMover.canAcceptStack(destinationSlot, destinationInventory, movingStack)) {
                continue;
            }

            int limit = Math.min(
                    Math.min(destinationSlot.getMaxStackSize(movingStack), destinationInventory.getMaxStackSize(movingStack)),
                    movingStack.getMaxStackSize()
            );
            int movable = Math.min(movingStack.getCount(), limit);
            if (movable <= 0) {
                continue;
            }

            simulatedDestinationStacks.set(i, movingStack.copyWithCount(movable));
            movingStack.shrink(movable);
            addMovedSlotIndex(movedSlotIndices, menuIndexOfSlot(menu, destinationSlot));
        }
    }

    /**
     * Simulates moving part of one stack into one destination slot.
     *
     * @param menu menu that owns the destination slot.
     * @param destinationInventory inventory that should receive items.
     * @param movingStack mutable simulated source stack.
     * @param destinationSlot destination slot to test.
     * @param simulatedDestinationStack mutable simulated destination stack.
     * @param movedSlotIndices output menu slot indices that accepted simulated items.
     */
    private static void estimateMoveIntoSlot(
            AbstractContainerMenu menu,
            Container destinationInventory,
            ItemStack movingStack,
            Slot destinationSlot,
            ItemStack simulatedDestinationStack,
            List<Integer> movedSlotIndices
    ) {
        if (!InventoryItemMover.canAcceptStack(destinationSlot, destinationInventory, movingStack)) {
            return;
        }

        int limit = Math.min(
                Math.min(destinationSlot.getMaxStackSize(movingStack), destinationInventory.getMaxStackSize(movingStack)),
                movingStack.getMaxStackSize()
        );
        int freeSpace = simulatedDestinationStack.isEmpty() ? limit : limit - simulatedDestinationStack.getCount();
        int movable = Math.min(movingStack.getCount(), freeSpace);
        if (movable <= 0) {
            return;
        }

        simulatedDestinationStack.grow(movable);
        movingStack.shrink(movable);
        addMovedSlotIndex(movedSlotIndices, menuIndexOfSlot(menu, destinationSlot));
    }

    /**
     * Adds a slot index once while preserving insertion order.
     *
     * @param movedSlotIndices mutable output list.
     * @param slotIndex menu slot index to add.
     */
    private static void addMovedSlotIndex(List<Integer> movedSlotIndices, int slotIndex) {
        if (!movedSlotIndices.contains(slotIndex)) {
            movedSlotIndices.add(slotIndex);
        }
    }

    /**
     * Picks up one source slot and distributes the carried stack into destination slots.
     *
     * @param minecraft active client instance; must have non-null player and game mode.
     * @param menu current menu containing all passed slots.
     * @param sourceInventory source inventory that owns {@code sourceSlot}.
     * @param sourceSlot slot to pick up from; must contain an item and be pickup-allowed.
     * @param destinationInventory inventory that should receive items.
     * @param destinationSlots active slots belonging to {@code destinationInventory}, ordered by container slot index.
     */
    private static void moveClientSlot(Minecraft minecraft, AbstractContainerMenu menu, Container sourceInventory, Slot sourceSlot, Container destinationInventory, List<Slot> destinationSlots) {
        if (!sourceSlot.hasItem() || !sourceSlot.mayPickup(minecraft.player) || sourceSlot.container != sourceInventory || sourceInventory == destinationInventory) {
            return;
        }

        int sourceSlotIndex = menuIndexOfSlot(menu, sourceSlot);
        minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlotIndex, 0, ClickType.PICKUP, minecraft.player);
        moveCarriedStackWithClicks(minecraft, menu, destinationInventory, destinationSlots, false);
        moveCarriedStackWithClicks(minecraft, menu, destinationInventory, destinationSlots, true);

        if (!menu.getCarried().isEmpty()) {
            minecraft.gameMode.handleInventoryMouseClick(menu.containerId, sourceSlotIndex, 0, ClickType.PICKUP, minecraft.player);
        }
    }

    /**
     * Copies one creative tab source stack into destination slots without consuming the source.
     *
     * @param minecraft active client instance; must have non-null player and game mode.
     * @param sourceSlot creative tab item-list slot to copy from.
     * @param destinationInventory inventory that should receive copied items.
     * @param destinationSlots active slots belonging to {@code destinationInventory}, ordered by container slot index.
     * @param stackUnitTransfer {@code true} to copy a full max-size stack.
     */
    private static void copyCreativeClientSlot(Minecraft minecraft, Slot sourceSlot, Container destinationInventory, List<Slot> destinationSlots, boolean stackUnitTransfer) {
        ItemStack movingStack = createTransferStack(sourceSlot, stackUnitTransfer);
        if (movingStack.isEmpty()) {
            return;
        }

        copyCreativeStackWithSync(minecraft, destinationInventory, movingStack, destinationSlots, false);
        copyCreativeStackWithSync(minecraft, destinationInventory, movingStack, destinationSlots, true);
    }

    /**
     * Inserts a copied creative stack into existing or empty destination slots and syncs player inventory slots.
     *
     * @param minecraft active client instance; must have non-null player and game mode.
     * @param destinationInventory inventory that should receive copied items.
     * @param movingStack mutable copied creative stack.
     * @param destinationSlots candidate destination slots ordered by desired insertion priority.
     * @param emptySlotsOnly {@code true} to insert only into empty slots, {@code false} to merge into compatible stacks.
     */
    private static void copyCreativeStackWithSync(Minecraft minecraft, Container destinationInventory, ItemStack movingStack, List<Slot> destinationSlots, boolean emptySlotsOnly) {
        for (Slot destinationSlot : destinationSlots) {
            if (movingStack.isEmpty()) {
                return;
            }

            ItemStack destinationStack = destinationSlot.getItem();
            if (emptySlotsOnly != destinationStack.isEmpty()) {
                continue;
            }

            if (!emptySlotsOnly && !ItemStack.isSameItemSameComponents(destinationStack, movingStack)) {
                continue;
            }

            int beforeCount = movingStack.getCount();
            InventoryItemMover.copyItemStack(destinationInventory, movingStack, List.of(destinationSlot));
            if (movingStack.getCount() == beforeCount) {
                continue;
            }

            syncCreativeDestinationSlot(minecraft, destinationSlot);
        }
    }

    /**
     * Sends vanilla creative inventory sync for player inventory slots touched by a copy operation.
     *
     * @param minecraft active client instance; must have non-null game mode.
     * @param destinationSlot destination slot that was changed locally.
     */
    private static void syncCreativeDestinationSlot(Minecraft minecraft, Slot destinationSlot) {
        if (!(destinationSlot.container instanceof Inventory)) {
            return;
        }

        int containerSlot = destinationSlot.getContainerSlot();
        int creativeSlot = containerSlot >= 0 && containerSlot < 9 ? containerSlot + 36 : containerSlot;
        minecraft.gameMode.handleCreativeModeItemAdd(destinationSlot.getItem(), creativeSlot);
    }

    /**
     * Clicks destination slots until the carried stack is exhausted or no progress is possible.
     *
     * @param minecraft active client instance; must have non-null player and game mode.
     * @param menu current menu whose carried stack is being moved.
     * @param destinationInventory inventory that each destination slot must belong to.
     * @param destinationSlots candidate destination slots ordered by desired insertion priority.
     * @param emptySlotsOnly {@code true} to click only empty slots, {@code false} to merge only compatible non-empty stacks.
     */
    private static void moveCarriedStackWithClicks(Minecraft minecraft, AbstractContainerMenu menu, Container destinationInventory, List<Slot> destinationSlots, boolean emptySlotsOnly) {
        for (Slot destinationSlot : destinationSlots) {
            ItemStack carried = menu.getCarried();
            if (carried.isEmpty()) {
                return;
            }

            ItemStack destinationStack = destinationSlot.getItem();
            if (emptySlotsOnly != destinationStack.isEmpty()) {
                continue;
            }

            if (!emptySlotsOnly && !ItemStack.isSameItemSameComponents(destinationStack, carried)) {
                continue;
            }

            if (!InventoryItemMover.canAcceptStack(destinationSlot, destinationInventory, carried)) {
                continue;
            }

            if (!hasRoomForCarriedStack(destinationSlot, destinationInventory, carried)) {
                continue;
            }

            int beforeCount = carried.getCount();
            minecraft.gameMode.handleInventoryMouseClick(menu.containerId, menuIndexOfSlot(menu, destinationSlot), 0, ClickType.PICKUP, minecraft.player);
            if (!menu.getCarried().isEmpty() && menu.getCarried().getCount() == beforeCount) {
                return;
            }
        }
    }

    /**
     * Checks whether a destination slot has room for the carried stack.
     *
     * @param destinationSlot slot to inspect; must belong to {@code destinationInventory}.
     * @param destinationInventory inventory used for container stack limits.
     * @param carried stack currently carried by the menu; must not be empty.
     * @return {@code true} when the slot is empty or its compatible stack is below the effective slot limit.
     */
    private static boolean hasRoomForCarriedStack(Slot destinationSlot, Container destinationInventory, ItemStack carried) {
        int limit = Math.min(
                Math.min(destinationSlot.getMaxStackSize(carried), destinationInventory.getMaxStackSize(carried)),
                carried.getMaxStackSize()
        );
        return destinationSlot.getItem().isEmpty() || destinationSlot.getItem().getCount() < limit;
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

    /**
     * Item count snapshot for one destination slot.
     *
     * @param slotIndex menu slot index of one destination slot.
     * @param itemCount item count observed in that slot.
     */
    public record DestinationSlotSnapshot(int slotIndex, int itemCount) {
    }

    /**
     * Result of dispatching a transfer request.
     *
     * @param transferred whether a server request was sent or client fallback work started.
     * @param destinationChanged whether destination slot item counts changed immediately.
     * @param delayedSyncExpected whether a server response may still change destination slot item counts later.
     * @param movedDestinationSlotIndices menu slot indices whose item counts changed immediately.
     * @param beforeDestinationSnapshots destination counts captured before the transfer request.
     */
    public record TransferDispatchResult(
            boolean transferred,
            boolean destinationChanged,
            boolean delayedSyncExpected,
            List<Integer> movedDestinationSlotIndices,
            List<DestinationSlotSnapshot> beforeDestinationSnapshots
    ) {
        /**
         * Copies the moved slot list so callers cannot mutate this result.
         *
         * @param transferred whether transfer work started.
         * @param destinationChanged whether destination slot item counts changed immediately.
         * @param delayedSyncExpected whether a server response may still change destination slot item counts later.
         * @param movedDestinationSlotIndices menu slot indices whose item counts changed immediately.
         * @param beforeDestinationSnapshots destination counts captured before the transfer request.
         */
        public TransferDispatchResult {
            movedDestinationSlotIndices = List.copyOf(movedDestinationSlotIndices);
            beforeDestinationSnapshots = List.copyOf(beforeDestinationSnapshots);
        }

        /**
         * Creates an empty failed dispatch result.
         *
         * @return result with {@code transferred == false} and no moved destination slots.
         */
        private static TransferDispatchResult notTransferred() {
            return new TransferDispatchResult(false, false, false, List.of(), List.of());
        }
    }
}
