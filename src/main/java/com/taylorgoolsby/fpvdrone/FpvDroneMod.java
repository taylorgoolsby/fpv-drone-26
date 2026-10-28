package com.taylorgoolsby.fpvdrone;

import com.mojang.logging.LogUtils;
import com.taylorgoolsby.fpvdrone.race.RaceEvents;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLEnvironment;
import org.slf4j.Logger;

// The value here must match the mod id in META-INF/mods.toml
@Mod(FpvDroneMod.MODID)
public final class FpvDroneMod {
    public static final String MODID = "fpvdrone";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FpvDroneMod(FMLJavaModLoadingContext context) {
        // Runs on both client and dedicated server.
        DroneNetwork.register();

        PlayerEvent.PlayerLoggedInEvent.BUS.addListener(
                FpvDroneMod::onPlayerLoggedIn
        );

        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(
                FpvDroneMod::onPlayerLoggedOut
        );

        PlayerEvent.PlayerRespawnEvent.BUS.addListener(
                FpvDroneMod::onPlayerRespawn
        );

        TickEvent.PlayerTickEvent.Post.BUS.addListener(
                FpvDroneMod::onPlayerTick
        );

        // Racing: track building, racing, lap times. Server side logic; it also
        // runs on the integrated server in single player.
        RaceEvents.register();

        // Client-only setup, referenced only from ClientInit so the server
        // never loads DroneClientEvents.
        if (FMLEnvironment.dist.isClient()) {
            ClientInit.init();
        }

        LOGGER.info("FPV Drone mod loaded");
    }

    /** Height of the drone hitbox. Matches PlayerDimensionsMixin. */
    private static final float DRONE_BOX_HEIGHT = 0.3F;

    private static void onPlayerTick(TickEvent.PlayerTickEvent.Post event) {
        Player player = event.player();

        if (player instanceof ServerPlayer sp) {
            DroneTogglePacket.maintainFlight(sp);
        } else if (player.level().isClientSide()) {
            syncRemoteDimensions(player);
        }
    }

    /**
     * On a client, other players' hitboxes only change when something calls
     * refreshDimensions(). Their drone flag arrives by packet, so nothing
     * does; catch up here. (The local player refreshes in DroneState.)
     */
    private static void syncRemoteDimensions(Player player) {
        boolean drone = DroneRegistry.isDrone(player.getUUID());
        boolean hasDroneBox = Math.abs(player.getBbHeight() - DRONE_BOX_HEIGHT) < 0.001F;
        if (drone != hasDroneBox) {
            player.refreshDimensions();
        }
    }

    /** Tell a joining player who is already flying. */
    private static void onPlayerLoggedIn(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer joining)) return;

        for (java.util.UUID id : DroneRegistry.snapshot()) {
            DroneNetwork.sendStateTo(joining, id, true);
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        java.util.UUID id = event.getEntity().getUUID();
        DroneRegistry.set(id, false);
        DroneTogglePacket.forget(id);
        if (event.getEntity() instanceof ServerPlayer) {
            DroneNetwork.broadcastState(id, false);
        }
    }

    private static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        java.util.UUID id = event.getEntity().getUUID();
        DroneRegistry.set(id, false);
        DroneTogglePacket.forget(id);
        event.getEntity().refreshDimensions();
        if (event.getEntity() instanceof ServerPlayer) {
            DroneNetwork.broadcastState(id, false);
        }
    }
}