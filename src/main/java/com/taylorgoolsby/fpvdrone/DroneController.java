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
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * Client-only. Detects USB joysticks/gamepads through SDL3 (the input
 * backend vanilla uses as of 26.x) and exposes shaped stick values.
 * Call tick() from the client tick and poll() each frame.
 *
 * The settings screens use: listDevices(), selectDevice(), the binding
 * getters/setters, and beginCapture()/cancelCapture().
 */
public final class DroneController {

    /** The four flight functions that can be bound to a physical axis. */
    public enum Axis {
        ROLL("roll"), PITCH("pitch"), YAW("yaw"), THROTTLE("throttle");

        public final String key;

        Axis(String key) { this.key = key; }
    }

    /** A selectable device. {@code id} is stable across reconnects. */
    public record Device(String id, String name, boolean gamepad) {}

    // ---- Outputs (valid after poll() returns true) ----
    /** +1 = roll right. */
    public static float roll;
    /** +1 = nose down (stick forward). */
    public static float pitch;
    /** +1 = yaw right. */
    public static float yaw;
    /** 0..1 throttle. The stick position maps directly: -1 -> 0, +1 -> 1. */
    public static float throttle;

    // ---- State ----
    private static long handle = 0L;        // SDL_Gamepad* or SDL_Joystick*
    private static boolean isGamepad;
    private static String name = "";
    private static String connectedId = "";
    private static boolean virtual;
    private static int scanCooldown;
    private static boolean sdlReady;

    // ---- Config (defaults) ----
    private static float deadzone = 0.05f;
    private static float expo = 0.3f;
    public static float rollRate = 540f;   // deg/s at full stick
    public static float pitchRate = 540f;
    public static float yawRate = 360f;

    /** Empty = auto-detect the first suitable controller. */
    private static String selectedId = "";

    // Bindings, indexed by Axis.ordinal() (ROLL, PITCH, YAW, THROTTLE).
    // Gamepad: SDL_GAMEPAD_AXIS_* values. Raw joystick: axis index.
    // "invert" is applied so that the output matches the conventions
    // documented on roll/pitch/yaw/throttle above.
    private static final int[] GP_AXIS_DEFAULT = {
            SDLGamepad.SDL_GAMEPAD_AXIS_RIGHTX,
            SDLGamepad.SDL_GAMEPAD_AXIS_RIGHTY,
            SDLGamepad.SDL_GAMEPAD_AXIS_LEFTX,
            SDLGamepad.SDL_GAMEPAD_AXIS_LEFTY
    };
    private static final boolean[] GP_INVERT_DEFAULT = {false, true, false, true};
    private static final int[] RAW_AXIS_DEFAULT = {0, 1, 3, 2};
    private static final boolean[] RAW_INVERT_DEFAULT = {false, true, false, false};

    private static final int[] gpAxis = GP_AXIS_DEFAULT.clone();
    private static final boolean[] gpInvert = GP_INVERT_DEFAULT.clone();
    private static final int[] rawAxis = RAW_AXIS_DEFAULT.clone();
    private static final boolean[] rawInvert = RAW_INVERT_DEFAULT.clone();

    private static final int GAMEPAD_AXIS_COUNT = 6;
    private static final String[] GP_AXIS_NAME_KEYS = new String[GAMEPAD_AXIS_COUNT];
    static {
        for (int i = 0; i < GAMEPAD_AXIS_COUNT; i++) {
            GP_AXIS_NAME_KEYS[i] = "fpvdrone.axis.gamepad." + i;
        }
    }

    // ---- Binding capture ----
    private static final float CAPTURE_THRESHOLD = 0.5f;
    private static Axis captureTarget;
    private static Runnable captureDone;
    private static float[] captureBase;

    private static boolean configLoaded = false;

    private DroneController() {}

    public static boolean isConnected() {
        return handle != 0L;
    }

    public static String connectedName() {
        return name;
    }

    /** True if the connected device is the keyboard-driven test joystick. */
    public static boolean isVirtual() {
        return virtual;
    }

    /** Id of the connected device, or "" if none. */
    public static String connectedId() {
        return connectedId;
    }

    /** Id of the user's selected device, or "" for auto-detect. */
    public static String selectedId() {
        ensureConfig();
        return selectedId;
    }

