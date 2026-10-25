package com.taylorgoolsby.fpvdrone.screen;

import com.taylorgoolsby.fpvdrone.PhysicsSettings;
import com.taylorgoolsby.fpvdrone.PhysicsSettings.Param;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * The physical constants behind the flight model: air, the airframe and the
 * motor windings. Changes are saved when the screen is closed and take effect
 * the next time the physics steps.
 */
public class PhysicsSettingsScreen extends OptionsSubScreen {
    private static final Component TITLE =
            Component.translatable("fpvdrone.physics.title");

    public PhysicsSettingsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, TITLE);
    }

    @Override
    protected void addOptions() {
        Button reset = Button.builder(
                Component.translatable("fpvdrone.physics.reset"),
                b -> {
                    PhysicsSettings.reset();
                    this.minecraft.gui.setScreen(
                            new PhysicsSettingsScreen(this.lastScreen, this.options));
                }
        ).build();

        this.list.addSmall(slider(Param.AIR_DENSITY), slider(Param.DRONE_DENSITY));
        this.list.addSmall(slider(Param.DRAG_COEFFICIENT),
                slider(Param.RESISTANCE_PER_1000_FT));
        this.list.addSmall(slider(Param.TEMP_COEFF), slider(Param.SPECIFIC_HEAT));
        this.list.addSmall(slider(Param.HEAT_TRANSFER), reset);
    }

    private static PhysicsSlider slider(Param param) {
        return new PhysicsSlider(param);
    }

    @Override
    public void removed() {
        // Sliders only change the in-memory values while dragging.
        PhysicsSettings.save();
        super.removed();
    }

    /** A slider for one physics constant, snapping to the constant's step. */
    private static class PhysicsSlider extends AbstractSliderButton {
        private final Param param;

        PhysicsSlider(Param param) {
            super(0, 0, 150, 20, Component.empty(),
                    (PhysicsSettings.get(param) - param.min)
                            / (param.max - param.min));
            this.param = param;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            // The parent may call this before our field is set.
            if (param == null) return;
            setMessage(Component.translatable(
                    "fpvdrone.physics.slider",
                    Component.translatable("fpvdrone.physics.param." + param.key),
                    param.format(PhysicsSettings.get(param))
            ));
        }

        @Override
        protected void applyValue() {
            PhysicsSettings.set(param,
                    param.min + (float) this.value * (param.max - param.min));

            // Move the handle to the snapped value.
            this.value = (PhysicsSettings.get(param) - param.min)
                    / (param.max - param.min);
        }
    }
}