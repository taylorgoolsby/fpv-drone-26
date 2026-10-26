package com.taylorgoolsby.fpvdrone;

import com.mojang.blaze3d.platform.InputConstants;
import org.lwjgl.sdl.SDLJoystick;
import org.lwjgl.sdl.SDL_VirtualJoystickDesc;
import org.lwjgl.system.MemoryStack;

/**
 * Dev/test aid: a software SDL joystick driven by the keyboard, so the
 * controller screens and flight code can be tested with no hardware.
 *
 * Enabled by default in every build. To turn it off, launch with the JVM
 * flag -Dfpvdrone.virtual=false. It behaves like a Mode 2 RC transmitter in AETR order,
 * i.e. a raw (non-gamepad) device with 4 axes:
 *
 *   Right stick = arrow keys
 *     axis 0 = roll      LEFT / RIGHT   (springs to center)
 *     axis 1 = pitch     UP / DOWN      (UP = stick forward = negative, like SDL)
 *   Left stick = WASD
 *     axis 2 = throttle  W / S          (HOLDS its position, like a real
 *                                        throttle stick; starts at the bottom)
 *     axis 3 = yaw       A / D          (springs to center)
 *
 * The axis numbers and directions match the default raw bindings in
 * DroneController, so it flies out of the box, and can be rebound in the
 * bindings screen to test capture.
 */
final class VirtualJoystick {

    // On unless explicitly disabled with -Dfpvdrone.virtual=false.
    static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("fpvdrone.virtual"));
//    static final boolean ENABLED = false;

    private static final String NAME = "FPV Virtual Joystick (WASD + Arrows)";

    // Use the game's own key constants. In 26.x InputConstants.isKeyDown()
    // does not take GLFW codes (the toggle key's code 12 is SDL's scancode
    // for "I"), so hard-coded numbers silently poll the wrong keys.
    private static final int KEY_RIGHT = InputConstants.KEY_RIGHT;
    private static final int KEY_LEFT = InputConstants.KEY_LEFT;
    private static final int KEY_DOWN = InputConstants.KEY_DOWN;
    private static final int KEY_UP = InputConstants.KEY_UP;
    private static final int KEY_A = InputConstants.KEY_A;
    private static final int KEY_D = InputConstants.KEY_D;
    private static final int KEY_S = InputConstants.KEY_S;
    private static final int KEY_W = InputConstants.KEY_W;

    // Axis indices.
    private static final int ROLL = 0, PITCH = 1, THROTTLE = 2, YAW = 3;

    // Per client tick (20 Hz).
    private static final float RAMP = 0.25f;          // toward a held key
    private static final float RETURN = 0.5f;         // spring back to center
    private static final float THROTTLE_STEP = 0.04f; // ~2.5 s bottom to top

    private static int instanceId = 0;
    private static long joystick = 0L;
    private static boolean failed = false;

    private static final float[] axes = {0f, 0f, -1f, 0f};

    private VirtualJoystick() {}

    static boolean isInstance(int id) {
        return instanceId != 0 && id == instanceId;
    }

    /** Creates the virtual device once. Needs the SDL joystick subsystem initialised. */
    static void ensureAttached() {
        if (!ENABLED || failed || instanceId != 0) return;

        try (MemoryStack stack = MemoryStack.stackPush()) {
            SDL_VirtualJoystickDesc desc = SDL_VirtualJoystickDesc.calloc(stack);
            desc.version(SDL_VirtualJoystickDesc.SIZEOF) // SDL3: version = struct size
                    .type((short) SDLJoystick.SDL_JOYSTICK_TYPE_UNKNOWN)
                    .vendor_id((short) 0x4650)
                    .product_id((short) 0x5601)
                    .naxes((short) 4)
                    .nbuttons((short) 0)
                    .nhats((short) 0)
                    .name(stack.UTF8(NAME));

            int id = SDLJoystick.SDL_AttachVirtualJoystick(desc);
            if (id == 0) {
                failed = true;
                FpvDroneMod.LOGGER.warn("[fpvdrone] could not create virtual joystick");
                return;
            }

            long j = SDLJoystick.SDL_OpenJoystick(id);
            if (j == 0L) {
                SDLJoystick.SDL_DetachVirtualJoystick(id);
                failed = true;
                FpvDroneMod.LOGGER.warn("[fpvdrone] could not open virtual joystick");
                return;
            }

            instanceId = id;
            joystick = j;
            push();
            FpvDroneMod.LOGGER.info("[fpvdrone] virtual joystick active: WASD + arrow keys");
        }
    }

    /** Client tick: read the keyboard and update the virtual axes. */
    static void tick() {
        if (!ENABLED) return;
        ensureAttached();
        if (joystick == 0L) return;

        axes[ROLL] = approach(axes[ROLL], down(KEY_RIGHT) - down(KEY_LEFT));
        // Up arrow = stick forward = negative, as on a real SDL stick.
        axes[PITCH] = approach(axes[PITCH], down(KEY_DOWN) - down(KEY_UP));
        axes[YAW] = approach(axes[YAW], down(KEY_D) - down(KEY_A));

        // Throttle holds where you leave it.
        axes[THROTTLE] = clamp(axes[THROTTLE] + (down(KEY_W) - down(KEY_S)) * THROTTLE_STEP);

        push();
    }

    private static void push() {
        for (int i = 0; i < axes.length; i++) {
            SDLJoystick.SDL_SetJoystickVirtualAxis(
                    joystick, i, (short) Math.round(clamp(axes[i]) * 32767f));
        }
    }

    private static float down(int key) {
        return InputConstants.isKeyDown(key) ? 1f : 0f;
    }

    /** Moves toward the target: quickly back to center, a bit slower out to the edge. */
    private static float approach(float v, float target) {
        float step = target == 0f ? RETURN : RAMP;
        float d = target - v;
        return v + Math.max(-step, Math.min(step, d));
    }

    private static float clamp(float v) {
        return Math.max(-1f, Math.min(1f, v));
    }
}