package com.example.addon.modules;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.addon.HuntingUtilities;
import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.ShulkerBoxBlock;
import net.minecraft.block.enums.ChestType;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvent;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

public class InspectorGadget extends Module {

    public enum ScanState { SETUP, MOVING_TO_TILE, MOVING_TO_TARGET, OPENING_TARGET, WAITING, COOLDOWN, COMPLETE }

    public enum StorageTarget {
        Chests("Chests", Blocks.CHEST, Blocks.TRAPPED_CHEST),
        Barrels("Barrels", Blocks.BARREL),
        Chests_And_Barrels("Chests & Barrels", Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL),
        Dispensers_And_Droppers("Dispensers & Droppers", Blocks.DISPENSER, Blocks.DROPPER),
        Hoppers("Hoppers", Blocks.HOPPER),
        Furnaces("Furnaces", Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER),
        All_Standard("All Standard Storage",
            Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.BARREL,
            Blocks.DISPENSER, Blocks.DROPPER, Blocks.HOPPER,
            Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
            Blocks.BREWING_STAND);

        public final String title;
        public final Block[] blocks;

        StorageTarget(String title, Block... blocks) {
            this.title = title;
            this.blocks = blocks;
        }

        @Override
        public String toString() { return title; }

        public boolean contains(Block block) {
            for (Block b : blocks) {
                if (b == block) return true;
            }
            return false;
        }
    }

    public enum CompletionSound {
        None("None", null),
        LevelUp("Level Up", "minecraft:entity.player.levelup"),
        XpPickup("XP Pickup", "minecraft:entity.experience_orb.pickup"),
        TotemPop("Totem Pop", "minecraft:item.totem.use"),
        VillagerYes("Villager Yes", "minecraft:entity.villager.yes"),
        Pling("Pling", "minecraft:block.note_block.pling"),
        Bell("Bell", "minecraft:block.bell.use");

        public final String title;
        public final String id;

        CompletionSound(String title, String id) {
            this.title = title;
            this.id = id;
        }

        @Override
        public String toString() { return title; }
    }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgVisuals = settings.createGroup("Visuals");

    private final Setting<StorageTarget> targetStorage = sgGeneral.add(new EnumSetting.Builder<StorageTarget>()
        .name("target-storage")
        .description("Which storage blocks to scan and open.")
        .defaultValue(StorageTarget.Chests_And_Barrels)
        .build()
    );

