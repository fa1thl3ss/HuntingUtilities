package com.example.addon.modules;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.game.GameLeftEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
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

    private static final double NORMAL_SPEED  = 1.0;
    private static final int    GRACE_PERIOD  = 100; // ticks (~5 s) after activation

    // ═══════════════════════════════════════════════════════════════════════════
    // ThrottleSource
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * A single throttle trigger.
     * evaluate() returns the desired speed multiplier: 1.0 = full speed, lower = slower.
     * The tick handler takes the minimum across all active sources.
     */
    private interface ThrottleSource {
        String name();
        double evaluate();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // SafetyReason
    // ═══════════════════════════════════════════════════════════════════════════

    public enum SafetyReason {
        NONE,
        HURT,
        HOSTILE_NEARBY,
        PLAYER_NEARBY
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Setting Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgGeneral      = settings.getDefaultGroup();
    private final SettingGroup sgTps          = settings.createGroup("TPS");
    private final SettingGroup sgChunkLoading = settings.createGroup("Chunk Loading");
    private final SettingGroup sgPing         = settings.createGroup("Ping");
    private final SettingGroup sgSafety       = settings.createGroup("Safety");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — General
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Double> smoothing = sgGeneral.add(new DoubleSetting.Builder()
        .name("smoothing")
        .description("How quickly the speed adjusts. 0 = instant, ~0.5 = gradual.")
        .defaultValue(0.1).min(0.0).max(0.99).sliderMax(0.5)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — TPS
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Double> targetTps = sgTps.add(new DoubleSetting.Builder()
        .name("target-tps")
        .description("TPS above which no throttling is applied.")
        .defaultValue(19.0).min(1).max(20).sliderMax(20)
        .build()
    );

    private final Setting<Double> minTps = sgTps.add(new DoubleSetting.Builder()
        .name("min-tps")
        .description("TPS at which the slowest speed is applied.")
        .defaultValue(10.0).min(1).max(20).sliderMax(20)
        .build()
    );

    private final Setting<Double> tpsMinSpeed = sgTps.add(new DoubleSetting.Builder()
        .name("min-speed")
        .description("Speed multiplier applied when TPS is at or below min-tps.")
        .defaultValue(0.5).min(0.1).max(1.0).sliderMax(1.0)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Chunk Loading
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> chunkThrottle = sgChunkLoading.add(new BoolSetting.Builder()
        .name("chunk-throttle")
        .description("Slow down when many chunks are loading.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> chunkLoadThreshold = sgChunkLoading.add(new IntSetting.Builder()
        .name("chunk-load-threshold")
        .description("Unloaded chunk count that triggers throttling.")
        .defaultValue(50).min(1).sliderMax(200)
        .visible(chunkThrottle::get)
        .build()
    );

    private final Setting<Double> chunkLoadSlowdown = sgChunkLoading.add(new DoubleSetting.Builder()
        .name("chunk-load-slowdown")
        .description("Speed multiplier applied during heavy chunk loading.")
        .defaultValue(0.7).min(0.1).max(1.0).sliderMax(1.0)
        .visible(chunkThrottle::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Ping
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> pingThrottle = sgPing.add(new BoolSetting.Builder()
        .name("ping-throttle")
        .description("Slow down when server ping is high.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> pingThreshold = sgPing.add(new IntSetting.Builder()
        .name("ping-threshold")
        .description("Ping (ms) above which throttling begins.")
        .defaultValue(150).min(20).sliderMin(20).sliderMax(500)
        .visible(pingThrottle::get)
        .build()
    );

    private final Setting<Integer> maxPing = sgPing.add(new IntSetting.Builder()
        .name("max-ping")
        .description("Ping (ms) at which the slowest speed is applied.")
        .defaultValue(400).min(50).sliderMin(50).sliderMax(1000)
        .visible(pingThrottle::get)
        .build()
    );

    private final Setting<Double> pingMinSpeed = sgPing.add(new DoubleSetting.Builder()
        .name("ping-min-speed")
        .description("Speed multiplier applied when ping is at or above max-ping.")
        .defaultValue(0.6).min(0.1).max(1.0).sliderMax(1.0)
        .visible(pingThrottle::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Safety
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> combatSafety = sgSafety.add(new BoolSetting.Builder()
        .name("combat-safety")
        .description("Disables throttling when in combat or near enemies.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> safetyRange = sgSafety.add(new IntSetting.Builder()
        .name("safety-range")
        .description("Radius to check for hostile entities or players.")
        .defaultValue(15).min(0).sliderMax(32)
        .visible(combatSafety::get)
        .build()
    );

    private final Setting<Integer> safetyDuration = sgSafety.add(new IntSetting.Builder()
        .name("safety-duration")
        .description("Ticks to keep throttling disabled after a safety trigger.")
        .defaultValue(60).min(0).sliderMax(200)
        .visible(combatSafety::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════════

    private double       currentSpeed     = NORMAL_SPEED;
    private int          safetyTicks      = 0;
    private int          graceTicks       = 0;
    private SafetyReason lastSafetyReason = SafetyReason.NONE;

    // ═══════════════════════════════════════════════════════════════════════════
    // ThrottleSource instances
    // ═══════════════════════════════════════════════════════════════════════════

    private final ThrottleSource tpsSource = new ThrottleSource() {
        @Override public String name() { return "TPS"; }
        @Override public double evaluate() {
            double tps = TickRate.INSTANCE.getTickRate();
            if (tps >= targetTps.get()) return NORMAL_SPEED;
            if (tps <= minTps.get())    return tpsMinSpeed.get();
            return MathHelper.map(tps, minTps.get(), targetTps.get(), tpsMinSpeed.get(), NORMAL_SPEED);
        }
    };

    private final ThrottleSource chunkSource = new ThrottleSource() {
        @Override public String name() { return "Chunks"; }
        @Override public double evaluate() {
            if (!chunkThrottle.get()) return NORMAL_SPEED;

            // Don't throttle until the 3×3 area immediately around the player is loaded.
            // This prevents the feedback loop that traps the player on the loading screen.
            int px = mc.player.getChunkPos().x;
            int pz = mc.player.getChunkPos().z;
            for (int dx = -1; dx <= 1; dx++)
                for (int dz = -1; dz <= 1; dz++)
                    if (!mc.world.getChunkManager().isChunkLoaded(px + dx, pz + dz))
                        return NORMAL_SPEED;

            return countUnloadedChunks() > chunkLoadThreshold.get()
                ? chunkLoadSlowdown.get()
                : NORMAL_SPEED;
        }
    };

    private final ThrottleSource pingSource = new ThrottleSource() {
        @Override public String name() { return "Ping"; }
        @Override public double evaluate() {
            if (!pingThrottle.get()) return NORMAL_SPEED;
            int ping = getPlayerPing();
            if (ping <= pingThreshold.get()) return NORMAL_SPEED;
            if (ping >= maxPing.get())       return pingMinSpeed.get();
            return MathHelper.map(ping, pingThreshold.get(), maxPing.get(), NORMAL_SPEED, pingMinSpeed.get());
        }
    };

    /** Add new ThrottleSource instances here to extend the system. */
    private final ThrottleSource[] sources = { tpsSource, chunkSource, pingSource };

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════════

    public Timethrottle() {
        super(HuntingUtilities.CATEGORY, "time-throttle",
            "Automatically adjusts game speed based on server TPS, chunk loading, and ping.");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void onActivate() {
        currentSpeed     = NORMAL_SPEED;
        safetyTicks      = 0;
        graceTicks       = GRACE_PERIOD;
        lastSafetyReason = SafetyReason.NONE;
        // Clear any stale Timer override immediately (e.g. from a previous session)
        Modules.get().get(Timer.class).setOverride(NORMAL_SPEED);
    }

    @Override
    public void onDeactivate() {
        applySpeed(NORMAL_SPEED);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Disconnect — reset Timer so reconnecting doesn't inherit a stale override
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onGameLeft(GameLeftEvent event) {
        currentSpeed     = NORMAL_SPEED;
        safetyTicks      = 0;
        graceTicks       = 0;
        lastSafetyReason = SafetyReason.NONE;
        Modules.get().get(Timer.class).setOverride(NORMAL_SPEED);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tick
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;

        // ── Guard 1: player's own chunk not yet loaded = still on loading screen ──
        if (!mc.world.getChunkManager().isChunkLoaded(
                mc.player.getChunkPos().x, mc.player.getChunkPos().z)) {
            applySpeed(NORMAL_SPEED);
            return;
        }

        // ── Guard 2: grace period after activation ────────────────────────────
        if (graceTicks > 0) {
            graceTicks--;
            applySpeed(NORMAL_SPEED);
            return;
        }

        // ── Step 1: safety ────────────────────────────────────────────────────
        updateSafety();

        if (safetyTicks > 0) {
            safetyTicks--;
            applySpeed(NORMAL_SPEED);
            return;
        }

        lastSafetyReason = SafetyReason.NONE;

        // ── Step 2: evaluate sources, smooth, apply ───────────────────────────
        smoothAndApply(computeDesiredSpeed());
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Step 1 — safety
    // ═══════════════════════════════════════════════════════════════════════════

    private void updateSafety() {
        if (!combatSafety.get()) return;
        SafetyReason reason = detectSafetyReason();
        if (reason != SafetyReason.NONE) {
            lastSafetyReason = reason;
            safetyTicks      = safetyDuration.get();
        }
    }

    private SafetyReason detectSafetyReason() {
        if (mc.player.hurtTime > 0) return SafetyReason.HURT;

        int range = safetyRange.get();
        if (range <= 0) return SafetyReason.NONE;

        Box box = mc.player.getBoundingBox().expand(range);

        if (!mc.world.getEntitiesByClass(HostileEntity.class, box, Entity::isAlive).isEmpty())
            return SafetyReason.HOSTILE_NEARBY;

        if (!mc.world.getEntitiesByClass(PlayerEntity.class, box,
                p -> p != mc.player && p.isAlive()).isEmpty())
            return SafetyReason.PLAYER_NEARBY;

        return SafetyReason.NONE;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Step 2 — evaluate all sources, take the most conservative
    // ═══════════════════════════════════════════════════════════════════════════

    private double computeDesiredSpeed() {
        double desired = NORMAL_SPEED;
        for (ThrottleSource source : sources)
            desired = Math.min(desired, source.evaluate());
        return desired;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Step 3 — smooth and apply
    // ═══════════════════════════════════════════════════════════════════════════

    private void smoothAndApply(double desired) {
        currentSpeed = MathHelper.lerp(1.0 - smoothing.get(), currentSpeed, desired);
        applySpeed(currentSpeed);
    }

    private void applySpeed(double speed) {
        // Guard against NaN/Infinite/non-positive values that would corrupt the Timer
        if (Double.isNaN(speed) || Double.isInfinite(speed) || speed <= 0.0) {
            speed = NORMAL_SPEED;
        }
        currentSpeed = speed;
        Modules.get().get(Timer.class).setOverride(speed);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Public accessors (for companion HUD)
    // ═══════════════════════════════════════════════════════════════════════════

    public double       getCurrentSpeed()     { return currentSpeed; }
    public boolean      isSafetyActive()      { return safetyTicks > 0; }
    public SafetyReason getLastSafetyReason() { return lastSafetyReason; }
    public int          sourceCount()         { return sources.length; }
    public String       sourceName(int i)     { return (i >= 0 && i < sources.length) ? sources[i].name() : "?"; }
    public double       evaluateSource(int i) { return (i >= 0 && i < sources.length) ? sources[i].evaluate() : NORMAL_SPEED; }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private int getPlayerPing() {
        if (mc.getNetworkHandler() == null) return 0;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return entry != null ? entry.getLatency() : 0;
    }

    private int countUnloadedChunks() {
        if (mc.world == null || mc.player == null) return 0;
        int unloaded     = 0;
        int viewDistance = mc.options.getClampedViewDistance();
        int cx           = mc.player.getChunkPos().x;
        int cz           = mc.player.getChunkPos().z;
        for (int dx = -viewDistance; dx <= viewDistance; dx++)
            for (int dz = -viewDistance; dz <= viewDistance; dz++)
                if (!mc.world.getChunkManager().isChunkLoaded(cx + dx, cz + dz))
                    unloaded++;
        return unloaded;
    }
}