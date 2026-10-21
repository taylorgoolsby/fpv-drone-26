package com.taylorgoolsby.fpvdrone.screen;

import net.minecraft.client.Options;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

/**
 * Top-level settings screen for the FPV Drone mod.
 *
 * Extends OptionsSubScreen so it gets the vanilla options look for free:
 * title header, scrolling OptionsList, a "Done" footer button, and
 * returning to the previous screen on close.
 */
public class FpvSettingsScreen extends OptionsSubScreen {
    private static final Component TITLE =
            Component.translatable("fpvdrone.settings.title");

    public FpvSettingsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, TITLE);
    }

    @Override
    protected void addOptions() {
        // Nothing yet. Add settings here (this.list is non-null at this point),
        // e.g. this.list.addSmall(...) / this.list.addBig(...), or buttons
        // that open further sub-screens.
    }
}