package org.pepton.rectpick.network;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import org.pepton.rectpick.config.Consts;
import org.pepton.rectpick.transfer.InventoryTransferExecutor;
import org.slf4j.Logger;

/**
 * Common network registration for RectPick payloads.
 * <p>
 * This utility registers optional server-bound play payloads so client-only
 * installs can still fall back to vanilla click behavior.
 */
public final class RectPickNetworking {
    private static final Logger LOGGER = LogUtils.getLogger();
    private static final String NETWORK_VERSION = "2";

    private RectPickNetworking() {
    }

    /**
     * Registers RectPick payload handlers with NeoForge.
     *
     * @param event payload registration event fired on the mod bus; must occur before network setup completes.
     */
    public static void registerPayloadHandlers(RegisterPayloadHandlersEvent event) {
        event.registrar(NETWORK_VERSION)
                .optional()
                .playToServer(MoveItemsPayload.TYPE, MoveItemsPayload.STREAM_CODEC, RectPickNetworking::handleMoveItems);
    }

    /**
     * Handles a server-bound move request on the logical server.
     *
     * @param payload decoded move request; container id must match the sender's current menu.
     * @param context NeoForge payload context; must provide a server-side player in play phase.
     */
    private static void handleMoveItems(MoveItemsPayload payload, IPayloadContext context) {
        Player player = context.player();
        if (!(player instanceof ServerPlayer serverPlayer)) {
            debugLog("RectPick move payload ignored because sender is not a server player");
            return;
        }

        if (Consts.disableServerTransferForDebug) {
            debugLog("RectPick move payload ignored because debug client-side transfer mode is enabled");
            return;
        }

        AbstractContainerMenu menu = serverPlayer.containerMenu;
        if (menu.containerId != payload.containerId()) {
            debugLog(
                    "RectPick move payload ignored because container id changed: payload={}, current={}",
                    payload.containerId(),
                    menu.containerId
            );
            return;
        }

        InventoryTransferExecutor.serverTransfer(
                menu,
                serverPlayer,
                payload.targetSlotIndex(),
                payload.targetAe2Storage(),
                payload.sourceSlots()
        );
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
