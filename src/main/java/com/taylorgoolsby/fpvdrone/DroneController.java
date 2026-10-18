package com.taylorgoolsby.fpvdrone;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraftforge.fml.loading.FMLPaths;
import org.lwjgl.sdl.SDLGamepad;
import org.lwjgl.sdl.SDLInit;
import org.lwjgl.sdl.SDLJoystick;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * Client-only. Auto-detects a USB joystick/gamepad through SDL3 (the input
 * backend vanilla uses as of 26.x) and exposes shaped stick values.
 * Call tick() from the client tick and poll() each frame.
 */
public final class DroneController {

    // ---- Outputs (valid after poll() returns true) ----
    /** +1 = roll right. */
    public static float roll;
    /** +1 = nose down (stick forward). */
    public static float pitch;
    /** +1 = yaw right. */
    public static float yaw;
    /** If directThrottle: 0..1 throttle. Otherwise -1..1 rate input for the integrator. */
    public static float throttle;
    public static boolean directThrottle;

    // ---- State ----
    private static long handle = 0L;        // SDL_Gamepad* or SDL_Joystick*
    private static boolean isGamepad;
    private static String name = "";
    private static int scanCooldown;
    private static boolean sdlReady;

    // ---- Config (defaults) ----
    private static float deadzone = 0.05f;
    private static float expo = 0.3f;
    public static float rollRate = 540f;   // deg/s at full stick
    public static float pitchRate = 540f;
    public static float yawRate = 360f;

    // Raw-joystick axis mapping (used when SDL has no gamepad mapping).
    private static int axisRoll = 0, axisPitch = 1, axisThrottle = 2, axisYaw = 3;
    private static boolean invRoll = false, invPitch = true, invThrottle = false, invYaw = false;

    private static boolean configLoaded = false;

    private DroneController() {}

    public static boolean isConnected() {
        return handle != 0L;
    }

    /** Client tick: handles detection, hot-plug, and action-bar feedback. */
    public static void tick(Minecraft mc) {
        if (!configLoaded) loadConfig();
        if (!sdlReady) {
            sdlReady = SDLInit.SDL_InitSubSystem(
                    SDLInit.SDL_INIT_JOYSTICK | SDLInit.SDL_INIT_GAMEPAD);
            if (!sdlReady) return;
        }

        SDLJoystick.SDL_UpdateJoysticks();

        if (handle != 0L) {
            boolean connected = isGamepad
                    ? SDLGamepad.SDL_GamepadConnected(handle)
                    : SDLJoystick.SDL_JoystickConnected(handle);
            if (!connected) {
                String lost = name;
                disconnect();
                notify(mc, "Controller disconnected: " + lost);
            }
            return;
        }

        if (--scanCooldown > 0) return;
        scanCooldown = 20; // scan once per second

        IntBuffer ids = SDLJoystick.SDL_GetJoysticks();
        if (ids == null) return;

        for (int i = 0; i < ids.remaining(); i++) {
            int id = ids.get(ids.position() + i);

            if (SDLGamepad.SDL_IsGamepad(id)) {
                long g = SDLGamepad.SDL_OpenGamepad(id);
                if (g == 0L) continue;
                handle = g;
                isGamepad = true;
                String n = SDLGamepad.SDL_GetGamepadName(g);
                name = n != null ? n : "Unknown gamepad";
                directThrottle = false;
            } else {
                long j = SDLJoystick.SDL_OpenJoystick(id);
                if (j == 0L) continue;
                if (SDLJoystick.SDL_GetNumJoystickAxes(j) < 4) {
                    SDLJoystick.SDL_CloseJoystick(j); // skip non-flight devices
                    continue;
                }
                handle = j;
                isGamepad = false;
                String n = SDLJoystick.SDL_GetJoystickName(j);
                name = n != null ? n : "Unknown controller";
                directThrottle = true;
            }

            notify(mc, "Controller connected: " + name
                    + (isGamepad ? " (gamepad)" : " (RC/raw)"));
            break;
        }
    }

    private static void disconnect() {
        if (handle != 0L) {
            if (isGamepad) SDLGamepad.SDL_CloseGamepad(handle);
            else SDLJoystick.SDL_CloseJoystick(handle);
        }
        handle = 0L;
        name = "";
        roll = pitch = yaw = throttle = 0f;
        scanCooldown = 20;
    }

