package com.example.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.world.dimension.DimensionTypes;

import com.example.addon.HuntingUtilities;

public class EndSafe extends Module {

    // -------------------------------------------------------------------------
    // Sound options enum
    // -------------------------------------------------------------------------
    public enum WarnSound {
        Pling, Bell, Anvil, Basedrum, Chime, Hat;

        public SoundEvent getSoundEvent() {
            return switch (this) {
                case Pling    -> SoundEvents.BLOCK_NOTE_BLOCK_PLING.value();
                case Bell     -> SoundEvents.BLOCK_NOTE_BLOCK_BELL.value();
                case Anvil    -> SoundEvents.BLOCK_ANVIL_LAND;
                case Basedrum -> SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value();
                case Chime    -> SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value();
                case Hat      -> SoundEvents.BLOCK_NOTE_BLOCK_HAT.value();
            };
        }
    }

    // -------------------------------------------------------------------------
    // Setting groups
    // -------------------------------------------------------------------------
    private final SettingGroup sgGeneral      = settings.getDefaultGroup();
    private final SettingGroup sgWarn         = settings.createGroup("Warning Ping");
    private final SettingGroup sgDisconnect   = settings.createGroup("Auto Disconnect");
    private final SettingGroup sgOverworld    = settings.createGroup("Overworld Thresholds");
    private final SettingGroup sgEnd          = settings.createGroup("End Thresholds");
    private final SettingGroup sgGrace        = settings.createGroup("Grace Period");
    private final SettingGroup sgChorus       = settings.createGroup("Chorus Escape");

    // -------------------------------------------------------------------------
    // General
    // -------------------------------------------------------------------------
    private final Setting<Boolean> endOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("end-only")
        .description("Only activate in The End. Disable to also protect in the Overworld.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> perDimensionThresholds = sgGeneral.add(new BoolSetting.Builder()
        .name("per-dimension-thresholds")
        .description("Use separate warn and disconnect Y levels for the Overworld and End instead of shared values.")
        .defaultValue(false)
        .visible(() -> !endOnly.get())
        .build()
    );

