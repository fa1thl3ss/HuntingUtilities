package com.example.addon.hud;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.meteorclient.utils.world.TickRate;
import net.minecraft.client.MinecraftClient;

public class StatisticsInformation extends HudElement {

    public static final HudElementInfo<StatisticsInformation> INFO = new HudElementInfo<>(
        HuntingUtilities.HUD_GROUP,
        "statistics-information",
        "Provides a comprehensive display of real-time performance metrics, navigational data, and session-specific statistics.",
        StatisticsInformation::new
    );

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // ── Settings ──────────────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral = settings.getDefaultGroup();

    // Speed
    private final Setting<Boolean> showSpeed = sgGeneral.add(new BoolSetting.Builder()
        .name("show-speed")
        .description("Show player speed.")
        .defaultValue(true)
        .build()
    );

    public enum SpeedUnit { Both, BPS, KMH }

    private final Setting<SpeedUnit> speedUnit = sgGeneral.add(new EnumSetting.Builder<SpeedUnit>()
        .name("speed-unit")
        .description("Which speed unit(s) to display.")
        .defaultValue(SpeedUnit.Both)
        .visible(showSpeed::get)
        .build()
    );

    // FPS
    private final Setting<Boolean> showFps = sgGeneral.add(new BoolSetting.Builder()
        .name("show-fps")
        .description("Show current FPS.")
        .defaultValue(true)
        .build()
    );

    // TPS
    private final Setting<Boolean> showTps = sgGeneral.add(new BoolSetting.Builder()
        .name("show-tps")
        .description("Show server TPS.")
        .defaultValue(true)
        .build()
    );

    // Coordinates
    public enum CoordinateDisplay {
        Show,
        Hidden
    }

    private final Setting<CoordinateDisplay> coordinateDisplay = sgGeneral.add(new EnumSetting.Builder<CoordinateDisplay>()
        .name("coordinates")
        .description("Whether to show your current coordinates.")
        .defaultValue(CoordinateDisplay.Hidden)
        .build()
    );

    // Direction
    private final Setting<Boolean> showDirection = sgGeneral.add(new BoolSetting.Builder()
        .name("show-direction")
        .description("Show the direction you are facing (cardinal + yaw).")
        .defaultValue(true)
        .build()
    );

    public enum DirectionFormat { Cardinal, Yaw, Both }

    private final Setting<DirectionFormat> directionFormat = sgGeneral.add(new EnumSetting.Builder<DirectionFormat>()
        .name("direction-format")
        .description("How to display facing direction.")
        .defaultValue(DirectionFormat.Both)
        .visible(showDirection::get)
        .build()
    );

    // Memory
    private final Setting<Boolean> showMemory = sgGeneral.add(new BoolSetting.Builder()
        .name("show-memory")
        .description("Show JVM memory usage (used / max MB).")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> memoryColorCode = sgGeneral.add(new BoolSetting.Builder()
        .name("memory-color-code")
        .description("Color the memory value yellow above 75% and red above 90% usage.")
        .defaultValue(true)
        .visible(showMemory::get)
        .build()
    );

    // Chunks
    private final Setting<Boolean> showChunks = sgGeneral.add(new BoolSetting.Builder()
        .name("show-chunks")
        .description("Show number of loaded chunks.")
        .defaultValue(true)
        .build()
    );

    // Player Count
    private final Setting<Boolean> showPlayerCount = sgGeneral.add(new BoolSetting.Builder()
        .name("show-player-count")
        .description("Show number of players on the server.")
        .defaultValue(true)
        .build()
    );

    // Distance Traveled
    private final Setting<Boolean> showDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("show-distance")
        .description("Show total distance traveled since login.")
        .defaultValue(true)
        .build()
    );

    public enum DistanceUnit { Blocks, Km, Both }

    private final Setting<DistanceUnit> distanceUnit = sgGeneral.add(new EnumSetting.Builder<DistanceUnit>()
        .name("distance-unit")
        .description("Which unit(s) to display for distance traveled.")
        .defaultValue(DistanceUnit.Both)
        .visible(showDistance::get)
        .build()
    );

    private final Setting<Boolean> distanceIncludeY = sgGeneral.add(new BoolSetting.Builder()
        .name("distance-include-y")
        .description("Include vertical movement in the distance calculation.")
        .defaultValue(false)
        .visible(showDistance::get)
        .build()
    );

