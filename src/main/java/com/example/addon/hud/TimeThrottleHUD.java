package com.example.addon.hud;

import com.example.addon.HuntingUtilities;
import com.example.addon.modules.Timethrottle;

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
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.TickRate;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;

public class TimeThrottleHUD extends HudElement {

    public static final HudElementInfo<TimeThrottleHUD> INFO = new HudElementInfo<>(
        HuntingUtilities.HUD_GROUP,
        "time-throttle",
        "Displays the current TimeThrottle state: active triggers, speed multiplier, TPS, ping, and chunk load.",
        TimeThrottleHUD::new
    );

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // ── Settings ──────────────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    private final Setting<Boolean> showSpeed = sgGeneral.add(new BoolSetting.Builder()
        .name("show-speed")
        .description("Show the current Timer speed multiplier.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showTps = sgGeneral.add(new BoolSetting.Builder()
        .name("show-tps")
        .description("Show current server TPS.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showPing = sgGeneral.add(new BoolSetting.Builder()
        .name("show-ping")
        .description("Show current server ping.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showChunks = sgGeneral.add(new BoolSetting.Builder()
        .name("show-chunks")
        .description("Show number of unloaded chunks in view distance.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showTriggers = sgGeneral.add(new BoolSetting.Builder()
        .name("show-triggers")
        .description("Show which conditions are actively throttling the game speed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showSafety = sgGeneral.add(new BoolSetting.Builder()
        .name("show-safety")
        .description("Show when the combat safety override is active.")
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

    private final Setting<SettingColor> triggerActiveColor = sgGeneral.add(new ColorSetting.Builder()
        .name("trigger-active-color")
        .description("Color used for trigger labels that are currently active.")
        .defaultValue(new SettingColor(255, 80, 80, 255))
        .build()
    );

    private final Setting<SettingColor> triggerInactiveColor = sgGeneral.add(new ColorSetting.Builder()
        .name("trigger-inactive-color")
        .description("Color used for trigger labels that are currently inactive.")
        .defaultValue(new SettingColor(80, 200, 80, 255))
        .build()
    );

    private final Setting<SettingColor> safetyColor = sgGeneral.add(new ColorSetting.Builder()
        .name("safety-color")
        .description("Color shown when combat safety override is active.")
        .defaultValue(new SettingColor(255, 200, 0, 255))
        .build()
    );

    private final Setting<SettingColor> separatorColor = sgGeneral.add(new ColorSetting.Builder()
        .name("separator-color")
        .description("Color of the | separator between paired values.")
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

    public TimeThrottleHUD() {
        super(INFO);
    }

    // ── Render ────────────────────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {
        if (mc.player == null || mc.world == null) {
            setSize(0, 0);
            return;
        }

        Timethrottle module = Modules.get().get(Timethrottle.class);
        boolean moduleActive = module != null && module.isActive();

        double s          = scale.get();
        double padH       = 4 * s;
        double padV       = 2 * s;
        double rowGap     = 2 * s;
        double lineHeight = renderer.textHeight(false, s);
        double sepW       = renderer.textWidth(" | ", false, s);
        boolean rightAlign = alignment.get() == Alignment.Right;

        // ── Gather values ─────────────────────────────────────────────────────────

        float  tps        = TickRate.INSTANCE.getTickRate();
        int    ping       = getPlayerPing();
        int    unloaded   = countUnloadedChunks();
        double timerSpeed = moduleActive ? module.getCurrentSpeed() : 1.0;

        // ── Determine active triggers ─────────────────────────────────────────────

        boolean safetyActive = moduleActive && module.isSafetyActive();
        boolean tpsTrigger   = moduleActive && !safetyActive && tps < 19.0;
        boolean pingTrigger  = moduleActive && !safetyActive && ping > 150;
        boolean chunkTrigger = moduleActive && !safetyActive && unloaded > 50;

        // ── Build line content ────────────────────────────────────────────────────

        // Line 1: Speed
        String speedLabel = showSpeed.get() ? "Speed: " : null;
        String speedValue = showSpeed.get() ? String.format("%.2fx", timerSpeed) : null;
        SettingColor speedColor = getSpeedColor(timerSpeed);

        // Line 2: TPS | Ping
        String tpsLabel  = showTps.get()  ? "TPS: "  : null;
        String tpsValue  = showTps.get()  ? String.format("%.1f", tps) : null;
        String pingLabel = showPing.get() ? "Ping: " : null;
        String pingValue = showPing.get() ? ping + "ms" : null;
        SettingColor tpsColor  = getTpsColor(tps);
        SettingColor pingColor = getPingColor(ping);

        // Line 3: Unloaded Chunks
        String chunkLabel = showChunks.get() ? "Unloaded: " : null;
        String chunkValue = showChunks.get() ? unloaded + " chunks" : null;
        SettingColor chunkColor = unloaded > 50
            ? new SettingColor(255, 200, 0, 255)
            : valueColor.get();

        // Line 4: Trigger tags
        boolean hasTriggerLine = showTriggers.get() && moduleActive;

        // Line 5: Safety notice
        boolean hasSafetyLine = showSafety.get() && moduleActive && safetyActive;

        // ── Measure widths ────────────────────────────────────────────────────────

        double line1W = 0, line2W = 0, line3W = 0, line4W = 0, line5W = 0;

        if (speedLabel != null)
            line1W = renderer.textWidth(speedLabel, false, s) + renderer.textWidth(speedValue, false, s);

        if (tpsLabel != null)
            line2W += renderer.textWidth(tpsLabel, false, s) + renderer.textWidth(tpsValue, false, s);
        if (tpsLabel != null && pingLabel != null)
            line2W += sepW;
        if (pingLabel != null)
            line2W += renderer.textWidth(pingLabel, false, s) + renderer.textWidth(pingValue, false, s);

        if (chunkLabel != null)
            line3W = renderer.textWidth(chunkLabel, false, s) + renderer.textWidth(chunkValue, false, s);

        if (hasTriggerLine) {
            line4W = renderer.textWidth("Triggers: ", false, s)
                   + renderer.textWidth("[TPS]",    false, s) + renderer.textWidth(" ", false, s)
                   + renderer.textWidth("[Ping]",   false, s) + renderer.textWidth(" ", false, s)
                   + renderer.textWidth("[Chunks]", false, s);
        }

        if (hasSafetyLine)
            line5W = renderer.textWidth("! Combat Safety Active", false, s);

        boolean hasLine1 = speedLabel  != null;
        boolean hasLine2 = tpsLabel    != null || pingLabel  != null;
        boolean hasLine3 = chunkLabel  != null;
        boolean hasLine4 = hasTriggerLine;
        boolean hasLine5 = hasSafetyLine;

        if (!hasLine1 && !hasLine2 && !hasLine3 && !hasLine4 && !hasLine5) {
            setSize(0, 0);
            return;
        }

        double maxLineW = Math.max(line1W, Math.max(line2W, Math.max(line3W, Math.max(line4W, line5W))));
        double totalW   = maxLineW + padH * 2;
        int lineCount   = (hasLine1 ? 1 : 0) + (hasLine2 ? 1 : 0) + (hasLine3 ? 1 : 0)
                        + (hasLine4 ? 1 : 0) + (hasLine5 ? 1 : 0);
        double totalH   = lineCount * lineHeight + (lineCount - 1) * rowGap + padV * 2;

        int lineIdx = 0;

        // ── Draw Line 1: Speed ────────────────────────────────────────────────────
        if (hasLine1)
            lineIdx = drawLabelValue(renderer, s, padH, padV, rowGap, lineHeight,
                rightAlign, totalW, line1W, lineIdx,
                speedLabel, speedValue, labelColor.get(), speedColor);

        // ── Draw Line 2: TPS | Ping ───────────────────────────────────────────────
        if (hasLine2)
            lineIdx = drawPair(renderer, s, padH, padV, rowGap, lineHeight, sepW,
                rightAlign, totalW, line2W, lineIdx,
                tpsLabel, tpsValue, tpsColor,
                pingLabel, pingValue, pingColor);

        // ── Draw Line 3: Unloaded Chunks ──────────────────────────────────────────
        if (hasLine3)
            lineIdx = drawLabelValue(renderer, s, padH, padV, rowGap, lineHeight,
                rightAlign, totalW, line3W, lineIdx,
                chunkLabel, chunkValue, labelColor.get(), chunkColor);

        // ── Draw Line 4: Trigger tags ─────────────────────────────────────────────
        if (hasLine4) {
            double rowY = y + padV + lineIdx * (lineHeight + rowGap);
            if (showBackground.get()) {
                double bgW = line4W + padH * 2;
                double bgX = rightAlign ? x + totalW - bgW : x;
                renderer.quad(bgX, rowY - 1, bgW, lineHeight + 2, backgroundColor.get());
            }
            double cx = rightAlign
                ? x + totalW - padH - line4W
                : x + padH;

            renderer.text("Triggers: ", cx, rowY, labelColor.get(), false, s);
            cx += renderer.textWidth("Triggers: ", false, s);

            String[]  tags  = { "[TPS]", "[Ping]", "[Chunks]" };
            boolean[] activ = { tpsTrigger, pingTrigger, chunkTrigger };
            for (int i = 0; i < tags.length; i++) {
                SettingColor col = activ[i] ? triggerActiveColor.get() : triggerInactiveColor.get();
                renderer.text(tags[i], cx, rowY, col, false, s);
                cx += renderer.textWidth(tags[i], false, s);
                if (i < tags.length - 1) {
                    renderer.text(" ", cx, rowY, labelColor.get(), false, s);
                    cx += renderer.textWidth(" ", false, s);
                }
            }
            lineIdx++;
        }

        // ── Draw Line 5: Safety notice ────────────────────────────────────────────
        if (hasLine5) {
            double rowY = y + padV + lineIdx * (lineHeight + rowGap);
            if (showBackground.get()) {
                double bgW = line5W + padH * 2;
                double bgX = rightAlign ? x + totalW - bgW : x;
                renderer.quad(bgX, rowY - 1, bgW, lineHeight + 2, backgroundColor.get());
            }
            double tx = rightAlign ? x + totalW - padH - line5W : x + padH;
            renderer.text("! Combat Safety Active", tx, rowY, safetyColor.get(), false, s);
            lineIdx++;
        }

        setSize(totalW, totalH);
    }

    // ── Draw helpers ──────────────────────────────────────────────────────────────

    private int drawLabelValue(HudRenderer renderer, double s,
                               double padH, double padV, double rowGap, double lineHeight,
                               boolean rightAlign, double totalW, double lineW, int lineIdx,
                               String label, String value,
                               SettingColor lColor, SettingColor vColor) {
        double rowY     = y + padV + lineIdx * (lineHeight + rowGap);
        double lineBoxW = lineW + padH * 2;
        if (showBackground.get()) {
            double bgX = rightAlign ? x + totalW - lineBoxW : x;
            renderer.quad(bgX, rowY - 1, lineBoxW, lineHeight + 2, backgroundColor.get());
        }
        if (rightAlign) {
            double vw = renderer.textWidth(value, false, s);
            double lw = renderer.textWidth(label, false, s);
            double vx = x + totalW - padH - vw;
            renderer.text(label, vx - lw, rowY, lColor, false, s);
            renderer.text(value, vx,       rowY, vColor, false, s);
        } else {
            double cx = x + padH;
            renderer.text(label, cx, rowY, lColor, false, s);
            cx += renderer.textWidth(label, false, s);
            renderer.text(value, cx, rowY, vColor, false, s);
        }
        return lineIdx + 1;
    }

    private int drawPair(HudRenderer renderer, double s,
                         double padH, double padV, double rowGap, double lineHeight, double sepW,
                         boolean rightAlign, double totalW, double lineW, int lineIdx,
                         String labelA, String valueA, SettingColor colorA,
                         String labelB, String valueB, SettingColor colorB) {
        double rowY     = y + padV + lineIdx * (lineHeight + rowGap);
        double lineBoxW = lineW + padH * 2;
        if (showBackground.get()) {
            double bgX = rightAlign ? x + totalW - lineBoxW : x;
            renderer.quad(bgX, rowY - 1, lineBoxW, lineHeight + 2, backgroundColor.get());
        }
        if (rightAlign) {
            double cx = x + totalW - padH;
            if (labelB != null) {
                double vw = renderer.textWidth(valueB, false, s);
                double lw = renderer.textWidth(labelB, false, s);
                cx -= vw; renderer.text(valueB, cx, rowY, colorB,           false, s);
                cx -= lw; renderer.text(labelB, cx, rowY, labelColor.get(), false, s);
            }
            if (labelA != null && labelB != null) {
                cx -= sepW;
                renderer.text(" | ", cx, rowY, separatorColor.get(), false, s);
            }
            if (labelA != null) {
                double vw = renderer.textWidth(valueA, false, s);
                double lw = renderer.textWidth(labelA, false, s);
                cx -= vw; renderer.text(valueA, cx, rowY, colorA,           false, s);
                cx -= lw; renderer.text(labelA, cx, rowY, labelColor.get(), false, s);
            }
        } else {
            double cx = x + padH;
            if (labelA != null) {
                renderer.text(labelA, cx, rowY, labelColor.get(), false, s);
                cx += renderer.textWidth(labelA, false, s);
                renderer.text(valueA, cx, rowY, colorA, false, s);
                cx += renderer.textWidth(valueA, false, s);
            }
            if (labelA != null && labelB != null) {
                renderer.text(" | ", cx, rowY, separatorColor.get(), false, s);
                cx += sepW;
            }
            if (labelB != null) {
                renderer.text(labelB, cx, rowY, labelColor.get(), false, s);
                cx += renderer.textWidth(labelB, false, s);
                renderer.text(valueB, cx, rowY, colorB, false, s);
            }
        }
        return lineIdx + 1;
    }

    // ── Color helpers ─────────────────────────────────────────────────────────────

    private SettingColor getSpeedColor(double speed) {
        if (speed >= 0.99) return valueColor.get();
        if (speed >= 0.75) return new SettingColor(255, 200, 0,  255);
        if (speed >= 0.50) return new SettingColor(255, 130, 0,  255);
        return                    new SettingColor(255, 60,  60, 255);
    }

    private SettingColor getTpsColor(float tps) {
        if (tps >= 19f) return valueColor.get();
        if (tps >= 15f) return new SettingColor(255, 200, 0,  255);
        return                 new SettingColor(255, 60,  60, 255);
    }

    private SettingColor getPingColor(int ping) {
        if (ping <  150) return valueColor.get();
        if (ping <  300) return new SettingColor(255, 200, 0,  255);
        return                  new SettingColor(255, 60,  60, 255);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private int getPlayerPing() {
        if (mc.getNetworkHandler() == null || mc.player == null) return 0;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry != null ? entry.getLatency() : 0;
    }

    private int countUnloadedChunks() {
        if (mc.world == null || mc.player == null) return 0;
        int unloaded     = 0;
        int viewDistance = mc.options.getClampedViewDistance();
        int pcx          = mc.player.getChunkPos().x;
        int pcz          = mc.player.getChunkPos().z;
        for (int dx = -viewDistance; dx <= viewDistance; dx++)
            for (int dz = -viewDistance; dz <= viewDistance; dz++)
                if (!mc.world.getChunkManager().isChunkLoaded(pcx + dx, pcz + dz))
                    unloaded++;
        return unloaded;
    }
}