package com.example.addon.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;

/**
 * Info Assistant HUD
 *
 * Dynamically reads ALL loaded modules at render time — both Meteor Client
 * built-ins and every Hunting Utilities module — and displays only the ones
 * that have a keybind assigned by the player.
 *
 * Includes a KeybindSetting that lets the player press a key to instantly
 * show or hide the entire element without opening the HUD editor.
 */
public class InfoAssistantHud extends HudElement {

    // ── Registration ──────────────────────────────────────────────────────────────

    public static final HudElementInfo<InfoAssistantHud> INFO = new HudElementInfo<>(
        HuntingUtilities.HUD_GROUP,
        "info-assistant",
        "Lists every module that has a keybind, grouped by category.",
        InfoAssistantHud::new
    );

    // ── Settings ──────────────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Keybind> toggleKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("toggle-key")
        .description("Press this key to show or hide Info Assistant.")
        .defaultValue(Keybind.none())
        .action(this::onToggleKey)
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

    private final Setting<Boolean> showTitle = sgGeneral.add(new BoolSetting.Builder()
        .name("show-title")
        .description("Show the 'Info Assistant' header line.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showCategories = sgGeneral.add(new BoolSetting.Builder()
        .name("show-categories")
        .description("Show category group headers between module entries.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showDescriptions = sgGeneral.add(new BoolSetting.Builder()
        .name("show-descriptions")
        .description("Show the module description column.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> huOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("hu-modules-only")
        .description("Only list Hunting Utilities modules; hide Meteor built-ins.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> showBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Draw a background panel behind the element.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .defaultValue(new SettingColor(0, 0, 0, 100))
        .visible(showBackground::get)
        .build()
    );

    // ── Colours ───────────────────────────────────────────────────────────────────

    private final Setting<SettingColor> titleColor = sgGeneral.add(new ColorSetting.Builder()
        .name("title-color")
        .description("Color of the 'Info Assistant' header.")
        .defaultValue(new SettingColor(255, 200, 50, 255))
        .build()
    );

    private final Setting<SettingColor> categoryColor = sgGeneral.add(new ColorSetting.Builder()
        .name("category-color")
        .description("Color of the category group headers.")
        .defaultValue(new SettingColor(140, 140, 140, 255))
        .visible(showCategories::get)
        .build()
    );

    private final Setting<SettingColor> moduleColor = sgGeneral.add(new ColorSetting.Builder()
        .name("module-color")
        .description("Color of the module name column.")
        .defaultValue(new SettingColor(210, 210, 210, 255))
        .build()
    );

    private final Setting<SettingColor> kbTagColor = sgGeneral.add(new ColorSetting.Builder()
        .name("kb-tag-color")
        .description("Color of the (KB) label.")
        .defaultValue(new SettingColor(120, 180, 255, 255))
        .build()
    );

    private final Setting<SettingColor> keyColor = sgGeneral.add(new ColorSetting.Builder()
        .name("key-color")
        .description("Color of the key name text.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> separatorColor = sgGeneral.add(new ColorSetting.Builder()
        .name("separator-color")
        .description("Color of the | separator between key and description.")
        .defaultValue(new SettingColor(70, 70, 70, 255))
        .build()
    );

    private final Setting<SettingColor> descriptionColor = sgGeneral.add(new ColorSetting.Builder()
        .name("description-color")
        .description("Color of the module description text.")
        .defaultValue(new SettingColor(155, 155, 155, 255))
        .build()
    );

    // ── Alignment ─────────────────────────────────────────────────────────────────

    public enum Alignment { Left, Right }

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Align the element text left or right.")
        .defaultValue(Alignment.Left)
        .build()
    );

    // ── Internal row model ────────────────────────────────────────────────────────

    private record Row(boolean isHeader, String label, String key, String description) {}

    // ── Visibility state ──────────────────────────────────────────────────────────

    /** Tracks whether the element is currently visible (toggled by the keybind). */
    private boolean visible = true;

    // ── Constructor ───────────────────────────────────────────────────────────────

    public InfoAssistantHud() {
        super(INFO);
    }

    // ── Toggle action ─────────────────────────────────────────────────────────────

    /** Called by the KeybindSetting whenever the bound key is pressed. */
    private void onToggleKey() {
        visible = !visible;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {

        // Respect the toggle — render nothing when hidden
        if (!visible) {
            setSize(0, 0);
            return;
        }

        List<Row> rows = buildRows();

        if (rows.isEmpty() && !showTitle.get()) {
            setSize(0, 0);
            return;
        }

        // ── Layout constants ──────────────────────────────────────────────────────

        final double s      = scale.get();
        final double padH   = 6 * s;
        final double padV   = 4 * s;
        final double rowGap = 2 * s;
        final double colGap = 8 * s;
        final double lh     = renderer.textHeight(false, s);
        final boolean right = alignment.get() == Alignment.Right;

        // ── Measure columns ───────────────────────────────────────────────────────

        double colAW   = 0;
        double colBW   = 0;
        double colCW   = 0;
        double catMaxW = 0;

        for (Row r : rows) {
            if (r.isHeader()) {
                catMaxW = Math.max(catMaxW, renderer.textWidth(r.label(), false, s));
            } else {
                colAW = Math.max(colAW, renderer.textWidth(r.label(), false, s));
                colBW = Math.max(colBW, renderer.textWidth("(KB) " + r.key(), false, s));
                if (showDescriptions.get())
                    colCW = Math.max(colCW, renderer.textWidth(r.description(), false, s));
            }
        }

        double sepW   = renderer.textWidth(" | ", false, s);
        double titleW = showTitle.get() ? renderer.textWidth("Info Assistant", false, s) : 0;

        double contentW = colAW + colGap + colBW + (showDescriptions.get() ? sepW + colCW : 0);
        double innerW   = Math.max(Math.max(contentW, titleW), catMaxW);
        double totalW   = innerW + padH * 2;

        int    totalRowCount = rows.size() + (showTitle.get() ? 1 : 0);
        double totalH        = totalRowCount * lh + Math.max(0, totalRowCount - 1) * rowGap + padV * 2;

        // ── Background ────────────────────────────────────────────────────────────

        if (showBackground.get())
            renderer.quad(x, y, totalW, totalH, backgroundColor.get());

        // ── Draw ──────────────────────────────────────────────────────────────────

        int idx = 0;

        if (showTitle.get()) {
            double rowY = rowY(idx, y, padV, lh, rowGap);
            double tw   = renderer.textWidth("Info Assistant", false, s);
            double tx   = right ? x + totalW - padH - tw : x + padH;
            renderer.text("Info Assistant", tx, rowY, titleColor.get(), false, s);
            idx++;
        }

        for (Row row : rows) {
            double rowY = rowY(idx, y, padV, lh, rowGap);

            if (row.isHeader()) {
                double hw = renderer.textWidth(row.label(), false, s);
                double hx = right ? x + totalW - padH - hw : x + padH;
                renderer.text(row.label(), hx, rowY, categoryColor.get(), false, s);

            } else {
                String kbTag   = "(KB) ";
                double kbTagW  = renderer.textWidth(kbTag, false, s);
                double keyValW = renderer.textWidth(row.key(), false, s);
                double nameW   = renderer.textWidth(row.label(), false, s);

                if (right) {
                    double cx = x + totalW - padH;

                    if (showDescriptions.get()) {
                        double dw = renderer.textWidth(row.description(), false, s);
                        cx -= dw;
                        renderer.text(row.description(), cx, rowY, descriptionColor.get(), false, s);
                        cx -= sepW;
                        renderer.text(" | ", cx, rowY, separatorColor.get(), false, s);
                    }

                    cx -= keyValW;
                    renderer.text(row.key(), cx, rowY, keyColor.get(), false, s);
                    cx -= kbTagW;
                    renderer.text(kbTag, cx, rowY, kbTagColor.get(), false, s);
                    cx -= colGap + nameW;
                    renderer.text(row.label(), cx, rowY, moduleColor.get(), false, s);

                } else {
                    renderer.text(row.label(), x + padH, rowY, moduleColor.get(), false, s);

                    double bx = x + padH + colAW + colGap;
                    renderer.text(kbTag, bx, rowY, kbTagColor.get(), false, s);
                    renderer.text(row.key(), bx + kbTagW, rowY, keyColor.get(), false, s);

                    if (showDescriptions.get()) {
                        double sepX = x + padH + colAW + colGap + colBW;
                        renderer.text(" | ", sepX, rowY, separatorColor.get(), false, s);
                        renderer.text(row.description(), sepX + sepW, rowY, descriptionColor.get(), false, s);
                    }
                }
            }

            idx++;
        }

        setSize(totalW, totalH);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private List<Row> buildRows() {
        List<Row> rows = new ArrayList<>();

        List<Module> allModules = new ArrayList<>(Modules.get().getAll());

        allModules.sort(Comparator
            .comparingInt((Module m) -> m.category == HuntingUtilities.CATEGORY ? 0 : 1)
            .thenComparing(m -> m.category.name)
            .thenComparing(m -> m.title)
        );

        String lastCategory = null;

        for (Module module : allModules) {
            if (huOnly.get() && module.category != HuntingUtilities.CATEGORY) continue;
            if (!module.keybind.isSet()) continue;

            String categoryName = module.category.name;

            if (showCategories.get() && !categoryName.equals(lastCategory)) {
                rows.add(new Row(true, "[" + categoryName + "]", null, null));
                lastCategory = categoryName;
            }

            String keyName = module.keybind.toString().toUpperCase();

            String desc = (module.description != null) ? module.description : "";
            if (desc.length() > 55) desc = desc.substring(0, 52) + "...";

            rows.add(new Row(false, module.title, keyName, desc));
        }

        return rows;
    }

    private static double rowY(int idx, double baseY, double padV, double lh, double rowGap) {
        return baseY + padV + idx * (lh + rowGap);
    }
}