package com.taylorgoolsby.fpvdrone.screen;

import com.taylorgoolsby.fpvdrone.DroneController;
import com.taylorgoolsby.fpvdrone.DroneController.Axis;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Options;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.options.OptionsSubScreen;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Betaflight-style rates for yaw, pitch and roll.
 *
 * Left half: rate, super rate and expo sliders for each axis, stacked
 * vertically. Right half: the three rate curves superimposed (yaw red,
 * pitch green, roll blue), updating live as the sliders move. Changes apply
 * immediately and are saved when the screen is closed.
 */
public class RatesSettingsScreen extends OptionsSubScreen {
    private static final Component TITLE =
            Component.translatable("fpvdrone.rates.title");

    // Stacking order on the left, and the color of each axis.
    private static final Axis[] AXES = {Axis.YAW, Axis.PITCH, Axis.ROLL};

    private static final int COLOR_YAW = 0xFFFF5555;
    private static final int COLOR_PITCH = 0xFF55FF55;
    private static final int COLOR_ROLL = 0xFF5555FF;
    private static final int COLOR_FRAME = 0x88FFFFFF;
    private static final int COLOR_GRID = 0x33FFFFFF;

    private static final int CURVE_THICKNESS = 2;

    // Approximate space taken by the title and the Done button.
    private static final int HEADER = 36;
    private static final int FOOTER = 36;

    private final List<RateSlider> sliders = new ArrayList<>();
    private Button infoLabel;
    private Button resetButton;

    // Graph rectangle, in GUI pixels. Set by layoutCustom().
    private int graphX;
    private int graphY;
    private int graphW;
    private int graphH;

    public RatesSettingsScreen(Screen lastScreen, Options options) {
        super(lastScreen, options, TITLE);
    }

    @Override
    protected void addOptions() {
        // The standard options list isn't used. See init().
    }

    @Override
    protected void init() {
        super.init();

        // Drop the stock list: it can't do a two-column layout with a graph.
        if (this.list != null) {
            this.removeWidget(this.list);
        }

        sliders.clear();
        for (Axis a : AXES) {
            sliders.add(addRenderableWidget(new RateSlider(
                    a, "rate",
                    DroneController.RC_RATE_MIN, DroneController.RC_RATE_MAX,
                    () -> DroneController.getRcRate(a),
                    v -> DroneController.setRcRate(a, v),
                    this::updateInfo
            )));
            sliders.add(addRenderableWidget(new RateSlider(
                    a, "super",
                    DroneController.SUPER_RATE_MIN,
                    DroneController.SUPER_RATE_MAX,
                    () -> DroneController.getSuperRate(a),
                    v -> DroneController.setSuperRate(a, v),
                    this::updateInfo
            )));
            sliders.add(addRenderableWidget(new RateSlider(
                    a, "expo",
                    DroneController.EXPO_MIN, DroneController.EXPO_MAX,
                    () -> DroneController.getExpo(a),
                    v -> DroneController.setExpo(a, v),
                    this::updateInfo
            )));
        }

        infoLabel = addRenderableWidget(
                Button.builder(Component.empty(), b -> {}).build());
        infoLabel.active = false;

        resetButton = addRenderableWidget(Button.builder(
                Component.translatable("fpvdrone.rates.reset"),
                b -> {
                    DroneController.resetRates();
                    this.minecraft.gui.setScreen(
                            new RatesSettingsScreen(this.lastScreen, this.options));
                }
        ).build());

        layoutCustom();
        updateInfo();
    }

    @Override
    protected void repositionElements() {
        super.repositionElements();
        layoutCustom();
    }

    /** Positions everything. Safe to call before the widgets exist. */
    private void layoutCustom() {
        if (sliders.isEmpty() || infoLabel == null || resetButton == null) {
            return;
        }

        int top = HEADER;
        int avail = this.height - HEADER - FOOTER;
        int half = this.width / 2;

        // ---- Left half: nine sliders, grouped by axis ----
        int sliderW = Math.min(200, half - 30);
        int sliderX = Math.max(10, (half - sliderW) / 2);

        // 9 sliders + 2px between sliders + 8px between axis groups.
        int sliderH = Math.max(12, Math.min(20, (avail - 28) / 9));

        int y = top;
        for (int i = 0; i < sliders.size(); i++) {
            RateSlider s = sliders.get(i);
            s.setX(sliderX);
            s.setY(y);
            s.setWidth(sliderW);
            s.setHeight(sliderH);
            y += sliderH + 2;
            if (i % 3 == 2) y += 6;
        }

        // ---- Right half: graph, then the full-stick numbers and Reset ----
        int rightX = half + 15;
        int rightW = Math.max(60, this.width - rightX - 15);

        graphX = rightX;
        graphY = top;
        graphW = rightW;
        graphH = Math.max(40, Math.min(rightW, avail - 52));

        int labelY = graphY + graphH + 4;
        infoLabel.setX(rightX);
        infoLabel.setY(labelY);
        infoLabel.setWidth(rightW);
        infoLabel.setHeight(20);

        resetButton.setX(rightX);
        resetButton.setY(labelY + 24);
        resetButton.setWidth(rightW);
        resetButton.setHeight(20);
    }

