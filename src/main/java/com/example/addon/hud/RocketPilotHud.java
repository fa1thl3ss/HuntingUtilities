package com.example.addon.hud;

import com.example.addon.HuntingUtilities;
import com.example.addon.modules.RocketPilot;

import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;

public class RocketPilotHud extends HudElement {
    public static final HudElementInfo<RocketPilotHud> INFO = new HudElementInfo<>(
        HuntingUtilities.HUD_GROUP,
        "rocket-pilot",
        "Displays RocketPilot status, elytra durability, and rocket count.",
        RocketPilotHud::new
    );

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgElytra  = settings.createGroup("Elytra Warnings");
    private final SettingGroup sgRockets = settings.createGroup("Rocket Warnings");

    // ── Visual settings ───────────────────────────────────────────────────────

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the HUD element.")
        .defaultValue(1.0)
        .min(0.25)
        .sliderRange(0.25, 4.0)
        .build()
    );

    public enum Alignment { Left, Center, Right }

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Align text to the left, center, or right within the element.")
        .defaultValue(Alignment.Left)
        .build()
    );

    public enum LabelMode { Text, Icon, Both }

    private final Setting<LabelMode> labelMode = sgGeneral.add(new EnumSetting.Builder<LabelMode>()
        .name("label-mode")
        .description("Show the item label as text, icon, or both.")
        .defaultValue(LabelMode.Both)
        .build()
    );

    public enum IconPosition { Left, Right, Above, Below }

    private final Setting<IconPosition> iconPosition = sgGeneral.add(new EnumSetting.Builder<IconPosition>()
        .name("icon-position")
        .description("Where the item icon appears relative to the text on each stat row.")
        .defaultValue(IconPosition.Left)
        .visible(() -> labelMode.get() != LabelMode.Text)
        .build()
    );

    private final Setting<Double> iconScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-scale")
        .description("Scale of the item icons.")
        .defaultValue(1.5)
        .min(0.5)
        .sliderRange(0.5, 4.0)
        .visible(() -> labelMode.get() != LabelMode.Text)
        .build()
    );

    private final Setting<Double> iconGapSetting = sgGeneral.add(new DoubleSetting.Builder()
        .name("icon-gap")
        .description("Gap in pixels between the icon and the text.")
        .defaultValue(4.0)
        .min(0)
        .sliderRange(0, 16)
        .visible(() -> labelMode.get() != LabelMode.Text)
        .build()
    );

    private final Setting<SettingColor> labelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("label-color")
        .description("Color for labels.")
        .defaultValue(new SettingColor(170, 170, 170, 255))
        .build()
    );

    private final Setting<SettingColor> valueColor = sgGeneral.add(new ColorSetting.Builder()
        .name("value-color")
        .description("Color for values when healthy.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<Boolean> showBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Show a background behind each row.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .defaultValue(new SettingColor(0, 0, 0, 80))
        .visible(showBackground::get)
        .build()
    );

    // ── Feature toggles ───────────────────────────────────────────────────────

    private final Setting<Boolean> showStatus = sgGeneral.add(new BoolSetting.Builder()
        .name("show-status")
        .description("Show the RocketPilot status line (only visible while module is active).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showDurability = sgGeneral.add(new BoolSetting.Builder()
        .name("show-durability")
        .description("Show the elytra's remaining durability.")
        .defaultValue(true)
        .build()
    );

    public enum DurabilityFormat { Both, Numbers, Percentage }

    private final Setting<DurabilityFormat> durabilityFormat = sgGeneral.add(new EnumSetting.Builder<DurabilityFormat>()
        .name("durability-format")
        .description("How to display elytra durability.")
        .defaultValue(DurabilityFormat.Both)
        .visible(showDurability::get)
        .build()
    );

    private final Setting<Boolean> showRocketCount = sgGeneral.add(new BoolSetting.Builder()
        .name("show-rocket-count")
        .description("Show total firework rockets across the inventory.")
        .defaultValue(true)
        .build()
    );

    // ── Elytra warnings ───────────────────────────────────────────────────────

    private final Setting<Double> elytraWarningThreshold = sgElytra.add(new DoubleSetting.Builder()
        .name("warning-threshold")
        .description("Elytra durability % to trigger warning color.")
        .defaultValue(25).min(0).sliderRange(0, 100).build()
    );

    private final Setting<SettingColor> elytraWarningColor = sgElytra.add(new ColorSetting.Builder()
        .name("warning-color")
        .defaultValue(new SettingColor(255, 165, 0, 255)).build()
    );

    private final Setting<Double> elytraCriticalThreshold = sgElytra.add(new DoubleSetting.Builder()
        .name("critical-threshold")
        .description("Elytra durability % to trigger critical color.")
        .defaultValue(10).min(0).sliderRange(0, 100).build()
    );

    private final Setting<SettingColor> elytraCriticalColor = sgElytra.add(new ColorSetting.Builder()
        .name("critical-color")
        .defaultValue(new SettingColor(255, 40, 40, 255)).build()
    );

    // ── Rocket warnings ───────────────────────────────────────────────────────

    private final Setting<Integer> rocketWarningThreshold = sgRockets.add(new IntSetting.Builder()
        .name("warning-threshold")
        .description("Rocket count to trigger warning color.")
        .defaultValue(16).min(0).sliderRange(0, 128).build()
    );

    private final Setting<SettingColor> rocketWarningColor = sgRockets.add(new ColorSetting.Builder()
        .name("warning-color")
        .defaultValue(new SettingColor(255, 165, 0, 255)).build()
    );

    private final Setting<Integer> rocketCriticalThreshold = sgRockets.add(new IntSetting.Builder()
        .name("critical-threshold")
        .description("Rocket count to trigger critical color.")
        .defaultValue(8).min(0).sliderRange(0, 128).build()
    );

    private final Setting<SettingColor> rocketCriticalColor = sgRockets.add(new ColorSetting.Builder()
        .name("critical-color")
        .defaultValue(new SettingColor(255, 40, 40, 255)).build()
    );

    // ── Constructor ───────────────────────────────────────────────────────────

    public RocketPilotHud() { super(INFO); }

    // ── Render ────────────────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {
        if (mc.player == null) { setSize(0, 0); return; }

        RocketPilot rp = Modules.get().get(RocketPilot.class);

        double s          = scale.get();
        double padH       = 4 * s;
        double padV       = 2 * s;
        double rowGap     = 2 * s;
        double lineHeight = renderer.textHeight(false, s);
        double iconSz     = 16.0 * iconScale.get();
        double iconGap    = iconGapSetting.get() * s;

        LabelMode    mode     = labelMode.get();
        IconPosition iconPos  = iconPosition.get();
        boolean      showIcon = mode != LabelMode.Text;
        boolean      showText = mode != LabelMode.Icon;

        // For Above/Below icon layouts each stat row is TWO visual rows tall
        boolean iconVertical = showIcon && (iconPos == IconPosition.Above || iconPos == IconPosition.Below);

        // Height of a single stat row
        double statRowH;
        if (!showIcon) {
            statRowH = lineHeight;
        } else if (iconVertical) {
            statRowH = iconSz + iconGap + lineHeight;
        } else {
            statRowH = Math.max(lineHeight, iconSz);
        }

        // Status line is always one text line tall
        double statusRowH = lineHeight;

        // ── Gather data ───────────────────────────────────────────────────────

        String statusValue = null;
        if (showStatus.get() && rp.isActive()) {
            RocketPilot.FlightPattern pat = rp.flightPattern.get();
            statusValue = (pat == RocketPilot.FlightPattern.Manual)
                ? rp.flightMode.get().toString() : pat.toString();
        }
        String statusLabel = "RocketPilot: ";
        double statusW = statusValue != null
            ? renderer.textWidth(statusLabel, false, s) + renderer.textWidth(statusValue, false, s)
            : 0;

        // Elytra
        ItemStack    elytraStack = ItemStack.EMPTY;
        String       durLabel = null, durValue = null;
        SettingColor durColor = valueColor.get();
        if (showDurability.get()) {
            ItemStack eq = mc.player.getEquippedStack(EquipmentSlot.CHEST);
            if (!eq.isEmpty() && eq.isOf(Items.ELYTRA)) {
                int remaining = eq.getMaxDamage() - eq.getDamage();
                int max       = eq.getMaxDamage();
                double pct    = 100.0 * remaining / max;
                elytraStack   = eq;
                durLabel      = showText ? eq.getName().getString() + ": " : "";
                durValue      = switch (durabilityFormat.get()) {
                    case Numbers    -> String.format("%d / %d", remaining, max);
                    case Percentage -> String.format("%.0f%%", pct);
                    default         -> String.format("%d / %d (%.0f%%)", remaining, max, pct);
                };
                if      (pct <= elytraCriticalThreshold.get()) durColor = elytraCriticalColor.get();
                else if (pct <= elytraWarningThreshold.get())  durColor = elytraWarningColor.get();
            }
        }

        // Rockets
        ItemStack    rocketStack = ItemStack.EMPTY;
        String       rocketLabel = null, rocketValue = null;
        SettingColor rocketColor = valueColor.get();
        if (showRocketCount.get()) {
            int rockets = countRockets();
            String name = "Firework Rocket";
            for (int i = 0; i < 36; i++) {
                ItemStack s2 = mc.player.getInventory().getStack(i);
                if (s2.isOf(Items.FIREWORK_ROCKET)) { name = s2.getName().getString(); rocketStack = s2; break; }
            }
            ItemStack offhand = mc.player.getOffHandStack();
            if (offhand.isOf(Items.FIREWORK_ROCKET)) { name = offhand.getName().getString(); rocketStack = offhand; }
            rocketLabel = showText ? name + ": " : "";
            rocketValue = String.valueOf(rockets);
            if      (rockets <= rocketCriticalThreshold.get()) rocketColor = rocketCriticalColor.get();
            else if (rockets <= rocketWarningThreshold.get())  rocketColor = rocketWarningColor.get();
        }

        boolean hasStatus = statusValue != null;
        boolean hasDur    = durLabel    != null;
        boolean hasRocket = rocketLabel != null;
        if (!hasStatus && !hasDur && !hasRocket) { setSize(0, 0); return; }

        // ── Measure text widths ───────────────────────────────────────────────

        // Text block width for each stat row (label + value, no icon)
        double durTextW    = durLabel    != null ? renderer.textWidth(durLabel,    false, s) + renderer.textWidth(durValue,    false, s) : 0;
        double rocketTextW = rocketLabel != null ? renderer.textWidth(rocketLabel, false, s) + renderer.textWidth(rocketValue, false, s) : 0;

        // Gap between icon and text — only non-zero when BOTH icon and text are shown
        double effectiveIconGap = (showIcon && showText) ? iconGap : 0;

        // Full row width depends on icon position
        double durW, rocketW;
        if (!showIcon || iconVertical) {
            // Vertical icon: icon sits above/below text, row width = max(iconSz, textW)
            durW    = durLabel    != null ? (showIcon && !elytraStack.isEmpty() ? Math.max(iconSz, durTextW)    : durTextW)    : 0;
            rocketW = rocketLabel != null ? (showIcon && !rocketStack.isEmpty() ? Math.max(iconSz, rocketTextW) : rocketTextW) : 0;
        } else {
            // Horizontal icon (Left/Right): icon + gap (only if text present) + text
            double durIconW    = (showIcon && !elytraStack.isEmpty())  ? iconSz + effectiveIconGap : 0;
            double rocketIconW = (showIcon && !rocketStack.isEmpty())  ? iconSz + effectiveIconGap : 0;
            durW    = durLabel    != null ? durIconW    + durTextW    : 0;
            rocketW = rocketLabel != null ? rocketIconW + rocketTextW : 0;
        }

        // ── Element dimensions ────────────────────────────────────────────────

        double contentW = Math.max(statusW, Math.max(durW, rocketW));
        // Ensure a minimum width when only icons are shown so the element is visible
        if (showIcon && !showText) contentW = Math.max(contentW, iconSz);
        double totalW   = contentW + padH * 2;

        double totalH = padV;
        if (hasStatus)  totalH += statusRowH + rowGap;
        if (hasDur)     totalH += statRowH   + rowGap;
        if (hasRocket)  totalH += statRowH   + rowGap;
        totalH -= rowGap; // remove trailing gap
        totalH += padV;

        // ── Draw ──────────────────────────────────────────────────────────────

        Alignment align       = alignment.get();
        boolean   rightAlign  = align == Alignment.Right;
        boolean   centerAlign = align == Alignment.Center;

        double curY = y + padV;

        if (hasStatus) {
            drawTextRow(renderer, s, x, curY, totalW, padH, statusRowH, lineHeight,
                rightAlign, centerAlign, statusW,
                statusLabel, statusValue, labelColor.get(), valueColor.get());
            curY += statusRowH + rowGap;
        }

        if (hasDur) {
            drawStatRow(renderer, s, x, curY, totalW, padH, statRowH, lineHeight,
                rightAlign, centerAlign, durW, durTextW,
                showIcon ? elytraStack : ItemStack.EMPTY, iconSz, effectiveIconGap, iconPos,
                durLabel, durValue, labelColor.get(), durColor);
            curY += statRowH + rowGap;
        }

        if (hasRocket) {
            drawStatRow(renderer, s, x, curY, totalW, padH, statRowH, lineHeight,
                rightAlign, centerAlign, rocketW, rocketTextW,
                showIcon ? rocketStack : ItemStack.EMPTY, iconSz, effectiveIconGap, iconPos,
                rocketLabel, rocketValue, labelColor.get(), rocketColor);
        }

        setSize(totalW, totalH);
    }

    // ── Draw a plain text row (used for status line) ──────────────────────────

    private void drawTextRow(HudRenderer renderer, double s,
                             double rx, double ry, double totalW, double padH,
                             double rowH, double lineHeight,
                             boolean rightAlign, boolean centerAlign,
                             double lineW,
                             String label, String value,
                             SettingColor lColor, SettingColor vColor) {

        double textY = ry + (rowH - lineHeight) / 2.0;

        if (showBackground.get())
            renderer.quad(rx, ry - 1, totalW, rowH + 2, backgroundColor.get());

        if (rightAlign) {
            double cx = rx + totalW - padH;
            cx -= renderer.textWidth(value, false, s);
            renderer.text(value, cx, textY, vColor, false, s);
            cx -= renderer.textWidth(label, false, s);
            renderer.text(label, cx, textY, lColor, false, s);
        } else {
            double cx = centerAlign ? rx + (totalW - lineW) / 2.0 : rx + padH;
            renderer.text(label, cx, textY, lColor, false, s);
            renderer.text(value, cx + renderer.textWidth(label, false, s), textY, vColor, false, s);
        }
    }

    // ── Draw a stat row (elytra or rocket) with configurable icon position ────
    //
    // iconPos controls where the icon sits relative to the text block:
    //   Left  – [icon] [label value]
    //   Right – [label value] [icon]
    //   Above – [icon centred]
    //             [label value]
    //   Below – [label value]
    //           [icon centred]

    private void drawStatRow(HudRenderer renderer, double s,
                             double rx, double ry, double totalW, double padH,
                             double rowH, double lineHeight,
                             boolean rightAlign, boolean centerAlign,
                             double lineW, double textW,
                             ItemStack icon, double iconSz, double iconGap,
                             IconPosition iconPos,
                             String label, String value,
                             SettingColor lColor, SettingColor vColor) {

        boolean hasIcon = !icon.isEmpty();

        if (showBackground.get())
            renderer.quad(rx, ry - 1, totalW, rowH + 2, backgroundColor.get());

        if (!hasIcon || iconPos == IconPosition.Left || iconPos == IconPosition.Right) {
            // ── Horizontal arrangement ────────────────────────────────────────
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
            // ── Vertical arrangement (Above / Below) ──────────────────────────
            // rowH = iconSz + iconGap + lineHeight
            double iconY, textY;
            if (iconPos == IconPosition.Above) {
                iconY = ry;
                textY = ry + iconSz + iconGap;
            } else { // Below
                textY = ry;
                iconY = ry + lineHeight + iconGap;
            }

            // Icon centred horizontally within totalW (or within lineW for center/right)
            double iconX;
            if (rightAlign) {
                iconX = rx + totalW - padH - iconSz;
            } else if (centerAlign) {
                iconX = rx + (totalW - iconSz) / 2.0;
            } else {
                iconX = rx + padH + (textW - iconSz) / 2.0;
                if (iconX < rx + padH) iconX = rx + padH;
            }
            if (hasIcon)
                renderer.item(icon, (int) iconX, (int) iconY, iconScale.get().floatValue(), false);

            // Text drawn on its row
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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private int countRockets() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isOf(Items.FIREWORK_ROCKET)) count += s.getCount();
        }
        ItemStack offhand = mc.player.getOffHandStack();
        if (offhand.isOf(Items.FIREWORK_ROCKET)) count += offhand.getCount();
        return count;
    }
}