package com.taylorgoolsby.fpvdrone;

import net.minecraft.resources.Identifier;
import net.minecraftforge.network.ChannelBuilder;
import net.minecraftforge.network.PacketDistributor;
import net.minecraftforge.network.SimpleChannel;

public final class DroneNetwork {

    private static final int PROTOCOL_VERSION = 1;

    public static final SimpleChannel CHANNEL = ChannelBuilder
            .named(Identifier.fromNamespaceAndPath(FpvDroneMod.MODID, "main"))
            .networkProtocolVersion(PROTOCOL_VERSION)
            .simpleChannel();

    private DroneNetwork() {}

    /** Call once from the mod constructor, on both client and server. */
    public static void register() {
        CHANNEL.play()
                .serverbound()
                .add(DroneTogglePacket.class, DroneTogglePacket.CODEC, DroneTogglePacket::handle);
    }

    /** Client-side helper: tell the server we entered or left drone mode. */
    public static void sendToggle(boolean active) {
        CHANNEL.send(new DroneTogglePacket(active), PacketDistributor.SERVER.noArg());
    }
}