    /** Shows the rate at full stick for each axis, in the axis color. */
    private void updateInfo() {
        if (infoLabel == null) return;
        infoLabel.setMessage(Component.translatable(
                "fpvdrone.rates.full_stick",
                coloredRate(Axis.YAW, ChatFormatting.RED),
                coloredRate(Axis.PITCH, ChatFormatting.GREEN),
                coloredRate(Axis.ROLL, ChatFormatting.BLUE)
        ));
    }

    private static Component coloredRate(Axis a, ChatFormatting color) {
        return Component.literal(String.format(Locale.ROOT, "%.0f",
                DroneController.rateDegPerSec(a, 1f))).withStyle(color);
    }

    @Override
    public void extractRenderState(
            GuiGraphicsExtractor gg, int mouseX, int mouseY, float partialTick
    ) {
        super.extractRenderState(gg, mouseX, mouseY, partialTick);
        drawGraph(gg);
    }

    private void drawGraph(GuiGraphicsExtractor gg) {
        if (graphW <= 0 || graphH <= 0) return;

        int right = graphX + graphW;
        int bottom = graphY + graphH;

        // The vertical scale fits the fastest curve, rounded up to 100 deg/s.
        float max = 1f;
        for (Axis a : AXES) {
            max = Math.max(max, DroneController.rateDegPerSec(a, 1f));
        }
        float graphMax = Math.max(100f, (float) Math.ceil(max / 100f) * 100f);

        // Quarter grid lines.
        for (int i = 1; i < 4; i++) {
            int gy = graphY + graphH * i / 4;
            int gx = graphX + graphW * i / 4;
            gg.fill(graphX, gy, right, gy + 1, COLOR_GRID);
            gg.fill(gx, graphY, gx + 1, bottom, COLOR_GRID);
        }

        // Frame.
        gg.fill(graphX, graphY, right, graphY + 1, COLOR_FRAME);
        gg.fill(graphX, bottom - 1, right, bottom, COLOR_FRAME);
        gg.fill(graphX, graphY, graphX + 1, bottom, COLOR_FRAME);
        gg.fill(right - 1, graphY, right, bottom, COLOR_FRAME);

        // Where the curves overlap, the last one drawn (roll) is on top.
        drawCurve(gg, Axis.YAW, COLOR_YAW, graphMax);
        drawCurve(gg, Axis.PITCH, COLOR_PITCH, graphMax);
        drawCurve(gg, Axis.ROLL, COLOR_ROLL, graphMax);
    }

    /** Stick 0 (left) to full stick (right) against deg/s (up). */
    private void drawCurve(
            GuiGraphicsExtractor gg, Axis axis, int color, float graphMax
    ) {
        int bottom = graphY + graphH;
        int prevY = -1;

        for (int c = 0; c < graphW; c++) {
            float stick = graphW > 1 ? c / (float) (graphW - 1) : 0f;
            float deg = DroneController.rateDegPerSec(axis, stick);
            float t = Math.max(0f, Math.min(1f, deg / graphMax));
            int y = bottom - 1 - Math.round(t * (graphH - 1));

            // Join this column to the previous one so steep parts have no gaps.
            int yTop = prevY < 0 ? y : Math.min(prevY, y);
            int yBottom = prevY < 0 ? y : Math.max(prevY, y);

            gg.fill(graphX + c, yTop,
                    graphX + c + 1,
                    Math.min(bottom, yBottom + CURVE_THICKNESS),
                    color);
            prevY = y;
        }
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
        private final Runnable onChange;

        RateSlider(
                Axis axis,
                String param,
                float min,
                float max,
                Supplier<Float> getter,
                Consumer<Float> setter,
                Runnable onChange
        ) {
            super(0, 0, 150, 20, Component.empty(),
                    (getter.get() - min) / (max - min));
            this.axis = axis;
            this.param = param;
            this.min = min;
            this.max = max;
            this.getter = getter;
            this.setter = setter;
            this.onChange = onChange;
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
            ).withStyle(axisColor(axis)));
        }

        @Override
        protected void applyValue() {
            float v = min + (float) this.value * (max - min);
            setter.accept(Math.round(v * 100f) / 100f);
            onChange.run();
        }

        private static ChatFormatting axisColor(Axis a) {
            return switch (a) {
                case YAW -> ChatFormatting.RED;
                case PITCH -> ChatFormatting.GREEN;
                default -> ChatFormatting.BLUE;
            };
        }
    }
}