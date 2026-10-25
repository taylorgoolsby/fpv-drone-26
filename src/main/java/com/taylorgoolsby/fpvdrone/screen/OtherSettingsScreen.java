package com.taylorgoolsby.fpvdrone.screen;

import com.taylorgoolsby.fpvdrone.DroneController;
import net.minecraft.client.Options;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;

/**
 * Miscellaneous FPV settings that don't belong on the controller or
 * bindings screens.
 */
public class OtherSettingsScreen extends OptionsSubScreen {
    private static final Component TITLE =
            Component.translatable("fpvdrone.other.title");

    private static final int FULL_WIDTH = 310;

    public OtherSettingsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, TITLE);
    }

    @Override
    protected void addOptions() {
        Button showAxisValues = Button.builder(
                showAxisValuesLabel(),
                b -> {
                    DroneController.setShowAxisValues(
                            !DroneController.isShowAxisValues());
                    b.setMessage(showAxisValuesLabel());
                }
        ).width(FULL_WIDTH).build();

        this.list.addSmall(showAxisValues, null);
    }

    private static Component showAxisValuesLabel() {
        return Component.translatable("fpvdrone.other.show_axis_values",
                DroneController.isShowAxisValues()
                        ? CommonComponents.OPTION_ON
                        : CommonComponents.OPTION_OFF);
    }
}