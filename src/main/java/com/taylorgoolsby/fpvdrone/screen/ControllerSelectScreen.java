package com.taylorgoolsby.fpvdrone.screen;

import com.taylorgoolsby.fpvdrone.DroneController;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import java.util.List;

/**
 * Lists connected USB controllers and lets the player pick the one to fly with.
 * The active controller is shown in green.
 */
public class ControllerSelectScreen extends OptionsSubScreen {
    private static final Component TITLE =
            Component.translatable("fpvdrone.controller.title");

    // A full row in the OptionsList is two 150px buttons + 10px gap.
    private static final int FULL_WIDTH = 310;

    public ControllerSelectScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, TITLE);
    }

    @Override
    protected void addOptions() {
        List<DroneController.Device> devices = DroneController.listDevices();
        String selected = DroneController.selectedId();
        String active = DroneController.connectedId();

        this.list.addSmall(
                row(Component.translatable("fpvdrone.controller.refresh"),
                        b -> reopen()),
                null);

        MutableComponent auto = Component.translatable("fpvdrone.controller.auto");
        if (selected.isEmpty()) auto.withStyle(ChatFormatting.GREEN);
        this.list.addSmall(
                row(auto, b -> choose("")),
                null);

        if (devices.isEmpty()) {
            Button none = row(Component.translatable("fpvdrone.controller.none"), b -> {});
            none.active = false;
            this.list.addSmall(none, null);
            return;
        }

        for (DroneController.Device d : devices) {
            MutableComponent label = Component.literal(d.name()).append(
                    Component.translatable(d.gamepad()
                            ? "fpvdrone.controller.kind.gamepad"
                            : "fpvdrone.controller.kind.raw"));
            if (d.id().equals(active)) label.withStyle(ChatFormatting.GREEN);

            this.list.addSmall(row(label, b -> choose(d.id())), null);
        }
    }

    private void choose(String id) {
        DroneController.selectDevice(id, this.minecraft);
        reopen();
    }

    /** Opens a fresh copy of this screen so the list and highlight update. */
    private void reopen() {
        this.minecraft.gui.setScreen(
                new ControllerSelectScreen(this.lastScreen, this.options));
    }

    private static Button row(Component text, Button.OnPress onPress) {
        return Button.builder(text, onPress).width(FULL_WIDTH).build();
    }
}