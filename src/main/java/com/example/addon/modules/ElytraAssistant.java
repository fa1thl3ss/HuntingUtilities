package com.example.addon.modules;

import org.lwjgl.glfw.GLFW;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.sound.SoundEvent;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.Hand;

public class ElytraAssistant extends Module {

    // ═══════════════════════════════════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════════════════════════════════

    public enum MiddleClickAction { None, Rocket, Pearl }

    public enum WarningSound {
        Anvil,
        WitherSpawn,
        CreeperPrimed,
        ExperienceOrb,
        Bell,
        NoteBassDrum;

        public SoundEvent toSoundEvent() {
            return switch (this) {
                case Anvil         -> SoundEvents.BLOCK_ANVIL_LAND;
                case WitherSpawn   -> SoundEvents.ENTITY_WITHER_SPAWN;
                case CreeperPrimed -> SoundEvents.ENTITY_CREEPER_PRIMED;
                case ExperienceOrb -> SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP;
                case Bell          -> SoundEvents.BLOCK_BELL_USE;
                case NoteBassDrum  -> SoundEvents.BLOCK_NOTE_BLOCK_BASEDRUM.value();
            };
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Setting Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgDurability = settings.createGroup("Durability");
    private final SettingGroup sgUtilities  = settings.createGroup("Utilities");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Durability
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> enableAutoSwap = sgDurability.add(new BoolSetting.Builder()
        .name("enable-auto-swap")
        .description("Automatically swap to a fresh elytra when durability is low.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> durabilityThreshold = sgDurability.add(new IntSetting.Builder()
        .name("durability-threshold")
        .description("Remaining durability below which swap occurs.")
        .defaultValue(10)
        .min(1)
        .sliderMax(100)
        .visible(enableAutoSwap::get)
        .build()
    );

    private final Setting<WarningSound> warningSoundType = sgDurability.add(new EnumSetting.Builder<WarningSound>()
        .name("warning-sound")
        .description("Sound played when no replacement elytra is available.")
        .defaultValue(WarningSound.Anvil)
        .visible(enableAutoSwap::get)
        .build()
    );

    private final Setting<Double> warningSoundVolume = sgDurability.add(new DoubleSetting.Builder()
        .name("warning-volume")
        .description("Volume of the no-replacement warning sound.")
        .defaultValue(1.0)
        .min(0.1)
        .sliderMax(2.0)
        .visible(enableAutoSwap::get)
        .build()
    );

    private final Setting<Keybind> autoSwapKey = sgDurability.add(new KeybindSetting.Builder()
        .name("auto-swap-key")
        .description("Key to toggle auto swap.")
        .defaultValue(Keybind.none())
        .action(() -> {
            if (mc.currentScreen != null) return;
            boolean val = !enableAutoSwap.get();
            enableAutoSwap.set(val);
            info("Auto Swap " + (val ? "enabled" : "disabled") + ".");
        })
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Utilities
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<MiddleClickAction> middleClickAction = sgUtilities.add(new EnumSetting.Builder<MiddleClickAction>()
        .name("middle-click-action")
        .description("Item to use when middle clicking.")
        .defaultValue(MiddleClickAction.None)
        .build()
    );

    public final Setting<Boolean> silentRocket = sgUtilities.add(new BoolSetting.Builder()
        .name("silent-rocket")
        .description("Prevents hand swing animation when using rockets.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> preventGroundUsage = sgUtilities.add(new BoolSetting.Builder()
        .name("prevent-ground-usage")
        .description("Blocks rocket usage while standing on ground.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Boolean> antiAfk = sgUtilities.add(new BoolSetting.Builder()
        .name("anti-afk")
        .description("Prevents being kicked for AFK by swinging your hand periodically.")
        .defaultValue(false)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════════

    private static final double AFK_INTERVAL_SECONDS = 15.0;

    private boolean noReplacementWarned  = false;
    private boolean noUsableElytraWarned = false;
    private boolean wasMiddlePressed     = false;
    private int     middleClickTimer     = 0;
    private int     swingTimer           = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════════

    public ElytraAssistant() {
        super(HuntingUtilities.CATEGORY, "elytra-assistant", "Smart elytra & rocket management.");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void onActivate() {
        noReplacementWarned  = false;
        noUsableElytraWarned = false;
        wasMiddlePressed     = false;
        middleClickTimer     = 0;
        swingTimer           = 0;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Event Handler
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (middleClickTimer > 0) middleClickTimer--;

        if (middleClickAction.get() != MiddleClickAction.None) {
            if (Input.isButtonPressed(GLFW.GLFW_MOUSE_BUTTON_MIDDLE)) {
                if (!wasMiddlePressed && middleClickTimer == 0) {
                    runMiddleClickAction();
                    wasMiddlePressed = true;
                    middleClickTimer = 5;
                }
            } else {
                wasMiddlePressed = false;
            }
        }

        if (antiAfk.get()) {
            if (swingTimer <= 0) {
                mc.player.swingHand(Hand.MAIN_HAND);
                int base = (int) (AFK_INTERVAL_SECONDS * 20);
                base += (int) ((Math.random() - 0.5) * (base * 0.4));
                swingTimer = Math.max(1, base);
            } else {
                swingTimer--;
            }
        }

        // Pause swapping if Mendbot is active to prevent conflicts
        if (Modules.get().get(Mendbot.class).isActive()) return;

        if (enableAutoSwap.get()) {
            handleChestplateElytraSwitch();
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Durability Logic
    // ═══════════════════════════════════════════════════════════════════════════

    private void handleChestplateElytraSwitch() {
        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (!chest.isOf(Items.ELYTRA)) return;

        int remaining = chest.getMaxDamage() - chest.getDamage();
        if (remaining > durabilityThreshold.get()) {
            noReplacementWarned = false;
            return;
        }

        FindItemResult replacement = findUsableElytra();
        if (replacement.found()) {
            silentEquip(replacement.slot());
            warning("Elytra durability low! Swapping to fresh elytra.");
            mc.player.playSound(SoundEvents.ENTITY_EXPERIENCE_ORB_PICKUP, 1.0f, 1.0f);
            noReplacementWarned = false;
        } else if (!noReplacementWarned) {
            warning("No replacement elytra available!");
            playWarningSound();
            noReplacementWarned = true;
        }
    }

    private FindItemResult findUsableElytra() {
        int bestSlot       = -1;
        int bestDurability = -1;

        for (int i = 0; i < mc.player.getInventory().main.size(); i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty() || !stack.isOf(Items.ELYTRA)) continue;

            int durability = stack.getMaxDamage() - stack.getDamage();
            if (durability > durabilityThreshold.get() && durability > bestDurability) {
                bestSlot       = i;
                bestDurability = durability;
            }
        }

        if (bestSlot != -1) return new FindItemResult(bestSlot, mc.player.getInventory().getStack(bestSlot).getCount());
        return new FindItemResult(-1, 0);
    }

    private void silentEquip(int slot) {
        InvUtils.move().from(getSlotId(slot)).toArmor(2);
    }

    private int getSlotId(int slot) {
        return (slot >= 0 && slot < 9) ? 36 + slot : slot;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Sound Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private void playWarningSound() {
        mc.player.playSound(
            warningSoundType.get().toSoundEvent(),
            warningSoundVolume.get().floatValue(),
            1.0f
        );
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Rocket / Middle Click Logic
    // ═══════════════════════════════════════════════════════════════════════════

    private void fireRocket() {
        if (mc.player == null || mc.interactionManager == null) return;
        if (preventGroundUsage.get() && mc.player.isOnGround()) return;

        if (mc.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) {
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
            return;
        }

        FindItemResult rocketResult = InvUtils.findInHotbar(Items.FIREWORK_ROCKET);
        if (rocketResult.found()) {
            int prevSlot = mc.player.getInventory().selectedSlot;
            InvUtils.swap(rocketResult.slot(), false);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.swap(prevSlot, false);
        }
    }

    private void runMiddleClickAction() {
        if (mc.currentScreen != null) return;

        MiddleClickAction action     = middleClickAction.get();
        FindItemResult    itemResult = null;

        if (action == MiddleClickAction.Rocket) {
            if (preventGroundUsage.get() && mc.player.isOnGround()) return;
            itemResult = InvUtils.find(Items.FIREWORK_ROCKET);
        } else if (action == MiddleClickAction.Pearl) {
            itemResult = InvUtils.find(Items.ENDER_PEARL);
        }

        if (itemResult == null || !itemResult.found()) return;

        int slot     = itemResult.slot();
        int prevSlot = mc.player.getInventory().selectedSlot;

        if (slot < 9) {
            InvUtils.swap(slot, true);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.swapBack();
        } else {
            InvUtils.move().from(slot).toHotbar(prevSlot);
            InvUtils.swap(prevSlot, true);
            mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
            InvUtils.swapBack();
            InvUtils.move().from(prevSlot).to(slot);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════════

    public boolean shouldPreventRocketUse() {
        return isActive() && preventGroundUsage.get() && mc.player.isOnGround();
    }

    public boolean shouldSilentRocket() {
        return isActive() && silentRocket.get();
    }

    public boolean isAutoSwapEnabled() {
        return isActive() && enableAutoSwap.get();
    }
}