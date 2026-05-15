package com.example.addon.hud;

import com.example.addon.HuntingUtilities;
import com.example.addon.modules.PearlPulse;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;

public class PearlCounterHud extends HudElement {

    // ═══════════════════════════════════════════════════════════════════════════
    // Registration
    // ═══════════════════════════════════════════════════════════════════════════

    public static final HudElementInfo<PearlCounterHud> INFO =
        new HudElementInfo<>(HuntingUtilities.HUD_GROUP, "pearl-counter",
            "Shows how many stasis pearls PearlPulse detects within range.",
            PearlCounterHud::new);

    // ═══════════════════════════════════════════════════════════════════════════
    // Setting Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColors  = settings.createGroup("Colors");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — General
    // ═══════════════════════════════════════════════════════════════════════════

    public enum LabelStyle { MINIMAL, COMPACT, VERBOSE }

    private final Setting<LabelStyle> labelStyle = sgGeneral.add(new EnumSetting.Builder<LabelStyle>()
        .name("label-style")
        .description("How much text to show around the count.\n" +
                     "  MINIMAL  →  3\n" +
                     "  COMPACT  →  Pearls: 3\n" +
                     "  VERBOSE  →  Stasis Pearls: 3 / 64b")
        .defaultValue(LabelStyle.COMPACT)
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

    private final Setting<Boolean> showWhenZero = sgGeneral.add(new BoolSetting.Builder()
        .name("show-when-zero")
        .description("Keep the HUD element visible even when no pearls are detected.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> requireModuleActive = sgGeneral.add(new BoolSetting.Builder()
        .name("require-module-active")
        .description("Hide the counter if the PearlPulse module is not currently enabled.")
        .defaultValue(true)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Colors
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<SettingColor> labelColor = sgColors.add(new ColorSetting.Builder()
        .name("label-color")
        .description("Color of the static label text.")
        .defaultValue(new SettingColor(170, 170, 170, 255))
        .build()
    );

    private final Setting<SettingColor> countColorNone = sgColors.add(new ColorSetting.Builder()
        .name("count-color-zero")
        .description("Count color when no pearls are detected.")
        .defaultValue(new SettingColor(120, 120, 120, 200))
        .build()
    );

    private final Setting<SettingColor> countColorSome = sgColors.add(new ColorSetting.Builder()
        .name("count-color-active")
        .description("Count color when one or more pearls are detected.")
        .defaultValue(new SettingColor(0, 210, 255, 255))
        .build()
    );

    private final Setting<Boolean> showBackground = sgColors.add(new BoolSetting.Builder()
        .name("background")
        .description("Draw a translucent background behind the counter.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgColors.add(new ColorSetting.Builder()
        .name("background-color")
        .description("Background fill color.")
        .defaultValue(new SettingColor(0, 0, 0, 80))
        .visible(showBackground::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════════

    public PearlCounterHud() {
        super(INFO);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Rendering
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void render(HudRenderer renderer) {
        MinecraftClient mc = MinecraftClient.getInstance();

        // Optionally hide when PearlPulse is disabled.
        PearlPulse module = Modules.get().get(PearlPulse.class);
        if (requireModuleActive.get() && (module == null || !module.isActive())) {
            setSize(0, 0);
            return;
        }

        // Use the public accessor so we don't touch the private Setting field.
        int detectionRange = (module != null) ? module.getRange() : 64;
        int count = 0;

        if (mc.world != null && mc.player != null) {
            for (Entity e : mc.world.getEntities()) {
                if (e.getType() != EntityType.ENDER_PEARL) continue;
                if (mc.player.distanceTo(e) <= detectionRange) count++;
            }
        }

        // Optionally hide when count is zero.
        if (count == 0 && !showWhenZero.get()) {
            setSize(0, 0);
            return;
        }

        // Build display strings.
        double s = scale.get();
        String label;
        String countStr;

        switch (labelStyle.get()) {
            case MINIMAL -> {
                label    = "";
                countStr = String.valueOf(count);
            }
            case VERBOSE -> {
                label    = "Stasis Pearls: ";
                countStr = count + " / " + detectionRange + "b";
            }
            default -> {           // COMPACT
                label    = "Pearls: ";
                countStr = String.valueOf(count);
            }
        }

        // Measure and size the element (matching PortalTrackerHud padding style).
        double padH   = 4 * s;
        double padV   = 2 * s;
        double textW  = renderer.textWidth(label + countStr, false, s);
        double textH  = renderer.textHeight(false, s);
        double totalW = textW + padH * 2;
        double totalH = textH + padV * 2;

        // Background.
        if (showBackground.get()) {
            renderer.quad(x, y, totalW, totalH, backgroundColor.get());
        }

        // Draw text — split label and count to color them independently.
        Color  countColor = (count > 0) ? countColorSome.get() : countColorNone.get();
        double cx         = x + padH;
        double rowY       = y + padV;

        if (label.isEmpty()) {
            renderer.text(countStr, cx, rowY, countColor, false, s);
        } else {
            renderer.text(label,    cx, rowY, labelColor.get(), false, s);
            cx += renderer.textWidth(label, false, s);
            renderer.text(countStr, cx, rowY, countColor,       false, s);
        }

        setSize(totalW, totalH);
    }
}