    private final Setting<Keybind> addTileKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("add-tile-key")
        .description("Key to add a tile while looking at a block.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> startKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("start-key")
        .description("Key to start the automated pathing sequence.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> clearKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("clear-key")
        .description("Key to clear all created tiles.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Keybind> pauseKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("pause-key")
        .description("Pauses the pathing so you can chat or move. Press again to resume.")
        .defaultValue(Keybind.none())
        .build()
    );

    private final Setting<Integer> tileScanRange = sgGeneral.add(new IntSetting.Builder()
        .name("tile-scan-range")
        .description("Radius to scan for storage blocks around each tile.")
        .defaultValue(5).min(2).max(10).sliderMax(10)
        .build()
    );

    private final Setting<Integer> openDelay = sgGeneral.add(new IntSetting.Builder()
        .name("open-delay")
        .description("How long to wait between opening and closing containers to prevent anti-cheat kicks.")
        .defaultValue(15).min(2).max(60).sliderMax(40)
        .build()
    );

    private final Setting<CompletionSound> completionSound = sgGeneral.add(new EnumSetting.Builder<CompletionSound>()
        .name("completion-sound")
        .description("Sound played when all tiles have been scanned.")
        .defaultValue(CompletionSound.LevelUp)
        .build()
    );

    private final Setting<SettingColor> highlightColor = sgVisuals.add(new ColorSetting.Builder()
        .name("storage-color")
        .description("Color of the storage blocks found during scan.")
        .defaultValue(new SettingColor(255, 215, 0, 200)).build()
    );

    private final Setting<SettingColor> pathColor = sgVisuals.add(new ColorSetting.Builder()
        .name("tile-color")
        .description("Color of the pathing tiles and sequence pillars.")
        .defaultValue(new SettingColor(0, 255, 100, 200)).build()
    );

    // ── State ──

    private ScanState currentState = ScanState.SETUP;
    private final List<BlockPos> pathTiles = new ArrayList<>();
    private final List<BlockPos> localTargets = new ArrayList<>();
    private final Set<BlockPos> visitedTargets = new HashSet<>();

    private int tileIndex = 0;
    private int targetIndex = 0;
    private int waitTimer = 0;
    
    private double lastDistCheck = Double.MAX_VALUE;
    private int stuckTimer = 0;
    private Vec3d stuckDestination = null;

    private boolean wasAddPressed = false;
    private boolean wasStartPressed = false;
    private boolean wasClearPressed = false;
    private boolean wasPausePressed = false;
    private boolean isPaused = false;

    private BlockPos currentInteractTile = null;
    private BlockPos currentPathTarget = null;

    private int openedCount = 0;
    private int shulkerCount = 0;

    private int antiAfkTimer = 0;
    private float jitterYaw = 0;
    private float jitterPitch = 0;
    
    // Obstacle avoidance state
    private int strafeTimer = 0;
    private int activeStrafe = 0; // 1 = left, -1 = right

    public InspectorGadget() {
        super(HuntingUtilities.CATEGORY, "inspector-gadget", "Walks a custom path of tiles to scan nearby storage blocks.");
    }

    @Override
    public void onActivate() {
        currentState = ScanState.SETUP;
        pathTiles.clear();
        localTargets.clear();
        visitedTargets.clear();
        tileIndex = 0;
        targetIndex = 0;
        waitTimer = 0;
        antiAfkTimer = 0;
        jitterYaw = 0;
        jitterPitch = 0;
        strafeTimer = 0;
        activeStrafe = 0;
        
        lastDistCheck = Double.MAX_VALUE;
        stuckTimer = 0;
        stuckDestination = null;

        wasAddPressed = false;
        wasStartPressed = false;
        wasClearPressed = false;
        wasPausePressed = false;
        isPaused = false;

        currentInteractTile = null;
        currentPathTarget = null;

        resetStats();
    }

    @Override
    public void onDeactivate() {
        releaseKeys();
        closeScreen();
        resetTargets();
    }

    private void releaseKeys() {
        mc.options.forwardKey.setPressed(false);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(false);
        mc.options.rightKey.setPressed(false);
        mc.options.jumpKey.setPressed(false);
    }

    private void closeScreen() {
        if (mc.currentScreen != null && !(mc.currentScreen instanceof InventoryScreen)) {
            mc.player.closeHandledScreen();
        }
    }

    private void resetTargets() {
        pathTiles.clear();
        localTargets.clear();
        visitedTargets.clear();
        currentInteractTile = null;
        currentPathTarget = null;
    }

    public int getOpenedCount() { return openedCount; }
    public int getNearbyCount() { return localTargets.size(); }
    public int getShulkerCount() { return shulkerCount; }

    public void resetStats() {
        openedCount = 0;
        shulkerCount = 0;
    }

    // ─────────────────────────── Logic ───────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (currentState != ScanState.SETUP) {
            boolean pausePressed = pauseKey.get().isPressed();
            if (pausePressed && !wasPausePressed) {
                isPaused = !isPaused;
                if (isPaused) {
                    releaseKeys();
                    closeScreen();
                    info("Pathing Paused.");
                } else {
                    antiAfkTimer = 0;
                    stuckTimer = 0;
                    stuckDestination = null;
                    info("Pathing Resumed.");
                }
            }
            wasPausePressed = pausePressed;

            if (isPaused) return;
        }

        if (currentState == ScanState.SETUP) {
            boolean addPressed = addTileKey.get().isPressed();
            boolean startPressed = startKey.get().isPressed();
            boolean clearPressed = clearKey.get().isPressed();

            if (addPressed && !wasAddPressed) addTile();

            if (startPressed && !wasStartPressed) {
                if (pathTiles.isEmpty()) {
                    error("No tiles created. Look at blocks and use the Add Tile key.");
                } else {
                    currentState = ScanState.MOVING_TO_TILE;
                    tileIndex = 0;
                    visitedTargets.clear();
                    antiAfkTimer = 0;
                    info("Starting pathing sequence for %d tiles.", pathTiles.size());
                }
            }

            if (clearPressed && !wasClearPressed) {
                pathTiles.clear();
                info("Cleared all tiles.");
            }

            wasAddPressed = addPressed;
            wasStartPressed = startPressed;
            wasClearPressed = clearPressed;
            return;
        }

        switch (currentState) {
            case MOVING_TO_TILE -> handleMoveToTile();
            case MOVING_TO_TARGET -> handleMoveToTarget();
            case OPENING_TARGET -> handleOpeningTarget();
            case WAITING -> handleWaiting();
            case COOLDOWN -> handleCooldown();
            case COMPLETE -> handleCompletion();
        }
    }