    // ------------------------------------------------------------------
    // Device listing / selection (used by the settings screens)
    // ------------------------------------------------------------------

    /**
     * Enumerates devices usable for flying. Raw joysticks with fewer than
     * 4 axes (keyboards, mice and power buttons that some OSes expose as
     * joysticks) are hidden, same as in auto-detect.
     */
    public static List<Device> listDevices() {
        ensureConfig();
        List<Device> out = new ArrayList<>();
        if (!ensureSdl()) return out;

        VirtualJoystick.ensureAttached();

        SDLJoystick.SDL_UpdateJoysticks();
        IntBuffer ids = SDLJoystick.SDL_GetJoysticks();
        if (ids == null) return out;

        Set<String> seen = new HashSet<>();
        for (int i = 0; i < ids.remaining(); i++) {
            int id = ids.get(ids.position() + i);
            boolean gp = SDLGamepad.SDL_IsGamepad(id);
            if (!gp && !hasFlightAxes(id)) continue;

            String key = deviceKey(id);
            if (!seen.add(key)) continue; // identical devices share an id
            out.add(new Device(key, deviceName(id), gp));
        }
        return out;
    }

    /**
     * Selects a device ("" = auto-detect), saves it, and reconnects now.
     */
    public static void selectDevice(String id, Minecraft mc) {
        ensureConfig();
        selectedId = id == null ? "" : id;
        saveConfig();

        cancelCapture();
        disconnect();
        scanCooldown = 0;
        if (ensureSdl()) scanAndConnect(mc);
    }

    private static boolean hasFlightAxes(int instanceId) {
        long j = SDLJoystick.SDL_OpenJoystick(instanceId);
        if (j == 0L) return false;
        boolean ok = SDLJoystick.SDL_GetNumJoystickAxes(j) >= 4;
        SDLJoystick.SDL_CloseJoystick(j);
        return ok;
    }

    private static String deviceName(int instanceId) {
        String n = SDLJoystick.SDL_GetJoystickNameForID(instanceId);
        return n != null ? n : "Unknown controller";
    }

    /** vendor:product:name. Instance ids change every plug-in; this doesn't. */
    private static String deviceKey(int instanceId) {
        int vid = SDLJoystick.SDL_GetJoystickVendorForID(instanceId) & 0xFFFF;
        int pid = SDLJoystick.SDL_GetJoystickProductForID(instanceId) & 0xFFFF;
        return String.format("%04x:%04x:%s", vid, pid, deviceName(instanceId));
    }

    // ------------------------------------------------------------------
    // Tick / connection
    // ------------------------------------------------------------------

    private static void ensureConfig() {
        if (!configLoaded) loadConfig();
    }

    private static boolean ensureSdl() {
        if (!sdlReady) {
            sdlReady = SDLInit.SDL_InitSubSystem(
                    SDLInit.SDL_INIT_JOYSTICK | SDLInit.SDL_INIT_GAMEPAD);
        }
        return sdlReady;
    }

    /**
     * Client tick: handles detection, hot-plug, binding capture and
     * action-bar feedback. Safe to call with no world loaded.
     */
    public static void tick(Minecraft mc) {
        ensureConfig();
        if (!ensureSdl()) return;

        VirtualJoystick.tick(); // no-op unless -Dfpvdrone.virtual=true
        SDLJoystick.SDL_UpdateJoysticks();

        if (handle != 0L) {
            boolean connected = isGamepad
                    ? SDLGamepad.SDL_GamepadConnected(handle)
                    : SDLJoystick.SDL_JoystickConnected(handle);
            if (!connected) {
                String lost = name;
                cancelCapture();
                disconnect();
                notify(mc, "Controller disconnected: " + lost);
                return;
            }
            if (captureTarget != null) updateCapture();
            return;
        }

        if (--scanCooldown > 0) return;
        scanCooldown = 20; // scan once per second

        scanAndConnect(mc);
    }

