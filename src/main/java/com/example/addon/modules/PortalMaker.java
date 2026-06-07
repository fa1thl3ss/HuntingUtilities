package com.example.addon.modules;

import java.util.ArrayList;
import java.util.List;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
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

    // ═══════════════════════════════════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════════════════════════════════

    public enum EntryMode {
        None, Walk, Pearl
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Setting Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgGlow    = settings.createGroup("Glow");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — General
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Integer> placeDelay = sgGeneral.add(new IntSetting.Builder()
        .name("place-delay")
        .description("Ticks to wait between placement actions.")
        .defaultValue(2).min(1).sliderRange(1, 12)
        .build()
    );

    private final Setting<Boolean> render = sgGeneral.add(new BoolSetting.Builder()
        .name("render")
        .description("Show remaining portal frame positions.")
        .defaultValue(true)
        .build()
    );

    private final Setting<ShapeMode> shapeMode = sgGeneral.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode")
        .description("How the preview boxes are rendered.")
        .defaultValue(ShapeMode.Both)
        .build()
    );

    private final Setting<SettingColor> sideColor = sgGeneral.add(new ColorSetting.Builder()
        .name("side-color")
        .defaultValue(new SettingColor(80, 160, 255, 35))
        .build()
    );

    private final Setting<SettingColor> lineColor = sgGeneral.add(new ColorSetting.Builder()
        .name("line-color")
        .defaultValue(new SettingColor(100, 180, 255, 255))
        .build()
    );

    private final Setting<EntryMode> entryMode = sgGeneral.add(new EnumSetting.Builder<EntryMode>()
        .name("entry-mode")
        .description("How to enter the portal after it is created.")
        .defaultValue(EntryMode.Walk)
        .build()
    );

    private final Setting<Boolean> renderBreadcrumbs = sgGeneral.add(new BoolSetting.Builder()
        .name("render-breadcrumbs")
        .description("Draw a trail showing the walker's path for debugging.")
        .defaultValue(false)
        .visible(() -> entryMode.get() == EntryMode.Walk)
        .build()
    );

    private final Setting<SettingColor> breadcrumbColor = sgGeneral.add(new ColorSetting.Builder()
        .name("breadcrumb-color")
        .description("Color of the breadcrumb trail.")
        .defaultValue(new SettingColor(255, 255, 255, 150))
        .visible(() -> renderBreadcrumbs.get() && entryMode.get() == EntryMode.Walk)
        .build()
    );

    private final Setting<Integer> finishDelay = sgGeneral.add(new IntSetting.Builder()
        .name("finish-delay")
        .description("Ticks to wait after lighting the portal before turning off.")
        .defaultValue(20).min(0).sliderMax(200)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Glow
    // ═══════════════════════════════════════════════════════════════════════════

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

    // ═══════════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════════

    public final List<BlockPos> portalFramePositions = new ArrayList<>();
    private int     placementIndex   = 0;
    private int     tickTimer        = 0;
    private int     finishTimer      = 0;
    private boolean pearlThrown      = false;
    private final List<Vec3d> breadcrumbs = new ArrayList<>();

    /** Ticks the player has been roughly stationary while walking to portal. */
    private int   stuckTicks        = 0;
    private Vec3d lastPos           = null;
    private int   scaffoldCooldown  = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════════

    public PortalMaker() {
        super(HuntingUtilities.CATEGORY, "portal-maker", "Builds and lights a minimal Nether portal (10 obsidian).");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

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
        breadcrumbs.clear();

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
        breadcrumbs.clear();
        stopMovement();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tick
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (isPlayerInPortal()) {
            stopMovement();
            toggle();
            return;
        }

        // Recalculate which blocks still need placing.
        placementIndex = portalFramePositions.size();
        for (int i = 0; i < portalFramePositions.size(); i++) {
            if (mc.world.getBlockState(portalFramePositions.get(i)).getBlock() != Blocks.OBSIDIAN) {
                placementIndex = i;
                break;
            }
        }

        // ── Phase 1: place obsidian ────────────────────────────────────────────
        if (placementIndex < portalFramePositions.size()) {
            if (!mc.player.getMainHandStack().isOf(Items.OBSIDIAN)) {
                FindItemResult obsidian = InvUtils.find(Items.OBSIDIAN);
                if (!obsidian.found()) { error("No obsidian found → disabled."); toggle(); return; }
                if (obsidian.isHotbar()) mc.player.getInventory().selectedSlot = obsidian.slot();
                else InvUtils.move().from(obsidian.slot()).toHotbar(mc.player.getInventory().selectedSlot);
            }

            tickTimer++;
            if (tickTimer < placeDelay.get()) return;
            tickTimer = 0;

            BlockPos target = portalFramePositions.get(placementIndex);
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

        // ── Phase 2: light / enter ─────────────────────────────────────────────
        if (isPortalLit()) {
            if (entryMode.get() != EntryMode.None) {
                moveToPortal();
            } else {
                if (finishTimer++ >= finishDelay.get()) {
                    info("PortalMaker finished.");
                    toggle();
                }
            }
        } else {
            finishTimer = 0;
            if (tickTimer++ >= 10) { lightPortal(); tickTimer = 0; }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Portal Logic
    // ═══════════════════════════════════════════════════════════════════════════

    private void lightPortal() {
        if (portalFramePositions.isEmpty()) return;
        if (!selectHotbarItem(Items.FLINT_AND_STEEL)) { warning("Cannot find flint & steel in hotbar."); return; }

        BlockPos bottom1 = portalFramePositions.get(0);
        BlockPos bottom2 = portalFramePositions.get(1);

        for (BlockPos pos : new BlockPos[]{bottom1, bottom2}) {
            if (mc.world.getBlockState(pos.up()).isAir()) {
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
        return mc.world.getBlockState(p1).getBlock() == Blocks.NETHER_PORTAL ||
               mc.world.getBlockState(p2).getBlock() == Blocks.NETHER_PORTAL;
    }

    private boolean isPlayerInPortal() {
        BlockPos feet = mc.player.getBlockPos();
        return mc.world.getBlockState(feet).isOf(Blocks.NETHER_PORTAL) ||
               mc.world.getBlockState(feet.up()).isOf(Blocks.NETHER_PORTAL);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Portal Entry Movement
    // ═══════════════════════════════════════════════════════════════════════════

    private void moveToPortal() {
        if (portalFramePositions.size() < 2 || mc.player == null || mc.world == null) return;

        // 1. Ender pearl fast-path
        if (entryMode.get() == EntryMode.Pearl) {
            if (!pearlThrown && selectHotbarItem(Items.ENDER_PEARL)) {
                Vec3d center = getPortalOpeningCenter().add(0, 0.5, 0);
                Rotations.rotate(Rotations.getYaw(center), Rotations.getPitch(center), () -> {
                    mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
                    mc.player.swingHand(Hand.MAIN_HAND);
                });
                pearlThrown = true;
            }
            return;
        }

        Vec3d target = getPortalOpeningCenter();
        Vec3d playerPos = mc.player.getPos();

        // 2. Safety Guards
        if (playerPos.y < target.y - 4.0) {
            error("Fell too far below the portal — stopping.");
            stopMovement();
            toggle();
            return;
        }

        if (isPlayerInPortal()) {
            stopMovement();
            return;
        }

        // 3. Stuck detection
        if (lastPos != null && lastPos.squaredDistanceTo(playerPos) < 0.001) {
            stuckTicks++;
        } else {
            stuckTicks = 0;
        }
        lastPos = playerPos;

        boolean slippery = isSlippery(mc.world.getBlockState(mc.player.getBlockPos().down()));

        // Active recovery: jump if stuck for a short while.
        // Recover faster on ice as sliding often prevents simple walking.
        if (stuckTicks > (slippery ? 10 : 20) && stuckTicks < 200 && mc.player.isOnGround()) {
            mc.player.jump();
        }

        if (stuckTicks > 200) {
            error("Stuck trying to enter portal opening — stopping.");
            stopMovement();
            toggle();
            return;
        }

        // 4. Movement Logic
        double dx = target.x - playerPos.x;
        double dz = target.z - playerPos.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));

        stopMovement(); // Reset keys for clean state

        if (hDist > 0.05) {
            Direction dir = directionFromVector(dx, dz);
            BlockPos playerFeet = mc.player.getBlockPos();
            BlockPos footPos = playerFeet.offset(dir);

            boolean footBlocked = isHardObstacle(footPos);
            boolean headBlocked = isHardObstacle(footPos.up());
            boolean gapAhead = !footBlocked && !mc.world.getBlockState(footPos.down()).isSolidBlock(mc.world, footPos.down())
                               && !mc.world.getBlockState(footPos.down()).isOf(Blocks.NETHER_PORTAL);

            if (gapAhead && mc.player.isOnGround()) {
                if (scaffoldCooldown <= 0) {
                    // On ice, sneaking while walking can kill all momentum or lock friction.
                    // Briefly release sneak if stuck to regain traction.
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

            // Drill through physical obstructions if stuck or completely blocked by terrain.
            if (stuckTicks > 40 || (footBlocked && headBlocked)) {
                for (BlockPos p : new BlockPos[]{footPos, footPos.up()}) {
                    BlockState bs = mc.world.getBlockState(p);
                    // Avoid breaking the portal itself or its obsidian frame.
                    if (!bs.isAir() && !bs.isOf(Blocks.OBSIDIAN) && !bs.isOf(Blocks.NETHER_PORTAL) && bs.getHardness(mc.world, p) >= 0) {
                        mc.interactionManager.attackBlock(p, dir.getOpposite());
                        mc.player.swingHand(Hand.MAIN_HAND);
                    }
                }
            }

            for (BlockPos p : new BlockPos[]{playerFeet, playerFeet.up(), footPos, footPos.up()}) {
                BlockState bs = mc.world.getBlockState(p);
                if (isSoftObstacle(bs)) {
                    mc.interactionManager.attackBlock(p, dir.getOpposite());
                    mc.player.swingHand(Hand.MAIN_HAND);
                }
            }

            // Movement input relative to current camera yaw
            float pYaw = MathHelper.wrapDegrees(mc.player.getYaw());
            float diff = MathHelper.wrapDegrees(yaw - pYaw);

            mc.options.forwardKey.setPressed(diff > -67.5 && diff <= 67.5);
            mc.options.backKey.setPressed(diff > 112.5 || diff <= -112.5);
            mc.options.leftKey.setPressed(diff > -157.5 && diff <= -22.5);
            mc.options.rightKey.setPressed(diff > 22.5 && diff <= 157.5);

            // Disable sprinting earlier on ice to avoid sliding past the portal opening.
            mc.options.sprintKey.setPressed(hDist > (slippery ? 4.0 : 1.5) && (diff > -30 && diff <= 30));
        }

        if (scaffoldCooldown > 0) scaffoldCooldown--;

        if (renderBreadcrumbs.get()) {
            if (breadcrumbs.isEmpty() || breadcrumbs.get(breadcrumbs.size() - 1).distanceTo(playerPos) > 0.15) {
                breadcrumbs.add(playerPos);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Geometry helpers
    // ═══════════════════════════════════════════════════════════════════════════

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

    /** Returns true for blocks with low friction like ice or slime. */
    private boolean isSlippery(BlockState state) {
        if (state.isAir()) return false;
        Block b = state.getBlock();
        return b == Blocks.ICE || b == Blocks.PACKED_ICE || b == Blocks.BLUE_ICE
            || b == Blocks.FROSTED_ICE || b == Blocks.SLIME_BLOCK;
    }

    /**
     * Returns true for blocks that are genuinely impassable and worth
     * jumping/scaffolding over: solid, non-portal, non-soft blocks.
     */
    private boolean isHardObstacle(BlockPos pos) {
        BlockState state = mc.world.getBlockState(pos);
        if (state.isAir() || state.isReplaceable())        return false;
        if (state.isOf(Blocks.NETHER_PORTAL))              return false;
        if (isSoftObstacle(state))                         return false;
        return true;
    }

    /**
     * Soft obstacles slow or marginally block the player but can be broken
     * through by attacking.  The player should walk into these rather than
     * jumping or scaffolding.
     * <p>
     * Covers: cobwebs, powder snow, and all leaf blocks.
     */
    private boolean isSoftObstacle(BlockState state) {
        Block b = state.getBlock();
        if (b == Blocks.COBWEB || b == Blocks.POWDER_SNOW) return true;
        if (b instanceof LeavesBlock) return true;
        return false;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Block Placement Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private void stopMovement() {
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.sprintKey.setPressed(false);
        mc.options.sneakKey.setPressed(false);
    }

    private boolean tryScaffoldPlace(BlockPos pos) {
        Direction[] order = {
            Direction.DOWN,
            Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST,
            Direction.UP
        };

        BlockPos  neighbor  = null;
        Direction placeSide = null;
        for (Direction side : order) {
            BlockPos check = pos.offset(side);
            if (!mc.world.getBlockState(check).isReplaceable()) { neighbor = check; placeSide = side.getOpposite(); break; }
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

    // ═══════════════════════════════════════════════════════════════════════════
    // Render
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (render.get() && !portalFramePositions.isEmpty()) {
            for (int i = placementIndex; i < portalFramePositions.size(); i++) {
                BlockPos pos = portalFramePositions.get(i);
                if (!mc.world.getBlockState(pos).isReplaceable()) continue;

                Box box = new Box(pos);
                renderGlowLayers(event, box, lineColor.get());
                event.renderer.box(box, sideColor.get(), lineColor.get(), shapeMode.get(), 0);
            }
        }

        if (renderBreadcrumbs.get() && breadcrumbs.size() > 1) {
            Vec3d prev = null;
            for (Vec3d pos : breadcrumbs) {
                if (prev != null) {
                    event.renderer.line(prev.x, prev.y, prev.z, pos.x, pos.y, pos.z, breadcrumbColor.get());
                }
                prev = pos;
            }
            if (prev != null && mc.player != null) {
                Vec3d current = mc.player.getLerpedPos(event.tickDelta);
                event.renderer.line(prev.x, prev.y, prev.z, current.x, current.y, current.z, breadcrumbColor.get());
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Bloom Rendering
    // ═══════════════════════════════════════════════════════════════════════════

    private void renderGlowLayers(Render3DEvent event, Box box, SettingColor color) {
        int    layers    = glowLayers.get();
        double spread    = glowSpread.get();
        int    baseAlpha = glowBaseAlpha.get();

        for (int i = layers; i >= 1; i--) {
            double expansion  = spread * i;
            int    layerAlpha = Math.max(4, (int) (baseAlpha * (1.0 - (double)(i - 1) / layers)));
            event.renderer.box(
                box.expand(expansion),
                withAlpha(color, layerAlpha),
                withAlpha(color, 0),
                ShapeMode.Sides, 0
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Color Helper
    // ═══════════════════════════════════════════════════════════════════════════

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Inventory Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean selectHotbarItem(Item targetItem) {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).getItem() == targetItem) {
                mc.player.getInventory().selectedSlot = i;
                return true;
            }
        }
        return false;
    }

    private boolean hasItemInHotbar(Item targetItem) {
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
}