package com.example.addon.modules;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.packets.PacketEvent;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.effect.StatusEffectUtil;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.Packet;
import net.minecraft.network.packet.c2s.play.HandSwingC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerActionC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInputC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerInteractBlockC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
import net.minecraft.network.packet.c2s.play.UpdateSelectedSlotC2SPacket;
import net.minecraft.network.packet.s2c.play.BlockUpdateS2CPacket;
import net.minecraft.network.packet.s2c.play.BundleS2CPacket;
import net.minecraft.network.packet.s2c.play.ChunkDeltaUpdateS2CPacket;
import net.minecraft.registry.tag.FluidTags;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.BlockView;
import net.minecraft.world.World;

/**
 * ObsidianFist — places and breaks Ender Chests as fast as the server allows.
 *
 * Target throughput: ~8 cycles/second (one confirmed break every 2–3 ticks).
 *
 *
 * Persistent mode (like AllMiner): blocks toggle() while active so the server's
 * open-break state is never inadvertently closed mid-session.
 */
public class ObsidianFist extends Module {

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRepair  = settings.createGroup("Auto Repair");
    private final SettingGroup sgRender  = settings.createGroup("Render");

    // ── General settings ──────────────────────────────────────────────────────

    private final Setting<Boolean> grim = sgGeneral.add(new BoolSetting.Builder()
        .name("grim")
        .description("Use Grim-compatible packet ordering (STOP→START→ABORT→STOP). Works on most modern anti-cheats.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> anchor = sgGeneral.add(new BoolSetting.Builder()
        .name("anchor")
        .description("Holds the server's open-break state alive between toggles. When you disable the module, anchor is automatically turned off first so the state closes cleanly.")
        .defaultValue(false)
        .build());

    private final Setting<Boolean> rotate = sgGeneral.add(new BoolSetting.Builder()
        .name("rotate")
        .description("Sends a silent rotation packet each cycle — no callback delay.")
        .defaultValue(true).build());

    private final Setting<Boolean> offhandPlace = sgGeneral.add(new BoolSetting.Builder()
        .name("offhand-place")
        .description("Place Ender Chests from the offhand slot for a faster cycle.")
        .defaultValue(true)
        .onChanged(this::onOffhandSettingChanged).build());

    private final Setting<Boolean> swapBack = sgGeneral.add(new BoolSetting.Builder()
        .name("swap-back")
        .description("Restore the previous hotbar slot when stopping.")
        .defaultValue(true).build());

    private final Setting<Integer> cycleDelay = sgGeneral.add(new IntSetting.Builder()
        .name("cycle-delay")
        .description("Extra ticks to wait after each confirmed break. 0 = maximum speed (~8/s).")
        .defaultValue(0).min(0).sliderMax(10).build());

    private final Setting<Double> speedThreshold = sgGeneral.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Damage accumulation threshold (0.1–1.0) before firing the break packet. "
                   + "Lower = fires earlier. Mirrors AllMiner's speed-mine behaviour.")
        .defaultValue(1.0).min(0.1).sliderRange(0.1, 1.0).build());

    private final Setting<Boolean> instant = sgGeneral.add(new BoolSetting.Builder()
        .name("instant")
        .description("Re-mines the position the moment the server confirms it is air, before placing the next chest. Matches AllMiner's instant behaviour — keeps the server's break state hot between cycles.")
        .defaultValue(true).build());

    private final Setting<Integer> breakTimeout = sgGeneral.add(new IntSetting.Builder()
        .name("break-timeout")
        .description("Ticks before assuming the server missed the break and retrying. Lower = faster recovery.")
        .defaultValue(3).min(1).sliderMax(20).build());

    private final Setting<Integer> burstCount = sgGeneral.add(new IntSetting.Builder()
        .name("burst-count")
        .description("How many cycles to run before stopping. Ignored when Loop is on.")
        .defaultValue(16).min(1).sliderMax(256).build());

    private final Setting<Boolean> loopBurst = sgGeneral.add(new BoolSetting.Builder()
        .name("loop")
        .description("Run indefinitely. Turn off to use Burst Count.")
        .defaultValue(true).build());

    private final Setting<Double> range = sgGeneral.add(new DoubleSetting.Builder()
        .name("range").defaultValue(5.0).min(0).sliderMax(6).build());

    // ── Auto Repair ───────────────────────────────────────────────────────────

    private final Setting<Boolean> autoRepair = sgRepair.add(new BoolSetting.Builder()
        .name("auto-repair").defaultValue(false).build());
    private final Setting<Integer> repairThreshold = sgRepair.add(new IntSetting.Builder()
        .name("durability-threshold").defaultValue(50).min(1).sliderMax(500)
        .visible(autoRepair::get).build());
    private final Setting<Integer> repairPacketsPerBurst = sgRepair.add(new IntSetting.Builder()
        .name("packets-per-burst").defaultValue(3).min(1).sliderMax(10)
        .visible(autoRepair::get).build());
    private final Setting<Integer> repairBurstDelay = sgRepair.add(new IntSetting.Builder()
        .name("burst-delay").defaultValue(4).min(0).sliderMax(20)
        .visible(autoRepair::get).build());

    // ── Render ────────────────────────────────────────────────────────────────

    private final Setting<Boolean>      render    = sgRender.add(new BoolSetting.Builder().name("render").defaultValue(true).build());
    private final Setting<ShapeMode>    shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>().name("shape-mode").defaultValue(ShapeMode.Both).build());
    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder().name("side-color").defaultValue(new SettingColor(255, 0, 0, 75)).build());
    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder().name("line-color").defaultValue(new SettingColor(255, 0, 0, 255)).build());

    // ── State machine ─────────────────────────────────────────────────────────

    private enum CycleState { IDLE, WAIT_BREAK, DELAY }

    private CycleState cycleState  = CycleState.IDLE;
    private BlockPos   currentPos;
    private Direction  currentDir;
    private BlockPos   placeTarget;
    private Direction  placeSide;
    private int        prevSlot    = -1;
    private int        cyclesDone  = 0;
    private int        timer       = 0;
    private int        retries     = 0;

    private float   breakDamage        = 0f;   // accumulated per-tick break progress
    private boolean stopSent           = false; // true once sendStopPackets has fired this cycle
    private volatile boolean breakConfirmed     = false;
    private volatile boolean restorationPending = false;

    private boolean shsAutoTotemWasEnabled = false;
    private boolean isRepairing            = false;
    private int     repairTimer            = 0;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ObsidianFist() {
        super(HuntingUtilities.CATEGORY, "obsidian-fist", "Rapidly places and breaks Ender Chests to produce Obsidian.");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void toggle() {
        // When disabling, automatically release anchor first so the server's
        // break state closes cleanly — no manual step required from the user.
        if (isActive() && anchor.get()) {
            anchor.set(false);
        }
        super.toggle();
    }

    @Override
    public void onActivate() {
        anchor.set(true);
        fullReset();
        onOffhandSettingChanged(offhandPlace.get());
    }

    @Override
    public void onDeactivate() {
        // anchor.set(false) was already called in toggle() before we get here,
        // so this guard only fires if onDeactivate is invoked by Meteor directly
        // (e.g. server kick) while anchor is still on.
        if (anchor.get() && mc.getNetworkHandler() != null) return;

        if (shsAutoTotemWasEnabled) {
            reEnableAutoTotem();
            shsAutoTotemWasEnabled = false;
        }
        fullReset();
    }

    private void fullReset() {
        if (swapBack.get() && prevSlot != -1) selectSlot(prevSlot);
        if (mc.interactionManager != null) mc.interactionManager.cancelBlockBreaking();
        cycleState         = CycleState.IDLE;
        currentPos         = null;
        currentDir         = null;
        placeTarget        = null;
        placeSide          = null;
        prevSlot           = -1;
        cyclesDone         = 0;
        timer              = 0;
        retries            = 0;
        breakDamage        = 0f;
        stopSent           = false;
        breakConfirmed     = false;
        restorationPending = false;
        isRepairing        = false;
        repairTimer        = 0;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        // Drain break confirmation at the very top — zero extra tick latency.
        if (breakConfirmed) {
            breakConfirmed     = false;
            restorationPending = false;
            onBreakConfirmed();
            if (cycleState == CycleState.IDLE) return;
        }

        // Ghost-block restoration: server pushed the block back, sync and retry.
        if (restorationPending) {
            restorationPending = false;
            timer = 0;
            clearClientBlocks();
            retries++;
            if (retries > 8) { error("Too many restorations — aborting."); fullReset(); return; }
            tickCycle();
            return;
        }

        // Auto repair check.
        if (autoRepair.get()) {
            FindItemResult pk = findPickaxe();
            if (pk.found()) {
                int dur = mc.player.getInventory().getStack(pk.slot()).getMaxDamage()
                        - mc.player.getInventory().getStack(pk.slot()).getDamage();
                if (dur <= repairThreshold.get() && !isRepairing) isRepairing = true;
                if (dur > repairThreshold.get() && isRepairing) { isRepairing = false; info("Pickaxe repaired."); }
            }
            if (isRepairing) { handleRepair(pk); return; }
        }

        // WAIT_BREAK must run every tick for damage accumulation — timer only
        // gates IDLE and DELAY (cycle-delay, retry waits, etc.).
        if (cycleState == CycleState.WAIT_BREAK) {
            tickWaitBreak();
            return;
        }

        if (timer > 0) { timer--; return; }

        switch (cycleState) {
            case IDLE  -> tickIdle();
            case DELAY -> tickCycle();
        }
    }

    // ── IDLE ──────────────────────────────────────────────────────────────────

    private void tickIdle() {
        if (!(mc.crosshairTarget instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) return;

        BlockPos pos = hit.getBlockPos();
        if (mc.player.squaredDistanceTo(pos.toCenterPos()) > range.get() * range.get()) return;
        if (prevSlot == -1) prevSlot = mc.player.getInventory().getSelectedSlot();

        if (mc.world.getBlockState(pos).getBlock() == Blocks.ENDER_CHEST) {
            // Existing chest — mine it directly and enter the break loop.
            if (!findPickaxe().found()) return;
            currentPos  = pos;
            currentDir  = hit.getSide();
            placeTarget = pos.offset(hit.getSide());
            placeSide   = hit.getSide().getOpposite();
            cyclesDone  = 0;
            retries     = 0;
            doInitialMine();
        } else {
            // Empty surface — place then mine.
            if (!findPickaxe().found() || !InvUtils.find(Items.ENDER_CHEST).found()) return;
            BlockPos placePos = pos.offset(hit.getSide());
            if (!canPlace(placePos)) return;
            currentPos  = placePos;
            currentDir  = getBreakDirection(placePos);
            placeTarget = pos;
            placeSide   = hit.getSide();
            cyclesDone  = 0;
            retries     = 0;
            tickCycle();
        }
    }

    // ── Initial mine (existing chest) ─────────────────────────────────────────

    private void doInitialMine() {
        FindItemResult pk = findPickaxe();
        if (!pk.found()) { fullReset(); return; }
        InvUtils.swap(pk.slot(), false);
        mc.interactionManager.cancelBlockBreaking();
        sendRotation(currentPos);
        sendStartPacket(currentPos, currentDir);
        breakDamage = 0f;
        stopSent    = false;
        cycleState  = CycleState.WAIT_BREAK;
        timer       = breakTimeout.get();
    }

    // ── Main cycle — place + START_DESTROY ──────────────────────────────────

    /**
     * Places the ender chest and immediately sends START_DESTROY_BLOCK.
     * Damage accumulates in tickWaitBreak each tick until it crosses
     * speedThreshold, at which point sendStopPackets fires the ABORT+STOP
     * to complete the break — matching AllMiner's speed-mine pattern.
     */
    private void tickCycle() {
        FindItemResult pickaxe  = findPickaxe();
        boolean        useOffhand = offhandPlace.get() && mc.player.getOffHandStack().isOf(Items.ENDER_CHEST);

        if (!pickaxe.found()) { fullReset(); return; }
        if (!useOffhand && !InvUtils.find(Items.ENDER_CHEST).found()) { fullReset(); return; }

        mc.interactionManager.cancelBlockBreaking();
        clearClientBlocks();

        if (useOffhand) {
            // Pickaxe in main hand, chest in offhand — just ensure pickaxe selected.
            if (mc.player.getInventory().getSelectedSlot() != pickaxe.slot())
                selectSlot(pickaxe.slot());
            doPlace(Hand.OFF_HAND);
        } else {
            // Silent-swap to echest, place, silent-swap to pickaxe.
            // InvUtils.swap(slot, true) uses a ClickSlotC2SPacket — it never
            // touches syncedSelectedSlot so there is no corrective packet race.
            FindItemResult echest = InvUtils.find(Items.ENDER_CHEST);
            if (!echest.found()) { fullReset(); return; }
            InvUtils.swap(echest.slot(), true);
            doPlace(Hand.MAIN_HAND);
            InvUtils.swap(pickaxe.slot(), true);
        }

        sendRotation(currentPos);
        sendStartPacket(currentPos, currentDir);

        breakDamage = 0f;
        stopSent    = false;
        cycleState  = CycleState.WAIT_BREAK;
        timer       = breakTimeout.get();
        retries     = 0;
    }

    // ── Packet sequence ───────────────────────────────────────────────────────

    /**
     * Begins a break: sends the START_DESTROY_BLOCK (plus Grim preamble if enabled).
     * Called once per cycle from tickCycle immediately after placing the chest.
     */
    private void sendStartPacket(BlockPos pos, Direction face) {
        if (grim.get()) {
            // Grim preamble: STOP first so the server resets its break state cleanly.
            send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,  pos, face));
        }
        send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, face));
        swing();
    }

    /**
     * Completes a break: sends ABORT + STOP once speedThreshold is crossed.
     * Extra swings are sent for non-instant breaks (delta < 1.0), matching
     * AllMiner's behaviour.
     */
    private void sendStopPackets(BlockPos pos, Direction face) {
        boolean isInstant = computeBreakDelta(mc.world.getBlockState(pos), mc.world, pos) >= 1f;

        send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, pos, face));
        send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,  pos, face));
        swing();
        if (!isInstant) { swing(); swing(); }
    }

    /**
     * Full packet burst used for the instant re-mine on confirmed break.
     * Grim on  : STOP → START → ABORT → STOP
     * Grim off : START → ABORT → STOP
     */
    private void sendFullPackets(BlockPos pos, Direction face) {
        boolean isInstant = computeBreakDelta(mc.world.getBlockState(pos), mc.world, pos) >= 1f;

        if (grim.get()) {
            send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,  pos, face));
            send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, face));
            send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, pos, face));
            send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,  pos, face));
            swing();
            if (!isInstant) { swing(); swing(); }
        } else {
            send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, face));
            send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, pos, face));
            send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,  pos, face));
            swing();
            if (!isInstant) {
                send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.START_DESTROY_BLOCK, pos, face));
                send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, pos, face));
                send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.STOP_DESTROY_BLOCK,  pos, face));
                swing();
            }
        }
    }

    // ── Break speed (mirrors AllMiner) ────────────────────────────────────────

    private float computeBreakDelta(BlockState state, BlockView world, BlockPos pos) {
        float hardness = state.getHardness(world, pos);
        if (hardness == -1f) return 0f;
        return toolBreakSpeed(state) / hardness / (canHarvest(state) ? 30f : 100f);
    }

    private float toolBreakSpeed(BlockState block) {
        FindItemResult pk = findPickaxe();
        if (!pk.found()) return 1f;
        ItemStack is  = mc.player.getInventory().getStack(pk.slot());
        float     spd = is.getMiningSpeedMultiplier(block);

        if (spd > 1f) {
            int eff = 0;
            for (var e : is.getEnchantments().getEnchantmentEntries())
                if (e.getKey().matchesKey(Enchantments.EFFICIENCY)) { eff = e.getIntValue(); break; }
            if (eff > 0 && !is.isEmpty()) spd += eff * eff + 1;
        }
        if (StatusEffectUtil.hasHaste(mc.player))
            spd *= 1f + (StatusEffectUtil.getHasteAmplifier(mc.player) + 1) * 0.2f;
        if (mc.player.hasStatusEffect(StatusEffects.MINING_FATIGUE)) {
            spd *= switch (mc.player.getStatusEffect(StatusEffects.MINING_FATIGUE).getAmplifier()) {
                case 0  -> 0.3f;
                case 1  -> 0.09f;
                case 2  -> 0.0027f;
                default -> 8.1e-4f;
            };
        }
        if (mc.player.isSubmergedIn(FluidTags.WATER)) {
            boolean aqua = false;
            ItemStack helm = mc.player.getEquippedStack(EquipmentSlot.HEAD);
            if (!helm.isEmpty())
                for (var e : helm.getEnchantments().getEnchantmentEntries())
                    if (e.getKey().matchesKey(Enchantments.AQUA_AFFINITY)) { aqua = true; break; }
            if (!aqua) spd /= 5f;
        }
        if (!mc.player.isOnGround()) spd /= 5f;
        return spd;
    }

    private boolean canHarvest(BlockState state) {
        FindItemResult pk = findPickaxe();
        if (!pk.found()) return false;
        if (!state.isToolRequired()) return true;
        return mc.player.getInventory().getStack(pk.slot()).isSuitableFor(state);
    }

    // ── WAIT_BREAK — damage accumulation + speed-mine trigger ────────────────

    /**
     * Called each tick while waiting for the server to confirm the break.
     *
     * Mirrors AllMiner's tickPrimaryBlock / tickBreakQueue pattern:
     *   • Accumulate breakDamage by computeBreakDelta each tick.
     *   • Once breakDamage >= speedThreshold, fire ABORT+STOP (sendStopPackets).
     *   • If breakTimeout ticks pass without a server confirm, retry the cycle.
     */
    private void tickWaitBreak() {
        if (currentPos == null || currentDir == null) return;

        // Accumulate mining progress this tick (mirrors AllMiner addDamage).
        BlockState state = mc.world.getBlockState(currentPos);
        if (!state.isAir()) {
            breakDamage += computeBreakDelta(state, mc.world, currentPos);
        }

        // Speed-mine: once threshold crossed, send the stop packets.
        if (breakDamage >= speedThreshold.get()) {
            if (!stopSent) {
                sendRotation(currentPos);
                sendStopPackets(currentPos, currentDir);
                stopSent = true;
            }
            // Stay in WAIT_BREAK — packet receiver sets breakConfirmed when air.
            return;
        }

        // Timeout safety: count ticks spent waiting; retry if we exceed breakTimeout.
        timer--;
        if (timer <= 0) {
            retries++;
            if (retries > 8) { error("Break timed out — aborting."); fullReset(); return; }
            breakDamage = 0f;
            cycleState  = CycleState.DELAY;
            tickCycle();
        }
    }

    // ── Break confirmed ───────────────────────────────────────────────────────

    private void onBreakConfirmed() {
        mc.interactionManager.cancelBlockBreaking();
        mc.player.swingHand(Hand.MAIN_HAND);
        cyclesDone++;
        retries = 0;

        if (!loopBurst.get() && cyclesDone >= burstCount.get()) {
            fullReset();
            return;
        }

        // Instant: re-mine the now-air position so the server's break state
        // stays hot — mirrors AllMiner's instant queue behaviour.
        if (instant.get() && currentPos != null && currentDir != null) {
            sendFullPackets(currentPos, currentDir);
        }

        // Clear any ghost chest the server may have rejected before next cycle.
        clearClientBlocks();

        if (cycleDelay.get() > 0) {
            cycleState = CycleState.DELAY;
            timer      = cycleDelay.get();
        } else {
            tickCycle();
        }
    }

    // ── Packet receiver ───────────────────────────────────────────────────────

    @EventHandler
    private void onPacket(PacketEvent.Receive event) {
        if (currentPos == null) return;

        if (event.packet instanceof BlockUpdateS2CPacket p) {
            if (p.getPos().equals(currentPos)) handleBlockUpdate(p.getState().isAir());

        } else if (event.packet instanceof ChunkDeltaUpdateS2CPacket p) {
            p.visitUpdates((pos, st) -> {
                if (pos.equals(currentPos)) handleBlockUpdate(st.isAir());
            });

        } else if (event.packet instanceof BundleS2CPacket bundle) {
            for (Packet<?> inner : bundle.getPackets())
                if (inner instanceof BlockUpdateS2CPacket p && p.getPos().equals(currentPos))
                    handleBlockUpdate(p.getState().isAir());
        }
    }

    private void handleBlockUpdate(boolean isAir) {
        if (cycleState != CycleState.WAIT_BREAK) return;
        if (isAir) {
            breakConfirmed = true;
            timer          = 0;
        } else if (stopSent) {
            // Non-air after we sent STOP = server rejected the break.
            // Non-air before STOP = chest just placed normally, ignore.
            restorationPending = true;
            timer              = 0;
        }
    }

    // ── Placement ─────────────────────────────────────────────────────────────

    private void doPlace(Hand hand) {
        sendRotation(placeTarget);

        boolean sneak = !mc.player.isSneaking() && isClickable(mc.world.getBlockState(placeTarget).getBlock());
        if (sneak) send(new PlayerInputC2SPacket(sneakInput(true)));

        BlockHitResult hit = new BlockHitResult(
            new Vec3d(
                placeTarget.getX() + 0.5 + placeSide.getOffsetX() * 0.5,
                placeTarget.getY() + 0.5 + placeSide.getOffsetY() * 0.5,
                placeTarget.getZ() + 0.5 + placeSide.getOffsetZ() * 0.5),
            placeSide, placeTarget, false);

        // Raw packet — never goes through ClientPlayerInteractionManager so
        // the client-side item stack and syncedSelectedSlot are never touched.
        send(new PlayerInteractBlockC2SPacket(hand, hit, 0));
        send(new HandSwingC2SPacket(hand));

        if (sneak) send(new PlayerInputC2SPacket(sneakInput(false)));
    }

    private net.minecraft.util.PlayerInput sneakInput(boolean sneaking) {
        net.minecraft.util.PlayerInput cur = mc.player.input.playerInput;
        return new net.minecraft.util.PlayerInput(cur.forward(), cur.backward(), cur.left(), cur.right(), cur.jump(), sneaking, cur.sprint());
    }

    // ── Compatibility ─────────────────────────────────────────────────────────

    private void onOffhandSettingChanged(boolean enabled) {
        if (!isActive()) return;
        if (enabled && !shsAutoTotemWasEnabled) {
            ServerHealthcareSystem shs = Modules.get().get(ServerHealthcareSystem.class);
            if (shs != null && shs.isAutoTotemEnabled()) {
                shsAutoTotemWasEnabled = true;
                shs.setAutoTotem(false);
                info("Temporarily disabled Auto Totem in ServerHealthcareSystem to use offhand.");
            }
        } else if (!enabled && shsAutoTotemWasEnabled) {
            reEnableAutoTotem();
            shsAutoTotemWasEnabled = false;
        }
    }

    private void reEnableAutoTotem() {
        ServerHealthcareSystem shs = Modules.get().get(ServerHealthcareSystem.class);
        if (shs != null && shs.isActive()) {
            shs.setAutoTotem(true);
            info("Re-enabled Auto Totem in ServerHealthcareSystem.");
        }
    }

    // ── Auto repair ───────────────────────────────────────────────────────────

    private void handleRepair(FindItemResult pickaxe) {
        if (repairTimer > 0) { repairTimer--; return; }
        if (!pickaxe.isMainHand()) InvUtils.swap(pickaxe.slot(), false);
        if (!mc.player.getOffHandStack().isOf(Items.EXPERIENCE_BOTTLE)) {
            FindItemResult xp = InvUtils.find(Items.EXPERIENCE_BOTTLE);
            if (!xp.found()) { error("No XP bottles."); autoRepair.set(false); isRepairing = false; return; }
            InvUtils.move().from(xp.slot()).toOffhand();
            repairTimer = 2;
            return;
        }
        send(new PlayerMoveC2SPacket.LookAndOnGround(
            mc.player.getYaw(), 90f, mc.player.isOnGround(), mc.player.horizontalCollision));
        for (int i = 0; i < repairPacketsPerBurst.get(); i++)
            mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
        repairTimer = repairBurstDelay.get();
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void send(Packet<?> pkt) {
        mc.player.networkHandler.sendPacket(pkt);
    }

    private void swing() {
        send(new HandSwingC2SPacket(Hand.MAIN_HAND));
    }

    /** Selects a hotbar slot on both client and server. */
    private void selectSlot(int slot) {
        if (slot < 0 || slot > 8) return;
        mc.player.getInventory().setSelectedSlot(slot);
        send(new UpdateSelectedSlotC2SPacket(slot));
    }

    private void sendRotation(BlockPos target) {
        if (!rotate.get() || target == null || mc.player == null) return;
        Vec3d eyes  = mc.player.getEyePos();
        Vec3d d     = target.toCenterPos().subtract(eyes);
        float yaw   = (float) MathHelper.wrapDegrees(Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
        float pitch = (float) MathHelper.wrapDegrees(
            -Math.toDegrees(Math.atan2(d.y, Math.sqrt(d.x * d.x + d.z * d.z))));
        send(new PlayerMoveC2SPacket.LookAndOnGround(yaw, pitch, mc.player.isOnGround(), mc.player.horizontalCollision));
    }

    /**
     * Clears ghost blocks on both the client world and the server.
     *
     * Two kinds of ghost can appear:
     *   1. A ghost ender chest — interactBlock() updates the client world
     *      optimistically; if the server rejects it the chest sits there forever.
     *      Fix: forcibly set those positions to air in the client world.
     *   2. A ghost break-in-progress — the server still thinks we are mining.
     *      Fix: send ABORT_DESTROY_BLOCK so the server drops the dig state.
     */
    private void clearClientBlocks() {
        if (mc.world == null || mc.player == null) return;

        // Force client world to air at both positions so the next placement
        // starts from a clean slate and no ghost chest lingers visually.
        if (currentPos  != null && !mc.world.getBlockState(currentPos).isAir())
            mc.world.setBlockState(currentPos,  Blocks.AIR.getDefaultState(), 0);
        if (placeTarget != null && !mc.world.getBlockState(placeTarget).isAir())
            mc.world.setBlockState(placeTarget, Blocks.AIR.getDefaultState(), 0);

        // Tell the server to drop any lingering dig state at both positions.
        if (currentPos  != null)
            send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, currentPos,  Direction.UP));
        if (placeTarget != null)
            send(new PlayerActionC2SPacket(PlayerActionC2SPacket.Action.ABORT_DESTROY_BLOCK, placeTarget, Direction.UP));
    }

    private FindItemResult findPickaxe() {
        FindItemResult r = InvUtils.find(Items.NETHERITE_PICKAXE);
        return r.found() ? r : InvUtils.find(Items.DIAMOND_PICKAXE);
    }

    private Direction getBreakDirection(BlockPos pos) {
        Vec3d diff = mc.player.getEyePos().subtract(pos.toCenterPos());
        return Direction.getFacing(diff.x, diff.y, diff.z);
    }

    private boolean canPlace(BlockPos pos) {
        if (!World.isValid(pos)) return false;
        if (!mc.world.getBlockState(pos).isReplaceable()) return false;
        if (!mc.world.getFluidState(pos).isEmpty()) return false;
        if (mc.world.isOutOfHeightLimit(pos)) return false;
        return mc.world.canPlace(Blocks.ENDER_CHEST.getDefaultState(), pos,
            net.minecraft.block.ShapeContext.absent());
    }

    private boolean isClickable(Block block) {
        return block instanceof net.minecraft.block.CraftingTableBlock
            || block instanceof net.minecraft.block.AnvilBlock
            || block instanceof net.minecraft.block.BlockWithEntity
            || block instanceof net.minecraft.block.BedBlock
            || block instanceof net.minecraft.block.FenceGateBlock
            || block instanceof net.minecraft.block.DoorBlock
            || block instanceof net.minecraft.block.TrapdoorBlock
            || block == Blocks.CHEST
            || block == Blocks.ENDER_CHEST;
    }

    // ── Render ────────────────────────────────────────────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!render.get() || currentPos == null) return;
        event.renderer.box(currentPos, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
    }

}