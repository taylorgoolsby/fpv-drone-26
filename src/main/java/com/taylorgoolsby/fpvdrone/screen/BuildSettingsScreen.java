package com.taylorgoolsby.fpvdrone.screen;

import com.taylorgoolsby.fpvdrone.DroneBuild;
import com.taylorgoolsby.fpvdrone.DroneBuild.Param;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

import java.util.Locale;

/**
 * The drone build used by the physics: mass, battery, motor and prop
 * dimensions, plus the camera angle. Motor and blade mass are derived from the dimensions and shown
 * read-only. Changes are saved when the screen is closed and take effect the
 * next time drone mode is entered.
 */
public class BuildSettingsScreen extends OptionsSubScreen {
    private static final Component TITLE =
            Component.translatable("fpvdrone.build.title");

    private Button bladeMassLabel;
    private Button motorMassLabel;

    public BuildSettingsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, TITLE);
    }

    @Override
    protected void addOptions() {
        bladeMassLabel = inactiveLabel();
        motorMassLabel = inactiveLabel();

        Button reset = Button.builder(
                Component.translatable("fpvdrone.build.reset"),
                b -> {
                    DroneBuild.reset();
                    this.minecraft.gui.setScreen(
                            new BuildSettingsScreen(this.lastScreen, this.options));
                }
        ).build();

        this.list.addSmall(slider(Param.DRONE_MASS), slider(Param.BATTERY_CELLS));
        this.list.addSmall(slider(Param.MOTOR_KV), slider(Param.BLADES));
        this.list.addSmall(slider(Param.PROP_RADIUS), slider(Param.PROP_PITCH));
        this.list.addSmall(slider(Param.PROP_WIDTH), bladeMassLabel);
        this.list.addSmall(slider(Param.MOTOR_WIDTH), slider(Param.MOTOR_HEIGHT));
        this.list.addSmall(motorMassLabel, slider(Param.CAMERA_ANGLE));
        this.list.addSmall(reset, null);

        updateDerivedLabels();
    }

    private BuildSlider slider(Param param) {
        return new BuildSlider(param, this::updateDerivedLabels);
    }

    private static Button inactiveLabel() {
        Button label = Button.builder(Component.empty(), b -> {}).build();
        label.active = false;
        return label;
    }

    /** Motor and blade mass follow the dimensions, so refresh them live. */
    private void updateDerivedLabels() {
        if (bladeMassLabel != null) {
            bladeMassLabel.setMessage(Component.translatable(
                    "fpvdrone.build.blade_mass",
                    grams(DroneBuild.bladeMass())));
        }
        if (motorMassLabel != null) {
            motorMassLabel.setMessage(Component.translatable(
                    "fpvdrone.build.motor_mass",
                    grams(DroneBuild.motorMass())));
        }
    }

    private static String grams(float kilograms) {
        return String.format(Locale.ROOT, "%.1f g", kilograms * 1000f);
    }

    @Override
    public void removed() {
        // Sliders only change the in-memory values while dragging.
        DroneBuild.save();
        super.removed();
    }

    /** A slider for one build parameter, snapping to the parameter's step. */
    private static class BuildSlider extends AbstractSliderButton {
        private final Param param;
        private final Runnable onChange;

        BuildSlider(Param param, Runnable onChange) {
            super(0, 0, 150, 20, Component.empty(),
                    (DroneBuild.get(param) - param.min)
                            / (param.max - param.min));
            this.param = param;
            this.onChange = onChange;
            updateMessage();
        }

        @Override
        protected void updateMessage() {
            // The parent may call this before our fields are set.
            if (param == null) return;
            setMessage(Component.translatable(
                    "fpvdrone.build.slider",
                    Component.translatable("fpvdrone.build.param." + param.key),
                    param.format(DroneBuild.get(param))
            ));
        }

        @Override
        protected void applyValue() {
            DroneBuild.set(param,
                    param.min + (float) this.value * (param.max - param.min));

            // Move the handle to the snapped value so whole-number settings
            // (cells, blades) visibly click into place.
            this.value = (DroneBuild.get(param) - param.min)
                    / (param.max - param.min);
            onChange.run();
        }
    }
}