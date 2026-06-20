package org.pepton.rectpick.api;

import com.mojang.logging.LogUtils;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import org.pepton.rectpick.config.Consts;
import org.pepton.rectpick.network.MoveItemsPayload;
import org.slf4j.Logger;

/**
 * AE2 integration point for RectPick.
 * <p>
 * This class is only called when AE2 is loaded. It follows AE2's terminal interaction
 * model by resolving RepoSlot entries to serial ids and delegating movement to AE2's menu logic.
 */
public final class Ae2Api {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String AE_BASE_MENU_CLASS_NAME = "appeng.menu.AEBaseMenu";
    private static final String ME_STORAGE_MENU_CLASS_NAME = "appeng.menu.me.common.MEStorageMenu";
    private static final String REPO_SLOT_CLASS_NAME = "appeng.client.gui.me.common.RepoSlot";
    private static final String INVENTORY_ACTION_CLASS_NAME = "appeng.helpers.InventoryAction";
    private static final String CRAFTING_GRID_SEMANTIC_ID = "CRAFTING_GRID";
    private static final String STORAGE_SEMANTIC_ID = "STORAGE";

    private Ae2Api() {
    }

    /**
     * Checks whether this target points at AE2 terminal storage.
     *
     * @param menu current menu.
     * @param targetSlotIndex menu slot index selected as transfer target.
     * @return {@code true} when the target is AE2's storage display area.
     */
    public static boolean isStorageTarget(AbstractContainerMenu menu, int targetSlotIndex) {
        return isRepoSlot(menu, targetSlotIndex) || hasSlotSemantic(menu, targetSlotIndex, STORAGE_SEMANTIC_ID);
    }

    /**
     * Checks whether a menu is an AE2 storage terminal menu.
     *
     * @param menu menu to inspect; may be any vanilla or modded menu.
     * @return {@code true} when the menu is an AE2 ME storage menu.
     */
    public static boolean isTerminalMenu(AbstractContainerMenu menu) {
        return isInstanceOf(menu, ME_STORAGE_MENU_CLASS_NAME);
    }

    /**
     * Checks whether AE2 should own this server transfer.
     *
     * @param menu current server menu.
     * @param targetSlotIndex target menu slot index from the client.
     * @param targetAe2Storage whether the client selected AE2 storage as the destination.
     * @param sourceSlots selected source slots and client-visible stacks.
     * @return {@code true} when the operation should be handled through AE2 storage APIs.
     */
    public static boolean shouldHandleServerTransfer(
            AbstractContainerMenu menu,
            int targetSlotIndex,
            boolean targetAe2Storage,
            List<MoveItemsPayload.SourceSlot> sourceSlots
    ) {
        if (!isTerminalMenu(menu)) {
            return false;
        }

        return targetAe2Storage || sourceSlots.stream().anyMatch(sourceSlot -> sourceSlot.ae2Serial() >= 0);
    }

    /**
     * Moves selected items between normal slots and AE2 terminal storage on the server.
     *
     * @param menu current AE2 terminal menu.
     * @param player server player requesting the transfer.
     * @param targetSlotIndex target menu slot index selected by the client.
     * @param targetAe2Storage whether the client selected AE2 storage as the destination.
     * @param sourceSlots selected source slots and client-visible stacks.
     * @return total number of items moved between AE2 storage and normal inventory slots.
     */
    public static int serverTransfer(
            AbstractContainerMenu menu,
            ServerPlayer player,
            int targetSlotIndex,
            boolean targetAe2Storage,
            List<MoveItemsPayload.SourceSlot> sourceSlots
    ) {
        if (!isTerminalMenu(menu)) {
            return 0;
        }

        if (!canInteractWithGrid(menu)) {
            return 0;
        }

        int movedTotal = targetAe2Storage
                ? quickMoveSlotsIntoStorage(menu, player, sourceSlots)
                : shiftClickStorageEntries(menu, sourceSlots);

        if (movedTotal > 0) {
            menu.broadcastChanges();
        }

        debugLog(
                "RectPick AE2 terminal transfer completed: movedItems={}, targetSlot={}, targetStorage={}, sources={}",
                movedTotal,
                targetSlotIndex,
                targetAe2Storage,
                sourceSlots.stream().map(MoveItemsPayload.SourceSlot::slotIndex).toList()
        );
        return movedTotal;
    }