    // Time Online
    private final Setting<Boolean> showTimeOnline = sgGeneral.add(new BoolSetting.Builder()
        .name("show-time-online")
        .description("Show time spent online since joining the server.")
        .defaultValue(true)
        .build()
    );

    public enum TimeFormat { HMS, HM, Seconds }

    private final Setting<TimeFormat> timeFormat = sgGeneral.add(new EnumSetting.Builder<TimeFormat>()
        .name("time-format")
        .description("How to display the time online. HMS: 1h 23m 45s  HM: 1h 23m  Seconds: 5025s")
        .defaultValue(TimeFormat.HMS)
        .visible(showTimeOnline::get)
        .build()
    );

    // Stability
    private final Setting<Boolean> showStability = sgGeneral.add(new BoolSetting.Builder()
        .name("show-stability")
        .description("Show connection stability based on time since last server tick.")
        .defaultValue(false)
        .build()
    );

    // TPS Guard
    private final Setting<Boolean> tpsGuard = sgGeneral.add(new BoolSetting.Builder()
        .name("tps-guard")
        .description("Show a warning if TPS is too low for safe movement through unloaded chunks.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> tpsGuardThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("tps-guard-threshold")
        .description("TPS threshold for the safety warning.")
        .defaultValue(15.0).min(1.0).max(20.0)
        .visible(tpsGuard::get)
        .build()
    );

    private final Setting<Boolean> tpsGuardHideStatic = sgGeneral.add(new BoolSetting.Builder()
        .name("tps-guard-hide-stationary")
        .description("Hide the TPS guard warning when the player is not moving.")
        .defaultValue(false)
        .visible(tpsGuard::get)
        .build()
    );

    private final Setting<SettingColor> tpsGuardColor = sgGeneral.add(new ColorSetting.Builder()
        .name("tps-guard-color")
        .description("Color for the TPS guard warning.")
        .defaultValue(new SettingColor(255, 60, 60, 255))
        .visible(tpsGuard::get)
        .build()
    );

    // Visuals
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
        .description("Color of the | separator between FPS and TPS.")
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

    // ── State ─────────────────────────────────────────────────────────────────────

    private double distanceTraveled = 0.0;
    private double prevX = Double.MAX_VALUE;
    private double prevY = Double.MAX_VALUE;
    private double prevZ = Double.MAX_VALUE;

    /** System time (ms) when the HUD element was first ticked after joining. */
    private long sessionStartMs = -1;

    // ── Constructor ───────────────────────────────────────────────────────────────

    public StatisticsInformation() {
        super(INFO);
    }

    // ── Tick ──────────────────────────────────────────────────────────────────────

    @Override
    public void tick(HudRenderer renderer) {
        if (mc.player == null) {
            // Player left — reset so the timer restarts on next join
            prevX = Double.MAX_VALUE;
            prevY = Double.MAX_VALUE;
            prevZ = Double.MAX_VALUE;
            distanceTraveled = 0.0;
            sessionStartMs = -1;
            return;
        }

        // Start the session timer the first tick we have a player
        if (sessionStartMs < 0) sessionStartMs = System.currentTimeMillis();

        double cx = mc.player.getX();
        double cy = mc.player.getY();
        double cz = mc.player.getZ();

        if (prevX != Double.MAX_VALUE) {
            double dx = cx - prevX;
            double dz = cz - prevZ;
            double dy = distanceIncludeY.get() ? (cy - prevY) : 0.0;
            distanceTraveled += Math.sqrt(dx * dx + dy * dy + dz * dz);
        }

        prevX = cx;
        prevY = cy;
        prevZ = cz;
    }

    // ── Rendering ─────────────────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {
        double s = scale.get();

        double padH       = 4 * s;
        double padV       = 2 * s;
        double rowGap     = 2 * s;
        double lineHeight = renderer.textHeight(false, s);
        double sepW       = renderer.textWidth(" | ", false, s);
        boolean rightAlign = alignment.get() == Alignment.Right;

        // ── TPS color ─────────────────────────────────────────────────────────────
        float tps = TickRate.INSTANCE.getTickRate();
        SettingColor tpsColor = tps < 10f
            ? new SettingColor(255, 60,  60,  255)
            : tps < 15f
            ? new SettingColor(255, 200, 0,   255)
            : valueColor.get();

        // ── Line 1: Speed ─────────────────────────────────────────────────────────
        String speedLabel = null, speedValue = null;
        if (showSpeed.get()) {
            double bps = getSpeedBps();
            double kmh = bps * 3.6;
            speedLabel = "Speed: ";
            speedValue = switch (speedUnit.get()) {
                case BPS  -> String.format("%.1f bps", bps);
                case KMH  -> String.format("%.1f km/h", kmh);
                case Both -> String.format("%.1f bps / %.1f km/h", bps, kmh);
            };
        }

        // ── Line 2: FPS | TPS ─────────────────────────────────────────────────────
        String fpsLabel = null, fpsValue = null, tpsLabel = null, tpsValue = null;
        if (showFps.get()) { fpsLabel = "FPS: "; fpsValue = String.valueOf(mc.getCurrentFps()); }
        if (showTps.get()) { tpsLabel = "TPS: "; tpsValue = String.format("%.1f", tps); }

        // ── Line 3: Direction ─────────────────────────────────────────────────────
        String dirLabel = null, dirValue = null;
        if (showDirection.get() && mc.player != null) {
            dirLabel = "Facing: ";
            float yaw = mc.player.getYaw() % 360f;
            if (yaw < 0) yaw += 360f;
            String cardinal = getCardinal(yaw);
            dirValue = switch (directionFormat.get()) {
                case Cardinal -> cardinal;
                case Yaw      -> String.format("%.1f°", yaw);
                case Both     -> String.format("%s  %.1f°", cardinal, yaw);
            };
        }

        // ── Line 9: Coordinates ───────────────────────────────────────────────────
        String coordsLabel = null, coordsValue = null;
        if (coordinateDisplay.get() == CoordinateDisplay.Show && mc.player != null) {
            coordsLabel = "Pos: ";
            coordsValue = String.format("%d, %d, %d", (int) mc.player.getX(), (int) mc.player.getY(), (int) mc.player.getZ());
        }

        // ── Line 4: Memory ────────────────────────────────────────────────────────
        String memLabel = null, memValue = null;
        SettingColor memColor = valueColor.get();
        if (showMemory.get()) {
            Runtime rt = Runtime.getRuntime();
            long usedMB = (rt.totalMemory() - rt.freeMemory()) / (1024 * 1024);
            long maxMB  = rt.maxMemory() / (1024 * 1024);
            double pct  = (double) usedMB / maxMB;
            memLabel = "Mem: ";
            memValue = usedMB + " / " + maxMB + " MB";
            if (memoryColorCode.get()) {
                if      (pct >= 0.90) memColor = new SettingColor(255, 60,  60,  255);
                else if (pct >= 0.75) memColor = new SettingColor(255, 200, 0,   255);
            }
        }

        // ── Line 5: Chunks | Players ──────────────────────────────────────────────
        String chunkLabel = null, chunkValue = null;
        String playerLabel = null, playerValue = null;
        if (showChunks.get() && mc.worldRenderer != null) {
            chunkLabel = "Chunks: ";
            chunkValue = String.valueOf(mc.worldRenderer.getCompletedChunkCount());
        }
        if (showPlayerCount.get() && mc.getNetworkHandler() != null) {
            playerLabel = "Players: ";
            playerValue = String.valueOf(mc.getNetworkHandler().getPlayerList().size());
        }

        // ── Line 6: Distance Traveled ─────────────────────────────────────────────
        String distLabel = null, distValue = null;
        if (showDistance.get()) {
            distLabel = "Traveled: ";
            distValue = switch (distanceUnit.get()) {
                case Blocks -> String.format("%.0f m", distanceTraveled);
                case Km     -> String.format("%.3f km", distanceTraveled / 1000.0);
                case Both   -> String.format("%.0f m  /  %.3f km", distanceTraveled, distanceTraveled / 1000.0);
            };
        }

        // ── Line 7: Time Online ───────────────────────────────────────────────────
        String timeLabel = null, timeValue = null;
        if (showTimeOnline.get() && sessionStartMs >= 0) {
            long totalSecs = (System.currentTimeMillis() - sessionStartMs) / 1000L;
            long hours     = totalSecs / 3600;
            long minutes   = (totalSecs % 3600) / 60;
            long seconds   = totalSecs % 60;
            timeLabel = "Online: ";
            timeValue = switch (timeFormat.get()) {
                case Seconds -> String.format("%ds", totalSecs);
                case HM      -> hours > 0
                    ? String.format("%dh %02dm", hours, minutes)
                    : String.format("%dm", minutes);
                case HMS     -> hours > 0
                    ? String.format("%dh %02dm %02ds", hours, minutes, seconds)
                    : minutes > 0
                    ? String.format("%dm %02ds", minutes, seconds)
                    : String.format("%ds", seconds);
            };
        }

        // ── Line 8: Stability ─────────────────────────────────────────────────────
        String stabLabel = null, stabValue = null;
        SettingColor stabColor = valueColor.get();
        if (showStability.get()) {
            long lastTick = (long) TickRate.INSTANCE.getTimeSinceLastTick();
            stabLabel = "Stability: ";
            if (lastTick > 1000) {
                stabValue = "DESYNC";
                stabColor = new SettingColor(255, 60, 60, 255);
            } else {
                stabValue = lastTick + "ms";
                if (lastTick > 250) stabColor = new SettingColor(255, 200, 0, 255);
            }
        }

        // ── Line 10: TPS Guard ────────────────────────────────────────────────────
        String guardLabel = null, guardValue = null;
        if (tpsGuard.get() && tps < tpsGuardThreshold.get() && !(tpsGuardHideStatic.get() && getSpeedBps() < 0.1)) {
            guardLabel = "! TPS Guard: ";
            guardValue = "DANGER";
        }

        // ── Measure all line widths ───────────────────────────────────────────────
        double line1W = 0, line2W = 0, line3W = 0, line4W = 0, line5W = 0, line6W = 0, line7W = 0, line8W = 0, line9W = 0, line10W = 0;

        if (speedLabel  != null) line1W = renderer.textWidth(speedLabel,  false, s) + renderer.textWidth(speedValue,  false, s);
        if (fpsLabel    != null) line2W += renderer.textWidth(fpsLabel,   false, s) + renderer.textWidth(fpsValue,   false, s);
        if (fpsLabel    != null && tpsLabel != null) line2W += sepW;
        if (tpsLabel    != null) line2W += renderer.textWidth(tpsLabel,   false, s) + renderer.textWidth(tpsValue,   false, s);
        if (dirLabel    != null) line3W = renderer.textWidth(dirLabel,    false, s) + renderer.textWidth(dirValue,   false, s);
        if (coordsLabel != null) line9W = renderer.textWidth(coordsLabel, false, s) + renderer.textWidth(coordsValue, false, s);
        if (memLabel    != null) line4W = renderer.textWidth(memLabel,    false, s) + renderer.textWidth(memValue,   false, s);
        if (chunkLabel  != null) line5W += renderer.textWidth(chunkLabel, false, s) + renderer.textWidth(chunkValue, false, s);
        if (chunkLabel  != null && playerLabel != null) line5W += sepW;
        if (playerLabel != null) line5W += renderer.textWidth(playerLabel, false, s) + renderer.textWidth(playerValue, false, s);
        if (distLabel   != null) line6W = renderer.textWidth(distLabel,   false, s) + renderer.textWidth(distValue,  false, s);
        if (timeLabel   != null) line7W = renderer.textWidth(timeLabel,   false, s) + renderer.textWidth(timeValue,  false, s);
        if (stabLabel   != null) line8W = renderer.textWidth(stabLabel,   false, s) + renderer.textWidth(stabValue,  false, s);
        if (guardLabel  != null) line10W = renderer.textWidth(guardLabel,  false, s) + renderer.textWidth(guardValue,  false, s);

        boolean hasLine1 = speedLabel  != null;
        boolean hasLine2 = fpsLabel    != null || tpsLabel    != null;
        boolean hasLine3 = dirLabel    != null;
        boolean hasLine9 = coordsLabel != null;
        boolean hasLine4 = memLabel    != null;
        boolean hasLine5 = chunkLabel  != null || playerLabel != null;
        boolean hasLine6 = distLabel   != null;
        boolean hasLine7 = timeLabel   != null;
        boolean hasLine8 = stabLabel   != null;
        boolean hasLine10 = guardLabel != null;

        if (!hasLine1 && !hasLine2 && !hasLine3 && !hasLine4 && !hasLine5 && !hasLine6 && !hasLine7 && !hasLine8 && !hasLine9 && !hasLine10) {
            setSize(0, 0); return;
        }

        double maxLineW  = Math.max(line1W, Math.max(line2W, Math.max(line3W, Math.max(line4W,
                           Math.max(line5W, Math.max(line6W, Math.max(line7W, Math.max(line8W, Math.max(line9W, line10W)))))))));
        double totalW    = maxLineW + padH * 2;
        int    lineCount = (hasLine1 ? 1 : 0) + (hasLine2 ? 1 : 0) + (hasLine3 ? 1 : 0)
                         + (hasLine4 ? 1 : 0) + (hasLine5 ? 1 : 0) + (hasLine6 ? 1 : 0) + (hasLine9 ? 1 : 0)
                         + (hasLine7 ? 1 : 0) + (hasLine8 ? 1 : 0) + (hasLine10 ? 1 : 0);
        double totalH    = lineCount * lineHeight + (lineCount - 1) * rowGap + padV * 2;

        int lineIdx = 0;

        if (hasLine1) lineIdx = drawLabelValue(renderer, s, padH, padV, rowGap, lineHeight,
            rightAlign, totalW, line1W, lineIdx, speedLabel, speedValue, labelColor.get(), valueColor.get());

        if (hasLine2) lineIdx = drawPair(renderer, s, padH, padV, rowGap, lineHeight, sepW,
            rightAlign, totalW, line2W, lineIdx,
            fpsLabel, fpsValue, valueColor.get(), tpsLabel, tpsValue, tpsColor);

        if (hasLine3) lineIdx = drawLabelValue(renderer, s, padH, padV, rowGap, lineHeight,
            rightAlign, totalW, line3W, lineIdx, dirLabel, dirValue, labelColor.get(), valueColor.get());

        if (hasLine9) lineIdx = drawLabelValue(renderer, s, padH, padV, rowGap, lineHeight,
            rightAlign, totalW, line9W, lineIdx, coordsLabel, coordsValue, labelColor.get(), valueColor.get());

        if (hasLine4) lineIdx = drawLabelValue(renderer, s, padH, padV, rowGap, lineHeight,
            rightAlign, totalW, line4W, lineIdx, memLabel, memValue, labelColor.get(), memColor);

        if (hasLine5) lineIdx = drawPair(renderer, s, padH, padV, rowGap, lineHeight, sepW,
            rightAlign, totalW, line5W, lineIdx,
            chunkLabel, chunkValue, valueColor.get(), playerLabel, playerValue, valueColor.get());

        if (hasLine6) lineIdx = drawLabelValue(renderer, s, padH, padV, rowGap, lineHeight,
            rightAlign, totalW, line6W, lineIdx, distLabel, distValue, labelColor.get(), valueColor.get());

        if (hasLine7) lineIdx = drawLabelValue(renderer, s, padH, padV, rowGap, lineHeight,
            rightAlign, totalW, line7W, lineIdx, timeLabel, timeValue, labelColor.get(), valueColor.get());

        if (hasLine8) lineIdx = drawLabelValue(renderer, s, padH, padV, rowGap, lineHeight,
            rightAlign, totalW, line8W, lineIdx, stabLabel, stabValue, labelColor.get(), stabColor);

        if (hasLine10) lineIdx = drawLabelValue(renderer, s, padH, padV, rowGap, lineHeight,
            rightAlign, totalW, line10W, lineIdx, guardLabel, guardValue, labelColor.get(), tpsGuardColor.get());

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
                cx -= vw; renderer.text(valueB, cx, rowY, colorB, false, s);
                cx -= lw; renderer.text(labelB, cx, rowY, labelColor.get(), false, s);
            }
            if (labelA != null && labelB != null) {
                cx -= sepW;
                renderer.text(" | ", cx, rowY, separatorColor.get(), false, s);
            }
            if (labelA != null) {
                double vw = renderer.textWidth(valueA, false, s);
                double lw = renderer.textWidth(labelA, false, s);
                cx -= vw; renderer.text(valueA, cx, rowY, colorA, false, s);
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

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private double getSpeedBps() {
        if (mc.player == null) return 0.0;
        double dx = mc.player.getX() - mc.player.lastRenderX;
        double dz = mc.player.getZ() - mc.player.lastRenderZ;
        return Math.sqrt(dx * dx + dz * dz) * 20.0;
    }

    private String getCardinal(float yaw) {
        if (yaw < 22.5f  || yaw >= 337.5f) return "S";
        if (yaw < 67.5f)                   return "SW";
        if (yaw < 112.5f)                  return "W";
        if (yaw < 157.5f)                  return "NW";
        if (yaw < 202.5f)                  return "N";
        if (yaw < 247.5f)                  return "NE";
        if (yaw < 292.5f)                  return "E";
        return                                    "SE";
    }
}