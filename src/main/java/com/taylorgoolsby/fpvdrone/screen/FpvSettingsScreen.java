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

        Button other = Button.builder(
                Component.translatable("fpvdrone.settings.other"),
                b -> this.minecraft.gui.setScreen(
                        new OtherSettingsScreen(this, this.options))
        ).build();

        this.list.addSmall(controller, bindings);
        this.list.addSmall(other, null);
    }
}