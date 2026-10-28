package com.taylorgoolsby.fpvdrone.race;

import com.taylorgoolsby.fpvdrone.FpvDroneMod;
import com.taylorgoolsby.fpvdrone.race.db.Database;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.entity.player.PlayerEvent;
import net.minecraftforge.event.entity.player.PlayerInteractEvent;
import net.minecraftforge.event.level.BlockEvent;
import net.minecraftforge.event.level.ExplosionEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppedEvent;
import net.minecraftforge.fml.loading.FMLPaths;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server side event wiring for racing. These listeners also run on the
 * integrated server of a single player game; they never touch client classes.
 *
 * Stories:
 *
 * Creating a track
 *   - Place a target block. You are now in building mode.
 *   - Right click each gate's interior face with a stick, in flying order.
 *   - /race setName <name>
 *   - Right click the target block with the stick to save the track.
 *
 * Breaking the target block
 *   - Exits building mode, or deletes the track and removes everyone racing it.
 *
 * Changing a block of a gate someone is racing
 *   - Everyone racing that track is removed from it.
 *
 * Racing
 *   - Right click a registered target block with an empty hand.
 *   - Right click it again to leave.
 */
public final class RaceEvents {
    /** Holding the use key repeats right clicks. The same click on the same block within this window is ignored. */
    private static final long DEBOUNCE_MILLIS = 200;
    private static final Map<String, Long> lastRightClick = new HashMap<>();

    private RaceEvents() {}

    public static void register() {
        ServerStartingEvent.BUS.addListener(RaceEvents::onServerStarting);
        ServerStoppedEvent.BUS.addListener(RaceEvents::onServerStopped);
        RegisterCommandsEvent.BUS.addListener(RaceEvents::onRegisterCommands);

        BlockEvent.EntityPlaceEvent.BUS.addListener(RaceEvents::onBlockPlace);
        BlockEvent.EntityMultiPlaceEvent.BUS.addListener(RaceEvents::onBlockPlaceMulti);
        BlockEvent.BreakEvent.BUS.addListener(RaceEvents::onBlockBreak);
        ExplosionEvent.Detonate.BUS.addListener(RaceEvents::onExplosion);

        // A block-bodied lambda that returns a value can only be the cancelling listener type.
        PlayerInteractEvent.RightClickBlock.BUS.addListener(event -> {
            return onRightClickBlock(event);
        });

        TickEvent.PlayerTickEvent.Post.BUS.addListener(RaceEvents::onPlayerTick);
        PlayerEvent.PlayerLoggedOutEvent.BUS.addListener(RaceEvents::onPlayerLoggedOut);
    }

    // ---------------------------------------------------------------- lifecycle

    private static void onServerStarting(ServerStartingEvent event) {
        RaceServer.server = event.getServer();
        Database.setLoggers(FpvDroneMod.LOGGER::info);
        try {
            Database.init(FMLPaths.GAMEDIR.get());
            RaceServer.databaseReady = true;
            RaceServer.databaseError = "";
        } catch (Throwable t) {
            RaceServer.databaseReady = false;
            RaceServer.databaseError = "(" + t.getMessage() + ") See latest.log.";
            FpvDroneMod.LOGGER.error("Racing is disabled: the race database could not be prepared.", t);
        }
    }

    private static void onServerStopped(ServerStoppedEvent event) {
        RaceServer.databaseReady = false;
        RaceServer.server = null;
        Database.shutdown();
        RaceBuild.clear();
        RaceNavigate.clear();
        RaceWaypoints.clear();
        lastRightClick.clear();
    }

    private static void onRegisterCommands(RegisterCommandsEvent event) {
        RaceCommand.register(event.getDispatcher());
    }

    // ------------------------------------------------------------------- blocks

    private static boolean isTargetBlock(BlockState state) {
        return state.is(Blocks.TARGET);
    }

