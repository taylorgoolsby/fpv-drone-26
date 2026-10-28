package com.taylorgoolsby.fpvdrone.race.client;

import com.taylorgoolsby.fpvdrone.race.RaceClientState;
import net.minecraftforge.client.event.ClientPlayerNetworkEvent;

/**
 * Client-only entry point for the racing feature. Called from ClientInit so a
 * dedicated server never loads any of these classes.
 */
public final class RaceClient {
    private RaceClient() {}

    public static void register() {
        RaceHud.register();
        RaceGizmos.register();

        // Forget everything when leaving a world or server.
        ClientPlayerNetworkEvent.LoggingOut.BUS.addListener(RaceClient::onLoggingOut);
    }

    private static void onLoggingOut(ClientPlayerNetworkEvent.LoggingOut event) {
        RaceClientState.reset();
    }
}
