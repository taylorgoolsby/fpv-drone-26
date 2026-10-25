package com.taylorgoolsby.fpvdrone;

import net.minecraftforge.fml.loading.FMLPaths;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

/**
 * The physical constants AdvancedPhysicsCore uses for air, the airframe and
 * the motor windings. Values are stored in SI units and saved to
 * config/fpvdrone-physics.properties.
 *
 * INCHES_PER_METER, REF_TEMP and the vacuum permeability are not here: they
 * are fixed in the core.
 */
public final class PhysicsSettings {

    /**
     * One configurable constant.
     *
     * @param displayScale multiply the stored value by this to get the number
     *                     shown to the user
     */
    public enum Param {
        AIR_DENSITY("air_density", 1.225f, 0.1f, 3.0f, 0.005f, 1f, 3, " kg/m\u00b3"),
        DRONE_DENSITY("drone_density", 1500f, 200f, 5000f, 50f, 1f, 0, " kg/m\u00b3"),
        DRAG_COEFFICIENT("drag_coefficient", 1.05f, 0.1f, 3.0f, 0.01f, 1f, 2, ""),
        RESISTANCE_PER_1000_FT("resistance_per_1000_ft", 25.7f, 1f, 100f, 0.1f, 1f, 1, " ohm/kft"),
        TEMP_COEFF("temp_coeff", 0.00393f, 0f, 0.01f, 0.00001f, 1000f, 2, "e-3/K"),
        SPECIFIC_HEAT("specific_heat", 400f, 100f, 1000f, 5f, 1f, 0, " J/kgK"),
        HEAT_TRANSFER("heat_transfer", 50f, 1f, 500f, 1f, 1f, 0, " W/m2K");

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

        /** The value as shown to the user, for example "1.225 kg/m3". */
        public String format(float value) {
            return String.format(java.util.Locale.ROOT, "%." + decimals + "f%s",
                    value * displayScale, unit);
        }
    }

    private static final float[] values = new float[Param.values().length];
    private static boolean loaded = false;
    private static int version = 0;

    static {
        for (Param p : Param.values()) {
            values[p.ordinal()] = p.defaultValue;
        }
    }

    private PhysicsSettings() {}

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

    /** Restores every constant to its default and saves. */
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

    private static float snap(Param p, float value) {
        float steps = Math.round((value - p.min) / p.step);
        float snapped = p.min + steps * p.step;
        return Math.max(p.min, Math.min(p.max, snapped));
    }

    private static Path configPath() {
        return FMLPaths.CONFIGDIR.get().resolve("fpvdrone-physics.properties");
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
            FpvDroneMod.LOGGER.warn("[fpvdrone] could not read physics config", e);
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
            props.store(out, "FPV Drone physics constants, in SI units.");
        } catch (IOException e) {
            FpvDroneMod.LOGGER.warn("[fpvdrone] could not write physics config", e);
        }
    }
}