    private static void scanAndConnect(Minecraft mc) {
        IntBuffer ids = SDLJoystick.SDL_GetJoysticks();
        if (ids == null) return;

        for (int i = 0; i < ids.remaining(); i++) {
            int id = ids.get(ids.position() + i);

            // A specific controller was selected: ignore everything else.
            String key = deviceKey(id);
            if (!selectedId.isEmpty() && !selectedId.equals(key)) continue;

            if (SDLGamepad.SDL_IsGamepad(id)) {
                long g = SDLGamepad.SDL_OpenGamepad(id);
                if (g == 0L) continue;
                handle = g;
                isGamepad = true;
            } else {
                long j = SDLJoystick.SDL_OpenJoystick(id);
                if (j == 0L) continue;
                if (SDLJoystick.SDL_GetNumJoystickAxes(j) < 4) {
                    SDLJoystick.SDL_CloseJoystick(j); // skip non-flight devices
                    continue;
                }
                handle = j;
                isGamepad = false;
            }

            name = deviceName(id);
            connectedId = key;
            virtual = VirtualJoystick.isInstance(id);

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
        connectedId = "";
        virtual = false;
        roll = pitch = yaw = throttle = 0f;
        scanCooldown = 20;
    }

    private static void notify(Minecraft mc, String msg) {
        if (mc.player != null) {
            mc.gui.chatListener().handleOverlay(Component.literal("[FPV] " + msg));
        }
    }

    // ------------------------------------------------------------------
    // Polling
    // ------------------------------------------------------------------

    /** Per frame. Returns false if no controller is connected. */
    public static boolean poll() {
        if (handle == 0L) return false;

        // Gamepad defaults are Mode 2: left = throttle/yaw, right =
        // pitch/roll. Inversion (SDL stick-up is negative) lives in the
        // bindings.
        roll = shape(read(Axis.ROLL));
        pitch = shape(read(Axis.PITCH));
        yaw = shape(read(Axis.YAW));

        // Throttle stick maps directly: -1..1 -> 0..1, with no expo, no
        // deadzone and no integration, for gamepads and RC transmitters
        // alike.
        float t = read(Axis.THROTTLE);
        throttle = Math.max(0f, Math.min(1f, (t + 1f) * 0.5f));
        return true;
    }

    /** Reads the bound axis for a function, with inversion applied. */
    private static float read(Axis a) {
        int i = a.ordinal();
        return isGamepad
                ? gamepadAxis(gpAxis[i], gpInvert[i])
                : rawAxis(rawAxis[i], rawInvert[i]);
    }

    private static float gamepadAxis(int axis, boolean invert) {
        float v = norm(SDLGamepad.SDL_GetGamepadAxis(handle, axis));
        return invert ? -v : v;
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

    // ------------------------------------------------------------------
    // Bindings (used by the bindings screen)
    // ------------------------------------------------------------------

    /** Human-readable name of the physical axis bound to a function. */
    public static Component bindingName(Axis a) {
        ensureConfig();
        int i = a.ordinal();
        if (isGamepad) {
            int ax = gpAxis[i];
            if (ax >= 0 && ax < GAMEPAD_AXIS_COUNT) {
                return Component.translatable(GP_AXIS_NAME_KEYS[ax]);
            }
            return Component.translatable("fpvdrone.axis.raw", ax);
        }
        return Component.translatable("fpvdrone.axis.raw", rawAxis[i]);
    }

    public static boolean isInverted(Axis a) {
        ensureConfig();
        return isGamepad ? gpInvert[a.ordinal()] : rawInvert[a.ordinal()];
    }

    public static void toggleInvert(Axis a) {
        int i = a.ordinal();
        if (isGamepad) gpInvert[i] = !gpInvert[i];
        else rawInvert[i] = !rawInvert[i];
        saveConfig();
    }

    /** Resets bindings for the kind of device currently connected. */
    public static void resetBindings() {
        cancelCapture();
        if (isGamepad) {
            System.arraycopy(GP_AXIS_DEFAULT, 0, gpAxis, 0, gpAxis.length);
            System.arraycopy(GP_INVERT_DEFAULT, 0, gpInvert, 0, gpInvert.length);
        } else {
            System.arraycopy(RAW_AXIS_DEFAULT, 0, rawAxis, 0, rawAxis.length);
            System.arraycopy(RAW_INVERT_DEFAULT, 0, rawInvert, 0, rawInvert.length);
        }
        saveConfig();
    }

    /**
     * Starts listening for the user to move a stick. The axis that moves
     * the most becomes the binding for {@code target}, and the direction it
     * moved decides inversion (the user is asked to move the stick in the
     * function's positive direction). {@code onDone} runs on the client
     * thread once a binding was captured; it is not run on cancel.
     *
     * @return false if no controller is connected
     */
    public static boolean beginCapture(Axis target, Runnable onDone) {
        if (handle == 0L) return false;
        captureTarget = target;
        captureDone = onDone;
        captureBase = readAllAxes(); // baseline, so resting positions are ignored
        return true;
    }

    public static void cancelCapture() {
        captureTarget = null;
        captureDone = null;
        captureBase = null;
    }

    public static Axis capturingAxis() {
        return captureTarget;
    }

    private static float[] readAllAxes() {
        int n = isGamepad ? GAMEPAD_AXIS_COUNT : SDLJoystick.SDL_GetNumJoystickAxes(handle);
        float[] v = new float[n];
        for (int i = 0; i < n; i++) {
            v[i] = isGamepad
                    ? norm(SDLGamepad.SDL_GetGamepadAxis(handle, i))
                    : norm(SDLJoystick.SDL_GetJoystickAxis(handle, i));
        }
        return v;
    }

    private static void updateCapture() {
        float[] now = readAllAxes();
        int best = -1;
        float bestDelta = 0f;
        int n = Math.min(now.length, captureBase.length);
        for (int i = 0; i < n; i++) {
            float d = now[i] - captureBase[i];
            if (Math.abs(d) > Math.abs(bestDelta)) {
                best = i;
                bestDelta = d;
            }
        }
        if (best < 0 || Math.abs(bestDelta) < CAPTURE_THRESHOLD) return;

        int i = captureTarget.ordinal();
        if (isGamepad) {
            gpAxis[i] = best;
            gpInvert[i] = bestDelta < 0f;
        } else {
            rawAxis[i] = best;
            rawInvert[i] = bestDelta < 0f;
        }

        Runnable done = captureDone;
        cancelCapture();
        saveConfig();
        if (done != null) done.run();
    }

    // ------------------------------------------------------------------
    // Config
    // ------------------------------------------------------------------

    private static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve("fpvdrone-controller.properties");
    }

    private static void loadConfig() {
        configLoaded = true;
        Path path = configPath();
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

        selectedId = p.getProperty("selected_controller", "");

        for (Axis a : Axis.values()) {
            int i = a.ordinal();
            gpAxis[i] = getI(p, "gp_axis_" + a.key, gpAxis[i]);
            gpInvert[i] = getB(p, "gp_invert_" + a.key, gpInvert[i]);
            rawAxis[i] = getI(p, "raw_axis_" + a.key, rawAxis[i]);
            rawInvert[i] = getB(p, "raw_invert_" + a.key, rawInvert[i]);
        }

        if (!Files.exists(path)) saveConfig();
    }

    /** Writes the whole config file. */
    public static void saveConfig() {
        Properties p = new Properties();
        p.setProperty("deadzone", String.valueOf(deadzone));
        p.setProperty("expo", String.valueOf(expo));
        p.setProperty("roll_rate_deg_per_sec", String.valueOf(rollRate));
        p.setProperty("pitch_rate_deg_per_sec", String.valueOf(pitchRate));
        p.setProperty("yaw_rate_deg_per_sec", String.valueOf(yawRate));
        p.setProperty("selected_controller", selectedId);

        for (Axis a : Axis.values()) {
            int i = a.ordinal();
            p.setProperty("gp_axis_" + a.key, String.valueOf(gpAxis[i]));
            p.setProperty("gp_invert_" + a.key, String.valueOf(gpInvert[i]));
            p.setProperty("raw_axis_" + a.key, String.valueOf(rawAxis[i]));
            p.setProperty("raw_invert_" + a.key, String.valueOf(rawInvert[i]));
        }

        try (OutputStream out = Files.newOutputStream(configPath())) {
            p.store(out, "FPV Drone controller config. selected_controller empty = auto-detect. "
                    + "gp_* applies to gamepads (SDL axis ids), raw_* to RC/joystick devices (axis index).");
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