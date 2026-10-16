package com.taylorgoolsby.fpvdrone;

import com.mojang.logging.LogUtils;
import net.minecraft.server.level.ServerPlayer;
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

        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(
                FpvDroneMod::onPlayerLoggedOut
        );

        PlayerEvent.PlayerRespawnEvent.BUS.addListener(
                FpvDroneMod::onPlayerRespawn
        );

        TickEvent.PlayerTickEvent.Post.BUS.addListener(
                FpvDroneMod::onPlayerTick
        );

        // Client-only setup, referenced only from ClientInit so the server
        // never loads DroneClientEvents.
        if (FMLEnvironment.dist.isClient()) {
            ClientInit.init();
        }

        LOGGER.info("FPV Drone mod loaded");
    }

    private static void onPlayerTick(TickEvent.PlayerTickEvent.Post event) {
        if (event.player() instanceof ServerPlayer sp) {
            DroneTogglePacket.maintainFlight(sp);
        }
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        java.util.UUID id = event.getEntity().getUUID();
        DroneRegistry.set(id, false);
        DroneTogglePacket.forget(id);
    }

    private static void onPlayerRespawn(PlayerEvent.PlayerRespawnEvent event) {
        java.util.UUID id = event.getEntity().getUUID();
        DroneRegistry.set(id, false);
        DroneTogglePacket.forget(id);
        event.getEntity().refreshDimensions();
    }
}