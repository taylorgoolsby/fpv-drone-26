package com.taylorgoolsby.fpvdrone;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Abilities;
import net.minecraftforge.event.network.CustomPayloadEvent;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Client -> server. Tells the server whether the sender is in drone mode.
 * Must stay free of client-only imports: this class loads on dedicated servers.
 */
public record DroneTogglePacket(boolean active) {

    public static final StreamCodec<RegistryFriendlyByteBuf, DroneTogglePacket> CODEC =
            StreamCodec.of(
                    (buf, msg) -> buf.writeBoolean(msg.active()),
                    buf -> new DroneTogglePacket(buf.readBoolean())
            );

    /** Per player: {mayfly, flying} from before drone mode, so we can restore them. */
    private static final Map<UUID, boolean[]> PREV_ABILITIES = new ConcurrentHashMap<>();

    public static void handle(DroneTogglePacket msg, CustomPayloadEvent.Context ctx) {
        ctx.enqueueWork(() -> {
            ServerPlayer sender = ctx.getSender();
            if (sender == null) return;

            boolean active = msg.active() && sender.isAlive();

            DroneRegistry.set(sender.getUUID(), active);
            applyFlightExemption(sender, active);
            sender.refreshDimensions();

            // Everyone else needs to know too, to draw the floating head.
            DroneNetwork.broadcastState(sender.getUUID(), active);

            // DIAGNOSTIC: remove once everything is confirmed working.
            FpvDroneMod.LOGGER.info(
                    "[fpvdrone] toggle from {}: requested={} applied={} serverBoxWidth={} serverBoxHeight={}",
                    sender.getPlainTextName(),
                    msg.active(),
                    active,
                    sender.getBbWidth(),
                    sender.getBbHeight()
            );
        });
        ctx.setPacketHandled(true);
    }

    /**
     * While in drone mode: mayfly (no fly-kick) and flying (no server-side
     * gravity fighting the client's drone physics). Restores both on exit.
     */
    private static void applyFlightExemption(ServerPlayer player, boolean active) {
        UUID id = player.getUUID();
        Abilities abilities = player.getAbilities();

        if (active) {
            // putIfAbsent: a repeated "true" must not overwrite the saved values.
            PREV_ABILITIES.putIfAbsent(id, new boolean[] { abilities.mayfly, abilities.flying });
            abilities.mayfly = true;
            abilities.flying = true;
            player.onUpdateAbilities();
        } else {
            boolean[] prev = PREV_ABILITIES.remove(id);
            if (prev != null) {
                abilities.mayfly = prev[0];
                abilities.flying = prev[0] && prev[1];
                player.onUpdateAbilities();
            }
        }
    }

    /**
     * Called every server tick for every player. Vanilla clears flying when the
     * client touches the ground, so re-assert it while a drone is airborne.
     */
    public static void maintainFlight(ServerPlayer player) {
        if (!DroneRegistry.isDrone(player.getUUID())) return;

        Abilities abilities = player.getAbilities();
        if (!player.onGround() && (!abilities.flying || !abilities.mayfly)) {
            abilities.mayfly = true;
            abilities.flying = true;
            player.onUpdateAbilities();
        }
    }

    /** Drop saved state for a player who logged out or respawned. */
    public static void forget(UUID id) {
        PREV_ABILITIES.remove(id);
    }
}