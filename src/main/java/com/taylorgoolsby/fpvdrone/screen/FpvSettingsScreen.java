package com.taylorgoolsby.fpvdrone.screen;

import com.taylorgoolsby.fpvdrone.DroneController;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Top-level settings screen for the FPV Drone mod. Hub for the sub-screens.
 */
public class FpvSettingsScreen extends OptionsSubScreen {
    private static final Component TITLE =
            Component.translatable("fpvdrone.settings.title");

    public FpvSettingsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, TITLE);
    }

    @Override
    protected void addOptions() {
        Button controller = Button.builder(
                Component.translatable("fpvdrone.settings.controller"),
                b -> this.minecraft.gui.setScreen(
                        new ControllerSelectScreen(this, this.options))
        ).build();

        Button bindings = Button.builder(
                Component.translatable("fpvdrone.settings.bindings"),
                b -> this.minecraft.gui.setScreen(
                        new ControllerBindingsScreen(this, this.options))
        ).build();

        // Bindings only make sense once a controller is connected. This is
        // evaluated each time the screen is (re)initialised, including when
        // returning from the controller selection screen.
        bindings.active = DroneController.isConnected();

        Button rates = Button.builder(
                Component.translatable("fpvdrone.settings.rates"),
                b -> this.minecraft.gui.setScreen(
                        new RatesSettingsScreen(this, this.options))
        ).build();

        Button build = Button.builder(
                Component.translatable("fpvdrone.settings.build"),
                b -> this.minecraft.gui.setScreen(
                        new BuildSettingsScreen(this, this.options))
        ).build();

        Button physics = Button.builder(
                Component.translatable("fpvdrone.settings.physics"),
                b -> this.minecraft.gui.setScreen(
                        new PhysicsSettingsScreen(this, this.options))
        ).build();

        Button other = Button.builder(
                Component.translatable("fpvdrone.settings.other"),
                b -> this.minecraft.gui.setScreen(
                        new OtherSettingsScreen(this, this.options))
        ).build();

        this.list.addSmall(controller, bindings);
        this.list.addSmall(build, rates);
        this.list.addSmall(physics, other);
    }
}