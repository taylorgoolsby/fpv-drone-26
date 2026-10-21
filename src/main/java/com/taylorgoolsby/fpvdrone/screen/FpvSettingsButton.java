package com.taylorgoolsby.fpvdrone.screen;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.OptionsList;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.screens.options.controls.ControlsScreen;
import net.minecraft.network.chat.Component;
import net.minecraftforge.client.event.ScreenEvent;

/**
 * Adds an "FPV Settings" button to the vanilla Options > Controls screen.
 * Client-only; registered from ClientInit.
 */
public final class FpvSettingsButton {

    private FpvSettingsButton() {}

    public static void register() {
        ScreenEvent.Init.Post.BUS.addListener(FpvSettingsButton::onScreenInit);
    }

    private static void onScreenInit(ScreenEvent.Init.Post event) {
        if (!(event.getScreen() instanceof ControlsScreen controls)) return;

        // ControlsScreen.list is protected, but the OptionsList is also
        // one of the screen's child widgets, so we can find it there.
        OptionsList list = null;
        for (GuiEventListener listener : event.getListenersList()) {
            if (listener instanceof OptionsList found) {
                list = found;
                break;
            }
        }
        if (list == null) return;

        Button button = Button.builder(
                Component.translatable("fpvdrone.settings.button"),
                b -> {
                    Minecraft mc = Minecraft.getInstance();
                    mc.gui.setScreen(new FpvSettingsScreen(controls, mc.options));
                }
        ).build();

        // Same call vanilla uses for its Mouse Settings / Key Binds row.
        // A null right-hand widget leaves the other half of the row empty.
        list.addSmall(button, null);
    }
}