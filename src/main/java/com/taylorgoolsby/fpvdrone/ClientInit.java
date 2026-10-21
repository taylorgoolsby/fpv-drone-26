package com.taylorgoolsby.fpvdrone;

import com.taylorgoolsby.fpvdrone.screen.FpvSettingsButton;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;

/**
 * Client-only entry point. Kept in its own class so a dedicated server
 * never loads DroneClientEvents or any other client classes.
 */
final class ClientInit {

    private ClientInit() {}

    static void init() {
        DroneClientEvents.register();
        FpvSettingsButton.register();

        // Reset drone state when disconnecting from a world or server.
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(
                ClientInit::onLoggingOut
        );
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        DroneState.reset();
    }
}