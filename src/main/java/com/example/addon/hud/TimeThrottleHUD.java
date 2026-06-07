package com.example.addon.hud;

import com.example.addon.HuntingUtilities;
import com.example.addon.modules.Timethrottle;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

public class TimeThrottleHUD extends HudElement {
    public static final HudElementInfo<TimeThrottleHUD> INFO = new HudElementInfo<>(
        HuntingUtilities.HUD_GROUP,
        "time-throttle",
        "Displays the system speed impact of the Timethrottle module.",
        TimeThrottleHUD::new
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    public enum Alignment { Left, Center, Right }

    // ── Settings ──────────────────────────────────────────────────────────────

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .defaultValue(1.0).min(0.5).sliderMax(3.0)
        .build()
    );

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Align text to the left, center, or right.")
        .defaultValue(Alignment.Left)
        .build()
    );

    private final Setting<SettingColor> labelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("label-color")
        .description("Color for the 'System Speed:' label.")
        .defaultValue(new SettingColor(170, 170, 170, 255))
        .build()
    );

    private final Setting<SettingColor> valueColor = sgGeneral.add(new ColorSetting.Builder()
        .name("value-color")
        .description("Default color for the speed percentage.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<Boolean> showBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Show a background highlight behind the HUD element.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .defaultValue(new SettingColor(0, 0, 0, 80))
        .visible(showBackground::get)
        .build()
    );

    private final Setting<Boolean> showSource = sgGeneral.add(new BoolSetting.Builder()
        .name("show-source")
        .description("Show which source is currently causing the most slowdown.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showBar = sgGeneral.add(new BoolSetting.Builder()
        .name("show-bar")
        .description("Shows a color-coded speed multiplier bar.")
        .defaultValue(true)
        .build()
    );

    public TimeThrottleHUD() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        Timethrottle module = Modules.get().get(Timethrottle.class);
        if (module == null || !module.isActive()) {
            if (isInEditor()) {
                setSize(renderer.textWidth("Speed: 100%", false, scale.get()), renderer.textHeight(false, scale.get()));
                renderer.text("Speed: 100%", x, y, Color.GRAY, false, scale.get());
            } else setSize(0, 0);
            return;
        }

        // Fetches current multiplier (e.g., 1.0 for full speed, 0.5 for half speed)
        double mult = module.getCurrentSpeed();

        // Find bottleneck source
        String sourceName = null;
        if (showSource.get()) {
            double minVal = 0.99; // Only show if actually throttling
            for (int i = 0; i < module.sourceCount(); i++) {
                double val = module.evaluateSource(i);
                if (val < minVal) {
                    minVal = val;
                    sourceName = module.sourceName(i);
                }
            }
        }

        String label = "System Speed: ";
        String value = String.format("%.0f%%", mult * 100);
        if (sourceName != null) value += " (" + sourceName + ")";
        
        double s     = scale.get();
        double padH  = 4 * s;
        double padV  = 2 * s;
        double lw    = renderer.textWidth(label, false, s);
        double vw    = renderer.textWidth(value, false, s);
        double lh    = renderer.textHeight(false, s);
        double totalW = lw + vw + padH * 2;
        double totalH = lh + padV * 2 + (showBar.get() ? 6 * s : 0);

        setSize(totalW, totalH);

        if (showBackground.get()) {
            renderer.quad(x, y, totalW, totalH, backgroundColor.get());
        }

        // Dynamic color logic: Green for full speed, shifting to Red as speed is throttled
        SettingColor col;
        if (mult > 0.8) col = new SettingColor(60, 255, 60, 255);      // Green
        else if (mult > 0.4) col = new SettingColor(255, 165, 0, 255); // Orange
        else col = new SettingColor(255, 60, 60, 255);                // Red

        double tx = switch (alignment.get()) {
            case Left   -> x + padH;
            case Right  -> x + totalW - padH - (lw + vw);
            case Center -> x + (totalW - (lw + vw)) / 2.0;
        };

        renderer.text(label, tx, y + padV, labelColor.get(), false, s);
        renderer.text(value, tx + lw, y + padV, col, false, s);

        if (showBar.get()) {
            double barW = totalW - padH * 2;
            double barY = y + lh + padV + 2 * s;
            double barX = x + padH;
            
            renderer.quad(barX, barY, barW, 3 * s, new Color(0, 0, 0, 100)); // Background
            renderer.quad(barX, barY, barW * mult, 3 * s, col); // Progress
        }
    }
}