package com.example.addon.modules;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.network.ClientPlayerEntity;
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
    private final SettingGroup sgGeneral    = settings.getDefaultGroup();
    private final SettingGroup sgWarn       = settings.createGroup("Warning Ping");
    private final SettingGroup sgDisconnect = settings.createGroup("Auto Disconnect");

    // -------------------------------------------------------------------------
    // General
    // -------------------------------------------------------------------------
    private final Setting<Boolean> endOnly = sgGeneral.add(new BoolSetting.Builder()
        .name("end-only")
        .description("Only activate in The End. Disable to protect against the void in any dimension.")
        .defaultValue(true)
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
        .description("Y level at which the warning ping triggers.")
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
        .description("Y level at which auto-disconnect triggers. Should be lower than warn-y-level.")
        .defaultValue(-10)
        .range(-128, 320)
        .sliderRange(-128, 320)
        .build()
    );

    // -------------------------------------------------------------------------
    // State
    // -------------------------------------------------------------------------
    private boolean hasDisconnected = false;
    private int warnTickCounter     = 0;

    public EndSafe() {
        super(HuntingUtilities.CATEGORY, "EndSafe", "Protects you from the void by warning or disconnecting at low Y levels.");
    }

    @Override
    public void onActivate() {
        hasDisconnected = false;
        warnTickCounter = 0;
    }

    @Override
    public void onDeactivate() {
        hasDisconnected = false;
        warnTickCounter = 0;
    }

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Dimension check
        if (endOnly.get() && !mc.world.getDimensionEntry().matchesKey(DimensionTypes.THE_END)) {
            warnTickCounter = 0;
            return;
        }

        ClientPlayerEntity player = mc.player;
        double playerY = player.getY();

        // Sanity check: warn Y should be above disconnect Y
        if (warnEnabled.get() && disconnectEnabled.get() && warnY.get() <= disconnectY.get()) {
            warning("⚠ EndSafe config issue: warn-y-level (" + warnY.get()
                + ") is not above disconnect-y-level (" + disconnectY.get()
                + "). You may not receive warnings before being disconnected!");
        }

        // -----------------------------------------------------------------
        // Auto Disconnect (highest priority)
        // -----------------------------------------------------------------
        if (disconnectEnabled.get() && playerY < disconnectY.get()) {
            if (!hasDisconnected) {
                hasDisconnected = true;

                // Prominent on-screen title
                mc.inGameHud.setTitle(Text.literal("§c§lENDSAFE DISCONNECT"));
                mc.inGameHud.setSubtitle(Text.literal(
                    "§eY: " + String.format("%.1f", playerY) + " §7is below §c" + disconnectY.get()
                ));

                // Chat log
                info("§cDisconnected — Y §e" + String.format("%.1f", playerY)
                    + " §cis below safe threshold §e(" + disconnectY.get() + ")§c.");

                // Disconnect
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
        if (warnEnabled.get() && playerY < warnY.get()) {
            warnTickCounter++;

            int interval = warnInterval.get();
            // interval=0 means fire only once (on tick 1); otherwise repeat every N ticks
            boolean shouldWarn = (interval == 0)
                ? (warnTickCounter == 1)
                : (warnTickCounter % interval == 1);

            if (shouldWarn) {
                // On-screen title overlay
                mc.inGameHud.setTitle(Text.literal("§e§l⚠ VOID WARNING"));
                mc.inGameHud.setSubtitle(Text.literal(
                    "§fY: §c" + String.format("%.1f", playerY) + "  §f| Safe above: §a" + warnY.get()
                ));

                // Chat warning
                warning("⚠ EndSafe: Below Y §c" + warnY.get()
                    + "§r! Current Y: §c" + String.format("%.1f", playerY));

                // Sound ping
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
}