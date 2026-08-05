package com.example.addon.modules;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BlockListSetting;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

/**
 * Terralien - Dimension-Aware Xeno-Scanner
 * 
 * Identifies and highlights unnatural block clusters based on user-defined,
 * per-dimension block lists. Features intelligent highway detection via 
 * 3D aspect-ratio math to prevent client freezing on large veins.
 */
public class Terralien extends Module {

    // ================================================================== //
    //                         ENUMS & CONSTANTS                         //
    // ================================================================== //

    public enum HighlightStyle { GLOW, SPECTRAL }

    private static final int DIMENSION_SETTLE_TICKS = 40;
    private static final int CHUNK_SCAN_LIMIT_PER_TICK = 64;
    private static final int CLEANUP_INTERVAL_TICKS = 60;

    // ================================================================== //
    //                            SETTINGS                              //
    // ================================================================== //

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgOverworld = settings.createGroup("Overworld");
    private final SettingGroup sgNether = settings.createGroup("Nether");
    private final SettingGroup sgEnd = settings.createGroup("End");
    private final SettingGroup sgRender = settings.createGroup("Render");

    // --- Overworld Configuration ---
    private final Setting<Boolean> scanOverworld = sgOverworld.add(new BoolSetting.Builder()
        .name("scan").description("Scan for alien blocks in the Overworld.").defaultValue(false).build());

    private final Setting<List<Block>> overworldBlocks = sgOverworld.add(new BlockListSetting.Builder()
        .name("alien-blocks").description("Blocks considered unnatural in the Overworld.")
        .defaultValue(Blocks.NETHERRACK, Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.BASALT, 
            Blocks.BLACKSTONE, Blocks.CRIMSON_NYLIUM, Blocks.WARPED_NYLIUM,
            Blocks.NETHER_BRICKS, Blocks.CRIMSON_PLANKS, Blocks.WARPED_PLANKS,
            Blocks.END_STONE, Blocks.PURPUR_BLOCK, Blocks.PURPUR_PILLAR)
        .build());

    // --- Nether Configuration ---
    private final Setting<Boolean> scanNether = sgNether.add(new BoolSetting.Builder()
        .name("scan").description("Scan for alien blocks in the Nether.").defaultValue(true).build());

    private final Setting<List<Block>> netherBlocks = sgNether.add(new BlockListSetting.Builder()
        .name("alien-blocks").description("Blocks considered unnatural in the Nether.")
        .defaultValue(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT, Blocks.PODZOL,
            Blocks.STONE, Blocks.DEEPSLATE, Blocks.SAND, Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, 
            Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS, Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS, 
            Blocks.MANGROVE_PLANKS, Blocks.CHERRY_PLANKS, Blocks.BAMBOO_PLANKS, Blocks.COBBLESTONE, 
            Blocks.STONE_BRICKS, Blocks.BRICKS, Blocks.GLASS, Blocks.CRAFTING_TABLE, Blocks.FURNACE, 
            Blocks.CHEST, Blocks.ENDER_CHEST, Blocks.TORCH, Blocks.LADDER, Blocks.END_STONE)
        .build());

    // --- End Configuration ---
    private final Setting<Boolean> scanEnd = sgEnd.add(new BoolSetting.Builder()
        .name("scan").description("Scan for alien blocks in the End.").defaultValue(true).build());

    private final Setting<List<Block>> endBlocks = sgEnd.add(new BlockListSetting.Builder()
        .name("alien-blocks").description("Blocks considered unnatural in the End.")
        .defaultValue(Blocks.DIRT, Blocks.GRASS_BLOCK, Blocks.COARSE_DIRT, Blocks.PODZOL,
            Blocks.STONE, Blocks.DEEPSLATE, Blocks.SAND, Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, 
            Blocks.BIRCH_PLANKS, Blocks.JUNGLE_PLANKS, Blocks.ACACIA_PLANKS, Blocks.DARK_OAK_PLANKS, 
            Blocks.MANGROVE_PLANKS, Blocks.CHERRY_PLANKS, Blocks.BAMBOO_PLANKS, Blocks.COBBLESTONE, 
            Blocks.STONE_BRICKS, Blocks.BRICKS, Blocks.GLASS, Blocks.CRAFTING_TABLE, Blocks.FURNACE, 
            Blocks.CHEST, Blocks.ENDER_CHEST, Blocks.TORCH, Blocks.LADDER, Blocks.NETHERRACK, 
            Blocks.SOUL_SAND, Blocks.SOUL_SOIL, Blocks.RESPAWN_ANCHOR)
        .build());

