package com.example.addon.modules;

import java.util.Set;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnchantmentListSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.FoodComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class ServerHealthcareSystem extends Module {

    // ── Setting Groups ────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral   = settings.getDefaultGroup();
    private final SettingGroup sgAutoArmor = settings.createGroup("Auto Armor");
    private final SettingGroup sgAutoEat   = settings.createGroup("Auto Eat");
    private final SettingGroup sgSafety    = settings.createGroup("Safety");

    // ── General ───────────────────────────────────────────────────────────────

    private final Setting<OperationMode> mode = sgGeneral.add(new EnumSetting.Builder<OperationMode>()
        .name("mode")
        .description("Changes the behavior of the module between Default and Quick Respawn modes.")
        .defaultValue(OperationMode.Default)
        .build()
    );

    private final Setting<Boolean> autoRespawn = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-respawn")
        .description("Automatically respawns after death.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> autoTotem = sgGeneral.add(new BoolSetting.Builder()
        .name("auto-totem")
        .description("Automatically equips a totem of undying in your offhand.")
        .defaultValue(true)
        .visible(() -> mode.get() == OperationMode.Default)
        .build()
    );

    // ── Auto Armor ────────────────────────────────────────────────────────────

    private final Setting<Boolean> autoArmor = sgAutoArmor.add(new BoolSetting.Builder()
        .name("auto-armor")
        .description("Automatically equips the best armor in your inventory.")
        .defaultValue(true)
        .visible(() -> mode.get() == OperationMode.Default)
        .build()
    );

    private final Setting<ChestplateMode> chestplateMode = sgAutoArmor.add(new EnumSetting.Builder<ChestplateMode>()
        .name("chestplate-mode")
        .description("How to manage the chest slot.")
        .defaultValue(ChestplateMode.Chestplate)
        .visible(() -> mode.get() == OperationMode.Default && autoArmor.get())
        .build()
    );

    private final Setting<Keybind> switchModeKey = sgAutoArmor.add(new KeybindSetting.Builder()
        .name("switch-preference-key")
        .description("Switches between preferring Chestplate or Elytra.")
        .defaultValue(Keybind.none())
        .action(() -> {
            if (mc.currentScreen != null) return;
            ChestplateMode current = chestplateMode.get();
            ChestplateMode next;
            switch (current) {
                case Chestplate: next = ChestplateMode.Elytra; break;
                case Elytra:     next = ChestplateMode.Smart; break;
                default:         next = ChestplateMode.Chestplate; break;
            }
            chestplateMode.set(next);
            info("Chestplate mode set to: %s", next.name());
        })
        .visible(() -> mode.get() == OperationMode.Default && autoArmor.get())
        .build()
    );

    private final Setting<Integer> swapDelay = sgAutoArmor.add(new IntSetting.Builder()
        .name("swap-delay")
        .description("Ticks to wait after performing a chest/elytra swap.")
        .defaultValue(10)
        .min(0)
        .visible(() -> mode.get() == OperationMode.Default && autoArmor.get() && chestplateMode.get() == ChestplateMode.Smart)
        .build()
    );

    private final Setting<Set<RegistryKey<Enchantment>>> ignoredEnchantments = sgAutoArmor.add(new EnchantmentListSetting.Builder()
        .name("ignored-enchantments")
        .description("Armor with these enchantments will be ignored by Auto Armor.")
        .defaultValue(Enchantments.BINDING_CURSE)
        .visible(() -> mode.get() == OperationMode.Default && autoArmor.get())
        .build()
    );

    // ── Auto Eat ──────────────────────────────────────────────────────────────

    private final Setting<Boolean> autoEat = sgAutoEat.add(new BoolSetting.Builder()
        .name("auto-eat")
        .description("Automatically eats food when conditions are met.")
        .defaultValue(true)
        .build()
    );

    private final Setting<EatMode> eatMode = sgAutoEat.add(new EnumSetting.Builder<EatMode>()
        .name("eat-mode")
        .description("What types of food to eat.")
        .defaultValue(EatMode.Emergency)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Boolean> preferEnchanted = sgAutoEat.add(new BoolSetting.Builder()
        .name("prefer-enchanted")
        .description("Prefer enchanted golden apples over regular ones.")
        .defaultValue(false)
        .visible(() -> autoEat.get() && eatMode.get() != EatMode.Standard && eatMode.get() != EatMode.Emergency)
        .build()
    );

    private final Setting<Integer> healthThreshold = sgAutoEat.add(new IntSetting.Builder()
        .name("health-threshold")
        .description("Health at which auto-eat triggers (out of 20). Set to 0 to disable health-based eating.")
        .defaultValue(10) // 5 hearts
        .min(0)
        .max(19)
        .sliderRange(0, 19)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Integer> hungerLoss = sgAutoEat.add(new IntSetting.Builder()
        .name("hunger-loss")
        .description("How many TOTAL hunger points must be lost to trigger eating.")
        .defaultValue(2)
        .min(1)
        .max(20)
        .sliderRange(1, 10)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Boolean> eatOnFire = sgAutoEat.add(new BoolSetting.Builder()
        .name("eat-on-fire")
        .description("Eat when on fire and taking damage.")
        .defaultValue(true)
        .visible(() -> autoEat.get() && eatMode.get() != EatMode.Standard)
        .build()
    );

    private final Setting<Integer> eatCooldown = sgAutoEat.add(new IntSetting.Builder()
        .name("eat-cooldown")
        .description("Ticks to wait after eating before eating again.")
        .defaultValue(20)
        .min(0)
        .max(100)
        .sliderRange(0, 60)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Boolean> swapBack = sgAutoEat.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Swap back to original slot after eating.")
        .defaultValue(true)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Boolean> pauseInCombat = sgAutoEat.add(new BoolSetting.Builder()
        .name("pause-in-combat")
        .description("Don't eat normal food while taking damage (gapples still work).")
        .defaultValue(false)
        .visible(() -> autoEat.get() && eatMode.get() != EatMode.Emergency)
        .build()
    );

    private final Setting<Boolean> skipIfRegen = sgAutoEat.add(new BoolSetting.Builder()
        .name("skip-if-regen")
        .description("Doesn't eat golden apples for hunger if you already have regeneration. Prioritizes survival for health/fire.")
        .defaultValue(true)
        .visible(() -> autoEat.get() && eatMode.get() != EatMode.Standard)
        .build()
    );

    // ── Safety ────────────────────────────────────────────────────────────────

    private final Setting<Boolean> disconnectOnTotemPop = sgSafety.add(new BoolSetting.Builder()
        .name("disconnect-on-totem-pop")
        .description("Disconnects when a totem of undying is consumed.")
        .defaultValue(false)
        .visible(() -> mode.get() == OperationMode.Default)
        .build()
    );

    private final Setting<Boolean> disconnectOnNoTotems = sgSafety.add(new BoolSetting.Builder()
        .name("disconnect-on-no-totems")
        .description("Disconnects if totem count reaches zero.")
        .defaultValue(false)
        .visible(() -> mode.get() == OperationMode.Default)
        .build()
    );

    private final Setting<Keybind> breakBedHotkey = sgSafety.add(new KeybindSetting.Builder()
        .name("break-bed-hotkey")
        .description("Hotkey to automatically break the nearest bed when in Quick Respawn mode.")
        .defaultValue(Keybind.none())
        .action(() -> {
            if (mc.currentScreen != null) return;
            if (mode.get() == OperationMode.QuickRespawn) {
                BlockPos nearest = findNearestBed();
                if (nearest != null) {
                    bedToBreak = nearest;
                    breakTickCounter = 0;
                    bedOriginalHotbarSlot = mc.player.getInventory().selectedSlot;
                    info("Initiating bed breaking at %s...", nearest.toShortString());
                } else {
                    warning("No bed found nearby to break.");
                }
            }
        })
        .visible(() -> mode.get() == OperationMode.QuickRespawn)
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────

    // Auto Eat
    private boolean isEating              = false;
    private boolean ateForFire            = false;
    private boolean tookDamageWhileOnFire = false;
    private int     eatHotbarSlot         = -1;
    private int     eatOriginalHotbarSlot = -1;
    private Item    eatTargetItem         = null;
    private int     eatStartupTicks       = 0;
    private int     eatTicksRemaining     = 0;
    private float   lastHealth            = -1;
    private int     highestHungerSeen     = -1; // Tracks peak hunger to calculate total loss
    private int     eatCooldownTimer      = 0;
    private boolean tookDamageRecently    = false;
    private int     damageTimer           = 0;

    // Auto Armor
    private int swapTimer = 0;

    // Auto Break Bed
    private BlockPos bedToBreak = null;
    private int breakTickCounter = 0;
    private int bedOriginalHotbarSlot = -1;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ServerHealthcareSystem() {
        super(HuntingUtilities.CATEGORY, "server-healthcare-system",
            "SHS — Manages health, safety, tracking, and server monitoring.");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        if (mc.player != null) {
            lastHealth = mc.player.getHealth();
            highestHungerSeen = mc.player.getHungerManager().getFoodLevel();
        }
        resetState();
    }

    @Override
    public void onDeactivate() {
        stopEating();
        lastHealth = -1;
        highestHungerSeen = -1;
        if (bedOriginalHotbarSlot != -1) {
            InvUtils.swap(bedOriginalHotbarSlot, false);
        }
        resetState();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (mc.player != null) {
            lastHealth = mc.player.getHealth();
            highestHungerSeen = mc.player.getHungerManager().getFoodLevel();
        }
        resetState();
        if (autoTotem.get()) tickAutoTotem();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    public boolean isAutoTotemEnabled() { return isActive() && autoTotem.get(); }
    public void setAutoTotem(boolean enabled) { autoTotem.set(enabled); }
    public boolean isEating() { return isEating; }

    // ── State Helpers ─────────────────────────────────────────────────────────

    private void resetState() {
        isEating              = false;
        ateForFire            = false;
        tookDamageWhileOnFire = false;
        eatHotbarSlot         = -1;
        eatOriginalHotbarSlot = -1;
        eatTargetItem         = null;
        eatStartupTicks       = 0;
        eatTicksRemaining     = 0;
        eatCooldownTimer      = 0;
        tookDamageRecently    = false;
        damageTimer           = 0;
        swapTimer             = 0;
        bedOriginalHotbarSlot = -1;
        highestHungerSeen     = -1;
    }

    private void stopEating() {
        mc.options.useKey.setPressed(false);
        isEating              = false;
        eatHotbarSlot         = -1;
        eatOriginalHotbarSlot = -1;
        eatTargetItem         = null;
        eatStartupTicks       = 0;
        eatTicksRemaining     = 0;
    }

    private void finishEating() {
        mc.options.useKey.setPressed(false);

        if (swapBack.get() && eatOriginalHotbarSlot != -1 && eatOriginalHotbarSlot != eatHotbarSlot) {
            InvUtils.swap(eatOriginalHotbarSlot, false);
        }

        isEating              = false;
        eatHotbarSlot         = -1;
        eatOriginalHotbarSlot = -1;
        
        // Apply the normal user-configured cooldown
        eatCooldownTimer = eatCooldown.get();
        
        // Force a minimum 60-tick (3 second) cooldown after eating a gapple 
        // to prevent chain-eating before regeneration has time to heal you.
        if (eatTargetItem == Items.GOLDEN_APPLE || eatTargetItem == Items.ENCHANTED_GOLDEN_APPLE) {
            eatCooldownTimer = Math.max(eatCooldownTimer, 60);
        }

        eatTargetItem         = null;
        eatStartupTicks       = 0;
        eatTicksRemaining     = 0;
        
        // Reset hunger baseline AFTER eating so it establishes a new peak
        highestHungerSeen     = -1; 
    }

    private void sendUseItemPacket() {
        if (mc.player == null) return;
        mc.player.networkHandler.sendPacket(
            new PlayerInteractItemC2SPacket(
                Hand.MAIN_HAND,
                mc.player.currentScreenHandler.getRevision(),
                mc.player.getYaw(),
                mc.player.getPitch()
            )
        );
        mc.player.swingHand(Hand.MAIN_HAND);
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (swapTimer > 0) swapTimer--;

        switch (mode.get()) {
            case Default -> {
                tickAutoTotem();
                tickAutoArmor();
                tickAutoEat(); 
                tickHealthTracking();
                tickAutoRespawn();
            }
            case QuickRespawn -> {
                tickAutoRespawn();
                tickAutoEat();
                tickHealthTracking();
            }
        }
    }

    // ── Event Handlers ────────────────────────────────────────────────────────

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (mc.player == null || mc.world == null || mode.get() != OperationMode.Default || !disconnectOnTotemPop.get()) return;

        if (event.packet instanceof EntityStatusS2CPacket packet) {
            if (packet.getStatus() == 35
                    && packet.getEntity(mc.world) != null
                    && packet.getEntity(mc.world).getId() == mc.player.getId()) {
                disconnect("[SHS] Disconnected on totem pop. " + countTotems() + " totems remaining.");
            }
        }
    }

    // ── Tick Logic Subroutines ────────────────────────────────────────────────

    private void tickHealthTracking() {
        if (mc.player == null) return;
        
        if (lastHealth == -1) lastHealth = mc.player.getHealth();
        
        float health = mc.player.getHealth();

        if (health < lastHealth) {
            tookDamageRecently = true;
            damageTimer = 40;
        }

        if (mc.player.isOnFire()) {
            if (health < lastHealth) tookDamageWhileOnFire = true;
        } else {
            ateForFire            = false;
            tookDamageWhileOnFire = false;
        }
        
        lastHealth = health;

        if (tookDamageRecently) {
            damageTimer--;
            if (damageTimer <= 0) {
                tookDamageRecently = false;
                damageTimer = 0;
            }
        }
    }

    private void tickAutoRespawn() {
        if (autoRespawn.get() && mc.currentScreen instanceof DeathScreen) {
            mc.player.requestRespawn();
            mc.setScreen(null);
        }
    }

    private void tickQuickRespawnMode() {
        if (bedToBreak != null) {
            if (!(mc.world.getBlockState(bedToBreak).getBlock() instanceof BedBlock)) {
                info("Bed at %s broken.", bedToBreak.toShortString());
                bedToBreak = null;
                if (bedOriginalHotbarSlot != -1) {
                    InvUtils.swap(bedOriginalHotbarSlot, false);
                    bedOriginalHotbarSlot = -1;
                }
                return;
            }

            if (mc.player.getPos().distanceTo(Vec3d.ofCenter(bedToBreak)) > 6.0) {
                warning("Too far from bed, stopping breaking.");
                bedToBreak = null;
                if (bedOriginalHotbarSlot != -1) {
                    InvUtils.swap(bedOriginalHotbarSlot, false);
                    bedOriginalHotbarSlot = -1;
                }
                return;
            }

            int bestToolSlot = findBestTool(bedToBreak);

            if (bestToolSlot != -1 && mc.player.getInventory().selectedSlot != bestToolSlot) {
                if (bedOriginalHotbarSlot == -1) {
                    bedOriginalHotbarSlot = mc.player.getInventory().selectedSlot;
                }
                InvUtils.swap(bestToolSlot, false);
            }

            Rotations.rotate(Rotations.getYaw(bedToBreak), Rotations.getPitch(bedToBreak), () -> {
                mc.interactionManager.updateBlockBreakingProgress(bedToBreak, Direction.UP);
                mc.player.swingHand(Hand.MAIN_HAND);
            });

            breakTickCounter++;
        }
    }

    private void tickAutoTotem() {
        if (!autoTotem.get()) return;

        if (!mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            FindItemResult totem = InvUtils.find(Items.TOTEM_OF_UNDYING);
            if (totem.found()) {
                InvUtils.move().from(totem.slot()).toOffhand();
            } else if (disconnectOnNoTotems.get()) {
                disconnect("[SHS] Disconnected — no totems remaining.");
            }
        }
    }

    private void tickAutoArmor() {
        if (!autoArmor.get() || swapTimer > 0) return;

        if (chestplateMode.get() == ChestplateMode.Smart) handleChestplateElytraSwitch();

        EquipmentSlot[] slots = { EquipmentSlot.FEET, EquipmentSlot.LEGS, EquipmentSlot.CHEST, EquipmentSlot.HEAD };
        for (int i = 0; i < 4; i++) {
            EquipmentSlot slot = slots[i];
            if (slot == EquipmentSlot.CHEST && chestplateMode.get() == ChestplateMode.Smart) continue;

            ItemStack current   = mc.player.getEquippedStack(slot);
            int       bestValue = getArmorValue(current);

            if (slot == EquipmentSlot.CHEST && chestplateMode.get() == ChestplateMode.Elytra
                    && current.isOf(Items.ELYTRA)) {
                bestValue = 1_000_000;
            }

            int bestSlot = -1;
            for (int j = 0; j < 36; j++) {
                ItemStack stack = mc.player.getInventory().getStack(j);
                if (stack.isEmpty()) continue;
                if (hasIgnoredEnchantment(stack)) continue;
                var equippable = stack.get(DataComponentTypes.EQUIPPABLE);
                if (equippable == null || equippable.slot() != slot) continue;

                int value = getArmorValue(stack);
                if (slot == EquipmentSlot.CHEST && chestplateMode.get() == ChestplateMode.Elytra
                        && stack.isOf(Items.ELYTRA)) {
                    value = 1_000_000;
                }

                if (value > bestValue) { bestValue = value; bestSlot = j; }
            }

            if (bestSlot != -1) InvUtils.move().from(bestSlot).toArmor(i);
        }
    }

    private void tickAutoEat() {
        if (!autoEat.get()) return;

        if (eatCooldownTimer > 0) {
            eatCooldownTimer--;
            return;
        }

        if (!isEating) {
            boolean needsHealth = healthThreshold.get() > 0
                && mc.player.getHealth() <= healthThreshold.get();
                
            int currentHunger = mc.player.getHungerManager().getFoodLevel();
            
            // Track the highest hunger point to accurately measure total loss over time
            if (highestHungerSeen == -1 || currentHunger > highestHungerSeen) {
                highestHungerSeen = currentHunger;
            }
            
            boolean needsHunger = highestHungerSeen != -1 
                && (highestHungerSeen - currentHunger) >= hungerLoss.get();
                
            boolean needsFireEat = eatOnFire.get()
                && mc.player.isOnFire() && tookDamageWhileOnFire && !ateForFire;

            boolean isEmergency = needsHealth || needsFireEat;

            if (pauseInCombat.get() && tookDamageRecently && !isEmergency) {
                return;
            }

            if (!needsHealth && !needsHunger && !needsFireEat) return;

            int foodSlot = findBestFood(isEmergency);
            if (foodSlot == -1) return;

            ItemStack foodStack = mc.player.getInventory().getStack(foodSlot);
            eatTargetItem = foodStack.getItem();

            // Skip if we already have regeneration and the toggle is on
            // BUT never skip if we are critically low on health or burning
            if (skipIfRegen.get() && (foodStack.isOf(Items.GOLDEN_APPLE) || foodStack.isOf(Items.ENCHANTED_GOLDEN_APPLE))) {
                if (mc.player.hasStatusEffect(StatusEffects.REGENERATION)) {
                    if (!needsHealth && !needsFireEat) {
                        return; 
                    }
                }
            }

            eatOriginalHotbarSlot = mc.player.getInventory().selectedSlot;

            if (foodSlot < 9) {
                eatHotbarSlot = foodSlot;
            } else {
                eatHotbarSlot = findEmptyHotbarSlot();
                if (eatHotbarSlot == -1) eatHotbarSlot = eatOriginalHotbarSlot;
                InvUtils.move().from(foodSlot).toHotbar(eatHotbarSlot);
            }

            mc.player.getInventory().selectedSlot = eatHotbarSlot;

            eatTicksRemaining = foodStack.getItem().getMaxUseTime(foodStack, mc.player);
            eatStartupTicks = 3;

            mc.options.useKey.setPressed(true);
            sendUseItemPacket();
            isEating = true;

            if (needsFireEat) {
                ateForFire            = true;
                tookDamageWhileOnFire = false;
            }

        } else {
            if (eatStartupTicks > 0) {
                eatStartupTicks--;
                mc.player.getInventory().selectedSlot = eatHotbarSlot;
                mc.options.useKey.setPressed(true);
                if (eatTicksRemaining > 0) eatTicksRemaining--;
                return;
            }

            ItemStack hotbarStack = mc.player.getInventory().getStack(eatHotbarSlot);
            boolean hotbarHasFood = eatTargetItem != null && hotbarStack.isOf(eatTargetItem);

            if (!hotbarHasFood) {
                finishEating();
                return;
            }

            if (mc.currentScreen != null) {
                finishEating();
                return;
            }

            mc.player.getInventory().selectedSlot = eatHotbarSlot;
            mc.options.useKey.setPressed(true);

            // ANTI-INTERRUPT: If we get hit, vanilla server cancels the eat.
            // We detect the cancellation and instantly restart the eat to bypass it.
            if (!mc.player.isUsingItem() && hotbarHasFood) {
                sendUseItemPacket();
                eatTicksRemaining = hotbarStack.getItem().getMaxUseTime(hotbarStack, mc.player);
                return; 
            }

            if (eatTicksRemaining > 0) {
                eatTicksRemaining--;
            } else {
                finishEating();
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void disconnect(String reason) {
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.getConnection().disconnect(Text.literal(reason));
        }
        this.toggle();
    }

    private boolean isEdible(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return false;
        return stack.isOf(Items.GOLDEN_APPLE)
            || stack.isOf(Items.ENCHANTED_GOLDEN_APPLE)
            || stack.get(DataComponentTypes.FOOD) != null;
    }

    private int findEmptyHotbarSlot() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) return i;
        }
        return -1;
    }

    private int findBestFood(boolean emergency) {
        EatMode mode = eatMode.get();

        if (mode == EatMode.Emergency) {
            return findBestEnchantedGapple();
        }

        if (emergency) {
            return findBestGapple(); 
        }

        if (mode == EatMode.Standard) {
            return findBestNormalFood();
        }

        return findBestNormalFood();
    }

    private int findBestEnchantedGapple() {
        int hotbar = -1;
        int inv = -1;
        for (int i = 0; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.ENCHANTED_GOLDEN_APPLE)) {
                if (i < 9) { if (hotbar == -1) hotbar = i; }
                else { if (inv == -1) inv = i; }
            }
        }
        return hotbar != -1 ? hotbar : inv;
    }

    private int findBestNormalFood() {
        int bestSlot = -1;
        int bestValue = -1;

        int hotbarBest = -1;
        int hotbarBestValue = -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;

            FoodComponent food = stack.get(DataComponentTypes.FOOD);
            if (food == null) continue;

            int value = (int) (food.nutrition() + food.saturation() * 10);
            if (stack.getMaxCount() > 1) value += 100;

            if (i < 9) {
                if (value > hotbarBestValue) {
                    hotbarBestValue = value;
                    hotbarBest = i;
                }
            } else {
                if (value > bestValue) {
                    bestValue = value;
                    bestSlot = i;
                }
            }
        }

        return hotbarBest != -1 ? hotbarBest : bestSlot;
    }

    private int findBestGapple() {
        int hotbarGapple     = -1;
        int hotbarEgapple    = -1;
        int inventoryEgapple = -1;
        int inventoryGapple  = -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;

            if (stack.isOf(Items.ENCHANTED_GOLDEN_APPLE)) {
                if (i < 9) {
                    if (hotbarEgapple == -1) hotbarEgapple = i;
                } else {
                    if (inventoryEgapple == -1) inventoryEgapple = i;
                }
            } else if (stack.isOf(Items.GOLDEN_APPLE)) {
                if (i < 9) {
                    if (hotbarGapple == -1) hotbarGapple = i;
                } else {
                    if (inventoryGapple == -1) inventoryGapple = i;
                }
            }
        }

        if (preferEnchanted.get()) {
            if (hotbarEgapple != -1) return hotbarEgapple;
            if (inventoryEgapple != -1) return inventoryEgapple;
            if (hotbarGapple != -1) return hotbarGapple;
            return inventoryGapple;
        } else {
            if (hotbarGapple != -1) return hotbarGapple;
            if (inventoryGapple != -1) return inventoryGapple;
            if (hotbarEgapple != -1) return hotbarEgapple;
            return inventoryEgapple;
        }
    }

    private void handleChestplateElytraSwitch() {
        if (Modules.get().get(RocketPilot.class).isActive()) return;

        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (mc.player.isOnGround()) {
            if (chest.isOf(Items.ELYTRA)) {
                FindItemResult cp = findBestChestplate();
                if (cp.found()) { InvUtils.move().from(cp.slot()).toArmor(2); swapTimer = swapDelay.get(); }
            }
        } else {
            if (!chest.isOf(Items.ELYTRA)) {
                FindItemResult elytra = InvUtils.find(Items.ELYTRA);
                if (elytra.found()) { InvUtils.move().from(elytra.slot()).toArmor(2); swapTimer = swapDelay.get(); }
            }
        }
    }

    private FindItemResult findBestChestplate() {
        int bestValue = -1, bestSlot = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;
            var equippable = stack.get(DataComponentTypes.EQUIPPABLE);
            if (equippable == null || equippable.slot() != EquipmentSlot.CHEST) continue;
            if (stack.isOf(Items.ELYTRA)) continue;
            int value = getArmorValue(stack);
            if (value > bestValue) { bestValue = value; bestSlot = i; }
        }
        return bestSlot != -1
            ? new FindItemResult(bestSlot, mc.player.getInventory().getStack(bestSlot).getCount())
            : new FindItemResult(-1, 0);
    }

    private boolean hasIgnoredEnchantment(ItemStack stack) {
        if (ignoredEnchantments.get().isEmpty()) return false;
        ItemEnchantmentsComponent enchants = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchants == null) return false;
        for (RegistryEntry<Enchantment> entry : enchants.getEnchantments()) {
            if (entry.getKey().isPresent() && ignoredEnchantments.get().contains(entry.getKey().get())) return true;
        }
        return false;
    }

    private int getArmorValue(ItemStack stack) {
        if (stack.isEmpty()) return -1;
        if (stack.getOrDefault(DataComponentTypes.EQUIPPABLE, null) == null) return -1;

        AttributeModifiersComponent attrs = stack.getOrDefault(DataComponentTypes.ATTRIBUTE_MODIFIERS, null);
        double armor = 0, toughness = 0;

        if (attrs != null) {
            for (var entry : attrs.modifiers()) {
                if (entry == null || entry.attribute() == null || entry.modifier() == null) continue;
                var keyOpt = entry.attribute().getKey();
                if (keyOpt == null || keyOpt.isEmpty()) continue;
                String id = keyOpt.get().getValue().toString();
                double v  = entry.modifier().value();
                if      (id.equals("minecraft:generic.armor"))           armor     += v;
                else if (id.equals("minecraft:generic.armor_toughness")) toughness += v;
            }
        }

        double enchBonus =
              getEnchantmentLevel(stack, "minecraft:protection")            * 3.0
            + getEnchantmentLevel(stack, "minecraft:fire_protection")       * 1.0
            + getEnchantmentLevel(stack, "minecraft:projectile_protection") * 1.0;

        return (int) (armor * 100 + toughness * 10 + enchBonus);
    }

    private int countTotems() {
        if (mc.player == null) return 0;
        int count = 0;
        for (ItemStack stack : mc.player.getInventory().main) {
            if (stack.isOf(Items.TOTEM_OF_UNDYING)) count += stack.getCount();
        }
        if (mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING))
            count += mc.player.getOffHandStack().getCount();
        return count;
    }

    private int getEnchantmentLevel(ItemStack stack, String id) {
        ItemEnchantmentsComponent enchants = stack.get(DataComponentTypes.ENCHANTMENTS);
        if (enchants == null) return 0;
        for (RegistryEntry<Enchantment> entry : enchants.getEnchantments()) {
            if (entry.getKey().isPresent() && entry.getKey().get().getValue().toString().equals(id))
                return enchants.getLevel(entry);
        }
        return 0;
    }

    private BlockPos findNearestBed() {
        if (mc.player == null || mc.world == null) return null;

        BlockPos playerPos = mc.player.getBlockPos();
        double minDistanceSq = Double.MAX_VALUE;
        BlockPos nearestBed = null;

        for (int x = -5; x <= 5; x++) {
            for (int y = -5; y <= 5; y++) {
                for (int z = -5; z <= 5; z++) {
                    BlockPos pos = playerPos.add(x, y, z);
                    if (mc.world.getBlockState(pos).getBlock() instanceof BedBlock) {
                        double distanceSq = playerPos.getSquaredDistance(pos);
                        if (distanceSq < minDistanceSq) {
                            minDistanceSq = distanceSq;
                            nearestBed = pos;
                        }
                    }
                }
            }
        }
        return nearestBed;
    }

    private int findBestTool(BlockPos blockPos) {
        if (mc.player == null || mc.world == null) return -1;

        BlockState state = mc.world.getBlockState(blockPos);
        float bestSpeed = 1.0f;
        int bestSlot = -1;

        for (int i = 0; i < 9; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;

            float speed = stack.getMiningSpeedMultiplier(state);
            if (speed > bestSpeed) { bestSpeed = speed; bestSlot = i; }
        }
        return bestSlot;
    }

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum OperationMode {
        Default,
        QuickRespawn
    }

    public enum ChestplateMode {
        Chestplate,
        Elytra,
        Smart
    }

    public enum EatMode {
        Emergency,
        Standard,
        Smart
    }
}