    private static void notify(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.gui.chatListener().handleOverlay(Component.literal("[FPV] " + msg));
        }
    }

    /** Per frame. Returns false if no controller is connected. */
    public static boolean poll() {
        if (handle == 0L) return false;

        if (isGamepad) {
            // Mode 2: left = throttle/yaw, right = pitch/roll.
            // SDL: stick up is negative, so negate Y axes.
            yaw = shape(gpAxis(SDLGamepad.SDL_GAMEPAD_AXIS_LEFTX));
            throttle = shape(-gpAxis(SDLGamepad.SDL_GAMEPAD_AXIS_LEFTY));
            roll = shape(gpAxis(SDLGamepad.SDL_GAMEPAD_AXIS_RIGHTX));
            pitch = shape(-gpAxis(SDLGamepad.SDL_GAMEPAD_AXIS_RIGHTY));
            return true;
        }

        roll = shape(rawAxis(axisRoll, invRoll));
        pitch = shape(rawAxis(axisPitch, invPitch));
        yaw = shape(rawAxis(axisYaw, invYaw));

        // Raw throttle: -1..1 -> 0..1, no expo, no deadzone around center.
        float t = rawAxis(axisThrottle, invThrottle);
        throttle = Math.max(0f, Math.min(1f, (t + 1f) * 0.5f));
        return true;
    }

    private static float gpAxis(int axis) {
        return norm(SDLGamepad.SDL_GetGamepadAxis(handle, axis));
    }

    private static float rawAxis(int index, boolean invert) {
        if (index < 0 || index >= SDLJoystick.SDL_GetNumJoystickAxes(handle)) return 0f;
        float v = norm(SDLJoystick.SDL_GetJoystickAxis(handle, index));
        return invert ? -v : v;
    }

    /** SDL axes are signed 16-bit. */
    private static float norm(short raw) {
        return Math.max(-1f, Math.min(1f, raw / 32767f));
    }

    /** Deadzone (rescaled so output starts at 0) + expo blend. */
    private static float shape(float v) {
        float a = Math.abs(v);
        if (a < deadzone) return 0f;
        a = (a - deadzone) / (1f - deadzone);
        a = (1f - expo) * a + expo * a * a * a;
        return Math.copySign(Math.min(a, 1f), v);
    }

    // ---- Config ----

    private static void loadConfig() {
        configLoaded = true;
        Path path = FMLPaths.CONFIGDIR.get().resolve("fpvdrone-controller.properties");
        Properties p = new Properties();

        if (Files.exists(path)) {
            try (InputStream in = Files.newInputStream(path)) {
                p.load(in);
            } catch (IOException e) {
                FpvDroneMod.LOGGER.warn("[fpvdrone] could not read controller config", e);
            }
        }

        deadzone = getF(p, "deadzone", deadzone);
        expo = getF(p, "expo", expo);
        rollRate = getF(p, "roll_rate_deg_per_sec", rollRate);
        pitchRate = getF(p, "pitch_rate_deg_per_sec", pitchRate);
        yawRate = getF(p, "yaw_rate_deg_per_sec", yawRate);

        axisRoll = getI(p, "raw_axis_roll", axisRoll);
        axisPitch = getI(p, "raw_axis_pitch", axisPitch);
        axisThrottle = getI(p, "raw_axis_throttle", axisThrottle);
        axisYaw = getI(p, "raw_axis_yaw", axisYaw);
        invRoll = getB(p, "raw_invert_roll", invRoll);
        invPitch = getB(p, "raw_invert_pitch", invPitch);
        invThrottle = getB(p, "raw_invert_throttle", invThrottle);
        invYaw = getB(p, "raw_invert_yaw", invYaw);

        if (!Files.exists(path)) writeDefaults(path);
    }

    private static void writeDefaults(Path path) {
        Properties p = new Properties();
        p.setProperty("deadzone", String.valueOf(deadzone));
        p.setProperty("expo", String.valueOf(expo));
        p.setProperty("roll_rate_deg_per_sec", String.valueOf(rollRate));
        p.setProperty("pitch_rate_deg_per_sec", String.valueOf(pitchRate));
        p.setProperty("yaw_rate_deg_per_sec", String.valueOf(yawRate));
        p.setProperty("raw_axis_roll", String.valueOf(axisRoll));
        p.setProperty("raw_axis_pitch", String.valueOf(axisPitch));
        p.setProperty("raw_axis_throttle", String.valueOf(axisThrottle));
        p.setProperty("raw_axis_yaw", String.valueOf(axisYaw));
        p.setProperty("raw_invert_roll", String.valueOf(invRoll));
        p.setProperty("raw_invert_pitch", String.valueOf(invPitch));
        p.setProperty("raw_invert_throttle", String.valueOf(invThrottle));
        p.setProperty("raw_invert_yaw", String.valueOf(invYaw));

        try (OutputStream out = Files.newOutputStream(path)) {
            p.store(out, "FPV Drone controller config. raw_* only applies to non-gamepad (RC) devices.");
        } catch (IOException e) {
            FpvDroneMod.LOGGER.warn("[fpvdrone] could not write controller config", e);
        }
    }

    private static float getF(Properties p, String k, float d) {
        try { return Float.parseFloat(p.getProperty(k, String.valueOf(d)).trim()); }
        catch (NumberFormatException e) { return d; }
    }

    private static int getI(Properties p, String k, int d) {
        try { return Integer.parseInt(p.getProperty(k, String.valueOf(d)).trim()); }
        catch (NumberFormatException e) { return d; }
    }

    private static boolean getB(Properties p, String k, boolean d) {
        return Boolean.parseBoolean(p.getProperty(k, String.valueOf(d)).trim());
    }
}