    private void addTile() {
        if (mc.crosshairTarget != null && mc.crosshairTarget.getType() == HitResult.Type.BLOCK) {
            BlockPos target = ((BlockHitResult) mc.crosshairTarget).getBlockPos();
            pathTiles.add(target);
            info("Added tile %d", pathTiles.size());
        } else {
            warning("You must look at a block to add a tile.");
        }
    }

    private void handleMoveToTile() {
        if (tileIndex >= pathTiles.size()) {
            currentState = ScanState.COMPLETE;
            return;
        }

        currentPathTarget = pathTiles.get(tileIndex);
        Vec3d targetPos = Vec3d.ofCenter(currentPathTarget).add(0, 0.5, 0);
        double distance = mc.player.getPos().distanceTo(targetPos);

        if (distance <= 1.2) {
            releaseKeys();
            populateLocalTargets();

            if (localTargets.isEmpty()) {
                info("Tile %d: No new targets found nearby. Moving to next tile.", tileIndex + 1);
                tileIndex++;
                currentPathTarget = null;
            } else {
                info("Tile %d: Found %d targets. Scanning...", tileIndex + 1, localTargets.size());
                targetIndex = 0;
                currentState = ScanState.MOVING_TO_TARGET;
            }
        } else {
            lookAndMove(targetPos);
            checkMoveStuck(targetPos);
        }
    }

    private void populateLocalTargets() {
        localTargets.clear();
        BlockPos center = pathTiles.get(tileIndex);
        int range = tileScanRange.get();
        StorageTarget storageFilter = targetStorage.get();

        for (int x = -range; x <= range; x++) {
            for (int y = -range; y <= range; y++) {
                for (int z = -range; z <= range; z++) {
                    if (x*x + y*y + z*z > range*range) continue;
                    BlockPos checkPos = center.add(x, y, z);
                    Block b = mc.world.getBlockState(checkPos).getBlock();

                    if (b instanceof ShulkerBoxBlock) {
                        shulkerCount++;
                    }

                    if (storageFilter.contains(b) && !visitedTargets.contains(checkPos)) {
                        localTargets.add(checkPos);
                    }
                }
            }
        }

        // Greedy nearest-neighbour sort from the player's current position to avoid zigzag.
        List<BlockPos> ordered = new ArrayList<>();
        Set<BlockPos> remaining = new HashSet<>(localTargets);
        Vec3d cursor = mc.player.getPos();
        while (!remaining.isEmpty()) {
            BlockPos nearest = null;
            double nearestDist = Double.MAX_VALUE;
            for (BlockPos p : remaining) {
                double d = p.getSquaredDistance(cursor);
                if (d < nearestDist) {
                    nearestDist = d;
                    nearest = p;
                }
            }
            ordered.add(nearest);
            remaining.remove(nearest);
            cursor = Vec3d.ofCenter(nearest);
        }
        localTargets.clear();
        localTargets.addAll(ordered);
    }

