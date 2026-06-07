package com.example.addon.hud;

import com.example.addon.HuntingUtilities;
import com.example.addon.modules.Mobanom;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public class MobanomHUD extends HudElement {
    public static final HudElementInfo<MobanomHUD> INFO = new HudElementInfo<>(
        HuntingUtilities.HUD_GROUP,
        "mobanom",
        "Displays nearby anomalous mobs detected by Mobanom.",
        MobanomHUD::new
    );

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum Alignment { Left, Center, Right }

    // ── Setting Groups ────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgToggles = settings.createGroup("Toggles");

    // ── Settings ──────────────────────────────────────────────────────────────

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the HUD element.")
        .defaultValue(1.0).min(0.25).sliderRange(0.25, 4.0)
        .build()
    );

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Align text to the left, center, or right.")
        .defaultValue(Alignment.Left)
        .build()
    );

    private final Setting<Boolean> showDistance = sgToggles.add(new BoolSetting.Builder()
        .name("show-distance")
        .description("Show the distance to the anomalous mob.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showType = sgToggles.add(new BoolSetting.Builder()
        .name("show-type")
        .description("Show the type of anomaly.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxRows = sgGeneral.add(new IntSetting.Builder()
        .name("max-rows")
        .description("Maximum number of anomalies to list.")
        .defaultValue(10).min(1).sliderMax(30)
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

    private final Setting<Boolean> showIndicator = sgGeneral.add(new BoolSetting.Builder()
        .name("show-indicator")
        .description("Show a symbol when no anomalies are detected to see where the HUD is.")
        .defaultValue(true)
        .build()
    );

    private final Setting<String> indicatorSymbol = sgGeneral.add(new StringSetting.Builder()
        .name("indicator-symbol")
        .description("The symbol to display when no anomalies are detected.")
        .defaultValue("(empty)")
        .visible(showIndicator::get)
        .build()
    );

    // ── Constructor ───────────────────────────────────────────────────────────

    public MobanomHUD() {
        super(INFO);
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {
        Mobanom module = Modules.get().get(Mobanom.class);
        if (mc.player == null || mc.world == null || module == null || !module.isActive()) {
            if (isInEditor()) {
                double lh = renderer.textHeight(false, scale.get());
                setSize(renderer.textWidth("Mobanom HUD", false, scale.get()), lh);
                renderer.text("Mobanom HUD", x, y, labelColor.get(), false, scale.get());
            } else setSize(0, 0);
            return;
        }

        Map<Integer, Mobanom.AnomalyType> anomalies = module.getAnomalies();
        double s = scale.get(), padH = 4 * s, padV = 2 * s, rowGap = 2 * s, lh = renderer.textHeight(false, s);

        if (anomalies.isEmpty()) {
            if (showIndicator.get()) {
                String sym = indicatorSymbol.get();
                double w = renderer.textWidth(sym, false, s) + padH * 2;
                double h = lh + padV * 2;
                setSize(w, h);
                if (showBackground.get()) renderer.quad(x, y, w, h, backgroundColor.get());
                renderer.text(sym, x + padH, y + padV, labelColor.get(), false, s);
            } else setSize(0, 0);
            return;
        }

        Alignment align = alignment.get();

        record AnomalyRow(String name, String typeStr, String distStr, float distance) {}
        List<AnomalyRow> rows = new ArrayList<>();

        for (Map.Entry<Integer, Mobanom.AnomalyType> entry : anomalies.entrySet()) {
            Entity entity = mc.world.getEntityById(entry.getKey());
            if (entity == null) continue;
            rows.add(new AnomalyRow(entity.getType().getName().getString(), 
                showType.get() ? " [" + formatType(entry.getValue()) + "]" : "", 
                showDistance.get() ? String.format(" %.0fm", mc.player.distanceTo(entity)) : "", 
                mc.player.distanceTo(entity)));
        }

        rows.sort(Comparator.comparingDouble(r -> r.distance));
        if (rows.size() > maxRows.get()) rows = rows.subList(0, maxRows.get());

        double maxW = 0, totalH = rows.size() * lh + (rows.size() - 1) * rowGap + padV * 2;
        double[] rowWidths = new double[rows.size()];

        for (int i = 0; i < rows.size(); i++) {
            AnomalyRow r = rows.get(i);
            rowWidths[i] = renderer.textWidth(r.name + r.typeStr + r.distStr, false, s);
            maxW = Math.max(maxW, rowWidths[i]);
        }
        double totalW = maxW + padH * 2;

        for (int i = 0; i < rows.size(); i++) {
            AnomalyRow r = rows.get(i);
            double rowY = y + padV + i * (lh + rowGap);
            double rowW = rowWidths[i];

            if (showBackground.get()) {
                double bgW = rowW + padH * 2;
                double bx = switch (align) { case Right -> x + totalW - bgW; case Center -> x + (totalW - bgW) / 2.0; default -> x; };
                renderer.quad(bx, rowY - 1, bgW, lh + 2, backgroundColor.get());
            }
            drawRow(renderer, s, align, totalW, padH, rowY, r.name, r.typeStr, r.distStr, valueColor.get(), labelColor.get());
        }
        setSize(totalW, totalH);
    }

    private void drawRow(HudRenderer renderer, double s, Alignment align, double totalW, double padH, double rowY, String name, String type, String dist, SettingColor nameCol, SettingColor metaCol) {
        double tx = (align == Alignment.Right) ? x + totalW - padH - renderer.textWidth(name + type + dist, false, s) : 
                    (align == Alignment.Center) ? x + (totalW - renderer.textWidth(name + type + dist, false, s)) / 2.0 : x + padH;
        
        renderer.text(name, tx, rowY, nameCol, false, s); tx += renderer.textWidth(name, false, s);
        renderer.text(type, tx, rowY, metaCol, false, s); tx += renderer.textWidth(type, false, s);
        renderer.text(dist, tx, rowY, metaCol, false, s);
    }

    private String formatType(Mobanom.AnomalyType type) {
        return switch (type) {
            case DIMENSION_NETHER -> "Nether"; case DIMENSION_END -> "End"; case DIMENSION_OVERWORLD -> "Overworld";
            case ITEM -> "Item"; case CHESTED -> "Chested";
        };
    }
}