    // -------------------------------------------------------------------------
    // Warning Ping
    // -------------------------------------------------------------------------
    private final Setting<Boolean> warnEnabled = sgWarn.add(new BoolSetting.Builder()
        .name("warn-enabled")
        .description("Play a sound and show a title warning when below the warning Y level.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> warnY = sgWarn.add(new IntSetting.Builder()
        .name("warn-y-level")
        .description("Y level at which the warning ping triggers. Used when per-dimension-thresholds is off.")
        .defaultValue(0)
        .range(-128, 320)
        .sliderRange(-128, 320)
        .build()
    );

    private final Setting<Integer> warnInterval = sgWarn.add(new IntSetting.Builder()
        .name("warn-interval")
        .description("How often the warning repeats while below the Y level, in ticks (20 = 1 second). Set to 0 to warn only once.")
        .defaultValue(40)
        .range(0, 200)
        .sliderRange(0, 200)
        .build()
    );

    private final Setting<WarnSound> warnSound = sgWarn.add(new EnumSetting.Builder<WarnSound>()
        .name("warn-sound")
        .description("The sound played when the warning triggers.")
        .defaultValue(WarnSound.Pling)
        .build()
    );

    private final Setting<Double> warnVolume = sgWarn.add(new DoubleSetting.Builder()
        .name("warn-volume")
        .description("Volume of the warning ping sound (0.0 = silent, 1.0 = full volume).")
        .defaultValue(1.0)
        .range(0.0, 1.0)
        .sliderRange(0.0, 1.0)
        .build()
    );

    // -------------------------------------------------------------------------
    // Auto Disconnect
    // -------------------------------------------------------------------------
    private final Setting<Boolean> disconnectEnabled = sgDisconnect.add(new BoolSetting.Builder()
        .name("disconnect-enabled")
        .description("Automatically disconnect when below the disconnect Y level.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> disconnectY = sgDisconnect.add(new IntSetting.Builder()
        .name("disconnect-y-level")
        .description("Y level at which auto-disconnect triggers. Used when per-dimension-thresholds is off.")
        .defaultValue(-10)
        .range(-128, 320)
        .sliderRange(-128, 320)
        .build()
    );

    // -------------------------------------------------------------------------
    // Overworld Thresholds
    // -------------------------------------------------------------------------
    private final Setting<Integer> overworldWarnY = sgOverworld.add(new IntSetting.Builder()
        .name("warn-y-level")
        .description("Overworld Y level at which the warning triggers.")
        .defaultValue(-60)
        .range(-128, 320)
        .sliderRange(-128, 320)
        .visible(() -> !endOnly.get() && perDimensionThresholds.get())
        .build()
    );

    private final Setting<Integer> overworldDisconnectY = sgOverworld.add(new IntSetting.Builder()
        .name("disconnect-y-level")
        .description("Overworld Y level at which auto-disconnect triggers.")
        .defaultValue(-70)
        .range(-128, 320)
        .sliderRange(-128, 320)
        .visible(() -> !endOnly.get() && perDimensionThresholds.get())
        .build()
    );

    // -------------------------------------------------------------------------
    // End Thresholds
    // -------------------------------------------------------------------------
    private final Setting<Integer> endWarnY = sgEnd.add(new IntSetting.Builder()
        .name("warn-y-level")
        .description("End Y level at which the warning triggers.")
        .defaultValue(0)
        .range(-128, 320)
        .sliderRange(-128, 320)
        .visible(() -> perDimensionThresholds.get() || endOnly.get())
        .build()
    );

    private final Setting<Integer> endDisconnectY = sgEnd.add(new IntSetting.Builder()
        .name("disconnect-y-level")
        .description("End Y level at which auto-disconnect triggers.")
        .defaultValue(-10)
        .range(-128, 320)
        .sliderRange(-128, 320)
        .visible(() -> perDimensionThresholds.get() || endOnly.get())
        .build()
    );

    // -------------------------------------------------------------------------
    // Grace Period
    // -------------------------------------------------------------------------
    private final Setting<Boolean> graceEnabled = sgGrace.add(new BoolSetting.Builder()
        .name("grace-enabled")
        .description("Wait a set number of ticks below the threshold before firing warnings or disconnect. Prevents false triggers from lag spikes or brief knockback.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> graceTicks = sgGrace.add(new IntSetting.Builder()
        .name("grace-ticks")
        .description("How many consecutive ticks the player must be below the threshold before actions fire (20 = 1 second).")
        .defaultValue(10)
        .range(1, 100)
        .sliderRange(1, 60)
        .visible(graceEnabled::get)
        .build()
    );

    // -------------------------------------------------------------------------
    // Chorus Escape
    // -------------------------------------------------------------------------
    private final Setting<Keybind> chorusEscapeKey = sgChorus.add(new KeybindSetting.Builder()
        .name("chorus-escape-key")
        .description("Eats a chorus fruit to escape the void. Ignores warnings/disconnects while active and disables module on landing.")
        .defaultValue(Keybind.none())
        .build()
    );

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    private boolean hasDisconnected    = false;
    private int     warnTickCounter    = 0;
    private int     graceTickCounter   = 0;
    
    private boolean chorusEscapeActive = false;
    private boolean hasTriggeredEat    = false;
    private boolean wasChorusPressed   = false;

    public EndSafe() {
        super(HuntingUtilities.CATEGORY, "EndSafe", "Protects you from the void by warning, disconnecting, or chorus teleporting at low Y levels.");
    }

    @Override
    public void onActivate() {
        hasDisconnected    = false;
        warnTickCounter    = 0;
        graceTickCounter   = 0;
        chorusEscapeActive = false;
        hasTriggeredEat    = false;
        wasChorusPressed   = false;
    }

    @Override
    public void onDeactivate() {
        hasDisconnected    = false;
        warnTickCounter    = 0;
        graceTickCounter   = 0;
        
        // Safety: release right click if the module is manually toggled off mid-eat
        if (mc.options != null) mc.options.useKey.setPressed(false);
        
        chorusEscapeActive = false;
        hasTriggeredEat    = false;
        wasChorusPressed   = false;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // -----------------------------------------------------------------
        // Chorus Escape Keybind Logic
        // -----------------------------------------------------------------
        boolean chorusPressed = chorusEscapeKey.get().isPressed();
        if (chorusPressed && !wasChorusPressed && !chorusEscapeActive) {
            chorusEscapeActive = true;
            hasTriggeredEat = false;
        }
        wasChorusPressed = chorusPressed;

        if (chorusEscapeActive) {
            if (!hasTriggeredEat) {
                if (!selectHotbarItem(Items.CHORUS_FRUIT)) {
                    warning("No Chorus Fruit found in hotbar! Falling back to normal EndSafe logic.");
                    chorusEscapeActive = false;
                    // Fall through to normal logic so disconnect can still save you
                } else {
                    mc.options.useKey.setPressed(true);
                    hasTriggeredEat = true;
                    return; // Bypass normal EndSafe logic while eating
                }
            } else {
                // Wait until the eating process finishes (which triggers the teleport)
                if (!mc.player.isUsingItem()) {
                    mc.options.useKey.setPressed(false);
                    info("Chorus escape successful. Disabling EndSafe.");
                    toggle();
                    return;
                }
                return; // Bypass normal EndSafe logic while eating/teleporting
            }
        }

        // -----------------------------------------------------------------
        // Normal Dimension & Y-Level Logic
        // -----------------------------------------------------------------
        boolean inEnd      = mc.world.getDimensionEntry().matchesKey(DimensionTypes.THE_END);
        boolean inOverworld = mc.world.getDimensionEntry().matchesKey(DimensionTypes.OVERWORLD);

        // Dimension gate — ignore Nether always; also ignore Overworld if end-only is on.
        if (!inEnd && !inOverworld) { resetCounters(); return; }
        if (endOnly.get() && !inEnd) { resetCounters(); return; }

        // Resolve the effective thresholds for this dimension.
        int effectiveWarnY;
        int effectiveDisconnectY;

        if (perDimensionThresholds.get() && !endOnly.get()) {
            if (inEnd) {
                effectiveWarnY       = endWarnY.get();
                effectiveDisconnectY = endDisconnectY.get();
            } else {
                // Overworld
                effectiveWarnY       = overworldWarnY.get();
                effectiveDisconnectY = overworldDisconnectY.get();
            }
        } else if (endOnly.get()) {
            // end-only mode: always use the End threshold group
            effectiveWarnY       = endWarnY.get();
            effectiveDisconnectY = endDisconnectY.get();
        } else {
            // Single shared thresholds
            effectiveWarnY       = warnY.get();
            effectiveDisconnectY = disconnectY.get();
        }

        ClientPlayerEntity player = mc.player;
        double playerY = player.getY();

        // Sanity check
        if (warnEnabled.get() && disconnectEnabled.get() && effectiveWarnY <= effectiveDisconnectY) {
            warning("⚠ EndSafe config issue: warn-y-level (" + effectiveWarnY
                + ") is not above disconnect-y-level (" + effectiveDisconnectY
                + "). You may not receive warnings before being disconnected!");
        }

        // Determine whether the player is currently in danger (below either threshold).
        boolean belowDisconnect = disconnectEnabled.get() && playerY < effectiveDisconnectY;
        boolean belowWarn       = warnEnabled.get()       && playerY < effectiveWarnY;
        boolean inDanger        = belowDisconnect || belowWarn;

        // -----------------------------------------------------------------
        // Grace period
        // -----------------------------------------------------------------
        if (inDanger && graceEnabled.get()) {
            graceTickCounter++;
            if (graceTickCounter < graceTicks.get()) {
                // Still within grace window — don't act yet, but don't reset warn
                // counter either so interval timing stays accurate once grace expires.
                return;
            }
            // Grace period expired — fall through to act.
        } else if (!inDanger) {
            graceTickCounter = 0;
        }

        // -----------------------------------------------------------------
        // Auto Disconnect (highest priority)
        // -----------------------------------------------------------------
        if (belowDisconnect) {
            if (!hasDisconnected) {
                hasDisconnected = true;

                mc.inGameHud.setTitle(Text.literal("§c§lENDSAFE DISCONNECT"));
                mc.inGameHud.setSubtitle(Text.literal(
                    "§eY: " + String.format("%.1f", playerY) + " §7is below §c" + effectiveDisconnectY
                ));

                info("§cDisconnected — Y §e" + String.format("%.1f", playerY)
                    + " §cis below safe threshold §e(" + effectiveDisconnectY + ")§c.");

                mc.world.disconnect();
                mc.disconnect();
            }
            return;
        } else {
            hasDisconnected = false;
        }

        // -----------------------------------------------------------------
        // Warning Ping
        // -----------------------------------------------------------------
        if (belowWarn) {
            warnTickCounter++;

            int interval = warnInterval.get();
            boolean shouldWarn = (interval == 0)
                ? (warnTickCounter == 1)
                : (warnTickCounter % interval == 1);

            if (shouldWarn) {
                mc.inGameHud.setTitle(Text.literal("§e§l⚠ VOID WARNING"));
                mc.inGameHud.setSubtitle(Text.literal(
                    "§fY: §c" + String.format("%.1f", playerY) + "  §f| Safe above: §a" + effectiveWarnY
                ));

                warning("⚠ EndSafe: Below Y §c" + effectiveWarnY
                    + "§r! Current Y: §c" + String.format("%.1f", playerY));

                player.playSound(
                    warnSound.get().getSoundEvent(),
                    warnVolume.get().floatValue(),
                    2.0f
                );
            }
        } else {
            warnTickCounter = 0;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void resetCounters() {
        warnTickCounter  = 0;
        graceTickCounter = 0;
    }

    private boolean selectHotbarItem(Item item) {
        if (mc.player == null) return false;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(item)) {
                mc.player.getInventory().selectedSlot = i;
                return true;
            }
        }
        return false;
    }
}