    // --- General Configuration ---
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("Detection range in chunks.").defaultValue(8).min(1).max(64).build());

    private final Setting<Integer> maxClusterSize = sgGeneral.add(new IntSetting.Builder()
        .name("max-cluster-size").description("Max blocks in a cluster before triggering highway check.").defaultValue(512).min(16).sliderMax(2048).build());

    private final Setting<Integer> minY = sgGeneral.add(new IntSetting.Builder()
        .name("min-y").description("Minimum Y level to scan.").defaultValue(-64).min(-64).sliderMax(320).build());

    private final Setting<Integer> maxY = sgGeneral.add(new IntSetting.Builder()
        .name("max-y").description("Maximum Y level to scan.").defaultValue(320).min(-64).sliderMax(320).build());

    // --- Render Configuration ---
    private final Setting<SettingColor> blockColor = sgRender.add(new ColorSetting.Builder()
        .name("block-color").defaultValue(new SettingColor(255, 0, 80, 255)).build());

    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").defaultValue(ShapeMode.Both).build());

    private final Setting<HighlightStyle> highlightStyle = sgRender.add(new EnumSetting.Builder<HighlightStyle>()
        .name("highlight-style").defaultValue(HighlightStyle.GLOW).build());

    private final Setting<Boolean> dynamicColors = sgRender.add(new BoolSetting.Builder()
        .name("dynamic-colors").defaultValue(false).build());

    private final Setting<Integer> maxRenderClusters = sgRender.add(new IntSetting.Builder()
        .name("max-render-clusters").description("Max clusters drawn per frame.").defaultValue(150).min(10).sliderMax(500).build());

    private final Setting<Integer> glowLayers = sgRender.add(new IntSetting.Builder()
        .name("glow-layers").defaultValue(4).min(1).sliderMax(8).visible(() -> highlightStyle.get() == HighlightStyle.GLOW).build());

    private final Setting<Double> glowSpread = sgRender.add(new DoubleSetting.Builder()
        .name("glow-spread").defaultValue(0.05).min(0.01).sliderMax(0.2).visible(() -> highlightStyle.get() == HighlightStyle.GLOW).build());

    private final Setting<Integer> glowBaseAlpha = sgRender.add(new IntSetting.Builder()
        .name("glow-base-alpha").defaultValue(50).min(4).sliderMax(150).visible(() -> highlightStyle.get() == HighlightStyle.GLOW).build());

    private final Setting<Integer> spectralLineAlpha = sgRender.add(new IntSetting.Builder()
        .name("line-alpha").defaultValue(255).visible(() -> highlightStyle.get() == HighlightStyle.SPECTRAL).build());

    private final Setting<Integer> spectralFillAlpha = sgRender.add(new IntSetting.Builder()
        .name("fill-alpha").defaultValue(15).visible(() -> highlightStyle.get() == HighlightStyle.SPECTRAL).build());

    private final Setting<Double> spectralExpand = sgRender.add(new DoubleSetting.Builder()
        .name("expand").defaultValue(0.05).visible(() -> highlightStyle.get() == HighlightStyle.SPECTRAL).build());

    // ================================================================== //
    //                       MODULE STATE                               //
    // ================================================================== //

    private final Set<BlockPos> pendingUnnaturalBlocks = ConcurrentHashMap.newKeySet();
    private final Map<BlockPos, AlienCluster> activeClusters = new ConcurrentHashMap<>();
    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final Set<ChunkPos> dirtyChunks = new HashSet<>();
    private final Set<BlockPos> processedBlocks = ConcurrentHashMap.newKeySet();
    private final Set<BlockPos> blacklistedHighways = ConcurrentHashMap.newKeySet();

    private final Set<Block> activeScanList = new HashSet<>();
    
    private boolean isDimensionAllowed = false;
    private String lastDimension = "";
    private int dimensionChangeCooldown = 0;
    private boolean stateDirty = false;
    private int cleanupTimer = 0;

    // ================================================================== //
    //                         LIFECYCLE                                //
    // ================================================================== //

    public Terralien() {
        super(HuntingUtilities.CATEGORY, "terralien", "Dimension-aware xeno-scanner with per-dimension custom block lists.");
    }

    @Override
    public void onActivate() {
        resetState();
        if (mc.player != null && mc.world != null) {
            lastDimension = mc.world.getRegistryKey().getValue().toString();
        }
    }

    @Override
    public void onDeactivate() {
        resetState();
    }

    private void resetState() {
        pendingUnnaturalBlocks.clear();
        activeClusters.clear();
        scannedChunks.clear();
        dirtyChunks.clear();
        processedBlocks.clear();
        blacklistedHighways.clear();
        activeScanList.clear();
        stateDirty = false;
    }

    // ================================================================== //
    //                        CORE LOGIC                                //
    // ================================================================== //

    private void updateDimensionState() {
        if (mc.world == null) return;
        
        String currentDim = mc.world.getRegistryKey().getValue().toString();
        isDimensionAllowed = false;
        activeScanList.clear();

        if (currentDim.contains("overworld") && scanOverworld.get()) {
            isDimensionAllowed = true;
            activeScanList.addAll(overworldBlocks.get());
        } else if (currentDim.contains("nether") && scanNether.get()) {
            isDimensionAllowed = true;
            activeScanList.addAll(netherBlocks.get());
        } else if (currentDim.contains("end") && scanEnd.get()) {
            isDimensionAllowed = true;
            activeScanList.addAll(endBlocks.get());
        }
    }

    private void handleDimensionChange() {
        String currentDim = mc.world.getRegistryKey().getValue().toString();
        if (currentDim.equals(lastDimension)) return;

        dimensionChangeCooldown = DIMENSION_SETTLE_TICKS;
        lastDimension = currentDim;
        resetState();

        String dimensionName = currentDim.contains("nether") ? "Nether" : currentDim.contains("end") ? "End" : "Overworld";
        info("§7Entered " + dimensionName + " — Terralien matrix recalibrated.");
    }

    // ================================================================== //
    //                          TICK LOOP                               //
    // ================================================================== //

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (dimensionChangeCooldown > 0) {
            dimensionChangeCooldown--;
            return;
        }

        handleDimensionChange();
        updateDimensionState();

        if (!isDimensionAllowed || activeScanList.isEmpty()) return;
        
        if (!dirtyChunks.isEmpty()) {
            scannedChunks.removeAll(dirtyChunks);
            dirtyChunks.clear();
        }

        BlockPos playerPos = mc.player.getBlockPos();
        int viewDistance = mc.options.getViewDistance().getValue();
        int effectiveRange = Math.min(range.get(), viewDistance + 2);

        scanSurroundingChunks(playerPos.getX() >> 4, playerPos.getZ() >> 4, effectiveRange);

        if (stateDirty) {
            stateDirty = false;
            processBlockGrouping();
        }

        if (++cleanupTimer >= CLEANUP_INTERVAL_TICKS) {
            cleanupTimer = 0;
            cleanupDistantObjects(effectiveRange);
        }
    }

    // ================================================================== //
    //                     CHUNK SCANNING LOGIC                         //
    // ================================================================== //

    private void scanSurroundingChunks(int centerChunkX, int centerChunkZ, int radius) {
        int radiusSq = radius * radius;
        int chunksScannedThisTick = 0;

        for (int depth = 0; depth <= radius; depth++) {
            for (int x = -depth; x <= depth; x++) {
                if (attemptChunkScan(centerChunkX + x, centerChunkZ - depth, radiusSq, centerChunkX, centerChunkZ)) {
                    if (++chunksScannedThisTick >= CHUNK_SCAN_LIMIT_PER_TICK) return;
                }
                if (depth > 0 && attemptChunkScan(centerChunkX + x, centerChunkZ + depth, radiusSq, centerChunkX, centerChunkZ)) {
                    if (++chunksScannedThisTick >= CHUNK_SCAN_LIMIT_PER_TICK) return;
                }
            }
            for (int z = -depth + 1; z < depth; z++) {
                if (attemptChunkScan(centerChunkX - depth, centerChunkZ + z, radiusSq, centerChunkX, centerChunkZ)) {
                    if (++chunksScannedThisTick >= CHUNK_SCAN_LIMIT_PER_TICK) return;
                }
                if (attemptChunkScan(centerChunkX + depth, centerChunkZ + z, radiusSq, centerChunkX, centerChunkZ)) {
                    if (++chunksScannedThisTick >= CHUNK_SCAN_LIMIT_PER_TICK) return;
                }
            }
        }
    }

    private boolean attemptChunkScan(int chunkX, int chunkZ, int radiusSq, int centerX, int centerZ) {
        int deltaX = chunkX - centerX;
        int deltaZ = chunkZ - centerZ;
        if (deltaX * deltaX + deltaZ * deltaZ > radiusSq) return false;

        ChunkPos chunkPos = new ChunkPos(chunkX, chunkZ);
        if (scannedChunks.contains(chunkPos)) return false;

        WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(chunkX, chunkZ);
        if (chunk != null) {
            analyzeChunk(chunk);
            scannedChunks.add(chunkPos);
            return true;
        }
        return false;
    }

    private void analyzeChunk(WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        int baseX = chunk.getPos().x << 4;
        int baseZ = chunk.getPos().z << 4;
        Set<Block> targets = activeScanList;

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            boolean containsTarget = section.hasAny(state -> targets.contains(state.getBlock()));
            if (!containsTarget) continue;

            int sectionBaseY = (chunk.getBottomSectionCoord() + i) * 16;
            if (sectionBaseY + 16 < minY.get() || sectionBaseY > maxY.get()) continue;

            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    int absoluteY = sectionBaseY + y;
                    if (absoluteY < minY.get() || absoluteY > maxY.get()) continue;

                    for (int z = 0; z < 16; z++) {
                        var blockState = section.getBlockState(x, y, z);
                        if (targets.contains(blockState.getBlock())) {
                            BlockPos pos = new BlockPos(baseX + x, absoluteY, baseZ + z);
                            if (!blacklistedHighways.contains(pos) && !processedBlocks.contains(pos)) {
                                if (pendingUnnaturalBlocks.add(pos)) {
                                    stateDirty = true;
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ================================================================== //
    //                      CLUSTER PROCESSING                           //
    // ================================================================== //

    private void processBlockGrouping() {
        Set<BlockPos> validClusterAnchors = new HashSet<>();
        int yMin = minY.get();
        int yMax = maxY.get();
        int sizeCap = maxClusterSize.get();
        int maxGroupsPerTick = 50; // Safety limit to prevent tick starvation
        int groupsProcessedThisTick = 0;

        for (BlockPos startPos : pendingUnnaturalBlocks) {
            if (groupsProcessedThisTick >= maxGroupsPerTick) break;
            if (processedBlocks.contains(startPos) || blacklistedHighways.contains(startPos)) continue;

            Set<BlockPos> clusterComponents = new HashSet<>();
            Queue<BlockPos> floodFillQueue = new LinkedList<>();
            Set<BlockPos> visitedNodes = new HashSet<>();
            Box clusterBoundingBox = new Box(startPos);

            floodFillQueue.add(startPos);
            visitedNodes.add(startPos);

            // Bounds tracking for aspect ratio math
            int minX = startPos.getX(), maxX = startPos.getX();
            int minYBound = startPos.getY(), maxYBound = startPos.getY();
            int minZ = startPos.getZ(), maxZ = startPos.getZ();
            
            boolean isHighway = false;

            while (!floodFillQueue.isEmpty()) {
                if (clusterComponents.size() >= sizeCap) {
                    // Aspect Ratio Check: Distinguish between 3D bases and 1D/2D highways
                    int sizeX = maxX - minX + 1;
                    int sizeY = maxYBound - minYBound + 1;
                    int sizeZ = maxZ - minZ + 1;

                    int longestSide = Math.max(sizeX, Math.max(sizeY, sizeZ));
                    int shortestSide = Math.min(sizeX, Math.min(sizeY, sizeZ));
                    int middleSide = (sizeX + sizeY + sizeZ) - longestSide - shortestSide;

                    // If it's massively long, but very thin, it's a highway. Blacklist it.
                    if (longestSide > 32 && longestSide > (middleSide * 2.5)) {
                        isHighway = true;
                    }
                    break;
                }

                BlockPos current = floodFillQueue.poll();
                clusterComponents.add(current);

                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.offset(dir);
                    if (pendingUnnaturalBlocks.contains(neighbor) && !processedBlocks.contains(neighbor) && !blacklistedHighways.contains(neighbor) && visitedNodes.add(neighbor)) {
                        floodFillQueue.add(neighbor);

                        // Update bounding dimensions
                        if (neighbor.getX() < minX) minX = neighbor.getX();
                        if (neighbor.getX() > maxX) maxX = neighbor.getX();
                        if (neighbor.getY() < minYBound) minYBound = neighbor.getY();
                        if (neighbor.getY() > maxYBound) maxYBound = neighbor.getY();
                        if (neighbor.getZ() < minZ) minZ = neighbor.getZ();
                        if (neighbor.getZ() > maxZ) maxZ = neighbor.getZ();

                        clusterBoundingBox = clusterBoundingBox.union(new Box(neighbor));
                    }
                }
            }

            if (isHighway) {
                blacklistedHighways.addAll(clusterComponents);
                pendingUnnaturalBlocks.removeAll(clusterComponents);
                continue;
            }

            BlockPos anchor = calculateClusterAnchor(clusterComponents);
            
            // Discard clusters outside Y-level constraints
            if (anchor.getY() < yMin || anchor.getY() > yMax) {
                pendingUnnaturalBlocks.removeAll(clusterComponents);
                continue;
            }

            validClusterAnchors.add(anchor);
            activeClusters.put(anchor, new AlienCluster(clusterBoundingBox.expand(0.02), clusterComponents));
            processedBlocks.addAll(clusterComponents);
            pendingUnnaturalBlocks.removeAll(clusterComponents); // Flush from memory to prevent lag
            groupsProcessedThisTick++;
        }

        // If we hit the safety limit, flag dirty so we finish next tick
        if (groupsProcessedThisTick >= maxGroupsPerTick && !pendingUnnaturalBlocks.isEmpty()) {
            stateDirty = true;
        }

        activeClusters.keySet().retainAll(validClusterAnchors);
    }

    private static BlockPos calculateClusterAnchor(Set<BlockPos> components) {
        BlockPos anchor = null;
        for (BlockPos pos : components) {
            if (anchor == null || pos.getY() < anchor.getY() || (pos.getY() == anchor.getY() && pos.getX() < anchor.getX())) {
                anchor = pos;
            }
        }
        return anchor;
    }

    // ================================================================== //
    //                        EVENT HANDLERS                            //
    // ================================================================== //

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.world == null || mc.player == null || !isDimensionAllowed) return;

        boolean isNewTarget = activeScanList.contains(event.newState.getBlock());
        boolean wasTarget = activeScanList.contains(event.oldState.getBlock());

        if (isNewTarget && !blacklistedHighways.contains(event.pos)) {
            pendingUnnaturalBlocks.add(event.pos);
            processedBlocks.remove(event.pos);
            stateDirty = true;
        } else if (wasTarget) {
            if (pendingUnnaturalBlocks.remove(event.pos)) {
                processedBlocks.remove(event.pos);
                stateDirty = true;
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null || !isDimensionAllowed) return;

        int renderCap = maxRenderClusters.get();
        int renderedCount = 0;

        for (AlienCluster cluster : activeClusters.values()) {
            if (renderedCount++ >= renderCap) break;

            SettingColor color = getBlockColor();
            if (highlightStyle.get() == HighlightStyle.SPECTRAL) {
                renderSpectralBox(event, cluster.boundingBox, color);
            } else {
                renderGlowBox(event, cluster.boundingBox, color);
                event.renderer.box(cluster.boundingBox, withAlpha(color, 0), color, shapeMode.get(), 0);
            }
        }
    }

    // ================================================================== //
    //                        RENDERING HELPERS                         //
    // ================================================================== //

    private void renderSpectralBox(Render3DEvent event, Box box, SettingColor color) {
        double expand = spectralExpand.get();
        Box renderBox = box.expand(expand);
        int lineAlpha = spectralLineAlpha.get();
        
        event.renderer.box(
            renderBox, 
            withAlpha(color, spectralFillAlpha.get()), 
            withAlpha(color, lineAlpha), 
            ShapeMode.Both, 
            0
        );
    }

    private void renderGlowBox(Render3DEvent event, Box box, SettingColor color) {
        int layers = glowLayers.get();
        double spread = glowSpread.get();
        int baseAlpha = glowBaseAlpha.get();

        for (int i = layers; i >= 1; i--) {
            int layerAlpha = Math.max(4, (int)(baseAlpha * (1.0 - (double)(i - 1) / layers)));
            event.renderer.box(
                box.expand(spread * i), 
                withAlpha(color, layerAlpha), 
                withAlpha(color, 0), 
                ShapeMode.Sides, 
                0
            );
        }
    }

    private SettingColor getBlockColor() {
        if (dynamicColors.get()) {
            float hue = (0.95f + (System.currentTimeMillis() % 3000L) / 3000f) % 1f;
            int rgb = java.awt.Color.HSBtoRGB(hue, 0.8f, 1.0f);
            return new SettingColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
        }
        return blockColor.get();
    }

    // ================================================================== //
    //                          UTILITIES                               //
    // ================================================================== //

    private void cleanupDistantObjects(int effectiveRange) {
        if (mc.player == null) return;
        double maxDistanceSq = Math.pow(effectiveRange * 16.0 + 64.0, 2);

        pendingUnnaturalBlocks.removeIf(pos -> pos.getSquaredDistance(mc.player.getEntityPos()) > maxDistanceSq);
        processedBlocks.removeIf(pos -> pos.getSquaredDistance(mc.player.getEntityPos()) > maxDistanceSq);
        blacklistedHighways.removeIf(pos -> pos.getSquaredDistance(mc.player.getEntityPos()) > maxDistanceSq);
        activeClusters.entrySet().removeIf(entry -> entry.getKey().getSquaredDistance(mc.player.getEntityPos()) > maxDistanceSq);

        int playerChunkX = mc.player.getBlockPos().getX() >> 4;
        int playerChunkZ = mc.player.getBlockPos().getZ() >> 4;
        int rangeSq = effectiveRange * effectiveRange;
        
        scannedChunks.removeIf(cp -> (cp.x - playerChunkX) * (cp.x - playerChunkX) + (cp.z - playerChunkZ) * (cp.z - playerChunkZ) > rangeSq);
    }

    public static SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    public void markChunkDirty(ChunkPos cp) {
        scannedChunks.remove(cp);
        dirtyChunks.add(cp);
        stateDirty = true;
    }

    // ================================================================== //
    //                          PUBLIC API                              //
    // ================================================================== //

    public boolean isTerralienEnabled() {
        return isActive() && isDimensionAllowed;
    }

    public int getTotalClusters() {
        return activeClusters.size();
    }

    // ================================================================== //
    //                           DATA CLASS                             //
    // ================================================================== //

    private static class AlienCluster {
        final Box boundingBox;
        final Set<BlockPos> componentBlocks;

        AlienCluster(Box boundingBox, Set<BlockPos> componentBlocks) {
            this.boundingBox = boundingBox;
            this.componentBlocks = componentBlocks;
        }
    }
}