package com.taylorgoolsby.fpvdrone;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

// The value here must match the mod id in META-INF/mods.toml
@Mod(FpvDroneMod.MODID)
public final class FpvDroneMod {
    public static final String MODID = "fpvdrone";
    public static final Logger LOGGER = LogUtils.getLogger();

    public FpvDroneMod(FMLJavaModLoadingContext context) {
        // Subscribes the key mapping, tick, and camera handlers.
        // This has to happen in the constructor, not in FMLClientSetupEvent,
        // because key mappings are registered earlier in startup than client setup.
        //
        // NOTE: DroneClientEvents uses client-only classes, so a dedicated
        // server would crash here. That's fine for runClient testing; see below.
        DroneClientEvents.register();

        LOGGER.info("FPV Drone mod loaded");
    }
}