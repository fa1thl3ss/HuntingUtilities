package com.example.addon.hud;

import com.example.addon.HuntingUtilities;
import com.example.addon.modules.LootLens;

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

public class LootLensHud extends HudElement {

    public static final HudElementInfo<LootLensHud> INFO = new HudElementInfo<>(
        HuntingUtilities.HUD_GROUP,
        "loot-lens-hud",
        "Shows nearby double chests, shulker boxes, and ender chests from Loot Lens.",
        LootLensHud::new
    );

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> showDoubleChests = sgGeneral.add(new BoolSetting.Builder()
        .name("show-chests")
        .description("Show the double chest count.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showShulkers = sgGeneral.add(new BoolSetting.Builder()
        .name("show-shulkers")
        .description("Show the shulker box count.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showEnderChests = sgGeneral.add(new BoolSetting.Builder()
        .name("show-ender-chests")
        .description("Show the ender chest count.")
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
        .description("Color of the | separators.")
        .defaultValue(new SettingColor(100, 100, 100, 255))
        .build()
    );

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

    public LootLensHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        LootLens module = Modules.get().get(LootLens.class);
        if (module == null || !module.isActive()) { setSize(0, 0); return; }

        double s = scale.get();

        // Build segments: each is [label, value], enabled segments get a separator between them
        java.util.List<String[]> segments = new java.util.ArrayList<>();
        if (showDoubleChests.get()) segments.add(new String[]{"Chests: ",       String.valueOf(module.getDoubleChestCount())});
        if (showShulkers.get())     segments.add(new String[]{"Shulkers: ",     String.valueOf(module.getShulkerBoxCount())});
        if (showEnderChests.get())  segments.add(new String[]{"Ender Chests: ", String.valueOf(module.getEnderChestCount())});

        if (segments.isEmpty()) { setSize(0, 0); return; }

        double padH       = 4 * s;
        double padV       = 2 * s;
        double lineHeight = renderer.textHeight(false, s);
        double sepW       = renderer.textWidth(" | ", false, s);

        // Measure total width
        double totalTextW = 0;
        for (int i = 0; i < segments.size(); i++) {
            totalTextW += renderer.textWidth(segments.get(i)[0], false, s);
            totalTextW += renderer.textWidth(segments.get(i)[1], false, s);
            if (i < segments.size() - 1) totalTextW += sepW;
        }

        double totalW = totalTextW + padH * 2;
        double totalH = lineHeight + padV * 2;

        if (showBackground.get()) {
            renderer.quad(x, y, totalW, totalH, backgroundColor.get());
        }

        double drawX = x + padH;
        double drawY = y + padV;

        for (int i = 0; i < segments.size(); i++) {
            String label = segments.get(i)[0];
            String value = segments.get(i)[1];

            renderer.text(label, drawX, drawY, labelColor.get(), false, s);
            drawX += renderer.textWidth(label, false, s);

            renderer.text(value, drawX, drawY, valueColor.get(), false, s);
            drawX += renderer.textWidth(value, false, s);

            if (i < segments.size() - 1) {
                renderer.text(" | ", drawX, drawY, separatorColor.get(), false, s);
                drawX += sepW;
            }
        }

        setSize(totalW, totalH);
    }
}