    private void handleMoveToTarget() {
        if (targetIndex >= localTargets.size()) {
            tileIndex++;
            localTargets.clear();
            currentInteractTile = null;
            currentPathTarget = null;
            currentState = ScanState.MOVING_TO_TILE;
            return;
        }

        BlockPos blockTarget = localTargets.get(targetIndex);
        Block block = mc.world.getBlockState(blockTarget).getBlock();
        StorageTarget storageFilter = targetStorage.get();

        if (!storageFilter.contains(block) || visitedTargets.contains(blockTarget)) {
            targetIndex++;
            return;
        }

        double distanceToChest = mc.player.getPos().distanceTo(Vec3d.ofCenter(blockTarget));

        if (currentInteractTile == null || !isStandable(currentInteractTile)) {
            currentInteractTile = null;
            List<BlockPos> validTiles = getValidInteractTiles(blockTarget);
            
            if (validTiles.isEmpty()) {
                // Fallback for elevated chests: Path to the block directly beneath the chest
                BlockPos fallbackTile = blockTarget.down();
                while (fallbackTile.getY() > mc.world.getBottomY() && mc.world.getBlockState(fallbackTile).getCollisionShape(mc.world, fallbackTile).isEmpty()) {
                    fallbackTile = fallbackTile.down();
                }
                
                // If the block beneath the chest is standable, use it
                if (isStandable(fallbackTile.up())) {
                    currentInteractTile = fallbackTile.up();
                } else {
                    // If we can't find a tile to stand on, but we are already close enough, just open it!
                    if (distanceToChest <= 4.2) {
                        releaseKeys();
                        currentState = ScanState.OPENING_TARGET;
                        return;
                    }
                    warning("Cannot path to block. Skipping.");
                    markVisited(blockTarget);
                    targetIndex++;
                    return;
                }
            } else {
                validTiles.sort(Comparator.comparingDouble(pos -> pos.getSquaredDistance(mc.player.getBlockPos())));
                currentInteractTile = validTiles.get(0);
            }
            
            // Reset stuck tracking for new target
            stuckTimer = 0;
            stuckDestination = null;
        }

        Vec3d targetPos = Vec3d.ofCenter(currentInteractTile).add(0, -0.5, 0);
        double distanceToTile = mc.player.getPos().distanceTo(targetPos);

        // If we are close enough to the tile OR we are within reach of the chest itself, open it.
        if (distanceToTile <= 1.2 || distanceToChest <= 4.2) {
            releaseKeys();
            currentState = ScanState.OPENING_TARGET;
        } else {
            lookAndMove(targetPos);
            checkMoveStuck(targetPos);
        }
    }

    private void checkMoveStuck(Vec3d destination) {
        // If we are chasing a new destination, reset the tracker
        if (stuckDestination == null || !destination.equals(stuckDestination)) {
            stuckDestination = destination;
            lastDistCheck = mc.player.getPos().distanceTo(destination);
            stuckTimer = 0;
            return;
        }

        stuckTimer++;

        // Every 3 seconds (60 ticks), evaluate our progress
        if (stuckTimer >= 60) {
            double currentDist = mc.player.getPos().distanceTo(destination);
            
            // If we haven't gotten at least 1 block closer in 3 seconds, we are hard stuck
            if (currentDist > lastDistCheck - 1.0) {
                warning("Stuck while moving. Skipping.");
                if (currentState == ScanState.MOVING_TO_TARGET) {
                    markVisited(localTargets.get(targetIndex));
                    targetIndex++;
                    currentInteractTile = null;
                } else if (currentState == ScanState.MOVING_TO_TILE) {
                    tileIndex++;
                    currentPathTarget = null;
                    localTargets.clear();
                }
                releaseKeys();
                strafeTimer = 0;
                activeStrafe = 0;
                stuckDestination = null; // Force reset
            } else {
                // We made progress, reset timer for the next 3 seconds
                lastDistCheck = currentDist;
                stuckTimer = 0;
            }
        }
    }

