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
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.screen.DeathScreen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.block.BedBlock;
import net.minecraft.block.BlockState;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.component.type.AttributeModifiersComponent;
import net.minecraft.component.type.ItemEnchantmentsComponent;
import net.minecraft.enchantment.Enchantment;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.AxeItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.PlayerInteractItemC2SPacket;
import net.minecraft.network.packet.s2c.play.EntityStatusS2CPacket;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Hand;
import net.minecraft.text.Text;
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
        .description("Automatically eats Golden Apples when low on health, hunger, or on fire.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> healthThreshold = sgAutoEat.add(new IntSetting.Builder()
        .name("health-threshold")
        .description("Health at which auto-eat triggers (out of 20). Set to 0 to disable health-based eating.")
        .defaultValue(10)
        .min(0)
        .max(19)
        .sliderRange(0, 19)
        .visible(autoEat::get)
        .build()
    );

    private final Setting<Integer> hungerLoss = sgAutoEat.add(new IntSetting.Builder()
        .name("hunger-loss")
        .description("How many hunger points must be lost before auto-eat triggers (out of 20).")
        .defaultValue(2)
        .min(1)
        .max(20)
        .sliderRange(1, 10)
        .visible(autoEat::get)
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
                    originalHotbarSlot = mc.player.getInventory().selectedSlot;
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
    private Item    eatTargetItem         = null;
    private int     eatStartupTicks       = 0;
    private int     eatTicksRemaining     = 0;
    private float   lastHealth            = -1;

    // Hunger tracking
    private int fullHunger = -1;

    // Auto Armor
    private int swapTimer = 0;

    // Auto Break Bed
    private BlockPos bedToBreak = null;
    private int breakTickCounter = 0;
    private int originalHotbarSlot = -1; // Stores the slot before tool swap for bed breaking
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
            fullHunger = mc.player.getHungerManager().getFoodLevel();
        }
        resetState();
        // No need to reset originalHotbarSlot here, it's handled in onDeactivate
    }

    @Override
    public void onDeactivate() {
        stopEating();
        lastHealth = -1;
        fullHunger = -1;
        if (originalHotbarSlot != -1) { // Ensure we swap back if module is deactivated mid-break
            InvUtils.swap(originalHotbarSlot, false);
        }
        resetState();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        if (mc.player != null) {
            lastHealth = mc.player.getHealth();
            fullHunger = mc.player.getHungerManager().getFoodLevel();
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
        originalHotbarSlot    = -1;
        eatTargetItem         = null;
        eatStartupTicks       = 0;
        eatTicksRemaining     = 0;
        swapTimer             = 0;
    }

    private void stopEating() {
        mc.options.useKey.setPressed(false);
        isEating          = false;
        eatHotbarSlot     = -1;
        eatTargetItem     = null;
        eatStartupTicks   = 0;
        eatTicksRemaining = 0;
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

        tickHealthTracking(); // Always needed for health-based triggers

        switch (mode.get()) {
            case Default -> {
                tickAutoTotem();
                tickAutoArmor();
                tickAutoEat();
                tickAutoRespawn();
            }
            case QuickRespawn -> {
                tickQuickRespawnMode();
            }
        }
    }

    private void tickHealthTracking() {
        if (lastHealth == -1) lastHealth = mc.player.getHealth();
        float health = mc.player.getHealth();

        if (mc.player.isOnFire()) {
            if (health < lastHealth) tookDamageWhileOnFire = true;
        } else {
            ateForFire            = false;
            tookDamageWhileOnFire = false;
        }
        lastHealth = health;

        int currentHunger = mc.player.getHungerManager().getFoodLevel();
        if (fullHunger == -1 || currentHunger > fullHunger) fullHunger = currentHunger;
    }

    private void tickAutoRespawn() {
        if (autoRespawn.get() && mc.currentScreen instanceof DeathScreen) {
            mc.player.requestRespawn();
            mc.setScreen(null);
        }
    }

    private void tickQuickRespawnMode() {
        tickAutoRespawn();
        tickAutoEat();

        if (bedToBreak != null) {
            // Check if bed is still there
            if (!(mc.world.getBlockState(bedToBreak).getBlock() instanceof BedBlock)) {
                info("Bed at %s broken.", bedToBreak.toShortString());
                bedToBreak = null;
                if (originalHotbarSlot != -1) {
                    InvUtils.swap(originalHotbarSlot, false); // Swap back
                    originalHotbarSlot = -1;
                }
                return;
            }

            // Check if player moved too far
            if (mc.player.getPos().distanceTo(Vec3d.ofCenter(bedToBreak)) > 6.0) {
                warning("Too far from bed, stopping breaking.");
                bedToBreak = null;
                if (originalHotbarSlot != -1) {
                    InvUtils.swap(originalHotbarSlot, false); // Swap back
                    originalHotbarSlot = -1;
                }
                return;
            }

            int bestToolSlot = findBestTool(bedToBreak);
            
            // If a better tool is found and it's not currently selected, swap to it.
            // If bestToolSlot is -1, it means the bare hand (or current item) is the best, so no swap is needed.
            if (bestToolSlot != -1 && mc.player.getInventory().selectedSlot != bestToolSlot) {
                // Save the original slot only if we are actually swapping to a tool.
                if (originalHotbarSlot == -1) {
                    originalHotbarSlot = mc.player.getInventory().selectedSlot;
                }
                InvUtils.swap(bestToolSlot, false);
            }

            Rotations.rotate(Rotations.getYaw(bedToBreak), Rotations.getPitch(bedToBreak), () -> {
                mc.interactionManager.updateBlockBreakingProgress(bedToBreak, Direction.UP);
                mc.player.swingHand(Hand.MAIN_HAND);
            });

            breakTickCounter++;
            // Optional: Add a timeout if breaking takes too long
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

        if (!isEating) {
            boolean needsHealth = healthThreshold.get() > 0
                && mc.player.getHealth() <= healthThreshold.get();
            boolean needsHunger = fullHunger != -1
                && (fullHunger - mc.player.getHungerManager().getFoodLevel()) >= hungerLoss.get();
            boolean needsFireEat = mc.player.isOnFire() && tookDamageWhileOnFire && !ateForFire;

            if (!needsHealth && !needsHunger && !needsFireEat) return;

            int gappleSlot = findBestGapple();
            if (gappleSlot == -1) return;

            eatTargetItem = mc.player.getInventory().getStack(gappleSlot).getItem();

            if (gappleSlot < 9) {
                eatHotbarSlot = gappleSlot;
            } else {
                eatHotbarSlot = mc.player.getInventory().selectedSlot;
                InvUtils.move().from(gappleSlot).toHotbar(eatHotbarSlot);
            }
            mc.player.getInventory().selectedSlot = eatHotbarSlot;

            eatStartupTicks   = 3;
            eatTicksRemaining = 32;

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

            ItemStack hotbarStack    = mc.player.getInventory().getStack(eatHotbarSlot);
            boolean hotbarHasGapple  = eatTargetItem != null && hotbarStack.isOf(eatTargetItem);
            ItemStack activeItem     = mc.player.getActiveItem();
            boolean activeIsGapple   = activeItem.isOf(Items.GOLDEN_APPLE)
                || activeItem.isOf(Items.ENCHANTED_GOLDEN_APPLE);

            if (!hotbarHasGapple && !activeIsGapple) { stopEating(); return; }

            mc.player.getInventory().selectedSlot = eatHotbarSlot;
            mc.options.useKey.setPressed(true);

            if (mc.currentScreen != null) {
                if (eatTicksRemaining > 0) eatTicksRemaining--;
                else stopEating();
            } else if (!mc.player.isUsingItem()) {
                stopEating();
            }
        }
    }

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

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void disconnect(String reason) {
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.getConnection().disconnect(Text.literal(reason));
        }
        this.toggle();
    }

    private void handleChestplateElytraSwitch() {
        if (Modules.get().get(RocketPilot.class).isActive()) return;

        ItemStack chest = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (mc.player.isOnGround()) {
            // If on ground, we want a chestplate. If we have an elytra, swap it.
            if (chest.isOf(Items.ELYTRA)) {
                FindItemResult cp = findBestChestplate();
                if (cp.found()) { InvUtils.move().from(cp.slot()).toArmor(2); swapTimer = swapDelay.get(); }
            }
        } else {
            // If in air, we want an elytra. If we don't have one, swap to it.
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

    private int findBestGapple() {
        int hotbarGapple     = -1;
        int inventoryEgapple = -1;
        int inventoryGapple  = -1;

        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isEmpty()) continue;

            if (stack.isOf(Items.ENCHANTED_GOLDEN_APPLE)) {
                if (i < 9) return i;
                if (inventoryEgapple == -1) inventoryEgapple = i;
            } else if (stack.isOf(Items.GOLDEN_APPLE)) {
                if (i < 9) { if (hotbarGapple == -1) hotbarGapple = i; }
                else        { if (inventoryGapple == -1) inventoryGapple = i; }
            }
        }

        if (hotbarGapple     != -1) return hotbarGapple;
        if (inventoryEgapple != -1) return inventoryEgapple;
        return inventoryGapple;
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

        // Search in a 5x5x5 cube around the player
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
        float bestSpeed = 1.0f; // Default speed for bare hand
        int bestSlot = -1;

        for (int i = 0; i < 9; i++) { // Iterate hotbar
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
}