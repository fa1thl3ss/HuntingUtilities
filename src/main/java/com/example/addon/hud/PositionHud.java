package com.example.addon.hud;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

public class PositionHud extends HudElement {

    public static final HudElementInfo<PositionHud> INFO = new HudElementInfo<>(
        HuntingUtilities.HUD_GROUP,
        "position",
        "Displays your current coordinates and their Nether equivalents.",
        PositionHud::new
    );

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

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

    private final Setting<SettingColor> netherLabelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("nether-label-color")
        .description("Color of the Nether coordinate labels.")
        .defaultValue(new SettingColor(200, 80, 80, 255))
        .build()
    );

    private final Setting<Boolean> showNether = sgGeneral.add(new BoolSetting.Builder()
        .name("show-nether-coords")
        .description("Show the Nether equivalent on a second line.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Show a per-line background highlight.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .defaultValue(new SettingColor(0, 0, 0, 80))
        .visible(showBackground::get)
        .build()
    );

    public PositionHud() {
        super(INFO);
    }

    @Override
    public void render(HudRenderer renderer) {
        if (mc.player == null) { setSize(0, 0); return; }

        double s = scale.get();

        double padH       = 4 * s;
        double padV       = 2 * s;
        double rowGap     = 2 * s;
        double lineHeight = renderer.textHeight(false, s);
        double sepW       = renderer.textWidth(" | ", false, s);

        BlockPos pos = mc.player.getBlockPos();
        int bx = pos.getX(), by = pos.getY(), bz = pos.getZ();

        // ── Determine Nether coords ───────────────────────────────────────────
        // If in Overworld → divide by 8 for Nether equivalent
        // If in Nether    → multiply by 8 for Overworld equivalent
        boolean inNether = mc.world != null
            && mc.world.getRegistryKey() == World.NETHER;
        boolean inEnd = mc.world != null
            && mc.world.getRegistryKey() == World.END;

        int nx, nz;
        String netherLineLabel;
        if (inNether) {
            nx = bx * 8; nz = bz * 8;
            netherLineLabel = "OW: ";
        } else {
            nx = bx / 8; nz = bz / 8;
            netherLineLabel = "Nether: ";
        }

        // ── Build line 1: current coords ──────────────────────────────────────
        // Format: X: 100 | Y: 64 | Z: -200
        String xLabel = "X: ", xVal = String.valueOf(bx);
        String yLabel = "Y: ", yVal = String.valueOf(by);
        String zLabel = "Z: ", zVal = String.valueOf(bz);

        double line1W = renderer.textWidth(xLabel, false, s) + renderer.textWidth(xVal, false, s)
                      + sepW
                      + renderer.textWidth(yLabel, false, s) + renderer.textWidth(yVal, false, s)
                      + sepW
                      + renderer.textWidth(zLabel, false, s) + renderer.textWidth(zVal, false, s);

        // ── Build line 2: nether/overworld coords ─────────────────────────────
        // Format: Nether: X: 12 | Z: -25   (Y not shown — same level)
        String nxLabel = "X: ", nxVal = String.valueOf(nx);
        String nzLabel = "Z: ", nzVal = String.valueOf(nz);

        double line2TextW = renderer.textWidth(netherLineLabel, false, s)
                          + renderer.textWidth(nxLabel, false, s) + renderer.textWidth(nxVal, false, s)
                          + sepW
                          + renderer.textWidth(nzLabel, false, s) + renderer.textWidth(nzVal, false, s);

        boolean hasLine2 = showNether.get() && !inEnd;

        double maxW   = hasLine2 ? Math.max(line1W, line2TextW) : line1W;
        double totalW = maxW + padH * 2;
        int lineCount = hasLine2 ? 2 : 1;
        double totalH = lineCount * lineHeight + (lineCount - 1) * rowGap + padV * 2;

        // ── Draw line 1 ───────────────────────────────────────────────────────
        double line1BoxW = line1W + padH * 2;
        double rowY1 = y + padV;
        if (showBackground.get())
            renderer.quad(x, rowY1 - 1, line1BoxW, lineHeight + 2, backgroundColor.get());

        double cx = x + padH;
        renderer.text(xLabel, cx, rowY1, labelColor.get(),     false, s); cx += renderer.textWidth(xLabel, false, s);
        renderer.text(xVal,   cx, rowY1, valueColor.get(),     false, s); cx += renderer.textWidth(xVal,   false, s);
        renderer.text(" | ",  cx, rowY1, separatorColor.get(), false, s); cx += sepW;
        renderer.text(yLabel, cx, rowY1, labelColor.get(),     false, s); cx += renderer.textWidth(yLabel, false, s);
        renderer.text(yVal,   cx, rowY1, valueColor.get(),     false, s); cx += renderer.textWidth(yVal,   false, s);
        renderer.text(" | ",  cx, rowY1, separatorColor.get(), false, s); cx += sepW;
        renderer.text(zLabel, cx, rowY1, labelColor.get(),     false, s); cx += renderer.textWidth(zLabel, false, s);
        renderer.text(zVal,   cx, rowY1, valueColor.get(),     false, s);

        // ── Draw line 2 ───────────────────────────────────────────────────────
        if (hasLine2) {
            double line2BoxW = line2TextW + padH * 2;
            double rowY2 = y + padV + lineHeight + rowGap;
            if (showBackground.get())
                renderer.quad(x, rowY2 - 1, line2BoxW, lineHeight + 2, backgroundColor.get());

            cx = x + padH;
            renderer.text(netherLineLabel, cx, rowY2, netherLabelColor.get(), false, s); cx += renderer.textWidth(netherLineLabel, false, s);
            renderer.text(nxLabel, cx, rowY2, labelColor.get(),     false, s); cx += renderer.textWidth(nxLabel, false, s);
            renderer.text(nxVal,   cx, rowY2, valueColor.get(),     false, s); cx += renderer.textWidth(nxVal,   false, s);
            renderer.text(" | ",   cx, rowY2, separatorColor.get(), false, s); cx += sepW;
            renderer.text(nzLabel, cx, rowY2, labelColor.get(),     false, s); cx += renderer.textWidth(nzLabel, false, s);
            renderer.text(nzVal,   cx, rowY2, valueColor.get(),     false, s);
        }

        setSize(totalW, totalH);
    }
}