    private void handleOpeningTarget() {
        BlockPos blockTarget = localTargets.get(targetIndex);

        Vec3d eye = mc.player.getEyePos();
        Vec3d blockCenter = Vec3d.ofCenter(blockTarget);

        Vec3d diff = eye.subtract(blockCenter);

        Direction side;
        double ax = Math.abs(diff.x), ay = Math.abs(diff.y), az = Math.abs(diff.z);
        if (ax >= ay && ax >= az) {
            side = diff.x > 0 ? Direction.EAST : Direction.WEST;
        } else if (ay >= ax && ay >= az) {
            side = diff.y > 0 ? Direction.UP : Direction.DOWN;
        } else {
            side = diff.z > 0 ? Direction.SOUTH : Direction.NORTH;
        }

        Vec3d hitVec = Vec3d.ofCenter(blockTarget)
            .add(Vec3d.of(side.getOpposite().getVector()).multiply(0.5));

        // Use strictly horizontal difference for Yaw to prevent wild swinging when the chest is directly above/below
        mc.player.setYaw((float) Math.toDegrees(Math.atan2(-(blockCenter.x - eye.x), blockCenter.z - eye.z)));
        mc.player.setHeadYaw(mc.player.getYaw());
        
        // Use 3D difference for Pitch, safely clamped
        double dist3d = blockCenter.distanceTo(eye);
        if (dist3d > 0) {
            mc.player.setPitch((float) -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, (blockCenter.y - eye.y) / dist3d)))));
        }

        BlockHitResult hitResult = new BlockHitResult(hitVec, side, blockTarget, false);
        mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND, hitResult);
        mc.player.swingHand(Hand.MAIN_HAND);

        currentState = ScanState.WAITING;
        waitTimer = 0;
    }

    private void handleWaiting() {
        waitTimer++;

        if (mc.currentScreen instanceof HandledScreen<?> && !(mc.currentScreen instanceof InventoryScreen)) {
            if (waitTimer >= openDelay.get()) {
                mc.player.closeHandledScreen();
                openedCount++;
                markVisited(localTargets.get(targetIndex));
                targetIndex++;
                currentInteractTile = null;
                waitTimer = 0;
                currentState = ScanState.COOLDOWN;
            }
        } else if (waitTimer > openDelay.get() + 20) {
            warning("Failed to open block. Skipping...");
            markVisited(localTargets.get(targetIndex));
            targetIndex++;
            currentInteractTile = null;
            waitTimer = 0;
            currentState = ScanState.COOLDOWN;
        }
    }

    private void handleCooldown() {
        waitTimer++;
        if (waitTimer >= openDelay.get()) {
            waitTimer = 0;
            currentState = ScanState.MOVING_TO_TARGET;
        }
    }

    private void handleCompletion() {
        releaseKeys();
        resetTargets();

        CompletionSound soundSetting = completionSound.get();
        if (soundSetting != CompletionSound.None && soundSetting.id != null) {
            try {
                Identifier soundId = Identifier.tryParse(soundSetting.id);
                if (soundId != null) {
                    SoundEvent sound = Registries.SOUND_EVENT.get(soundId);
                    if (sound != null) mc.player.playSound(sound, 1.0f, 1.0f);
                }
            } catch (Exception ignored) {}
        }

        info("Inspector Gadget: Path complete!");
        this.toggle();
    }

    // ─────────────────────────── Movement & Anti-AFK ───────────────────────────

    private void lookAndMove(Vec3d targetPos) {
        Vec3d diff = targetPos.subtract(mc.player.getPos());
        
        // Use horizontal difference for stable yaw, preventing wavy side-to-side when looking straight up/down
        double horDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        float yaw;
        if (horDist > 0.1) {
            yaw = (float) Math.toDegrees(Math.atan2(-diff.x, diff.z));
        } else {
            // Keep current facing if looking almost directly up/down to prevent spinning
            yaw = mc.player.getYaw();
        }
        
        // Clamp pitch calculation to prevent NaN from slight floating point errors
        double dist3d = diff.length();
        float pitch = 0;
        if (dist3d > 0) {
            pitch = (float) -Math.toDegrees(Math.asin(Math.max(-1.0, Math.min(1.0, diff.y / dist3d))));
        }

        if (antiAfkTimer >= 40) {
            jitterYaw = (float) (Math.random() * 4 - 2);
            jitterPitch = (float) (Math.random() * 2 - 1);
            antiAfkTimer = 0;
        } else {
            antiAfkTimer++;
        }

        mc.player.setYaw(yaw + jitterYaw);
        mc.player.setHeadYaw(yaw + jitterYaw);
        mc.player.setPitch(pitch + jitterPitch);

        boolean shouldJump = false;
        int strafe = 0; // 1 = left, -1 = right

        BlockPos playerBlock = mc.player.getBlockPos();
        Direction facing = mc.player.getHorizontalFacing();
        
        // Check blocks in front of the player
        BlockPos frontFeet = playerBlock.offset(facing);
        BlockState frontFeetState = mc.world.getBlockState(frontFeet);
        boolean frontFeetBlocked = !frontFeetState.getCollisionShape(mc.world, frontFeet).isEmpty();
        
        BlockPos frontHead = playerBlock.up().offset(facing);
        boolean frontHeadBlocked = !mc.world.getBlockState(frontHead).getCollisionShape(mc.world, frontHead).isEmpty();

        // Prevent jumping onto storage blocks entirely. They should be treated as walls to strafe around.
        boolean isStorageBlock = targetStorage.get().contains(frontFeetState.getBlock()) 
            || frontFeetState.getBlock() instanceof ShulkerBoxBlock 
            || frontFeetState.getBlock() == Blocks.ENDER_CHEST;

        if (strafeTimer > 0) {
            // We are currently strafing to bypass an obstacle
            strafe = activeStrafe;
            strafeTimer--;
            
            // If the front is finally clear, stop strafing early
            if (!frontFeetBlocked && !frontHeadBlocked) {
                strafeTimer = 0;
                strafe = 0;
            } else if (frontFeetBlocked && !frontHeadBlocked && !isStorageBlock) {
                shouldJump = true; // Jump while strafing if there's a 1-block step
            }
        } else {
            // No active strafe, check for new obstacles
            if (frontFeetBlocked) {
                if (!frontHeadBlocked && !isStorageBlock) {
                    // 1-block step in front, just jump
                    shouldJump = true;
                } else {
                    // Wall, chest, or 2-block high obstacle, determine strafe direction
                    Direction leftDir = facing.rotateYCounterclockwise();
                    Direction rightDir = facing.rotateYClockwise();

                    BlockPos leftFeet = playerBlock.offset(leftDir);
                    BlockPos leftHead = playerBlock.up().offset(leftDir);
                    boolean leftBlocked = !mc.world.getBlockState(leftFeet).getCollisionShape(mc.world, leftFeet).isEmpty()
                        || !mc.world.getBlockState(leftHead).getCollisionShape(mc.world, leftHead).isEmpty();

                    BlockPos rightFeet = playerBlock.offset(rightDir);
                    BlockPos rightHead = playerBlock.up().offset(rightDir);
                    boolean rightBlocked = !mc.world.getBlockState(rightFeet).getCollisionShape(mc.world, rightFeet).isEmpty()
                        || !mc.world.getBlockState(rightHead).getCollisionShape(mc.world, rightHead).isEmpty();

                    if (!leftBlocked && rightBlocked) {
                        strafe = 1; // Left is open
                    } else if (leftBlocked && !rightBlocked) {
                        strafe = -1; // Right is open
                    } else if (!leftBlocked && !rightBlocked) {
                        // Both open, pick the one closer to the target
                        Vec3d leftPos = Vec3d.ofCenter(leftFeet);
                        Vec3d rightPos = Vec3d.ofCenter(rightFeet);
                        if (leftPos.distanceTo(targetPos) < rightPos.distanceTo(targetPos)) {
                            strafe = 1;
                        } else {
                            strafe = -1;
                        }
                    } else {
                        // Both blocked, flip direction to try the other way
                        strafe = activeStrafe == 1 ? -1 : 1;
                    }
                    
                    activeStrafe = strafe;
                    strafeTimer = 15; // Strafe for 15 ticks (0.75s) to slide along the wall
                }
            }
        }

        // Apply movement keys
        mc.options.forwardKey.setPressed(true);
        mc.options.backKey.setPressed(false);
        mc.options.leftKey.setPressed(strafe == 1);
        mc.options.rightKey.setPressed(strafe == -1);
        mc.options.jumpKey.setPressed(shouldJump);
    }

    private boolean isStandable(BlockPos pos) {
        BlockState feet = mc.world.getBlockState(pos);
        BlockState head = mc.world.getBlockState(pos.up());
        BlockState floor = mc.world.getBlockState(pos.down());

        // Feet & head must be passable (no collision).
        if (!feet.getCollisionShape(mc.world, pos).isEmpty()) return false;
        if (!head.getCollisionShape(mc.world, pos.up()).isEmpty()) return false;
        // Floor must have a collision shape (any solid-enough surface counts).
        if (floor.getCollisionShape(mc.world, pos.down()).isEmpty()) return false;
        
        // Do not choose a spot on top of storage blocks
        Block floorBlock = floor.getBlock();
        if (targetStorage.get().contains(floorBlock) || floorBlock instanceof ShulkerBoxBlock || floorBlock == Blocks.ENDER_CHEST) {
            return false;
        }
        
        return true;
    }

    private List<BlockPos> getValidInteractTiles(BlockPos blockPos) {
        List<BlockPos> tiles = new ArrayList<>();
        // Search a 5x5 footprint around the chest, up to 5 blocks high or low to handle elevation changes.
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (dx == 0 && dz == 0) continue;
                if (dx * dx + dz * dz > 5) continue; // Roughly circular radius of 2
                for (int dy = -5; dy <= 5; dy++) {
                    BlockPos tilePos = blockPos.add(dx, dy, dz);
                    if (isStandable(tilePos)) {
                        tiles.add(tilePos);
                    }
                }
            }
        }
        return tiles;
    }

    // Marks a chest as visited, including its double chest half if it has one.
    private void markVisited(BlockPos pos) {
        visitedTargets.add(pos);
        BlockState state = mc.world.getBlockState(pos);
        if (state.contains(Properties.CHEST_TYPE)) {
            ChestType type = state.get(Properties.CHEST_TYPE);
            if (type != ChestType.SINGLE) {
                Direction facing = state.get(Properties.HORIZONTAL_FACING);
                Direction connectedDir = type == ChestType.LEFT ? facing.rotateYClockwise() : facing.rotateYCounterclockwise();
                BlockPos otherHalf = pos.offset(connectedDir);
                visitedTargets.add(otherHalf);
            }
        }
    }

    // ─────────────────────────── Rendering ───────────────────────────

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (pathTiles.isEmpty() && localTargets.isEmpty()) return;

        SettingColor pColor = pathColor.get();

        for (int i = 0; i < pathTiles.size(); i++) {
            BlockPos pos = pathTiles.get(i);

            // Flat decal highlighting for the tile
            Box flatTileBox = new Box(
                pos.getX(), pos.getY() + 1.0, pos.getZ(),
                pos.getX() + 1.0, pos.getY() + 1.02, pos.getZ() + 1.0
            );
            event.renderer.box(flatTileBox, withAlpha(pColor, 60), pColor, ShapeMode.Sides, 0);

            // Sequence pillar
            double height = Math.min((i + 1) * 0.25, 3.0);
            Box pillarBox = new Box(
                pos.getX() + 0.4, pos.getY() + 1.0, pos.getZ() + 0.4,
                pos.getX() + 0.6, pos.getY() + 1.0 + height, pos.getZ() + 0.6
            );
            event.renderer.box(pillarBox, withAlpha(pColor, 100), pColor, ShapeMode.Both, 0);
        }

        if (currentState != ScanState.SETUP && !localTargets.isEmpty()) {
            SettingColor cColor = highlightColor.get();
            for (BlockPos pos : localTargets) {
                if (visitedTargets.contains(pos)) continue;
                // Reverted chest rendering back to a full block outline
                event.renderer.box(new Box(pos), new SettingColor(0, 0, 0, 0), cColor, ShapeMode.Lines, 0);
            }
        }
    }

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, alpha);
    }
}