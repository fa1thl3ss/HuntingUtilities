package com.example.addon.hud;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.TickRate;
import net.minecraft.client.MinecraftClient;

public class StatsHud extends HudElement {

    public static final HudElementInfo<StatsHud> INFO = new HudElementInfo<>(
        HuntingUtilities.HUD_GROUP,
        "stats-hud",
        "Displays speed, FPS, and TPS.",
        StatsHud::new
    );

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // ── Settings ──────────────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> showSpeed = sgGeneral.add(new BoolSetting.Builder()
        .name("show-speed")
        .description("Show player speed.")
        .defaultValue(true)
        .build()
    );

    public enum SpeedUnit { Both, BPS, KMH }

    private final Setting<SpeedUnit> speedUnit = sgGeneral.add(new EnumSetting.Builder<SpeedUnit>()
        .name("speed-unit")
        .description("Which speed unit(s) to display.")
        .defaultValue(SpeedUnit.Both)
        .visible(showSpeed::get)
        .build()
    );

    private final Setting<Boolean> showFps = sgGeneral.add(new BoolSetting.Builder()
        .name("show-fps")
        .description("Show current FPS.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showTps = sgGeneral.add(new BoolSetting.Builder()
        .name("show-tps")
        .description("Show server TPS.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the HUD element.")
        .defaultValue(1.0)
        .min(0.25)
        .sliderRange(0.25, 4.0)
        .build()
    );

    private final Setting<SettingColor> labelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("label-color")
        .defaultValue(new SettingColor(170, 170, 170, 255))
        .build()
    );

    private final Setting<SettingColor> valueColor = sgGeneral.add(new ColorSetting.Builder()
        .name("value-color")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> separatorColor = sgGeneral.add(new ColorSetting.Builder()
        .name("separator-color")
        .description("Color of the | separator between FPS and TPS.")
        .defaultValue(new SettingColor(100, 100, 100, 255))
        .build()
    );

    private final Setting<Boolean> showBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Show a background highlight behind each line.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .defaultValue(new SettingColor(0, 0, 0, 80))
        .visible(showBackground::get)
        .build()
    );

    public enum Alignment { Left, Right }

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Align text to the left or right.")
        .defaultValue(Alignment.Left)
        .build()
    );

    // ── Constructor ───────────────────────────────────────────────────────────────

    public StatsHud() {
        super(INFO);
    }

    // ── Rendering ─────────────────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {
        double s = scale.get();

        double padH       = 4 * s;
        double padV       = 2 * s;
        double rowGap     = 2 * s;
        double lineHeight = renderer.textHeight(false, s);
        double sepW       = renderer.textWidth(" | ", false, s);
        boolean rightAlign = alignment.get() == Alignment.Right;

        float tps = TickRate.INSTANCE.getTickRate();
        SettingColor tpsColor = tps < 10f
            ? new SettingColor(255, 60,  60,  255)
            : tps < 15f
            ? new SettingColor(255, 200, 0,   255)
            : valueColor.get();

        // ── Line 1: Speed ─────────────────────────────────────────────────────────
        String speedLabel = null, speedValue = null;
        if (showSpeed.get()) {
            double bps = getSpeedBps();
            double kmh = bps * 3.6;
            speedLabel = "Speed: ";
            speedValue = switch (speedUnit.get()) {
                case BPS  -> String.format("%.1f bps", bps);
                case KMH  -> String.format("%.1f km/h", kmh);
                case Both -> String.format("%.1f bps / %.1f km/h", bps, kmh);
            };
        }

        // ── Line 2: FPS | TPS ─────────────────────────────────────────────────────
        String fpsLabel = null, fpsValue = null, tpsLabel = null, tpsValue = null;
        if (showFps.get()) { fpsLabel = "FPS: "; fpsValue = String.valueOf(mc.getCurrentFps()); }
        if (showTps.get()) { tpsLabel = "TPS: "; tpsValue = String.format("%.1f", tps); }

        // Measure line widths
        double line1W = 0;
        if (speedLabel != null)
            line1W = renderer.textWidth(speedLabel, false, s) + renderer.textWidth(speedValue, false, s);

        double line2W = 0;
        if (fpsLabel != null)
            line2W += renderer.textWidth(fpsLabel, false, s) + renderer.textWidth(fpsValue, false, s);
        if (fpsLabel != null && tpsLabel != null)
            line2W += sepW;
        if (tpsLabel != null)
            line2W += renderer.textWidth(tpsLabel, false, s) + renderer.textWidth(tpsValue, false, s);

        boolean hasLine1 = speedLabel != null;
        boolean hasLine2 = fpsLabel != null || tpsLabel != null;

        if (!hasLine1 && !hasLine2) { setSize(0, 0); return; }

        double maxLineW  = Math.max(line1W, line2W);
        double totalW    = maxLineW + padH * 2;
        int    lineCount = (hasLine1 ? 1 : 0) + (hasLine2 ? 1 : 0);
        double totalH    = lineCount * lineHeight + (lineCount - 1) * rowGap + padV * 2;

        int lineIdx = 0;

        // ── Draw line 1 ───────────────────────────────────────────────────────────
        if (hasLine1) {
            double rowY      = y + padV + lineIdx * (lineHeight + rowGap);
            double line1BoxW = line1W + padH * 2;
            if (showBackground.get()) {
                double bgX = rightAlign ? x + totalW - line1BoxW : x;
                renderer.quad(bgX, rowY - 1, line1BoxW, lineHeight + 2, backgroundColor.get());
            }

            if (rightAlign) {
                double lw = renderer.textWidth(speedLabel, false, s);
                double vw = renderer.textWidth(speedValue, false, s);
                double vx = x + totalW - padH - vw;
                renderer.text(speedLabel, vx - lw, rowY, labelColor.get(), false, s);
                renderer.text(speedValue, vx,       rowY, valueColor.get(), false, s);
            } else {
                double cx = x + padH;
                renderer.text(speedLabel, cx, rowY, labelColor.get(), false, s);
                cx += renderer.textWidth(speedLabel, false, s);
                renderer.text(speedValue, cx, rowY, valueColor.get(), false, s);
            }
            lineIdx++;
        }

        // ── Draw line 2 ───────────────────────────────────────────────────────────
        if (hasLine2) {
            double rowY      = y + padV + lineIdx * (lineHeight + rowGap);
            double line2BoxW = line2W + padH * 2;
            if (showBackground.get()) {
                double bgX = rightAlign ? x + totalW - line2BoxW : x;
                renderer.quad(bgX, rowY - 1, line2BoxW, lineHeight + 2, backgroundColor.get());
            }

            if (rightAlign) {
                // Build right-to-left
                double cx = x + totalW - padH;
                if (tpsLabel != null) {
                    double vw = renderer.textWidth(tpsValue, false, s);
                    double lw = renderer.textWidth(tpsLabel, false, s);
                    cx -= vw;
                    renderer.text(tpsValue, cx, rowY, tpsColor, false, s);
                    cx -= lw;
                    renderer.text(tpsLabel, cx, rowY, labelColor.get(), false, s);
                }
                if (fpsLabel != null && tpsLabel != null) {
                    cx -= sepW;
                    renderer.text(" | ", cx, rowY, separatorColor.get(), false, s);
                }
                if (fpsLabel != null) {
                    double vw = renderer.textWidth(fpsValue, false, s);
                    double lw = renderer.textWidth(fpsLabel, false, s);
                    cx -= vw;
                    renderer.text(fpsValue, cx, rowY, valueColor.get(), false, s);
                    cx -= lw;
                    renderer.text(fpsLabel, cx, rowY, labelColor.get(), false, s);
                }
            } else {
                double cx = x + padH;
                if (fpsLabel != null) {
                    renderer.text(fpsLabel, cx, rowY, labelColor.get(), false, s);
                    cx += renderer.textWidth(fpsLabel, false, s);
                    renderer.text(fpsValue, cx, rowY, valueColor.get(), false, s);
                    cx += renderer.textWidth(fpsValue, false, s);
                }
                if (fpsLabel != null && tpsLabel != null) {
                    renderer.text(" | ", cx, rowY, separatorColor.get(), false, s);
                    cx += sepW;
                }
                if (tpsLabel != null) {
                    renderer.text(tpsLabel, cx, rowY, labelColor.get(), false, s);
                    cx += renderer.textWidth(tpsLabel, false, s);
                    renderer.text(tpsValue, cx, rowY, tpsColor, false, s);
                }
            }
            lineIdx++;
        }

        setSize(totalW, totalH);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private double getSpeedBps() {
        if (mc.player == null) return 0.0;
        double dx = mc.player.getX() - mc.player.prevX;
        double dz = mc.player.getZ() - mc.player.prevZ;
        return Math.sqrt(dx * dx + dz * dz) * 20.0;
    }
}