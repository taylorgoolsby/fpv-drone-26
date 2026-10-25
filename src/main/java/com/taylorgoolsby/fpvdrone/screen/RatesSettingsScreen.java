package com.taylorgoolsby.fpvdrone.screen;

import com.taylorgoolsby.fpvdrone.DroneController;
import com.taylorgoolsby.fpvdrone.DroneController.Axis;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Betaflight-style rates for roll, pitch and yaw: rate (center
 * sensitivity), super rate and expo, per axis. Changes apply immediately and
 * are saved when the screen is closed.
 */
public class RatesSettingsScreen extends OptionsSubScreen {
    private static final Component TITLE =
            Component.translatable("fpvdrone.rates.title");

    private static final int SLIDER_WIDTH = 150;
    private static final int FULL_WIDTH = 310;

    private static final Axis[] AXES = {Axis.ROLL, Axis.PITCH, Axis.YAW};

    public RatesSettingsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, TITLE);
    }

    @Override
    protected void addOptions() {
        for (Axis a : AXES) {
            RateSlider rate = new RateSlider(
                    a, "rate",
                    DroneController.RC_RATE_MIN, DroneController.RC_RATE_MAX,
                    () -> DroneController.getRcRate(a),
                    v -> DroneController.setRcRate(a, v)
            );
            RateSlider superRate = new RateSlider(
                    a, "super",
                    DroneController.SUPER_RATE_MIN,
                    DroneController.SUPER_RATE_MAX,
                    () -> DroneController.getSuperRate(a),
                    v -> DroneController.setSuperRate(a, v)
            );
            RateSlider expo = new RateSlider(
                    a, "expo",
                    DroneController.EXPO_MIN, DroneController.EXPO_MAX,
                    () -> DroneController.getExpo(a),
                    v -> DroneController.setExpo(a, v)
            );

            this.list.addSmall(rate, superRate);
            this.list.addSmall(expo, null);
        }

        Button reset = Button.builder(
                Component.translatable("fpvdrone.rates.reset"),
                b -> {
                    DroneController.resetRates();
                    this.minecraft.gui.setScreen(
                            new RatesSettingsScreen(this.lastScreen, this.options));
                }
        ).width(FULL_WIDTH).build();
        this.list.addSmall(reset, null);
    }

    @Override
    public void removed() {
        // Sliders only change the in-memory values while dragging.
        DroneController.saveConfig();
        super.removed();
    }

    /** A slider for one rate parameter of one axis, in steps of 0.01. */
    private static class RateSlider extends AbstractSliderButton {
        private final Axis axis;
        private final String param;
        private final float min;
        private final float max;
        private final Supplier<Float> getter;
        private final Consumer<Float> setter;

        RateSlider(
                Axis axis,
                String param,
                float min,
                float max,
                Supplier<Float> getter,
                Consumer<Float> setter
        ) {
            super(0, 0, SLIDER_WIDTH, 20, Component.empty(),
                    (getter.get() - min) / (max - min));
            this.axis = axis;
            this.param = param;
            this.min = min;
            this.max = max;
            this.getter = getter;
            this.setter = setter;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            // The parent may call this before our fields are set.
            if (getter == null) return;
            setMessage(Component.translatable(
                    "fpvdrone.rates.slider",
                    Component.translatable("fpvdrone.axis." + axis.key),
                    Component.translatable("fpvdrone.rates.param." + param),
                    String.format(Locale.ROOT, "%.2f", getter.get())
            ));
        }

        @Override
        protected void applyValue() {
            float v = min + (float) this.value * (max - min);
            setter.accept(Math.round(v * 100f) / 100f);
        }
    }
}