package com.example.addon.hud;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.HuntingUtilities;
import com.example.addon.modules.InspectorGadget;

import meteordevelopment.meteorclient.events.world.TickEvent;
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
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class InspectorGadgetHud extends HudElement {

    public static final HudElementInfo<InspectorGadgetHud> INFO = new HudElementInfo<>(
        HuntingUtilities.HUD_GROUP,
        "inspector-gadget",
        "Displays storage scanning statistics for Inspector Gadget.",
        InspectorGadgetHud::new
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════════════════════════════════

    public enum Layout { Inline, Stacked, StackedIcons }
    public enum Alignment { Left, Center, Right }
    public enum LabelMode { Text, Icon, Both }
    public enum IconPosition { Left, Right, Above, Below }

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgGeneral  = settings.getDefaultGroup();
    private final SettingGroup sgToggles = settings.createGroup("Toggles");

    // ── Layout ────────────────────────────────────────────────────────────────

    private final Setting<Layout> layout = sgGeneral.add(new EnumSetting.Builder<Layout>()
        .name("layout")
        .description("How the data is presented.")
        .defaultValue(Layout.StackedIcons)
        .build()
    );

    private final Setting<SettingColor> separatorColor = sgGeneral.add(new ColorSetting.Builder()
        .name("separator-color")
        .description("Color of the | separators.")
        .defaultValue(new SettingColor(100, 100, 100, 255))
        .visible(() -> layout.get() == Layout.Inline)
        .build()
    );

    // ── Visual Settings ───────────────────────────────────────────────────────

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

    private final Setting<LabelMode> labelMode = sgGeneral.add(new EnumSetting.Builder<LabelMode>()
        .name("label-mode")
        .description("Show the item label as text, icon, or both.")
        .defaultValue(LabelMode.Both)
        .visible(() -> layout.get() == Layout.StackedIcons || layout.get() == Layout.Inline)
        .build()
    );

    private final Setting<IconPosition> iconPosition = sgGeneral.add(new EnumSetting.Builder<IconPosition>()
        .name("icon-position")
        .description("Where the item icon appears relative to the text.")
        .defaultValue(IconPosition.Left)
        .visible(() -> (layout.get() == Layout.StackedIcons || layout.get() == Layout.Inline) && labelMode.get() != LabelMode.Text)
        .build()
    );

    private final Setting<Double> iconScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-scale")
        .description("Scale of the item icons.")
        .defaultValue(1.5).min(0.5).sliderRange(0.5, 4.0)
        .visible(() -> (layout.get() == Layout.StackedIcons || layout.get() == Layout.Inline) && labelMode.get() != LabelMode.Text)
        .build()
    );

    private final Setting<Double> iconGapSetting = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-gap")
        .description("Gap in pixels between the icon and the text.")
        .defaultValue(4.0).min(0).sliderRange(0, 16)
        .visible(() -> (layout.get() == Layout.StackedIcons || layout.get() == Layout.Inline) && labelMode.get() != LabelMode.Text)
        .build()
    );

    private final Setting<SettingColor> labelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("label-color")
        .description("Color for labels.")
        .defaultValue(new SettingColor(170, 170, 170, 255))
        .build()
    );

    private final Setting<Boolean> showBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Show a background behind the element.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .defaultValue(new SettingColor(0, 0, 0, 80))
        .visible(showBackground::get)
        .build()
    );

    private final Setting<Keybind> clearStatsKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("clear-stats-key")
        .description("Keybind to clear the session statistics.")
        .defaultValue(Keybind.none())
        .build()
    );

    // ── Toggles ───────────────────────────────────────────────────────────────

    private final Setting<Boolean> showOpened = sgToggles.add(new BoolSetting.Builder()
        .name("show-opened")
        .description("Show total opened storage count.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hideOpenedIfZero = sgToggles.add(new BoolSetting.Builder()
        .name("hide-opened-if-zero")
        .description("Hide opened count if it is 0.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> openedColor = sgToggles.add(new ColorSetting.Builder()
        .name("opened-color")
        .description("Color for opened storage count.")
        .defaultValue(new SettingColor(100, 255, 100, 255))
        .visible(showOpened::get)
        .build()
    );

    private final Setting<Boolean> showNearby = sgToggles.add(new BoolSetting.Builder()
        .name("show-nearby")
        .description("Show nearby storage count.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hideNearbyIfZero = sgToggles.add(new BoolSetting.Builder()
        .name("hide-nearby-if-zero")
        .description("Hide nearby count if it is 0.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> nearbyColor = sgToggles.add(new ColorSetting.Builder()
        .name("nearby-color")
        .description("Color for nearby storage count.")
        .defaultValue(new SettingColor(255, 255, 100, 255))
        .visible(showNearby::get)
        .build()
    );

    private final Setting<Boolean> showShulkers = sgToggles.add(new BoolSetting.Builder()
        .name("show-shulkers")
        .description("Show total shulkers found count.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> hideShulkersIfZero = sgToggles.add(new BoolSetting.Builder()
        .name("hide-shulkers-if-zero")
        .description("Hide shulkers found count if it is 0.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> shulkerColor = sgToggles.add(new ColorSetting.Builder()
        .name("shulker-color")
        .description("Color for shulkers found count.")
        .defaultValue(new SettingColor(200, 100, 255, 255))
        .visible(showShulkers::get)
        .build()
    );

    // ── State ──

    private boolean wasClearPressed = false;
    
    private final ItemStack openedIcon = new ItemStack(Items.CHEST);
    private final ItemStack nearbyIcon = new ItemStack(Items.BARREL);
    private final ItemStack shulkerIcon = new ItemStack(Items.SHULKER_BOX);

    private record Stat(String label, String value, ItemStack icon, SettingColor valColor) {}

    public InspectorGadgetHud() {
        super(INFO);
    }

    // ── Clear Stats Logic ─────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        boolean isPressed = clearStatsKey.get().isPressed();
        if (isPressed && !wasClearPressed) {
            InspectorGadget module = Modules.get().get(InspectorGadget.class);
            if (module.isActive()) {
                module.resetStats();
            }
        }
        wasClearPressed = isPressed;
    }

    // ── Render Entry Point ────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {
        InspectorGadget module = Modules.get().get(InspectorGadget.class);
        if (!module.isActive() && !isInEditor()) {
            setSize(0, 0);
            return;
        }

        if (layout.get() == Layout.Inline) {
            renderInline(renderer, module);
        } else {
            renderStacked(renderer, module, layout.get() == Layout.StackedIcons);
        }
    }

    // ── Inline Render ─────────────────────────────────────────────────────────

    private void renderInline(HudRenderer renderer, InspectorGadget module) {
        double s = scale.get(), padH = 4 * s, padV = 2 * s, lh = renderer.textHeight(false, s);
        double sepW = renderer.textWidth(" | ", false, s);
        double iconSz = 16.0 * iconScale.get(), iconGap = iconGapSetting.get() * s;
        LabelMode mode = labelMode.get(); 
        boolean showIcon = mode != LabelMode.Text, showLabel = mode != LabelMode.Icon;
        IconPosition iconPos = iconPosition.get(); 
        double effIconGap = showIcon ? iconGap : 0;

        List<Stat> segments = new ArrayList<>();

        int openedCount = module.getOpenedCount();
        if (showOpened.get() && (!hideOpenedIfZero.get() || openedCount > 0 || isInEditor())) {
            segments.add(new Stat("Opened: ", String.valueOf(openedCount), openedIcon, openedColor.get()));
        }

        int nearbyCount = module.getNearbyCount();
        if (showNearby.get() && (!hideNearbyIfZero.get() || nearbyCount > 0 || isInEditor())) {
            segments.add(new Stat("Nearby: ", String.valueOf(nearbyCount), nearbyIcon, nearbyColor.get()));
        }

        int shulkerCount = module.getShulkerCount();
        if (showShulkers.get() && (!hideShulkersIfZero.get() || shulkerCount > 0 || isInEditor())) {
            segments.add(new Stat("Shulkers: ", String.valueOf(shulkerCount), shulkerIcon, shulkerColor.get()));
        }

        if (segments.isEmpty()) { setSize(0, 0); return; }

        double totalW = 0, rowH = showIcon ? Math.max(lh, iconSz) : lh;
        for (int i = 0; i < segments.size(); i++) {
            Stat st = segments.get(i);
            double segW = 0;
            if (showLabel) segW += renderer.textWidth(st.label, false, s);
            segW += renderer.textWidth(st.value, false, s);
            if (showIcon) segW += iconSz + effIconGap;
            totalW += segW;
            if (i < segments.size() - 1) totalW += sepW;
        }
        setSize(totalW + padH * 2, rowH + padV * 2);

        if (showBackground.get()) renderer.quad(x, y, getWidth(), getHeight(), backgroundColor.get());

        double cx = x + padH, rowY = y + padV;
        for (int i = 0; i < segments.size(); i++) {
            Stat st = segments.get(i);
            if (showIcon && iconPos == IconPosition.Left) {
                renderer.item(st.icon, (int) cx, (int) (rowY + (rowH - iconSz) / 2.0), iconScale.get().floatValue(), false);
                cx += iconSz + effIconGap;
            }
            if (showLabel) {
                renderer.text(st.label, cx, rowY + (rowH - lh) / 2.0, labelColor.get(), false, s);
                cx += renderer.textWidth(st.label, false, s);
            }
            renderer.text(st.value, cx, rowY + (rowH - lh) / 2.0, st.valColor, false, s);
            cx += renderer.textWidth(st.value, false, s);

            if (showIcon && iconPos != IconPosition.Left) {
                cx += effIconGap;
                renderer.item(st.icon, (int) cx, (int) (rowY + (rowH - iconSz) / 2.0), iconScale.get().floatValue(), false);
                cx += iconSz;
            }
            if (i < segments.size() - 1) {
                renderer.text(" | ", cx, rowY + (rowH - lh) / 2.0, separatorColor.get(), false, s);
                cx += sepW;
            }
        }
    }

    // ── Stacked Render ────────────────────────────────────────────────────────

    private void renderStacked(HudRenderer renderer, InspectorGadget module, boolean withIcons) {
        double s           = scale.get();
        double padH        = 4 * s;
        double padV        = 2 * s;
        double rowGap      = 2 * s;
        double lineHeight  = renderer.textHeight(false, s);
        double iconSz      = 16.0 * iconScale.get();
        double iconGap     = iconGapSetting.get() * s;

        LabelMode    mode         = withIcons ? labelMode.get() : LabelMode.Text;
        IconPosition iconPos      = withIcons ? iconPosition.get() : IconPosition.Left;
        boolean      showIcon     = withIcons && mode != LabelMode.Text;
        boolean      showText     = mode != LabelMode.Icon;
        boolean      iconVertical = showIcon && (iconPos == IconPosition.Above || iconPos == IconPosition.Below);

        double statRowH = !showIcon ? lineHeight
            : iconVertical ? iconSz + iconGap + lineHeight
            : Math.max(lineHeight, iconSz);

        // ── Gather Data ───────────────────────────────────────────────────────

        String openedLabel = null, openedValue = null;
        int openedCount = module.getOpenedCount();
        if (showOpened.get() && (!hideOpenedIfZero.get() || openedCount > 0 || isInEditor())) {
            openedLabel = showText ? "Opened: " : "";
            openedValue = String.valueOf(openedCount);
        }

        String nearbyLabel = null, nearbyValue = null;
        int nearbyCount = module.getNearbyCount();
        if (showNearby.get() && (!hideNearbyIfZero.get() || nearbyCount > 0 || isInEditor())) {
            nearbyLabel = showText ? "Nearby: " : "";
            nearbyValue = String.valueOf(nearbyCount);
        }

        String shulkerLabel = null, shulkerValue = null;
        int shulkerCount = module.getShulkerCount();
        if (showShulkers.get() && (!hideShulkersIfZero.get() || shulkerCount > 0 || isInEditor())) {
            shulkerLabel = showText ? "Shulkers: " : "";
            shulkerValue = String.valueOf(shulkerCount);
        }

        boolean hasOpened  = openedLabel  != null;
        boolean hasNearby  = nearbyLabel  != null;
        boolean hasShulker = shulkerLabel != null;

        if (!hasOpened && !hasNearby && !hasShulker) {
            if (isInEditor()) {
                double lh = renderer.textHeight(false, s);
                setSize(renderer.textWidth("Opened: 0", false, s) + padH * 2, lh + padV * 2);
                renderer.text("Opened: 0", x + padH, y + padV, Color.GRAY, false, s);
            } else {
                setSize(0, 0);
            }
            return;
        }

        // ── Measure Widths ────────────────────────────────────────────────────

        double openedTextW  = hasOpened  ? renderer.textWidth(openedLabel,  false, s) + renderer.textWidth(openedValue,  false, s) : 0;
        double nearbyTextW  = hasNearby  ? renderer.textWidth(nearbyLabel,  false, s) + renderer.textWidth(nearbyValue,  false, s) : 0;
        double shulkerTextW = hasShulker ? renderer.textWidth(shulkerLabel, false, s) + renderer.textWidth(shulkerValue, false, s) : 0;

        double effectiveIconGap = (showIcon && showText) ? iconGap : 0;

        double openedW, nearbyW, shulkerW;
        if (!showIcon || iconVertical) {
            openedW  = hasOpened  ? (showIcon ? Math.max(iconSz, openedTextW)  : openedTextW)  : 0;
            nearbyW  = hasNearby  ? (showIcon ? Math.max(iconSz, nearbyTextW)  : nearbyTextW)  : 0;
            shulkerW = hasShulker ? (showIcon ? Math.max(iconSz, shulkerTextW) : shulkerTextW) : 0;
        } else {
            double iconColW = iconSz + effectiveIconGap;
            openedW  = hasOpened  ? iconColW + openedTextW  : 0;
            nearbyW  = hasNearby  ? iconColW + nearbyTextW  : 0;
            shulkerW = hasShulker ? iconColW + shulkerTextW : 0;
        }

        // ── Dimensions ────────────────────────────────────────────────────────

        double contentW = Math.max(openedW, Math.max(nearbyW, shulkerW));
        if (showIcon && !showText) contentW = Math.max(contentW, iconSz);
        double totalW = contentW + padH * 2;

        double contentH = (hasOpened  ? statRowH + rowGap : 0)
                        + (hasNearby  ? statRowH + rowGap : 0)
                        + (hasShulker ? statRowH + rowGap : 0)
                        - rowGap;
        double totalH = contentH + padV * 2;

        // ── Draw ──────────────────────────────────────────────────────────────

        boolean rightAlign  = alignment.get() == Alignment.Right;
        boolean centerAlign = alignment.get() == Alignment.Center;

        if (showBackground.get())
            renderer.quad(x, y, totalW, totalH, backgroundColor.get());

        double curY = y + padV;

        if (hasOpened) {
            drawStatRow(renderer, s, x, curY, totalW, padH, statRowH, lineHeight,
                rightAlign, centerAlign, openedW, openedTextW,
                showIcon ? openedIcon : ItemStack.EMPTY, iconSz, effectiveIconGap, iconPos,
                openedLabel, openedValue, labelColor.get(), openedColor.get());
            curY += statRowH + rowGap;
        }

        if (hasNearby) {
            drawStatRow(renderer, s, x, curY, totalW, padH, statRowH, lineHeight,
                rightAlign, centerAlign, nearbyW, nearbyTextW,
                showIcon ? nearbyIcon : ItemStack.EMPTY, iconSz, effectiveIconGap, iconPos,
                nearbyLabel, nearbyValue, labelColor.get(), nearbyColor.get());
            curY += statRowH + rowGap;
        }

        if (hasShulker) {
            drawStatRow(renderer, s, x, curY, totalW, padH, statRowH, lineHeight,
                rightAlign, centerAlign, shulkerW, shulkerTextW,
                showIcon ? shulkerIcon : ItemStack.EMPTY, iconSz, effectiveIconGap, iconPos,
                shulkerLabel, shulkerValue, labelColor.get(), shulkerColor.get());
        }

        setSize(totalW, totalH);
    }

    // ── Draw Stat Row Helper ─────────────────────────────────────────────────

    private void drawStatRow(HudRenderer renderer, double s,
                             double rx, double ry, double totalW, double padH,
                             double rowH, double lineHeight,
                             boolean rightAlign, boolean centerAlign,
                             double lineW, double textW,
                             ItemStack icon, double iconSz, double iconGap,
                             IconPosition iconPos,
                             String label, String value,
                             SettingColor lColor, SettingColor vColor) {

        boolean hasIcon      = !icon.isEmpty();
        boolean iconVertical = hasIcon && (iconPos == IconPosition.Above || iconPos == IconPosition.Below);

        if (showBackground.get())
            renderer.quad(rx, ry - 1, totalW, rowH + 2, backgroundColor.get());

        if (!hasIcon || !iconVertical) {
            double textY = ry + (rowH - lineHeight) / 2.0;
            double iconY = ry + (rowH - iconSz)     / 2.0;

            if (rightAlign) {
                double cx = rx + totalW - padH;
                if (iconPos == IconPosition.Right && hasIcon) {
                    renderer.item(icon, (int)(cx - iconSz), (int) iconY, iconScale.get().floatValue(), false);
                    cx -= iconSz + iconGap;
                }
                if (value != null && !value.isEmpty()) {
                    cx -= renderer.textWidth(value, false, s);
                    renderer.text(value, cx, textY, vColor, false, s);
                }
                if (label != null && !label.isEmpty()) {
                    cx -= renderer.textWidth(label, false, s);
                    renderer.text(label, cx, textY, lColor, false, s);
                }
                if (iconPos == IconPosition.Left && hasIcon) {
                    cx -= iconGap + iconSz;
                    renderer.item(icon, (int) cx, (int) iconY, iconScale.get().floatValue(), false);
                }
            } else {
                double cx = centerAlign ? rx + (totalW - lineW) / 2.0 : rx + padH;
                if (iconPos == IconPosition.Left && hasIcon) {
                    renderer.item(icon, (int) cx, (int) iconY, iconScale.get().floatValue(), false);
                    cx += iconSz + iconGap;
                }
                if (label != null && !label.isEmpty()) {
                    renderer.text(label, cx, textY, lColor, false, s);
                    cx += renderer.textWidth(label, false, s);
                }
                if (value != null && !value.isEmpty()) {
                    renderer.text(value, cx, textY, vColor, false, s);
                    cx += renderer.textWidth(value, false, s);
                }
                if (iconPos == IconPosition.Right && hasIcon) {
                    cx += iconGap;
                    renderer.item(icon, (int) cx, (int)(ry + (rowH - iconSz) / 2.0), iconScale.get().floatValue(), false);
                }
            }

        } else {
            double iconY, textY;
            if (iconPos == IconPosition.Above) {
                iconY = ry;
                textY = ry + iconSz + iconGap;
            } else {
                textY = ry;
                iconY = ry + lineHeight + iconGap;
            }

            double iconX;
            if (rightAlign)       iconX = rx + totalW - padH - iconSz;
            else if (centerAlign) iconX = rx + (totalW - iconSz) / 2.0;
            else {
                iconX = rx + padH + (textW - iconSz) / 2.0;
                if (iconX < rx + padH) iconX = rx + padH;
            }
            if (hasIcon)
                renderer.item(icon, (int) iconX, (int) iconY, iconScale.get().floatValue(), false);

            if (rightAlign) {
                double cx = rx + totalW - padH;
                if (value != null && !value.isEmpty()) {
                    cx -= renderer.textWidth(value, false, s);
                    renderer.text(value, cx, textY, vColor, false, s);
                }
                if (label != null && !label.isEmpty()) {
                    cx -= renderer.textWidth(label, false, s);
                    renderer.text(label, cx, textY, lColor, false, s);
                }
            } else {
                double cx = centerAlign ? rx + (totalW - textW) / 2.0 : rx + padH;
                if (label != null && !label.isEmpty()) {
                    renderer.text(label, cx, textY, lColor, false, s);
                    cx += renderer.textWidth(label, false, s);
                }
                if (value != null && !value.isEmpty()) {
                    renderer.text(value, cx, textY, vColor, false, s);
                }
            }
        }
    }
}