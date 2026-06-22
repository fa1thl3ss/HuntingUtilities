package com.example.addon.modules;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.glfw.GLFW;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.misc.input.Input;
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.LeavesBlock;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

public class PortalMaker extends Module {

    // ── Enums ──────────────────────────────────────────────────────
    public enum EntryMode    { None, Walk, Pearl }
    private enum RecycleState { IDLE, STEPPING_OUT, WAITING, RE_ENTERING }

    // ── Setting Groups ─────────────────────────────────────────────
    private final SettingGroup sgGeneral      = settings.getDefaultGroup();
    private final SettingGroup sgMovement     = settings.createGroup("Movement & Entry");
    private final SettingGroup sgRecycle      = settings.createGroup("Recycle");
    private final SettingGroup sgRender       = settings.createGroup("Render");
    private final SettingGroup sgGlow         = settings.createGroup("Glow");

    // ── Settings — Building ────────────────────────────────────────
    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Ticks to wait between placement actions.")
        .defaultValue(2).min(1).sliderRange(1, 12)
        .build()
    );

    private final Setting<Integer> finishDelay = sgGeneral.add(new IntSetting.Builder()
        .name("finish-delay")
        .description("Ticks to wait after lighting the portal before turning off.")
        .defaultValue(20).min(0).sliderMax(200)
        .build()
    );

    private final Setting<Keybind> disableKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("disable-key")
        .description("Hotkey to quickly disable the module.")
        .defaultValue(Keybind.none())
        .action(() -> { if (isActive()) toggle(); })
        .build()
    );

    // ── Settings — Movement ────────────────────────────────────────
    private final Setting<EntryMode> entryMode = sgMovement.add(new EnumSetting.Builder<EntryMode>()
        .name("entry-mode")
        .description("How to enter the portal after it is created.")
        .defaultValue(EntryMode.Walk)
        .build()
    );

    // ── Settings — Recycle ─────────────────────────────────────────
    private final Setting<Boolean> autoRecycle = sgRecycle.add(new BoolSetting.Builder()
        .name("auto-recycle")
        .description("After changing dimension, automatically step out, wait, and go back in.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> cancelOnMovement = sgRecycle.add(new BoolSetting.Builder()
        .name("cancel-on-movement")
        .description("Cancels the recycle process if you manually press a movement key.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> recycleDelaySeconds = sgRecycle.add(new IntSetting.Builder()
        .name("recycle-wait-time")
        .description("How many seconds to wait before going back into the portal.")
        .defaultValue(5).min(1).sliderMax(60)
        .visible(autoRecycle::get)
        .build()
    );

    private final Setting<Keybind> recycleKey = sgRecycle.add(new KeybindSetting.Builder()
        .name("recycle-key")
        .description("Manual keybind to trigger the recycle cycle (step out -> wait -> in).")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Integer> dimensionSwitchCooldownTicks = sgRecycle.add(new IntSetting.Builder()
        .name("dimension-switch-cooldown")
        .description("Ticks to wait after a dimension change before resuming operations (e.g., recycling).")
        .defaultValue(40)
        .min(0).sliderMax(200)
        .build()
    );

    // ── Settings — Render ──────────────────────────────────────────
    private final Setting<Boolean> render = sgRender.add(new BoolSetting.Builder()
        .name("render")
        .description("Show remaining portal frame positions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the preview boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgRender.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(80, 160, 255, 35))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgRender.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(100, 180, 255, 255))
        .build()
    );

    // ── Settings — Glow ────────────────────────────────────────────
    private final Setting<Integer> glowLayers = sgGlow.add(new IntSetting.Builder()
        .name("glow-layers")
        .description("Number of bloom layers rendered around each preview block.")
        .defaultValue(4).min(1).sliderMax(8)
        .build()
    );

    private final Setting<Double> glowSpread = sgGlow.add(new DoubleSetting.Builder()
        .name("glow-spread")
        .description("How far each bloom layer expands outward (in blocks).")
        .defaultValue(0.05).min(0.01).sliderMax(0.2)
        .build()
    );

    private final Setting<Integer> glowBaseAlpha = sgGlow.add(new IntSetting.Builder()
        .name("glow-base-alpha")
        .description("Alpha of the innermost glow layer (0-255).")
        .defaultValue(60).min(4).sliderMax(150)
        .build()
    );

    // ── State ──────────────────────────────────────────────────────
    public final List<BlockPos> portalFramePositions = new ArrayList<>();
    private int     placementIndex   = 0;
    private int     tickTimer        = 0;
    private int     finishTimer      = 0;
    private boolean pearlThrown      = false;
    private String  lastDimension    = "";
    private String  builtDimension   = "";
    private boolean portalLitDetected = false;
    private int     dimensionChangeCooldown = 0;
    private RecycleState recycleState = RecycleState.IDLE;
    private Vec3d   recycleTarget    = null;
    private Vec3d   stepOutTarget    = null;
    private int     recycleWaitTimer = 0;
    private boolean wasRecyclePressed = false;

    private int   stuckTicks        = 0;
    private Vec3d lastPos           = null;
    private int   scaffoldCooldown  = 0;
    private int   consecutiveErrors = 0;

    public PortalMaker() {
        super(HuntingUtilities.CATEGORY, "portal-maker", "Builds and lights a minimal Nether portal (10 obsidian).");
    }

    // ── Safe Block State Helper ────────────────────────────────────
    private BlockState getSafeBlockState(BlockPos pos) {
        if (mc.world == null) return Blocks.AIR.getDefaultState();
        try {
            if (!mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4)) {
                return Blocks.AIR.getDefaultState();
            }
            return mc.world.getBlockState(pos);
        } catch (Exception e) {
            return Blocks.AIR.getDefaultState();
        }
    }

    private boolean isChunkSafe(BlockPos pos) {
        if (mc.world == null || mc.player == null) return false;
        try {
            return mc.world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4);
        } catch (Exception e) {
            return false;
        }
    }

    // ── Lifecycle ──────────────────────────────────────────────────
    @Override
    public void onActivate() {
        portalFramePositions.clear();
        placementIndex   = 0;
        tickTimer        = 0;
        finishTimer      = 0;
        pearlThrown      = false;
        stuckTicks       = 0;
        lastPos          = null;
        scaffoldCooldown = 0;
        recycleState     = RecycleState.IDLE;
        lastDimension    = "";
        wasRecyclePressed = false;
        builtDimension   = "";
        portalLitDetected = false;
        dimensionChangeCooldown = 0;
        consecutiveErrors = 0;

        if (mc.player == null || mc.world == null) { toggle(); return; }

        if (!hasItemInHotbar(Items.OBSIDIAN)) {
            int total = countItem(Items.OBSIDIAN);
            if (total > 0) warning("Obsidian is in inventory but not hotbar!");
        }
        int obsidianCount = getObsidianCount();
        if (obsidianCount < 10) {
            error("Need at least 10 obsidian (found " + obsidianCount + ")");
            toggle();
            return;
        }

        if (!hasItem(Items.FLINT_AND_STEEL)) warning("No flint & steel found — light manually.");

        Direction facing = mc.player.getHorizontalFacing();
        Direction right  = facing.rotateYClockwise();

        BlockPos feet     = mc.player.getBlockPos();
        boolean  adjusted = false;

        if (!mc.world.getBlockState(feet.down()).isFullCube(mc.world, feet.down())) {
            feet     = feet.up();
            adjusted = true;
        }

        BlockPos origin = feet.offset(facing, 2).offset(right, -1);

        portalFramePositions.add(origin.offset(right, 1));
        portalFramePositions.add(origin.offset(right, 2));
        portalFramePositions.add(origin.up(1));
        portalFramePositions.add(origin.up(2));
        portalFramePositions.add(origin.up(3));
        portalFramePositions.add(origin.offset(right, 3).up(1));
        portalFramePositions.add(origin.offset(right, 3).up(2));
        portalFramePositions.add(origin.offset(right, 3).up(3));
        portalFramePositions.add(origin.offset(right, 1).up(4));
        portalFramePositions.add(origin.offset(right, 2).up(4));

        if (adjusted) {
            BlockPos stepPos = feet.offset(facing, 1);
            if (mc.world.getBlockState(stepPos).isReplaceable()) portalFramePositions.add(stepPos);
        }

        boolean blocked = portalFramePositions.stream()
            .anyMatch(p -> !mc.world.getBlockState(p).isReplaceable());
        if (blocked) { error("Portal area is obstructed. Move slightly and try again."); toggle(); return; }

        long existing = portalFramePositions.stream()
            .filter(p -> mc.world.getBlockState(p).getBlock() == Blocks.OBSIDIAN)
            .count();
        if (existing >= 9) {
            info("Portal frame looks complete → attempting to light it.");
            placementIndex = portalFramePositions.size();
        }

        lastDimension = mc.world.getRegistryKey().getValue().toString();
        builtDimension = lastDimension;

        selectHotbarItem(Items.OBSIDIAN);
        info("Building minimal Nether portal...");
    }

    @Override
    public void onDeactivate() {
        portalFramePositions.clear();
        placementIndex   = 0;
        tickTimer        = 0;
        stuckTicks       = 0;
        lastPos          = null;
        stopMovement();
    }

    // ── Event Handlers ─────────────────────────────────────────────
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (mc.player.isDead() || !mc.player.isAlive()) {
            stopMovement();
            toggle();
            return;
        }

        if (cancelOnMovement.get() && isMovingManually()) {
            if (recycleState != RecycleState.IDLE || dimensionChangeCooldown > 0) {
                info("Recycle cancelled by manual movement.");
            }
            toggle();
            return;
        }

        try { 
            if (mc.world.getRegistryKey() == null) return; 
        } catch (Exception ignored) { 
            return; 
        }
        
        String currentDim;
        try {
            currentDim = mc.world.getRegistryKey().getValue().toString();
        } catch (Exception e) { 
            return; 
        }

        if (builtDimension.isEmpty()) builtDimension = currentDim;

        if (!currentDim.equals(lastDimension)) {
            lastDimension = currentDim;
            portalFramePositions.clear();
            if (autoRecycle.get()) {
                dimensionChangeCooldown = dimensionSwitchCooldownTicks.get();
            }
            return;
        }

        if (dimensionChangeCooldown > 0) {
            dimensionChangeCooldown--;
            if (dimensionChangeCooldown == 0) {
                startRecycle();
            }
            return;
        }

        boolean recyclePressed = recycleKey.get().isPressed();
        if (recyclePressed && !wasRecyclePressed) {
            if (recycleState == RecycleState.IDLE) {
                if (dimensionChangeCooldown > 0) info("Cannot recycle yet, waiting for dimension change cooldown.");
                else startRecycle();
            } else {
                recycleState = RecycleState.IDLE;
                stopMovement();
                info("Recycle cancelled.");
            }
        }
        wasRecyclePressed = recyclePressed;

        if (recycleState != RecycleState.IDLE) {
            handleRecycle();
            return;
        }

        if (isPlayerInPortal()) {
            stopMovement();
            if (!autoRecycle.get() && !recycleKey.get().isSet() && recycleState == RecycleState.IDLE) {
                toggle();
            }
            return;
        }

        if (isPortalLit()) portalLitDetected = true;

        if (portalLitDetected || !currentDim.equals(builtDimension)) {
            if (currentDim.equals(builtDimension)) handlePhase2();
            return;
        }

        placementIndex = portalFramePositions.size();
        for (int i = 0; i < portalFramePositions.size(); i++) {
            BlockPos bp = portalFramePositions.get(i);
            if (!isChunkSafe(bp)) {
                placementIndex = portalFramePositions.size();
                break;
            }
            if (mc.world.getBlockState(bp).getBlock() != Blocks.OBSIDIAN) {
                placementIndex = i;
                break;
            }
        }

        if (placementIndex < portalFramePositions.size()) {
            if (mc.player.getInventory().main.isEmpty()) return;

            if (!mc.player.getMainHandStack().isOf(Items.OBSIDIAN)) {
                FindItemResult obsidian = InvUtils.find(Items.OBSIDIAN);
                if (!obsidian.found()) { error("No obsidian found -> disabled."); toggle(); return; }
                if (obsidian.isHotbar()) mc.player.getInventory().selectedSlot = obsidian.slot();
                else InvUtils.move().from(obsidian.slot()).toHotbar(mc.player.getInventory().selectedSlot);
            }

            tickTimer++;
            if (tickTimer < placeDelay.get()) return;
            tickTimer = 0;

            BlockPos target = portalFramePositions.get(placementIndex);
            if (!isChunkSafe(target)) return;
            
            if (mc.world.getBlockState(target).getBlock() == Blocks.OBSIDIAN) { placementIndex++; return; }

            if (!mc.world.getBlockState(target).isReplaceable()) {
                mc.interactionManager.attackBlock(target, mc.player.getHorizontalFacing().getOpposite());
                mc.player.swingHand(Hand.MAIN_HAND);
                return;
            }

            Rotations.rotate(Rotations.getYaw(target), Rotations.getPitch(target), () -> {
                BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(target), Direction.UP, target, false);
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                mc.player.swingHand(Hand.MAIN_HAND);
            });
            placementIndex++;
            return;
        }

        handlePhase2();
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null) return;
        
        if (render.get() && !portalFramePositions.isEmpty()) {
            for (int i = placementIndex; i < portalFramePositions.size(); i++) {
                BlockPos pos = portalFramePositions.get(i);
                if (!isChunkSafe(pos)) continue;
                if (!mc.world.getBlockState(pos).isReplaceable()) continue;

                Box box = new Box(pos);
                renderGlowLayers(event, box, lineColor.get());
                event.renderer.box(box, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        }
    }

    // ── Building Logic ─────────────────────────────────────────────
    private void handlePhase2() {
        if (!portalLitDetected) {
            finishTimer = 0;
            if (tickTimer++ >= 10) { lightPortal(); tickTimer = 0; }
            return;
        }

        if (entryMode.get() != EntryMode.None) {
            moveToPortal();
        } else {
            if (finishTimer++ >= finishDelay.get()) {
                info("PortalMaker finished.");
                toggle();
            }
        }
    }

    // ── Recycle Logic ──────────────────────────────────────────────
    private void handleRecycle() {
        if (mc.player == null || mc.world == null) {
            recycleState = RecycleState.IDLE;
            stopMovement();
            return;
        }

        if (mc.player.isDead() || !mc.player.isAlive()) {
            recycleState = RecycleState.IDLE;
            stopMovement();
            toggle();
            return;
        }

        switch (recycleState) {
            case STEPPING_OUT -> {
                if (stepOutTarget == null) { recycleState = RecycleState.WAITING; return; }

                double dist = mc.player.getPos().distanceTo(stepOutTarget);
                if (dist < 0.3 || (dist < 1.2 && !isPlayerInPortal())) {
                    stopMovement();
                    recycleState = RecycleState.WAITING;
                    return;
                }
                moveTo(stepOutTarget);
            }
            case WAITING -> {
                stopMovement(); 
                if (recycleWaitTimer-- <= 0) {
                    recycleState = RecycleState.RE_ENTERING;
                    info("Wait complete. Re-entering portal...");
                }
            }
            case RE_ENTERING -> {
                moveTo(recycleTarget);
                if (isPlayerInPortal()) {
                    stopMovement();
                    toggle();
                }
            }
            default -> {}
        }
    }

    private void startRecycle() {
        if (mc.player == null || mc.world == null) {
            recycleState = RecycleState.IDLE;
            return;
        }
        
        BlockPos playerPos = mc.player.getBlockPos();
        if (!isChunkSafe(playerPos)) {
            dimensionChangeCooldown = 10; 
            return;
        }
        
        setupRecycleTarget();
        recycleState = RecycleState.STEPPING_OUT;
        recycleWaitTimer = recycleDelaySeconds.get() * 20;
        info("Initiating portal recycle...");
    }

    private void setupRecycleTarget() {
        if (mc.player == null || mc.world == null) {
            recycleTarget = null;
            stepOutTarget = null;
            return;
        }

        BlockPos pos = mc.player.getBlockPos();
        
        if (!getSafeBlockState(pos).isOf(Blocks.NETHER_PORTAL)) {
            for (BlockPos p : BlockPos.iterate(pos.add(-5, -5, -5), pos.add(5, 5, 5))) {
                if (!isChunkSafe(p)) continue; 
                if (getSafeBlockState(p).isOf(Blocks.NETHER_PORTAL)) {
                    pos = p;
                    break;
                }
            }
        }

        if (getSafeBlockState(pos).isOf(Blocks.NETHER_PORTAL)) {
            BlockState state = getSafeBlockState(pos);
            Direction.Axis axis = state.contains(net.minecraft.state.property.Properties.HORIZONTAL_AXIS) 
                ? state.get(net.minecraft.state.property.Properties.HORIZONTAL_AXIS) 
                : Direction.Axis.X;

            int minC = axis == Direction.Axis.X ? pos.getX() : pos.getZ();
            int maxC = minC;

            int maxIterations = 20;
            while (maxIterations-- > 0) {
                BlockPos checkPos = axis == Direction.Axis.X 
                    ? new BlockPos(minC - 1, pos.getY(), pos.getZ()) 
                    : new BlockPos(pos.getX(), pos.getY(), minC - 1);
                if (!isChunkSafe(checkPos)) break;
                if (!getSafeBlockState(checkPos).isOf(Blocks.NETHER_PORTAL)) break;
                minC--;
            }

            maxIterations = 20;
            while (maxIterations-- > 0) {
                BlockPos checkPos = axis == Direction.Axis.X 
                    ? new BlockPos(maxC + 1, pos.getY(), pos.getZ()) 
                    : new BlockPos(pos.getX(), pos.getY(), maxC + 1);
                if (!isChunkSafe(checkPos)) break;
                if (!getSafeBlockState(checkPos).isOf(Blocks.NETHER_PORTAL)) break;
                maxC++;
            }

            double mid = (minC + maxC + 1) / 2.0;
            if (axis == Direction.Axis.X) {
                recycleTarget = new Vec3d(mid, pos.getY(), pos.getZ() + 0.5);
                Vec3d o1 = recycleTarget.add(0, 0, 2.0);
                Vec3d o2 = recycleTarget.add(0, 0, -2.0);
                if (isAreaClear(o1)) stepOutTarget = o1;
                else if (isAreaClear(o2)) stepOutTarget = o2;
                else stepOutTarget = o1;
            } else {
                recycleTarget = new Vec3d(pos.getX() + 0.5, pos.getY(), mid);
                Vec3d o1 = recycleTarget.add(2.0, 0, 0);
                Vec3d o2 = recycleTarget.add(-2.0, 0, 0);
                if (isAreaClear(o1)) stepOutTarget = o1;
                else if (isAreaClear(o2)) stepOutTarget = o2;
                else stepOutTarget = o1;
            }
        } else {
            recycleTarget = mc.player.getPos();
            stepOutTarget = mc.player.getPos().add(mc.player.getRotationVector().multiply(-2.0));
        }
    }

    private boolean isAreaClear(Vec3d pos) {
        if (mc.world == null) return false;
        BlockPos bp = BlockPos.ofFloored(pos);
        if (!isChunkSafe(bp)) return false;
        return getSafeBlockState(bp).isReplaceable() && getSafeBlockState(bp.up()).isReplaceable();
    }

    // ── Portal Helpers ─────────────────────────────────────────────
    private boolean isPlayerInPortal() {
        if (mc.player == null || mc.world == null) return false;
        BlockPos feet = mc.player.getBlockPos();
        if (!isChunkSafe(feet)) return false;
        return getSafeBlockState(feet).isOf(Blocks.NETHER_PORTAL) ||
               getSafeBlockState(feet.up()).isOf(Blocks.NETHER_PORTAL);
    }

    private void lightPortal() {
        if (portalFramePositions.isEmpty()) return;
        if (!selectHotbarItem(Items.FLINT_AND_STEEL)) { warning("Cannot find flint & steel in hotbar."); return; }

        BlockPos bottom1 = portalFramePositions.get(0);
        BlockPos bottom2 = portalFramePositions.get(1);

        for (BlockPos pos : new BlockPos[]{bottom1, bottom2}) {
            if (!isChunkSafe(pos)) continue;
            if (getSafeBlockState(pos.up()).isAir()) {
                Rotations.rotate(Rotations.getYaw(pos), Rotations.getPitch(pos), () -> {
                    BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(pos).add(0, 0.5, 0), Direction.UP, pos, false);
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                    mc.player.swingHand(Hand.MAIN_HAND);
                });
                break;
            }
        }
    }

    private boolean isPortalLit() {
        if (portalFramePositions.size() < 2) return false;
        BlockPos p1 = portalFramePositions.get(0).up();
        BlockPos p2 = portalFramePositions.get(1).up();

        if (!isChunkSafe(p1) || !isChunkSafe(p2)) {
            return portalLitDetected;
        }

        return mc.world.getBlockState(p1).getBlock() == Blocks.NETHER_PORTAL ||
               mc.world.getBlockState(p2).getBlock() == Blocks.NETHER_PORTAL;
    }

    // ── Movement Engine ────────────────────────────────────────────
    private void moveToPortal() {
        if (portalFramePositions.size() < 2) return;
        moveTo(getPortalOpeningCenter());
    }

    private void moveTo(Vec3d target) {
        if (mc.player == null || mc.world == null || target == null) return;
        if (mc.player.isDead() || !mc.player.isAlive()) {
            stopMovement();
            toggle();
            return;
        }

        Vec3d playerPos = mc.player.getPos();

        if (playerPos.y < target.y - 4.0) {
            error("Fell too far below the portal — stopping.");
            stopMovement();
            toggle();
            return;
        }

        if (lastPos != null && lastPos.squaredDistanceTo(playerPos) < 0.001) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPos = playerPos;

        BlockPos feetPos = mc.player.getBlockPos();
        boolean slippery = isSlippery(getSafeBlockState(feetPos.down()));

        if (stuckTicks > (slippery ? 10 : 20) && stuckTicks < 200 && mc.player.isOnGround()) {
            mc.player.jump();
        }

        if (stuckTicks > 200) {
            error("Stuck trying to enter portal opening — stopping.");
            stopMovement();
            toggle();
            return;
        }

        double dx = target.x - playerPos.x;
        double dz = target.z - playerPos.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        stopMovement();

        if (hDist > 0.02) {
            Direction dir = directionFromVector(dx, dz);
            BlockPos playerFeet = mc.player.getBlockPos();
            BlockPos footPos = playerFeet.offset(dir);

            boolean footBlocked = isHardObstacle(footPos);
            boolean headBlocked = isHardObstacle(footPos.up());
            
            BlockState footDownState = getSafeBlockState(footPos.down());
            boolean gapAhead = !footBlocked && !footDownState.isSolidBlock(mc.world, footPos.down())
                               && !footDownState.isOf(Blocks.NETHER_PORTAL);

            if (gapAhead && mc.player.isOnGround()) {
                if (scaffoldCooldown <= 0) {
                    if (slippery && stuckTicks > 5) {
                        mc.options.sneakKey.setPressed(false);
                    } else {
                        mc.options.sneakKey.setPressed(true);
                    }

                    if (tryScaffoldPlace(footPos.down())) {
                        scaffoldCooldown = placeDelay.get() + 2;
                    }
                }
            } else if (footBlocked && !headBlocked && mc.player.isOnGround()) {
                mc.player.jump();
            }

            if (stuckTicks > 40 || (footBlocked && headBlocked)) {
                for (BlockPos p : new BlockPos[]{footPos, footPos.up()}) {
                    if (!isChunkSafe(p)) continue;
                    BlockState bs = getSafeBlockState(p);
                    if (!bs.isAir() && !bs.isOf(Blocks.OBSIDIAN) && !bs.isOf(Blocks.NETHER_PORTAL) && bs.getHardness(mc.world, p) >= 0) {
                        mc.interactionManager.attackBlock(p, dir.getOpposite());
                        mc.player.swingHand(Hand.MAIN_HAND);
                    }
                }
            }

            for (BlockPos p : new BlockPos[]{playerFeet, playerFeet.up(), footPos, footPos.up()}) {
                if (!isChunkSafe(p)) continue;
                BlockState bs = getSafeBlockState(p);
                if (isSoftObstacle(bs)) {
                    mc.interactionManager.attackBlock(p, dir.getOpposite());
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
            }

            float pYaw = MathHelper.wrapDegrees(mc.player.getYaw());
            float diff = MathHelper.wrapDegrees(yaw - pYaw);

            mc.options.forwardKey.setPressed(diff > -67.5 && diff <= 67.5);
            mc.options.backKey.setPressed(diff > 112.5 || diff <= -112.5);
            mc.options.leftKey.setPressed(diff > -157.5 && diff <= -22.5);
            mc.options.rightKey.setPressed(diff > 22.5 && diff <= 157.5);

            mc.options.sprintKey.setPressed(hDist > (slippery ? 4.0 : 1.5) && (diff > -30 && diff <= 30));
        }

        if (scaffoldCooldown > 0) scaffoldCooldown--;
    }

    private Vec3d getPortalOpeningCenter() {
        BlockPos p1 = portalFramePositions.get(0).up();
        BlockPos p2 = portalFramePositions.get(1).up();
        return new Vec3d(
            (p1.getX() + p2.getX()) / 2.0 + 0.5,
             p1.getY(),
            (p1.getZ() + p2.getZ()) / 2.0 + 0.5
        );
    }

    private Direction directionFromVector(double dx, double dz) {
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx > 0 ? Direction.EAST : Direction.WEST;
        } else {
            return dz > 0 ? Direction.SOUTH : Direction.NORTH;
        }
    }

    private boolean isSlippery(BlockState state) {
        if (state == null || state.isAir()) return false;
        Block b = state.getBlock();
        return b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE
            || b == Blocks.FROSTED_ICE || b == Blocks.SLIME_BLOCK;
    }

    private boolean isHardObstacle(BlockPos pos) {
        if (!isChunkSafe(pos)) return false; 
        BlockState state = getSafeBlockState(pos);
        if (state.isAir() || state.isReplaceable())        return false;
        if (state.isOf(Blocks.NETHER_PORTAL))              return false;
        if (isSoftObstacle(state))                         return false;
        return true;
    }

    private boolean isSoftObstacle(BlockState state) {
        if (state == null) return false;
        Block b = state.getBlock();
        if (b == Blocks.COBWEB || b == Blocks.POWDER_SNOW) return true;
        if (b instanceof LeavesBlock) return true;
        return false;
    }

    // ── Placement Helpers ──────────────────────────────────────────
    private void stopMovement() {
        if (mc.options == null) return;
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }

    private boolean tryScaffoldPlace(BlockPos pos) {
        if (!isChunkSafe(pos)) return false;
        
        Direction[] order = {
            Direction.DOWN,
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
            Direction.UP
        };

        BlockPos  neighbor  = null;
        Direction placeSide = null;
        for (Direction side : order) {
            BlockPos check = pos.offset(side);
            if (!isChunkSafe(check)) continue;
            if (!getSafeBlockState(check).isReplaceable()) { neighbor = check; placeSide = side.getOpposite(); break; }
        }
        if (neighbor == null) return false;

        if (!mc.player.getMainHandStack().isOf(Items.OBSIDIAN)) {
            FindItemResult obsidian = InvUtils.find(Items.OBSIDIAN);
            if (!obsidian.found()) return false;
            if (obsidian.isHotbar()) mc.player.getInventory().selectedSlot = obsidian.slot();
            else InvUtils.move().from(obsidian.slot()).toHotbar(mc.player.getInventory().selectedSlot);
        }

        final BlockPos  finalNeighbor  = neighbor;
        final Direction finalPlaceSide = placeSide;

        Rotations.rotate(
            Rotations.getYaw(Vec3d.ofCenter(finalNeighbor)),
            Rotations.getPitch(Vec3d.ofCenter(finalNeighbor)),
            () -> {
                BlockHitResult hit = new BlockHitResult(
                    Vec3d.ofCenter(finalNeighbor).offset(finalPlaceSide, 0.5),
                    finalPlaceSide, finalNeighbor, false
                );
                mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hit);
                mc.player.swingHand(Hand.MAIN_HAND);
            }
        );
        return true;
    }

    // ── Render Helpers ─────────────────────────────────────────────
    private void renderGlowLayers(Render3DEvent event, Box box, SettingColor color) {
        int    layers    = glowLayers.get();
        double spread    = glowSpread.get();
        int    baseAlpha = glowBaseAlpha.get();

        for (int i = layers; i >= 1; i--) {
            double expansion  = spread * i;
            int    layerAlpha = Math.max(4, (int) (baseAlpha * (1.0 - (double) (i - 1) / layers)));
            event.renderer.box(
                box.expand(expansion),
                withAlpha(color, layerAlpha),
                withAlpha(color, 0),
                ShapeMode.Sides, 0
            );
        }
    }

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    // ── Utility Helpers ────────────────────────────────────────────
    private boolean selectHotbarItem(Item targetItem) {
        if (mc.player == null) return false;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == targetItem) {
                mc.player.getInventory().selectedSlot = i;
                return true;
            }
        }
        return false;
    }

    private boolean hasItemInHotbar(Item targetItem) {
        if (mc.player == null) return false;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == targetItem) return true;
        }
        return false;
    }

    public int getObsidianCount() {
        return countItem(Items.OBSIDIAN);
    }

    private int countItem(Item targetItem) {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(targetItem)) count += stack.getCount();
        }
        ItemStack offhand = mc.player.getOffHandStack();
        if (offhand.isOf(targetItem)) count += offhand.getCount();
        return count;
    }

    private boolean hasItem(Item targetItem) {
        return countItem(targetItem) > 0;
    }

    private boolean isMovingManually() {
        if (mc.currentScreen != null) return false;
        return Input.isKeyPressed(GLFW.GLFW_KEY_W) || 
               Input.isKeyPressed(GLFW.GLFW_KEY_A) ||
               Input.isKeyPressed(GLFW.GLFW_KEY_S) || 
               Input.isKeyPressed(GLFW.GLFW_KEY_D) ||
               Input.isKeyPressed(GLFW.GLFW_KEY_SPACE) || 
               Input.isKeyPressed(GLFW.GLFW_KEY_LEFT_SHIFT) ||
               Input.isKeyPressed(GLFW.GLFW_KEY_RIGHT_SHIFT);
    }
}