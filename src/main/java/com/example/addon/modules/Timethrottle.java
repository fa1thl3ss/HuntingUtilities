package com.example.addon.modules;

import com.example.addon.HuntingUtilities;

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

    private final SettingGroup sgTps          = settings.createGroup("TPS");
    private final SettingGroup sgChunkLoading = settings.createGroup("Chunk Loading");
    private final SettingGroup sgPing         = settings.createGroup("Ping");
    private final SettingGroup sgSafety       = settings.createGroup("Safety");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — TPS
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Double> targetTps = sgTps.add(new DoubleSetting.Builder()
        .name("target-tps")
        .description("TPS above which the game runs at normal speed.")
        .defaultValue(19.0).min(1).max(20).sliderMax(20)
        .build()
    );

    private final Setting<Double> minTps = sgTps.add(new DoubleSetting.Builder()
        .name("min-tps")
        .description("TPS at which the game runs at its slowest speed.")
        .defaultValue(10.0).min(1).max(20).sliderMax(20)
        .build()
    );

    private final Setting<Double> minSpeed = sgTps.add(new DoubleSetting.Builder()
        .name("min-speed")
        .description("The minimum speed multiplier when TPS is at or below 'min-tps'.")
        .defaultValue(0.5).min(0.1).max(1.0).sliderMax(1.0)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Chunk Loading
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> chunkThrottle = sgChunkLoading.add(new BoolSetting.Builder()
        .name("chunk-throttle")
        .description("Also slow down the game when many chunks are loading.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> chunkLoadThreshold = sgChunkLoading.add(new IntSetting.Builder()
        .name("chunk-load-threshold")
        .description("Number of unloaded chunks in view distance to trigger throttling.")
        .defaultValue(50).min(1).sliderMax(200)
        .visible(chunkThrottle::get)
        .build()
    );

    private final Setting<Double> chunkLoadSlowdown = sgChunkLoading.add(new DoubleSetting.Builder()
        .name("chunk-load-slowdown")
        .description("Speed multiplier to apply when chunk loading is heavy.")
        .defaultValue(0.7).min(0.1).max(1.0).sliderMax(1.0)
        .visible(chunkThrottle::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Ping
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> pingThrottle = sgPing.add(new BoolSetting.Builder()
        .name("ping-throttle")
        .description("Slow down the game when server ping is high.")
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
        .description("Ping (ms) at which the game runs at its slowest speed.")
        .defaultValue(400).min(50).sliderMin(50).sliderMax(1000)
        .visible(pingThrottle::get)
        .build()
    );

    private final Setting<Double> pingMinSpeed = sgPing.add(new DoubleSetting.Builder()
        .name("ping-min-speed")
        .description("Minimum speed multiplier when ping is at or above 'max-ping'.")
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
        .description("Radius to check for hostile entities.")
        .defaultValue(15).min(0).sliderMax(32)
        .visible(combatSafety::get)
        .build()
    );

    private final Setting<Integer> safetyDuration = sgSafety.add(new IntSetting.Builder()
        .name("safety-duration")
        .description("How long (in ticks) to disable throttling after combat activity.")
        .defaultValue(60).min(0).sliderMax(200)
        .visible(combatSafety::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — General
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Double> smoothing = settings.getDefaultGroup().add(new DoubleSetting.Builder()
        .name("smoothing")
        .description("How quickly the speed adjusts. 0 is instant, 1 is no change.")
        .defaultValue(0.1).min(0.0).max(0.99).sliderMax(0.5)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════════

    private double currentSpeed = 1.0;
    private int    safetyTimer  = 0;

    public double getCurrentSpeed() {
    return currentSpeed;
}

public boolean isSafetyActive() {
    return safetyTimer > 0;
}

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
        currentSpeed = 1.0;
        safetyTimer  = 0;
    }

    @Override
    public void onDeactivate() {
        Modules.get().get(Timer.class).setOverride(1.0);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tick
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.world == null || mc.player == null) return;

        // ── Safety check ─────────────────────────────────────────────────────
        if (combatSafety.get()) {
            if (mc.player.hurtTime > 0) {
                safetyTimer = safetyDuration.get();
            } else if (safetyRange.get() > 0) {
                Box box = mc.player.getBoundingBox().expand(safetyRange.get());
                boolean enemyNearby =
                    !mc.world.getEntitiesByClass(HostileEntity.class, box, Entity::isAlive).isEmpty()
                    || !mc.world.getEntitiesByClass(PlayerEntity.class, box,
                            p -> p != mc.player && p.isAlive()).isEmpty();
                if (enemyNearby) safetyTimer = safetyDuration.get();
            }
        }

        if (safetyTimer > 0) {
            safetyTimer--;
            currentSpeed = 1.0;
            Modules.get().get(Timer.class).setOverride(1.0);
            return;
        }

        // ── TPS-based speed ──────────────────────────────────────────────────
        double tps      = TickRate.INSTANCE.getTickRate();
        double tpsSpeed = 1.0;

        if (tps <= targetTps.get()) {
            tpsSpeed = (tps <= minTps.get())
                ? minSpeed.get()
                : MathHelper.map(tps, minTps.get(), targetTps.get(), minSpeed.get(), 1.0);
        }

        // ── Chunk-loading-based speed ─────────────────────────────────────────
        double chunkSpeed = 1.0;
        if (chunkThrottle.get()) {
            if (countUnloadedChunks() > chunkLoadThreshold.get()) {
                chunkSpeed = chunkLoadSlowdown.get();
            }
        }

        // ── Ping-based speed ─────────────────────────────────────────────────
        double pingSpeed = 1.0;
        if (pingThrottle.get()) {
            int ping = getPlayerPing();
            if (ping > pingThreshold.get()) {
                pingSpeed = (ping >= maxPing.get())
                    ? pingMinSpeed.get()
                    : MathHelper.map(ping, pingThreshold.get(), maxPing.get(), 1.0, pingMinSpeed.get());
            }
        }

        // ── Combine: take the most conservative of all three ─────────────────
        double desiredSpeed = Math.min(tpsSpeed, Math.min(chunkSpeed, pingSpeed));

        // ── Smooth and apply ─────────────────────────────────────────────────
        currentSpeed = MathHelper.lerp(1.0 - smoothing.get(), currentSpeed, desiredSpeed);
        Modules.get().get(Timer.class).setOverride(currentSpeed);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Ping Helper
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Reads the local player's ping from the server's player list entry.
     * This is the same value displayed in the Tab menu — no extra packets needed.
     * Returns 0 on a singleplayer world or if the entry is unavailable.
     */
    private int getPlayerPing() {
        if (mc.getNetworkHandler() == null) return 0;
        PlayerListEntry entry = mc.getNetworkHandler().getPlayerListEntry(mc.player.getUuid());
        return (entry != null) ? entry.getLatency() : 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Chunk Helper
    // ═══════════════════════════════════════════════════════════════════════════

    private int countUnloadedChunks() {
        if (mc.world == null || mc.player == null) return 0;

        int unloaded      = 0;
        int viewDistance  = mc.options.getClampedViewDistance();
        int playerChunkX  = mc.player.getChunkPos().x;
        int playerChunkZ  = mc.player.getChunkPos().z;

        for (int x = -viewDistance; x <= viewDistance; x++) {
            for (int z = -viewDistance; z <= viewDistance; z++) {
                if (!mc.world.getChunkManager().isChunkLoaded(playerChunkX + x, playerChunkZ + z)) {
                    unloaded++;
                }
            }
        }
        return unloaded;
    }
}