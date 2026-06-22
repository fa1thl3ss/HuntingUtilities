package com.example.addon.modules;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.world.Timer;
import meteordevelopment.meteorclient.utils.world.TickRate;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;

public class Timethrottle extends Module {

    // ═══════════════════════════════════════════════════════════════════════════
    // Constants
    // ═══════════════════════════════════════════════════════════════════════════

    private static final double NORMAL_SPEED = 1.0;
    private static final int GRACE_PERIOD_TICKS = 100;

    // ═══════════════════════════════════════════════════════════════════════════
    // Throttle Mode Enum
    // ═══════════════════════════════════════════════════════════════════════════

    public enum ThrottleMode {
        Off("Off", "Disables this throttle source entirely"),
        Linear("Linear", "Smooth linear interpolation between full and minimum speed"),
        Step("Step", "Binary toggle — either full speed or minimum speed"),
        Aggressive("Aggressive", "Exponential curve that drops speed rapidly");

        private final String title;
        private final String description;

        ThrottleMode(String title, String description) {
            this.title = title;
            this.description = description;
        }

        @Override
        public String toString() {
            return title;
        }

        public String getDescription() {
            return description;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Safety Reason Enum
    // ═══════════════════════════════════════════════════════════════════════════

    public enum SafetyReason {
        None("None"),
        Hurt("Recently Hurt"),
        HostileNearby("Hostile Nearby"),
        PlayerNearby("Player Nearby");

        private final String title;

        SafetyReason(String title) {
            this.title = title;
        }

        @Override
        public String toString() {
            return title;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Source Info
    // ═══════════════════════════════════════════════════════════════════════════

    public static class SourceInfo {
        public String name;
        public ThrottleMode mode;
        public double value;
        public boolean active;

        public SourceInfo(String name, ThrottleMode mode) {
            this.name = name;
            this.mode = mode;
            this.value = NORMAL_SPEED;
            this.active = false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Setting Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgTps = settings.createGroup("TPS");
    private final SettingGroup sgChunks = settings.createGroup("Chunks");
    private final SettingGroup sgPing = settings.createGroup("Ping");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — General
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Double> smoothing = sgGeneral.add(new DoubleSetting.Builder()
        .name("smoothing")
        .description("Transition speed. 0 = instant, higher = more gradual.")
        .defaultValue(0.1)
        .min(0.0)
        .max(0.99)
        .sliderMax(0.5)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — TPS
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<ThrottleMode> tpsMode = sgTps.add(new EnumSetting.Builder<ThrottleMode>()
        .name("mode")
        .description("How TPS affects game speed.")
        .defaultValue(ThrottleMode.Linear)
        .build()
    );

    private final Setting<Double> tpsTarget = sgTps.add(new DoubleSetting.Builder()
        .name("target-tps")
        .description("TPS above which no throttling occurs.")
        .defaultValue(19.0)
        .min(1.0)
        .max(20.0)
        .sliderMax(20.0)
        .visible(() -> tpsMode.get() != ThrottleMode.Off)
        .build()
    );

    private final Setting<Double> tpsMinimum = sgTps.add(new DoubleSetting.Builder()
        .name("min-tps")
        .description("TPS at which minimum speed is applied.")
        .defaultValue(10.0)
        .min(1.0)
        .max(20.0)
        .sliderMax(20.0)
        .visible(() -> tpsMode.get() != ThrottleMode.Off)
        .build()
    );

    private final Setting<Double> tpsMinSpeed = sgTps.add(new DoubleSetting.Builder()
        .name("min-speed")
        .description("Speed multiplier at or below min-tps.")
        .defaultValue(0.5)
        .min(0.1)
        .max(1.0)
        .sliderMax(1.0)
        .visible(() -> tpsMode.get() != ThrottleMode.Off)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Chunks
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<ThrottleMode> chunkMode = sgChunks.add(new EnumSetting.Builder<ThrottleMode>()
        .name("mode")
        .description("How chunk loading affects game speed.")
        .defaultValue(ThrottleMode.Step)
        .build()
    );

    private final Setting<Integer> chunkThreshold = sgChunks.add(new IntSetting.Builder()
        .name("threshold")
        .description("Unloaded chunks to trigger throttling.")
        .defaultValue(50)
        .min(1)
        .sliderMax(200)
        .visible(() -> chunkMode.get() != ThrottleMode.Off)
        .build()
    );

    private final Setting<Double> chunkMinSpeed = sgChunks.add(new DoubleSetting.Builder()
        .name("min-speed")
        .description("Speed multiplier during heavy chunk loading.")
        .defaultValue(0.7)
        .min(0.1)
        .max(1.0)
        .sliderMax(1.0)
        .visible(() -> chunkMode.get() != ThrottleMode.Off)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Ping
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<ThrottleMode> pingMode = sgPing.add(new EnumSetting.Builder<ThrottleMode>()
        .name("mode")
        .description("How ping affects game speed.")
        .defaultValue(ThrottleMode.Linear)
        .build()
    );

    private final Setting<Integer> pingThreshold = sgPing.add(new IntSetting.Builder()
        .name("threshold")
        .description("Ping (ms) above which throttling begins.")
        .defaultValue(150)
        .min(20)
        .sliderMin(20)
        .sliderMax(500)
        .visible(() -> pingMode.get() != ThrottleMode.Off)
        .build()
    );

    private final Setting<Integer> pingMaximum = sgPing.add(new IntSetting.Builder()
        .name("max-ping")
        .description("Ping (ms) at which minimum speed is applied.")
        .defaultValue(400)
        .min(50)
        .sliderMin(50)
        .sliderMax(1000)
        .visible(() -> pingMode.get() != ThrottleMode.Off)
        .build()
    );

    private final Setting<Double> pingMinSpeed = sgPing.add(new DoubleSetting.Builder()
        .name("min-speed")
        .description("Speed multiplier at or above max-ping.")
        .defaultValue(0.6)
        .min(0.1)
        .max(1.0)
        .sliderMax(1.0)
        .visible(() -> pingMode.get() != ThrottleMode.Off)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Safety
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> combatSafety = sgSafety.add(new BoolSetting.Builder()
        .name("combat-safety")
        .description("Disables throttling near enemies or when hurt.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> safetyRange = sgSafety.add(new IntSetting.Builder()
        .name("range")
        .description("Detection radius for hostile entities and players.")
        .defaultValue(15)
        .min(0)
        .sliderMax(32)
        .visible(combatSafety::get)
        .build()
    );

    private final Setting<Integer> safetyDuration = sgSafety.add(new IntSetting.Builder()
        .name("duration")
        .description("Ticks to keep throttling disabled after trigger.")
        .defaultValue(60)
        .min(0)
        .sliderMax(200)
        .visible(combatSafety::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Runtime State
    // ═══════════════════════════════════════════════════════════════════════════

    private double currentSpeed = NORMAL_SPEED;
    private int safetyTicks = 0;
    private int graceTicks = 0;
    private SafetyReason lastSafetyReason = SafetyReason.None;

    private final SourceInfo tpsInfo = new SourceInfo("TPS", ThrottleMode.Linear);
    private final SourceInfo chunkInfo = new SourceInfo("Chunks", ThrottleMode.Step);
    private final SourceInfo pingInfo = new SourceInfo("Ping", ThrottleMode.Linear);
    private final SourceInfo[] sources = { tpsInfo, chunkInfo, pingInfo };

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════════

    public Timethrottle() {
        super(HuntingUtilities.CATEGORY, "time-throttle",
            "Dynamically adjusts timer based on TPS, chunks, and ping with configurable modes.");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Module Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void onActivate() {
        resetState();
        Modules.get().get(Timer.class).setOverride(NORMAL_SPEED);
    }

    @Override
    public void onDeactivate() {
        applySpeed(NORMAL_SPEED);
    }

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        resetState();
        Modules.get().get(Timer.class).setOverride(NORMAL_SPEED);
    }

    private void resetState() {
        currentSpeed = NORMAL_SPEED;
        safetyTicks = 0;
        graceTicks = GRACE_PERIOD_TICKS;
        lastSafetyReason = SafetyReason.None;

        for (SourceInfo info : sources) {
            info.value = NORMAL_SPEED;
            info.active = false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Main Tick Logic
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;

        // Guard: Still on loading screen
        if (!isPlayerChunkLoaded()) {
            resetSourceStates();
            applySpeed(NORMAL_SPEED);
            return;
        }

        // Guard: Grace period after activation
        if (graceTicks > 0) {
            graceTicks--;
            resetSourceStates();
            applySpeed(NORMAL_SPEED);
            return;
        }

        // Step 1: Safety check
        updateSafety();
        if (safetyTicks > 0) {
            safetyTicks--;
            resetSourceStates();
            applySpeed(NORMAL_SPEED);
            return;
        }
        lastSafetyReason = SafetyReason.None;

        // Step 2: Evaluate all throttle sources
        evaluateTps();
        evaluateChunks();
        evaluatePing();

        // Step 3: Determine most restrictive source
        double desired = computeDesiredSpeed();

        // Step 4: Smooth transition and apply
        smoothAndApply(desired);
    }

    private void resetSourceStates() {
        for (SourceInfo info : sources) {
            info.value = NORMAL_SPEED;
            info.active = false;
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TPS Evaluation
    // ═══════════════════════════════════════════════════════════════════════════

    private void evaluateTps() {
        ThrottleMode mode = tpsMode.get();
        tpsInfo.mode = mode;

        if (mode == ThrottleMode.Off) {
            tpsInfo.value = NORMAL_SPEED;
            tpsInfo.active = false;
            return;
        }

        double tps = TickRate.INSTANCE.getTickRate();
        double target = tpsTarget.get();
        double minimum = tpsMinimum.get();
        double minSpeed = tpsMinSpeed.get();

        if (tps >= target) {
            tpsInfo.value = NORMAL_SPEED;
            tpsInfo.active = false;
            return;
        }

        tpsInfo.active = true;

        if (tps <= minimum) {
            tpsInfo.value = minSpeed;
            return;
        }

        double progress = (tps - minimum) / (target - minimum);
        tpsInfo.value = calculateSpeed(mode, progress, minSpeed);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Chunk Evaluation
    // ═══════════════════════════════════════════════════════════════════════════

    private void evaluateChunks() {
        ThrottleMode mode = chunkMode.get();
        chunkInfo.mode = mode;

        if (mode == ThrottleMode.Off) {
            chunkInfo.value = NORMAL_SPEED;
            chunkInfo.active = false;
            return;
        }

        // Don't throttle until immediate 3x3 area is loaded
        if (!isImmediateAreaLoaded()) {
            chunkInfo.value = NORMAL_SPEED;
            chunkInfo.active = false;
            return;
        }

        int unloaded = countUnloadedChunks();
        int threshold = chunkThreshold.get();
        double minSpeed = chunkMinSpeed.get();

        if (unloaded <= threshold) {
            chunkInfo.value = NORMAL_SPEED;
            chunkInfo.active = false;
            return;
        }

        chunkInfo.active = true;

        int maxPossible = getMaxPossibleChunks();
        double progress = Math.min(1.0, (double) (unloaded - threshold) / (maxPossible - threshold));
        chunkInfo.value = calculateSpeed(mode, progress, minSpeed);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Ping Evaluation
    // ═══════════════════════════════════════════════════════════════════════════

    private void evaluatePing() {
        ThrottleMode mode = pingMode.get();
        pingInfo.mode = mode;

        if (mode == ThrottleMode.Off) {
            pingInfo.value = NORMAL_SPEED;
            pingInfo.active = false;
            return;
        }

        int ping = getPlayerPing();
        int threshold = pingThreshold.get();
        int maximum = pingMaximum.get();
        double minSpeed = pingMinSpeed.get();

        if (ping <= threshold) {
            pingInfo.value = NORMAL_SPEED;
            pingInfo.active = false;
            return;
        }

        pingInfo.active = true;

        if (ping >= maximum) {
            pingInfo.value = minSpeed;
            return;
        }

        double progress = (double) (ping - threshold) / (maximum - threshold);
        pingInfo.value = calculateSpeed(mode, progress, minSpeed);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Speed Calculation
    // ═══════════════════════════════════════════════════════════════════════════

    private double calculateSpeed(ThrottleMode mode, double progress, double minSpeed) {
        return switch (mode) {
            case Linear -> MathHelper.lerp(minSpeed, NORMAL_SPEED, progress);
            case Step -> progress < 0.5 ? minSpeed : NORMAL_SPEED;
            case Aggressive -> MathHelper.lerp(minSpeed, NORMAL_SPEED, progress * progress);
            default -> NORMAL_SPEED;
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Safety System
    // ═══════════════════════════════════════════════════════════════════════════

    private void updateSafety() {
        if (!combatSafety.get()) return;

        SafetyReason reason = detectSafetyReason();
        if (reason != SafetyReason.None) {
            lastSafetyReason = reason;
            safetyTicks = safetyDuration.get();
        }
    }

    private SafetyReason detectSafetyReason() {
        if (mc.player.hurtTime > 0) return SafetyReason.Hurt;

        int range = safetyRange.get();
        if (range <= 0) return SafetyReason.None;

        Box searchBox = mc.player.getBoundingBox().expand(range);

        if (!mc.world.getEntitiesByClass(HostileEntity.class, searchBox, Entity::isAlive).isEmpty()) {
            return SafetyReason.HostileNearby;
        }

        if (!mc.world.getEntitiesByClass(PlayerEntity.class, searchBox,
            p -> p != mc.player && p.isAlive()).isEmpty()) {
            return SafetyReason.PlayerNearby;
        }

        return SafetyReason.None;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Speed Application
    // ═══════════════════════════════════════════════════════════════════════════

    private double computeDesiredSpeed() {
        double desired = NORMAL_SPEED;
        for (SourceInfo info : sources) {
            if (info.value < desired) {
                desired = info.value;
            }
        }
        return desired;
    }

    private void smoothAndApply(double desired) {
        double smoothed = MathHelper.lerp(1.0 - smoothing.get(), currentSpeed, desired);
        applySpeed(smoothed);
    }

    private void applySpeed(double speed) {
        if (Double.isNaN(speed) || Double.isInfinite(speed) || speed <= 0.0) {
            speed = NORMAL_SPEED;
        }
        currentSpeed = speed;
        Modules.get().get(Timer.class).setOverride(speed);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Utility Methods
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean isPlayerChunkLoaded() {
        return mc.world.getChunkManager().isChunkLoaded(
            mc.player.getChunkPos().x,
            mc.player.getChunkPos().z
        );
    }

    private boolean isImmediateAreaLoaded() {
        int cx = mc.player.getChunkPos().x;
        int cz = mc.player.getChunkPos().z;
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (!mc.world.getChunkManager().isChunkLoaded(cx + dx, cz + dz)) {
                    return false;
                }
            }
        }
        return true;
    }

    private int countUnloadedChunks() {
        int unloaded = 0;
        int viewDistance = mc.options.getClampedViewDistance();
        int cx = mc.player.getChunkPos().x;
        int cz = mc.player.getChunkPos().z;

        for (int dx = -viewDistance; dx <= viewDistance; dx++) {
            for (int dz = -viewDistance; dz <= viewDistance; dz++) {
                if (!mc.world.getChunkManager().isChunkLoaded(cx + dx, cz + dz)) {
                    unloaded++;
                }
            }
        }
        return unloaded;
    }

    private int getMaxPossibleChunks() {
        int vd = mc.options.getClampedViewDistance();
        return (vd * 2 + 1) * (vd * 2 + 1);
    }

    private int getPlayerPing() {
        if (mc.getNetworkHandler() == null) return 0;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry != null ? entry.getLatency() : 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HUD Info String
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public String getInfoString() {
        if (isSafetyActive()) {
            return String.format("%.2f [SAFE: %s]", currentSpeed, lastSafetyReason);
        }

        SourceInfo limiting = getLimitingSource();
        if (limiting != null) {
            return String.format("%.2f [%s]", currentSpeed, limiting.name);
        }

        return String.format("%.2f", currentSpeed);
    }

    private SourceInfo getLimitingSource() {
        SourceInfo limiting = null;
        double min = NORMAL_SPEED;

        for (SourceInfo info : sources) {
            if (info.active && info.value < min) {
                min = info.value;
                limiting = info;
            }
        }
        return limiting;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Public Accessors
    // ═══════════════════════════════════════════════════════════════════════════

    public double getCurrentSpeed() {
        return currentSpeed;
    }

    public boolean isSafetyActive() {
        return safetyTicks > 0;
    }

    public SafetyReason getLastSafetyReason() {
        return lastSafetyReason;
    }

    public int getSafetyTicksRemaining() {
        return safetyTicks;
    }

    public SourceInfo getTpsInfo() {
        return tpsInfo;
    }

    public SourceInfo getChunkInfo() {
        return chunkInfo;
    }

    public SourceInfo getPingInfo() {
        return pingInfo;
    }

    public SourceInfo[] getSources() {
        return sources;
    }

    public SourceInfo getLimitingSourceInfo() {
        return getLimitingSource();
    }

    public boolean isThrottling() {
        return currentSpeed < NORMAL_SPEED - 0.001;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Legacy Accessors (Backward compatibility)
    // ═══════════════════════════════════════════════════════════════════════════

    public int sourceCount() {
        return sources.length;
    }

    public String sourceName(int i) {
        if (i < 0 || i >= sources.length) return "?";
        return sources[i].name;
    }

    public double evaluateSource(int i) {
        if (i < 0 || i >= sources.length) return NORMAL_SPEED;
        return sources[i].value;
    }
}