    private static void onBlockPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof Level level)) return;

        Entity entity = event.getEntity();
        String who = entity instanceof Player p ? p.getName().getString() : null;
        changedBlock(level, event.getPos(), who);

        if (entity instanceof ServerPlayer player && isTargetBlock(event.getPlacedBlock())) {
            RaceBuild.startTrack(RaceServer.getId(player.getUUID()), RaceServer.dimensionOf(level), event.getPos(), player);
        }
    }

    private static void onBlockPlaceMulti(BlockEvent.EntityMultiPlaceEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof Level level)) return;
        String who = event.getEntity() instanceof Player p ? p.getName().getString() : null;
        changedBlock(level, event.getPos(), who);
    }

    private static void onBlockBreak(BlockEvent.BreakEvent event) {
        if (event.getLevel().isClientSide()) return;
        if (!(event.getLevel() instanceof Level level)) return;
        if (!(event.getPlayer() instanceof ServerPlayer player)) return;

        if (isTargetBlock(event.getState())) {
            // removeTrack also takes the user out of building mode if this is their target block.
            RaceBuild.removeTrack(RaceServer.getId(player.getUUID()), RaceServer.dimensionOf(level), event.getPos(), player);
        }
        changedBlock(level, event.getPos(), player.getPlainTextName());
    }

    private static void onExplosion(ExplosionEvent.Detonate event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        if (event.getAffectedBlocks().isEmpty()) return;
        String dimension = RaceServer.dimensionOf(level);
        for (BlockPos pos : event.getAffectedBlocks()) {
            RaceNavigate.changedGateBlock(dimension, pos, "A gate was changed by an explosion");
        }
    }

    private static void changedBlock(Level level, BlockPos pos, String who) {
        String reason = who == null ? "A gate was changed" : "A gate was changed by " + who;
        RaceNavigate.changedGateBlock(RaceServer.dimensionOf(level), pos, reason);
    }

    // -------------------------------------------------------------- interaction

    /** @return true to cancel the interaction */
    private static boolean onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        // The client must never cancel this: a cancelled client side click is never sent to the server.
        if (event.getLevel().isClientSide()) return false;
        // The event fires once per hand. Only the main hand is used.
        if (event.getHand() != InteractionHand.MAIN_HAND) return false;
        if (!(event.getEntity() instanceof ServerPlayer player)) return false;

        Level level = event.getLevel();
        BlockPos pos = event.getPos();
        String userId = RaceServer.getId(player.getUUID());
        String dimension = RaceServer.dimensionOf(level);

        boolean isStick = player.getMainHandItem().is(Items.STICK);
        boolean building = RaceBuild.checkBuildingMode(userId);
        boolean isTarget = isTargetBlock(level.getBlockState(pos));
        boolean emptyHand = player.getMainHandItem().isEmpty();

        if (isStick && building) {
            // The player is adding a block to the track they are building.
            if (!debounce(userId, dimension, pos)) return true;
            RaceBuild.addBlock(userId, dimension, pos, event.getFace(), RaceServer.solidChecker(level), player);
            // Do not also open doors, chests, etc. while marking gates.
            event.setCancellationResult(InteractionResult.SUCCESS);
            return true;
        }

        if (isTarget && emptyHand && !building) {
            // The player is entering or leaving racing mode.
            if (!debounce(userId, dimension, pos)) return false;
            RaceNavigate.handleRightClickTrack(userId, dimension, pos, RaceServer.solidChecker(level), player);
        }
        return false;
    }

    /** @return false if this is a repeat of the same click */
    private static boolean debounce(String userId, String dimension, BlockPos pos) {
        long now = System.currentTimeMillis();
        if (lastRightClick.size() > 256) {
            lastRightClick.values().removeIf(t -> now - t > 5_000);
        }
        String key = userId + "|" + dimension + "|" + pos.asLong();
        Long last = lastRightClick.get(key);
        lastRightClick.put(key, now);
        return last == null || now - last >= DEBOUNCE_MILLIS;
    }

    // ------------------------------------------------------------------ players

    private static void onPlayerTick(TickEvent.PlayerTickEvent.Post event) {
        if (!(event.player() instanceof ServerPlayer player)) return;
        String userId = RaceServer.getId(player.getUUID());
        if (!RaceNavigate.checkRacingMode(userId)) return;

        RaceNavigate.onPlayerMoved(userId, RaceServer.dimensionOf(player.level()),
                new double[]{player.getX(), player.getY(), player.getZ()}, player);
    }

    private static void onPlayerLoggedOut(PlayerEvent.PlayerLoggedOutEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) return;
        UUID uuid = player.getUUID();
        String userId = RaceServer.getId(uuid);
        RaceBuild.exitBuildingMode(userId, null);
        RaceNavigate.exitRacingMode(userId, null, null);
        RaceWaypoints.forget(uuid);
    }
}