    /**
     * Quick-moves real menu source slots into AE2 terminal storage using AE2's menu logic.
     *
     * @param menu AE2 terminal menu.
     * @param player server player requesting the transfer.
     * @param sourceSlots selected source slot snapshots.
     * @return number of items accepted by AE2 storage.
     */
    private static int quickMoveSlotsIntoStorage(
            AbstractContainerMenu menu,
            ServerPlayer player,
            List<MoveItemsPayload.SourceSlot> sourceSlots
    ) {
        int movedTotal = 0;
        for (MoveItemsPayload.SourceSlot sourceSlotSnapshot : sourceSlots) {
            int sourceSlotIndex = sourceSlotSnapshot.slotIndex();
            if (!menu.isValidSlotIndex(sourceSlotIndex) || sourceSlotSnapshot.ae2Serial() >= 0) {
                continue;
            }

            Slot sourceSlot = menu.getSlot(sourceSlotIndex);
            if (!sourceSlot.hasItem() || !sourceSlot.mayPickup(player)) {
                continue;
            }

            int beforeCount = sourceSlot.getItem().getCount();
            menu.quickMoveStack(player, sourceSlotIndex);
            int afterCount = sourceSlot.getItem().getCount();
            movedTotal += Math.max(0, beforeCount - afterCount);
        }

        return movedTotal;
    }

    /**
     * Shift-clicks AE2 storage entries using AE2's menu interaction logic.
     *
     * @param menu AE2 terminal menu.
     * @param sourceSlots selected source slot snapshots; AE2 serials identify storage entries.
     * @return number of AE2 storage interactions invoked.
     */
    private static int shiftClickStorageEntries(AbstractContainerMenu menu, List<MoveItemsPayload.SourceSlot> sourceSlots) {
        int movedTotal = 0;

        for (MoveItemsPayload.SourceSlot sourceSlot : sourceSlots) {
            if (sourceSlot.ae2Serial() >= 0 && handleStorageInteraction(menu, sourceSlot.ae2Serial(), "SHIFT_CLICK")) {
                movedTotal++;
            }
        }

        return movedTotal;
    }

    /**
     * Returns the AE2 storage entry serial represented by a client RepoSlot.
     *
     * @param menu current menu that owns the slot.
     * @param slotIndex menu slot index to inspect.
     * @return AE2 serial, or {@code -1} when the slot is not an AE2 storage entry.
     */
    public static long getStorageEntrySerial(AbstractContainerMenu menu, int slotIndex) {
        if (!isRepoSlot(menu, slotIndex)) {
            return -1;
        }

        Object entry = invoke(menu.getSlot(slotIndex), "getEntry");
        Object serial = entry != null ? invoke(entry, "getSerial") : null;
        if (serial instanceof Long value) {
            return value;
        }

        return -1;
    }

    /**
     * Invokes AE2's ME storage interaction method.
     *
     * @param menu AE2 terminal menu.
     * @param serial AE2 storage entry serial, or {@code -1} for empty virtual slot behavior.
     * @param actionName name of the AE2 InventoryAction enum constant to invoke.
     * @return {@code true} when the interaction method was invoked.
     */
    public static boolean handleStorageInteraction(AbstractContainerMenu menu, long serial, String actionName) {
        Object action = inventoryAction(actionName);
        if (action == null) {
            return false;
        }

        return invokeVoid(menu, "handleInteraction", new Class<?>[] {long.class, action.getClass()}, serial, action);
    }

    /**
     * Checks whether an AE2 menu slot has the requested slot semantic.
     *
     * @param menu menu to inspect; may be any vanilla or modded menu.
     * @param targetSlotIndex valid menu slot index to inspect.
     * @param expectedSemanticId AE2 slot semantic id, such as {@code STORAGE}.
     * @return {@code true} when the menu is an AE2 menu and the slot semantic id matches.
     */
    private static boolean hasSlotSemantic(AbstractContainerMenu menu, int targetSlotIndex, String expectedSemanticId) {
        if (!isInstanceOf(menu, AE_BASE_MENU_CLASS_NAME) || !menu.isValidSlotIndex(targetSlotIndex)) {
            return false;
        }

        Object semantic = invoke(menu, "getSlotSemantic", new Class<?>[] {Slot.class}, menu.getSlot(targetSlotIndex));
        return semantic != null && expectedSemanticId.equals(getSemanticId(semantic));
    }

    /**
     * Checks whether one menu slot is AE2's virtual repository slot.
     *
     * @param menu current menu that owns the slot.
     * @param slotIndex menu slot index to inspect.
     * @return {@code true} when the slot is an AE2 RepoSlot.
     */
    private static boolean isRepoSlot(AbstractContainerMenu menu, int slotIndex) {
        return menu.isValidSlotIndex(slotIndex) && isInstanceOf(menu.getSlot(slotIndex), REPO_SLOT_CLASS_NAME);
    }

