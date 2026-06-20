package org.pepton.rectpick.transfer;

import java.util.List;
import net.minecraft.world.Container;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

/**
 * Low-level item insertion helper for moving one stack between two inventories.
 * <p>
 * The caller supplies destination slots so slot filters and ordering can be
 * enforced by the menu that owns the transfer.
 */
public final class InventoryItemMover {
    private InventoryItemMover() {
    }

    /**
     * Moves as much of one mutable stack as possible into destination slots.
     *
     * @param previousInventory source inventory that owns {@code beforeMoveStack}; must not be the same object as {@code nextInventory}.
     * @param nextInventory destination inventory that owns every slot in {@code destinationSlots}.
     * @param beforeMoveStack mutable source stack from the source slot; it is shrunk by the moved amount.
     * @param destinationSlots destination slots ordered by insertion priority and filtered to the destination inventory.
     * @return number of items removed from {@code beforeMoveStack} and inserted into destination slots.
     */
    public static int moveItemStack(Container previousInventory, Container nextInventory, ItemStack beforeMoveStack, List<Slot> destinationSlots) {
        if (previousInventory == nextInventory || beforeMoveStack.isEmpty()) {
            return 0;
        }

        int beforeCount = beforeMoveStack.getCount();
        moveIntoExistingStacks(nextInventory, beforeMoveStack, destinationSlots);
        moveIntoEmptySlots(nextInventory, beforeMoveStack, destinationSlots);

        if (beforeMoveStack.getCount() != beforeCount) {
            previousInventory.setChanged();
            nextInventory.setChanged();
        }

        return beforeCount - beforeMoveStack.getCount();
    }

    /**
     * Copies as much of one mutable stack as possible into destination slots without changing a source inventory.
     *
     * @param nextInventory destination inventory that owns every slot in {@code destinationSlots}.
     * @param copiedStack mutable stack copy; it is shrunk by the copied amount.
     * @param destinationSlots destination slots ordered by insertion priority and filtered to the destination inventory.
     * @return number of items removed from {@code copiedStack} and inserted into destination slots.
     */
    public static int copyItemStack(Container nextInventory, ItemStack copiedStack, List<Slot> destinationSlots) {
        if (copiedStack.isEmpty()) {
            return 0;
        }

        int beforeCount = copiedStack.getCount();
        moveIntoExistingStacks(nextInventory, copiedStack, destinationSlots);
        moveIntoEmptySlots(nextInventory, copiedStack, destinationSlots);

        if (copiedStack.getCount() != beforeCount) {
            nextInventory.setChanged();
        }

        return beforeCount - copiedStack.getCount();
    }

    /**
     * Checks whether a destination slot can accept the supplied stack.
     *
     * @param destinationSlot slot being tested; must be active and belong to {@code nextInventory}.
     * @param nextInventory inventory that should receive the stack.
     * @param stack stack to insert; must not be empty.
     * @return {@code true} when slot and container placement rules allow at least one item type-compatible insertion.
     */
    public static boolean canAcceptStack(Slot destinationSlot, Container nextInventory, ItemStack stack) {
        if (!destinationSlot.isActive() || destinationSlot.container != nextInventory || stack.isEmpty()) {
            return false;
        }

        int containerSlot = destinationSlot.getContainerSlot();
        return destinationSlot.mayPlace(stack)
                && nextInventory.canPlaceItem(containerSlot, stack)
                && destinationSlot.getMaxStackSize(stack) > 0;
    }

    /**
     * Merges a moving stack into compatible existing destination stacks.
     *
     * @param nextInventory destination inventory used for placement and stack limits.
     * @param movingStack mutable stack being inserted; it is shrunk by successful merges.
     * @param destinationSlots ordered candidate slots belonging to {@code nextInventory}.
     */
    private static void moveIntoExistingStacks(Container nextInventory, ItemStack movingStack, List<Slot> destinationSlots) {
        for (Slot destinationSlot : destinationSlots) {
            if (movingStack.isEmpty()) {
                return;
            }

            ItemStack destinationStack = destinationSlot.getItem();
            if (destinationStack.isEmpty() || !ItemStack.isSameItemSameComponents(destinationStack, movingStack)) {
                continue;
            }

            moveIntoSlot(destinationSlot, nextInventory, movingStack);
        }
    }

    /**
     * Inserts a moving stack into empty destination slots.
     *
     * @param nextInventory destination inventory used for placement and stack limits.
     * @param movingStack mutable stack being inserted; it is shrunk by successful inserts.
     * @param destinationSlots ordered candidate slots belonging to {@code nextInventory}.
     */
    private static void moveIntoEmptySlots(Container nextInventory, ItemStack movingStack, List<Slot> destinationSlots) {
        for (Slot destinationSlot : destinationSlots) {
            if (movingStack.isEmpty()) {
                return;
            }

            if (!destinationSlot.getItem().isEmpty()) {
                continue;
            }

            moveIntoSlot(destinationSlot, nextInventory, movingStack);
        }
    }

    /**
     * Moves items from one mutable stack into one destination slot.
     *
     * @param destinationSlot destination slot; must pass {@link #canAcceptStack(Slot, Container, ItemStack)}.
     * @param nextInventory destination inventory that owns {@code destinationSlot}.
     * @param movingStack mutable source stack; it is shrunk by the inserted amount.
     */
    private static void moveIntoSlot(Slot destinationSlot, Container nextInventory, ItemStack movingStack) {
        if (!canAcceptStack(destinationSlot, nextInventory, movingStack)) {
            return;
        }

        ItemStack destinationStack = destinationSlot.getItem();
        int limit = getSlotLimit(destinationSlot, nextInventory, movingStack);
        int movable;
        if (destinationStack.isEmpty()) {
            movable = Math.min(movingStack.getCount(), limit);
            if (movable <= 0) {
                return;
            }

            destinationSlot.set(movingStack.copyWithCount(movable));
            movingStack.shrink(movable);
        } else {
            int freeSpace = limit - destinationStack.getCount();
            movable = Math.min(movingStack.getCount(), freeSpace);
            if (movable <= 0) {
                return;
            }

            destinationStack.grow(movable);
            movingStack.shrink(movable);
        }

        destinationSlot.setChanged();
    }

    /**
     * Computes the effective max stack size for a slot and item.
     *
     * @param destinationSlot destination slot that supplies slot-specific limits.
     * @param nextInventory destination inventory that supplies container-specific limits.
     * @param stack stack being inserted.
     * @return the minimum of slot, container, and item max stack size.
     */
    private static int getSlotLimit(Slot destinationSlot, Container nextInventory, ItemStack stack) {
        return Math.min(
                Math.min(destinationSlot.getMaxStackSize(stack), nextInventory.getMaxStackSize(stack)),
                stack.getMaxStackSize()
        );
    }
}
