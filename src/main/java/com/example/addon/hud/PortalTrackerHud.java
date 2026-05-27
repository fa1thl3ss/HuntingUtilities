package com.example.addon.hud;

import com.example.addon.HuntingUtilities;
import com.example.addon.modules.PortalTracker;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

public class PortalTrackerHud extends HudElement {

    public static final HudElementInfo<PortalTrackerHud> INFO = new HudElementInfo<>(
        HuntingUtilities.HUD_GROUP,
        "portal-tracker",
        "Displays portals in the area and total portals created this session.",
        PortalTrackerHud::new
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // ── Scale ────────────────────────────────────────────────────────────────

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the HUD element.")
        .defaultValue(1.0)
        .min(0.25)
        .sliderRange(0.25, 4.0)
        .build()
    );

    // ── Field visibility ─────────────────────────────────────────────────────

    private final Setting<Boolean> showPortalsInArea = sgGeneral.add(new BoolSetting.Builder()
        .name("show-portals-in-area")
        .description("Show the portals in area count.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showPortalsCreated = sgGeneral.add(new BoolSetting.Builder()
        .name("show-portals-created")
        .description("Show the total portals created this session.")
        .defaultValue(true)
        .build()
    );

    // ── Colors ───────────────────────────────────────────────────────────────

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
        .description("Color of the | separator.")
        .defaultValue(new SettingColor(100, 100, 100, 255))
        .build()
    );

    // ── Background ───────────────────────────────────────────────────────────

    private final Setting<Boolean> showBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Show a background highlight behind the text.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .defaultValue(new SettingColor(0, 0, 0, 80))
        .visible(showBackground::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════

    public PortalTrackerHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        PortalTracker tracker = Modules.get().get(PortalTracker.class);

        if (tracker == null || !tracker.isActive()) {
            setSize(0, 0);
            return;
        }

        boolean showArea    = showPortalsInArea.get();
        boolean showCreated = showPortalsCreated.get();

        // Nothing to render
        if (!showArea && !showCreated) {
            setSize(0, 0);
            return;
        }

        boolean showSep = showArea && showCreated;

        double s = scale.get();

        double padH       = 4 * s;
        double padV       = 2 * s;
        double lineHeight = renderer.textHeight(false, s);
        double sepW       = renderer.textWidth(" | ", false, s);

        String foundLabel   = "Portals in Area: ";
        String foundValue   = String.valueOf(tracker.getTotalPortals());
        String createdLabel = "Portals Created: ";
        String createdValue = String.valueOf(tracker.getTotalCreated());

        double totalTextW = 0;
        if (showArea)    totalTextW += renderer.textWidth(foundLabel,   false, s)
                                     + renderer.textWidth(foundValue,   false, s);
        if (showSep)     totalTextW += sepW;
        if (showCreated) totalTextW += renderer.textWidth(createdLabel, false, s)
                                     + renderer.textWidth(createdValue, false, s);

        double totalW = totalTextW + padH * 2;
        double totalH = lineHeight + padV * 2;

        if (showBackground.get())
            renderer.quad(x, y, totalW, totalH, backgroundColor.get());

        double cx   = x + padH;
        double rowY = y + padV;

        if (showArea) {
            renderer.text(foundLabel, cx, rowY, labelColor.get(), false, s);
            cx += renderer.textWidth(foundLabel, false, s);
            renderer.text(foundValue, cx, rowY, valueColor.get(), false, s);
            cx += renderer.textWidth(foundValue, false, s);
        }

        if (showSep) {
            renderer.text(" | ", cx, rowY, separatorColor.get(), false, s);
            cx += sepW;
        }

        if (showCreated) {
            renderer.text(createdLabel, cx, rowY, labelColor.get(), false, s);
            cx += renderer.textWidth(createdLabel, false, s);
            renderer.text(createdValue, cx, rowY, valueColor.get(), false, s);
        }

        setSize(totalW, totalH);
    }
}