    /**
     * Checks the AE2 terminal link/power gate before inserting.
     *
     * @param menu AE2 storage menu instance.
     * @return {@code true} when AE2 reports that storage interaction is allowed.
     */
    private static boolean canInteractWithGrid(AbstractContainerMenu menu) {
        Object result = invoke(menu, "canInteractWithGrid");
        return Boolean.TRUE.equals(result);
    }

    /**
     * Reads an AE2 slot semantic id.
     *
     * @param semantic AE2 SlotSemantic instance.
     * @return semantic id string, or an empty string when the id could not be read.
     */
    private static String getSemanticId(Object semantic) {
        Object id = invoke(semantic, "id");
        return id instanceof String value ? value : "";
    }

    /**
     * Reads an AE2 InventoryAction enum constant by name.
     *
     * @param actionName enum constant name to read.
     * @return enum constant object, or {@code null} when AE2 is unavailable or the name is invalid.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object inventoryAction(String actionName) {
        try {
            Class<?> actionClass = Class.forName(INVENTORY_ACTION_CLASS_NAME);
            if (!Enum.class.isAssignableFrom(actionClass)) {
                return null;
            }

            return Enum.valueOf((Class<? extends Enum>) actionClass.asSubclass(Enum.class), actionName);
        } catch (ClassNotFoundException | IllegalArgumentException e) {
            debugLog("RectPick AE2 inventory action lookup failed: action={}, error={}", actionName, e.toString());
            return null;
        }
    }

    /**
     * Checks an object's runtime type by class name without requiring compile-time access to that class.
     *
     * @param value object to inspect.
     * @param className fully qualified class name.
     * @return {@code true} when {@code value} is an instance of the named class.
     */
    private static boolean isInstanceOf(Object value, String className) {
        if (value == null) {
            return false;
        }

        try {
            return Class.forName(className).isInstance(value);
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    /**
     * Invokes a no-argument method through reflection.
     *
     * @param target object that owns the method.
     * @param methodName method name to invoke.
     * @return raw method result, or {@code null} when invocation fails.
     */
    private static Object invoke(Object target, String methodName) {
        return invoke(target, methodName, new Class<?>[0]);
    }

    /**
     * Invokes a method through reflection.
     *
     * @param target object that owns the method.
     * @param methodName method name to invoke.
     * @param parameterTypes exact parameter types used to find the method.
     * @param args invocation arguments matching {@code parameterTypes}.
     * @return raw method result, or {@code null} when invocation fails.
     */
    private static Object invoke(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        Method method = findMethod(target.getClass(), methodName, parameterTypes);
        if (method == null) {
            return null;
        }

        try {
            method.setAccessible(true);
            return method.invoke(target, args);
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            debugLog("RectPick AE2 reflection method failed: method={}, error={}", methodName, e.toString());
            return null;
        }
    }

    /**
     * Invokes a method through reflection and reports whether invocation succeeded.
     *
     * @param target object that owns the method.
     * @param methodName method name to invoke.
     * @param parameterTypes exact parameter types used to find the method.
     * @param args invocation arguments matching {@code parameterTypes}.
     * @return {@code true} when the method was found and invoked without an exception.
     */
    private static boolean invokeVoid(Object target, String methodName, Class<?>[] parameterTypes, Object... args) {
        Method method = findMethod(target.getClass(), methodName, parameterTypes);
        if (method == null) {
            return false;
        }

        try {
            method.setAccessible(true);
            method.invoke(target, args);
            return true;
        } catch (IllegalAccessException | InvocationTargetException | RuntimeException e) {
            debugLog("RectPick AE2 reflection method failed: method={}, error={}", methodName, e.toString());
            return false;
        }
    }

    /**
     * Finds a method declared on a class or one of its superclasses.
     *
     * @param type class to start searching from.
     * @param methodName method name to find.
     * @param parameterTypes exact parameter type list.
     * @return matching method, or {@code null} when absent.
     */
    private static Method findMethod(Class<?> type, String methodName, Class<?>[] parameterTypes) {
        Class<?> current = type;
        while (current != null) {
            try {
                return current.getDeclaredMethod(methodName, parameterTypes);
            } catch (NoSuchMethodException e) {
                current = current.getSuperclass();
            }
        }

        return null;
    }

    private static void debugLog(String message, Object... args) {
        if (Consts.debugLog) {
            LOGGER.info(message, args);
        }
    }
}
