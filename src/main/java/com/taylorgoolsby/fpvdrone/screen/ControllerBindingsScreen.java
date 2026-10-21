package com.taylorgoolsby.fpvdrone.screen;

import com.taylorgoolsby.fpvdrone.DroneController;
import com.taylorgoolsby.fpvdrone.DroneController.Axis;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Binds roll, pitch, yaw and throttle to physical controller axes.
 * Click a binding, then move the stick in the direction the prompt asks for;
 * the axis that moved is bound and inversion is detected from the direction.
 */
public class ControllerBindingsScreen extends OptionsSubScreen {
    private static final Component TITLE =
            Component.translatable("fpvdrone.bindings.title");

    private static final int FULL_WIDTH = 310;

    private final Button[] bindButtons = new Button[Axis.values().length];
    private final Button[] invertButtons = new Button[Axis.values().length];
    private Button resetButton;

    public ControllerBindingsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, TITLE);
    }

    @Override
    protected void addOptions() {
        boolean connected = DroneController.isConnected();

        // Read-only banner showing which controller is being configured.
        Button info = Button.builder(
                connected
                        ? Component.translatable("fpvdrone.bindings.controller",
                        DroneController.connectedName())
                        : Component.translatable("fpvdrone.bindings.none"),
                b -> {}
        ).width(FULL_WIDTH).build();
        info.active = false;
        this.list.addSmall(info, null);

        for (Axis a : Axis.values()) {
            Button bind = Button.builder(bindLabel(a), b -> onBindPressed(a)).build();
            Button invert = Button.builder(invertLabel(a), b -> {
                DroneController.toggleInvert(a);
                b.setMessage(invertLabel(a));
            }).build();

            bind.active = connected;
            invert.active = connected;
            bindButtons[a.ordinal()] = bind;
            invertButtons[a.ordinal()] = invert;
            this.list.addSmall(bind, invert);
        }

        resetButton = Button.builder(
                Component.translatable("fpvdrone.bindings.reset"),
                b -> {
                    DroneController.resetBindings();
                    reopen();
                }
        ).width(FULL_WIDTH).build();
        resetButton.active = connected;
        this.list.addSmall(resetButton, null);
    }

    private void onBindPressed(Axis a) {
        // Clicking the button that is currently listening cancels.
        if (DroneController.capturingAxis() == a) {
            DroneController.cancelCapture();
            reopen();
            return;
        }

        boolean started = DroneController.beginCapture(a, () -> {
            // Runs from the client tick once a stick moved. Only refresh
            // if this screen is still the one on display.
            if (this.minecraft.gui.screen() == this) reopen();
        });
        if (!started) return;

        // Show the prompt and lock everything else while listening.
        for (Axis other : Axis.values()) {
            invertButtons[other.ordinal()].active = false;
            if (other != a) bindButtons[other.ordinal()].active = false;
        }
        resetButton.active = false;
        bindButtons[a.ordinal()].setMessage(
                Component.translatable("fpvdrone.bindings.listening." + a.key));
    }

    private static Component bindLabel(Axis a) {
        return Component.translatable("fpvdrone.bindings.entry",
                Component.translatable("fpvdrone.axis." + a.key),
                DroneController.bindingName(a));
    }

    private static Component invertLabel(Axis a) {
        return Component.translatable("fpvdrone.bindings.invert",
                DroneController.isInverted(a)
                        ? CommonComponents.OPTION_ON
                        : CommonComponents.OPTION_OFF);
    }

    /** Opens a fresh copy of this screen so every label is up to date. */
    private void reopen() {
        this.minecraft.gui.setScreen(
                new ControllerBindingsScreen(this.lastScreen, this.options));
    }

    @Override
    public void removed() {
        // Leaving the screen (Done, Esc, etc.) abandons any pending capture.
        DroneController.cancelCapture();
        super.removed();
    }
}