package com.taylorgoolsby.fpvdrone;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The user-configurable drone build that AdvancedPhysicsCore simulates.
 * Values are stored in SI units (kg, m) and saved to
 * config/fpvdrone-build.properties.
 *
 * Motor mass, bell mass and blade mass are not settings: they are derived
 * from the motor and prop dimensions using the density constants below.
 */
public final class DroneBuild {

    /**
     * One configurable parameter.
     *
     * @param displayScale multiply the stored value by this to get the number
     *                     shown to the user (for example 1000 for m -> mm)
     */
    public enum Param {
        DRONE_MASS("drone_mass", 0.65f, 0.10f, 2.00f, 0.005f, 1000f, 0, " g"),
        BATTERY_CELLS("battery_cells", 4f, 1f, 8f, 1f, 1f, 0, "S"),
        MOTOR_KV("motor_kv", 2000f, 500f, 5000f, 50f, 1f, 0, " kV"),
        BLADES("blades", 3f, 2f, 6f, 1f, 1f, 0, ""),
        PROP_RADIUS("prop_radius", 0.0635f, 0.025f, 0.150f, 0.0005f, 1000f, 1, " mm"),
        PROP_PITCH("prop_pitch", 0.10f, 0.03f, 0.25f, 0.001f, 1000f, 0, " mm"),
        PROP_WIDTH("prop_width", 0.015f, 0.005f, 0.040f, 0.0005f, 1000f, 1, " mm"),
        MOTOR_WIDTH("motor_width", 0.023f, 0.010f, 0.060f, 0.0005f, 1000f, 1, " mm"),
        MOTOR_HEIGHT("motor_height", 0.006f, 0.003f, 0.030f, 0.0005f, 1000f, 1, " mm");

        public final String key;
        public final float defaultValue;
        public final float min;
        public final float max;
        public final float step;
        public final float displayScale;
        public final int decimals;
        public final String unit;

        Param(
                String key,
                float defaultValue,
                float min,
                float max,
                float step,
                float displayScale,
                int decimals,
                String unit
        ) {
            this.key = key;
            this.defaultValue = defaultValue;
            this.min = min;
            this.max = max;
            this.step = step;
            this.displayScale = displayScale;
            this.decimals = decimals;
            this.unit = unit;
        }

        /** The value as shown to the user, for example "63.5 mm". */
        public String format(float value) {
            return String.format(java.util.Locale.ROOT, "%." + decimals + "f%s",
                    value * displayScale, unit);
        }
    }

    // ---------------------------------------------------------------------
    // Densities used to derive masses.
    // ---------------------------------------------------------------------

    /**
     * Effective density of a motor, in kg per m^3 of a cylinder the size of
     * its stator (motor width x motor height). It is much higher than any
     * material because the bell, base, shaft and windings extend beyond the
     * stator. Chosen so a 23 mm x 6 mm motor (a 2306) weighs 33 g.
     */
    public static final float MOTOR_DENSITY = 13240f;

    /** The rotating bell is this fraction of the motor's mass (12 g of 33 g). */
    public static final float BELL_MASS_FRACTION = 12f / 33f;

    /** Prop plastic. */
    public static final float BLADE_DENSITY = 1200f;

    /**
     * Effective average blade thickness, in m. The blade is treated as a
     * flat plate of radius x width x this thickness. Chosen so a 5 inch
     * blade weighs about 1.5 g.
     */
    public static final float BLADE_THICKNESS = 0.0013f;

    // ---------------------------------------------------------------------
    // State
    // ---------------------------------------------------------------------

    private static final float[] values = new float[Param.values().length];
    private static boolean loaded = false;
    private static int version = 0;

    static {
        for (Param p : Param.values()) {
            values[p.ordinal()] = p.defaultValue;
        }
    }

    private DroneBuild() {}

    // ---------------------------------------------------------------------
    // Access
    // ---------------------------------------------------------------------

    public static float get(Param p) {
        ensureLoaded();
        return values[p.ordinal()];
    }

    /**
     * Sets a value, snapped to the parameter's step and clamped to its range.
     * Does not save; call save() when done editing.
     */
    public static void set(Param p, float value) {
        ensureLoaded();
        values[p.ordinal()] = snap(p, value);
        version++;
    }

    /** Restores every parameter to its default and saves. */
    public static void reset() {
        ensureLoaded();
        for (Param p : Param.values()) {
            values[p.ordinal()] = p.defaultValue;
        }
        version++;
        save();
    }

    /** Increases every time any value changes. */
    public static int getVersion() {
        ensureLoaded();
        return version;
    }

    // ---------------------------------------------------------------------
    // Derived masses
    // ---------------------------------------------------------------------

    /** Motor mass in kg: effective density x stator-sized cylinder. */
    public static float motorMass() {
        float radius = get(Param.MOTOR_WIDTH) * 0.5f;
        float volume = (float) Math.PI * radius * radius
                * get(Param.MOTOR_HEIGHT);
        return MOTOR_DENSITY * volume;
    }

    /** Mass in kg of the rotating part of the motor. */
    public static float bellMass() {
        return BELL_MASS_FRACTION * motorMass();
    }

    /** Mass in kg of a single prop blade. */
    public static float bladeMass() {
        float volume = get(Param.PROP_RADIUS) * get(Param.PROP_WIDTH)
                * BLADE_THICKNESS;
        return BLADE_DENSITY * volume;
    }

    // ---------------------------------------------------------------------
    // Persistence
    // ---------------------------------------------------------------------

    private static float snap(Param p, float value) {
        float steps = Math.round((value - p.min) / p.step);
        float snapped = p.min + steps * p.step;
        return Math.max(p.min, Math.min(p.max, snapped));
    }

    private static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve("fpvdrone-build.properties");
    }

    private static void ensureLoaded() {
        if (loaded) return;
        loaded = true;

        Path path = configPath();
        if (!Files.exists(path)) return;

        Properties props = new Properties();
        try (InputStream in = Files.newInputStream(path)) {
            props.load(in);
        } catch (IOException e) {
            FpvDroneMod.LOGGER.warn("[fpvdrone] could not read build config", e);
            return;
        }

        for (Param p : Param.values()) {
            try {
                float v = Float.parseFloat(
                        props.getProperty(p.key, String.valueOf(p.defaultValue)).trim()
                );
                values[p.ordinal()] = Math.max(p.min, Math.min(p.max, v));
            } catch (NumberFormatException ignored) {
                values[p.ordinal()] = p.defaultValue;
            }
        }
        version++;
    }

    public static void save() {
        ensureLoaded();

        Properties props = new Properties();
        for (Param p : Param.values()) {
            props.setProperty(p.key, String.valueOf(values[p.ordinal()]));
        }

        try (OutputStream out = Files.newOutputStream(configPath())) {
            props.store(out, "FPV Drone build. SI units: kg for masses, m for lengths, "
                    + "rpm per volt for kv.");
        } catch (IOException e) {
            FpvDroneMod.LOGGER.warn("[fpvdrone] could not write build config", e);
        }
    }
}