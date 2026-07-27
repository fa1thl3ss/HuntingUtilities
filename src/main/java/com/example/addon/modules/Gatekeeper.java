package com.example.addon.modules;

import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.addon.HuntingUtilities;
import com.example.addon.mixin.EndGatewayBlockEntityAccessor;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
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
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.EndGatewayBlockEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.WorldChunk;

public class Gatekeeper extends Module {

    private static final int    CHUNK_SCAN_LIMIT_PER_TICK        = 64;
    private static final int    CLEANUP_INTERVAL_TICKS           = 60;
    private static final long   MESSAGE_COOLDOWN_MS              = 2000;

    public enum HighlightStyle    { GLOW, SPECTRAL }
    public enum GateDetectionMode { Off, Highlight, Notify, Both }
    public enum BeamStyle         { BOX, GUARDIAN }

    // ── Setting Groups ─────────────────────────────────────────────
    private final SettingGroup sgGeneral      = settings.getDefaultGroup();
    private final SettingGroup sgEndDimension = settings.createGroup("End Dimension");
    private final SettingGroup sgRender       = settings.createGroup("Render");
    private final SettingGroup sgBeam         = settings.createGroup("Beam");

    // ── Toggles ────────────────────────────────────────────────────
    private final Setting<Boolean> scanEndPortals = sgEndDimension.add(new BoolSetting.Builder()
        .name("end-portals").description("Scan End portal blocks.").defaultValue(true)
        .onChanged(v -> portalsDirty = true).build());

    private final Setting<Boolean> scanEndGateways = sgEndDimension.add(new BoolSetting.Builder()
        .name("end-gateways").description("Scan End gateways.").defaultValue(true)
        .onChanged(v -> portalsDirty = true).build());

    // ── General ────────────────────────────────────────────────────
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("Detection range in chunks.").defaultValue(32).min(16).max(64).build());

    // ── End Dimension ──────────────────────────────────────────────
    private final Setting<SettingColor> endPortalColor = sgEndDimension.add(new ColorSetting.Builder()
        .name("end-portal-color").defaultValue(new SettingColor(0, 255, 128, 255)).visible(scanEndPortals::get).build());

    private final Setting<SettingColor> endGatewayColor = sgEndDimension.add(new ColorSetting.Builder()
        .name("end-gateway-color").defaultValue(new SettingColor(255, 0, 255, 255)).visible(scanEndGateways::get).build());

    private final Setting<GateDetectionMode> anomalyDetection = sgEndDimension.add(new EnumSetting.Builder<GateDetectionMode>()
        .name("anomalies")
        .description("How to handle anomalous (broken or far-out) gateways.")
        .defaultValue(GateDetectionMode.Both)
        .visible(scanEndGateways::get)
        .build());

    private final Setting<SettingColor> anomalyGatewayColor = sgEndDimension.add(new ColorSetting.Builder()
        .name("anomaly-color")
        .description("Highlight color for anomalous gateways.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .visible(() -> anomalyDetection.get() == GateDetectionMode.Highlight || anomalyDetection.get() == GateDetectionMode.Both)
        .build());

    private final Setting<Integer> farOutThreshold = sgEndDimension.add(new IntSetting.Builder()
        .name("far-out-threshold")
        .description("Blocks from center to flag as far-out.")
        .defaultValue(5000)
        .min(1000)
        .sliderMax(100000)
        .visible(() -> anomalyDetection.get() != GateDetectionMode.Off)
        .build());

    // ── Render ─────────────────────────────────────────────────────
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").defaultValue(ShapeMode.Both).build());
    private final Setting<HighlightStyle> highlightStyle = sgRender.add(new EnumSetting.Builder<HighlightStyle>()
        .name("highlight-style").defaultValue(HighlightStyle.GLOW).build());
    private final Setting<Boolean> dynamicColors = sgRender.add(new BoolSetting.Builder()
        .name("dynamic-colors").defaultValue(false).build());

    // ── Beam ───────────────────────────────────────────────────────
    private final Setting<Boolean> showBeam = sgBeam.add(new BoolSetting.Builder()
        .name("show-beam").defaultValue(true).build());

    private final Setting<Integer> beamRange = sgBeam.add(new IntSetting.Builder()
        .name("beam-range")
        .description("Maximum horizontal distance (in chunks) to render the vertical beam.")
        .defaultValue(16)
        .min(1)
        .sliderMax(64)
        .visible(showBeam::get)
        .build());

    private final Setting<Boolean> onlyNearestBeam = sgBeam.add(new BoolSetting.Builder()
        .name("only-nearest-beam")
        .description("Only render the beam for the portal closest to the player.")
        .defaultValue(false)
        .visible(showBeam::get)
        .build());

    private final Setting<BeamStyle> beamStyle = sgBeam.add(new EnumSetting.Builder<BeamStyle>()
        .name("beam-style").defaultValue(BeamStyle.GUARDIAN).visible(showBeam::get).build());

    private final Setting<Integer> beamWidth = sgBeam.add(new IntSetting.Builder()
        .name("beam-width").description("Width of the box-style beam.").defaultValue(15).visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.BOX).build());

    private final Setting<Double> guardianRadius = sgBeam.add(new DoubleSetting.Builder()
        .name("guardian-radius").description("Radius of guardian-style beam strands.").defaultValue(0.08).visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.GUARDIAN).build());

    private final Setting<Integer> guardianStrands = sgBeam.add(new IntSetting.Builder()
        .name("guardian-strands").description("Number of rotating strands.").defaultValue(4).visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.GUARDIAN).build());

    private final Setting<Double> guardianSpinSpeed = sgBeam.add(new DoubleSetting.Builder()
        .name("guardian-spin-speed").description("Rotation speed of strands.").defaultValue(1.0).visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.GUARDIAN).build());

    private final Setting<Integer> guardianCoreAlpha = sgBeam.add(new IntSetting.Builder()
        .name("guardian-core-alpha").description("Alpha of the beam center.").defaultValue(90).visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.GUARDIAN).build());

    private final Setting<Integer> guardianStrandAlpha = sgBeam.add(new IntSetting.Builder()
        .name("guardian-strand-alpha").description("Alpha of the rotating strands.").defaultValue(160).visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.GUARDIAN).build());

    // ── State ──────────────────────────────────────────────────────
    private final Map<BlockPos, PortalType>      portals            = new ConcurrentHashMap<>();
    private final Map<BlockPos, PortalStructure> portalStructureMap = new ConcurrentHashMap<>();
    private final Set<ChunkPos>                  scannedChunks      = new HashSet<>();
    private final Set<ChunkPos>                  dirtyChunks        = new HashSet<>();
    private final Set<String>                    notifiedStructures = new HashSet<>();
    private final Map<String, Long>              messageCooldowns   = new ConcurrentHashMap<>();
    private boolean portalsDirty = false;
    private int cleanupTimer = 0;

    public Gatekeeper() {
        super(HuntingUtilities.CATEGORY, "gatekeeper", "Advanced End gateway and End portal tracking.");
    }

    // ── Lifecycle ──────────────────────────────────────────────────
    @Override
    public void onActivate() {
        clearAllState();
    }

    @Override
    public void onDeactivate() {
        clearAllState();
    }

    private void clearAllState() {
        portals.clear(); portalStructureMap.clear(); scannedChunks.clear(); dirtyChunks.clear();
        notifiedStructures.clear(); portalsDirty = false;
    }

    // ── Event Handlers ─────────────────────────────────────────────
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        if (!dirtyChunks.isEmpty()) { scannedChunks.removeAll(dirtyChunks); dirtyChunks.clear(); }
        BlockPos p = mc.player.getBlockPos();
        scanNewChunks(p.getX() >> 4, p.getZ() >> 4);
        if (portalsDirty) { portalsDirty = false; groupPortals(); }

        if (++cleanupTimer >= CLEANUP_INTERVAL_TICKS) {
            cleanupTimer = 0;
            cleanupDistantPortals();
        }
    }

    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.world == null) return;
        PortalType type = (event.newState.isOf(Blocks.END_GATEWAY)) ? PortalType.END_GATEWAY : (event.newState.isOf(Blocks.END_PORTAL)) ? PortalType.END_PORTAL : null;
        if (type != null) { portals.put(event.pos, type); portalsDirty = true; }
        else if (portals.remove(event.pos) != null) portalsDirty = true;
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;
        double beamDistSq = Math.pow(beamRange.get() * 16.0, 2);

        PortalStructure nearest = null;
        if (showBeam.get() && onlyNearestBeam.get()) {
            double minSq = Double.MAX_VALUE;
            for (PortalStructure structure : portalStructureMap.values()) {
                double sq = mc.player.getPos().squaredDistanceTo(structure.boundingBox.getCenter());
                if (sq < minSq) { minSq = sq; nearest = structure; }
            }
        }

        for (PortalStructure structure : portalStructureMap.values()) {
            SettingColor color = getStructureColor(structure);
            if (color == null) continue;
            if (highlightStyle.get() == HighlightStyle.SPECTRAL) renderSpectral(event, structure, color);
            else {
                renderGlowLayers(event, structure.boundingBox, color);
                event.renderer.box(structure.boundingBox, withAlpha(color, 0), color, shapeMode.get(), 0);
            }
            if (showBeam.get() && (nearest == null || structure == nearest) && mc.player.getPos().squaredDistanceTo(structure.boundingBox.getCenter()) <= beamDistSq) {
                renderBeams(event, List.of(new BeamData(structure.boundingBox, color)));
            }
        }
    }

    // ── Core Logic ─────────────────────────────────────────────────
    private void scanNewChunks(int centerChunkX, int centerChunkZ) {
        int r = range.get(), rSq = r * r, scanned = 0;
        for (int d = 0; d <= r; d++) {
            for (int x = -d; x <= d; x++) {
                if (tryScanChunk(centerChunkX + x, centerChunkZ - d, rSq, centerChunkX, centerChunkZ)) {
                    if (++scanned >= CHUNK_SCAN_LIMIT_PER_TICK) return;
                }
                if (d > 0 && tryScanChunk(centerChunkX + x, centerChunkZ + d, rSq, centerChunkX, centerChunkZ)) {
                    if (++scanned >= CHUNK_SCAN_LIMIT_PER_TICK) return;
                }
            }
            for (int z = -d + 1; z < d; z++) {
                if (tryScanChunk(centerChunkX - d, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) {
                    if (++scanned >= CHUNK_SCAN_LIMIT_PER_TICK) return;
                }
                if (tryScanChunk(centerChunkX + d, centerChunkZ + z, rSq, centerChunkX, centerChunkZ)) {
                    if (++scanned >= CHUNK_SCAN_LIMIT_PER_TICK) return;
                }
            }
        }
    }

    private boolean tryScanChunk(int cx, int cz, int rSq, int centerCX, int centerCZ) {
        int dx = cx - centerCX, dz = cz - centerCZ;
        if (dx * dx + dz * dz > rSq) return false;

        ChunkPos cp = new ChunkPos(cx, cz);
        if (scannedChunks.contains(cp)) return false;

        WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cx, cz);
        if (chunk != null) {
            scanChunk(chunk);
            scannedChunks.add(cp);
            return true;
        }
        return false;
    }

    private void scanChunk(WorldChunk chunk) {
        ChunkSection[] sections = chunk.getSectionArray();
        int chunkX = chunk.getPos().x << 4;
        int chunkZ = chunk.getPos().z << 4;

        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;

            // High-performance check: Skip entire section if no target blocks exist in the palette
            boolean hasPortal = scanEndPortals.get() && section.hasAny(state -> state.isOf(Blocks.END_PORTAL));
            boolean hasGateway = scanEndGateways.get() && section.hasAny(state -> state.isOf(Blocks.END_GATEWAY));
            if (!hasPortal && !hasGateway) continue;

            int sectionMinY = (chunk.getBottomSectionCoord() + i) * 16;
            for (int x = 0; x < 16; x++) {
                for (int y = 0; y < 16; y++) {
                    for (int z = 0; z < 16; z++) {
                        var state = section.getBlockState(x, y, z);
                        if (hasPortal && state.isOf(Blocks.END_PORTAL)) {
                            BlockPos pos = new BlockPos(chunkX + x, sectionMinY + y, chunkZ + z);
                            if (!portals.containsKey(pos)) {
                                portals.put(pos, PortalType.END_PORTAL);
                                portalsDirty = true;
                            }
                        } else if (hasGateway && state.isOf(Blocks.END_GATEWAY)) {
                            BlockPos pos = new BlockPos(chunkX + x, sectionMinY + y, chunkZ + z);
                            if (!portals.containsKey(pos)) {
                                portals.put(pos, PortalType.END_GATEWAY);
                                portalsDirty = true;
                            }
                        }
                    }
                }
            }
        }
    }

    private void groupPortals() {
        Set<BlockPos> visited = new HashSet<>();
        Set<BlockPos> active = new HashSet<>();

        for (BlockPos startPos : portals.keySet()) {
            if (visited.contains(startPos)) continue;
            PortalType type = portals.get(startPos);
            Set<BlockPos> component = new HashSet<>();
            Queue<BlockPos> queue = new LinkedList<>();
            Box structureBox = new Box(startPos);
            queue.add(startPos); visited.add(startPos);
            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                component.add(current);
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.offset(dir);
                    if (portals.get(neighbor) == type && visited.add(neighbor)) {
                        queue.add(neighbor); structureBox = structureBox.union(new Box(neighbor));
                    }
                }
            }
            BlockPos anchor = componentAnchor(component);
            active.add(anchor);
            BlockPos dest = null; GatewayState gs = GatewayState.NATURAL;
            if (type == PortalType.END_GATEWAY) {
                BlockEntity be = mc.world.getBlockEntity(anchor);
                if (be instanceof EndGatewayBlockEntity gateway) {
                    dest = ((EndGatewayBlockEntityAccessor) gateway).getExitPortalPos();
                    if (dest != null) {
                        if (dest.getX() == 0 && dest.getZ() == 0 && anomalyDetection.get() != GateDetectionMode.Off) gs = GatewayState.BROKEN;
                        else if (Math.sqrt(dest.getSquaredDistance(0, 0, 0)) > farOutThreshold.get() && anomalyDetection.get() != GateDetectionMode.Off) gs = GatewayState.FAR_OUT;
                    }
                }
            }
            portalStructureMap.put(anchor, new PortalStructure(structureBox.expand(0.02), component, type, dest, gs));
            if (type == PortalType.END_GATEWAY) notifyGateway(anchor, dest, gs);
        }
        portalStructureMap.keySet().retainAll(active);
    }

    private void cleanupDistantPortals() {
        if (mc.player == null) return;
        double distSq = Math.pow(range.get() * 16 + 64, 2);
        if (portals.entrySet().removeIf(e -> e.getKey().getSquaredDistance(mc.player.getPos()) > distSq)) portalsDirty = true;

        int px = mc.player.getBlockPos().getX() >> 4, pz = mc.player.getBlockPos().getZ() >> 4;
        int rSq = range.get() * range.get();
        scannedChunks.removeIf(cp -> (cp.x - px) * (cp.x - px) + (cp.z - pz) * (cp.z - pz) > rSq);
    }

    private void notifyGateway(BlockPos pos, BlockPos dest, GatewayState gs) {
        String id = "GW_" + pos.toShortString();
        if (!notifiedStructures.add(id)) return;
        if (gs == GatewayState.BROKEN && (anomalyDetection.get() == GateDetectionMode.Notify || anomalyDetection.get() == GateDetectionMode.Both))
            warning("§d§lBroken Gateway §7detected (Void link)");
        else if (gs == GatewayState.FAR_OUT && (anomalyDetection.get() == GateDetectionMode.Notify || anomalyDetection.get() == GateDetectionMode.Both))
            warning("§c§lFar-Out Gateway §7detected");
        else info("§dEnd Gateway §7detected");
    }

    private BlockPos componentAnchor(Set<BlockPos> comp) {
        BlockPos anchor = null;
        for (BlockPos p : comp) if (anchor == null || p.getY() < anchor.getY() || (p.getY() == anchor.getY() && p.getX() < anchor.getX())) anchor = p;
        return anchor;
    }

    // ── Render Helpers ─────────────────────────────────────────────
    private void renderSpectral(Render3DEvent event, PortalStructure structure, SettingColor color) {
        event.renderer.box(structure.boundingBox.expand(0.05), withAlpha(color, 15), withAlpha(color, 255), ShapeMode.Both, 0);
    }

    private void renderGlowLayers(Render3DEvent event, Box box, SettingColor color) {
        for (int i = 4; i >= 1; i--) {
            int alpha = Math.max(4, (int)(50 * (1.0 - (double)(i-1)/4)));
            event.renderer.box(box.expand(0.05 * i), withAlpha(color, alpha), withAlpha(color, 0), ShapeMode.Sides, 0);
        }
    }

    private void renderBeams(Render3DEvent event, List<BeamData> beams) {
        for (BeamData beam : beams) {
            if (beamStyle.get() == BeamStyle.GUARDIAN) renderGuardianBeam(event, beam.box, beam.color);
            else renderBoxBeam(event, beam.box, beam.color);
        }
    }

    private void renderBoxBeam(Render3DEvent event, Box anchorBox, SettingColor color) {
        double beamSize = beamWidth.get() / 100.0, centerX = (anchorBox.minX + anchorBox.maxX) / 2.0, centerZ = (anchorBox.minZ + anchorBox.maxZ) / 2.0;
        int worldBot = mc.world.getBottomY(), worldTop = worldBot + mc.world.getHeight();
        Box beamBox = new Box(centerX - beamSize, worldBot, centerZ - beamSize, centerX + beamSize, worldTop, centerZ + beamSize);
        renderGlowLayers(event, beamBox, color);
        event.renderer.box(beamBox, withAlpha(color, 60), color, ShapeMode.Both, 0);
    }

    private void renderGuardianBeam(Render3DEvent event, Box anchorBox, SettingColor color) {
        double cx = (anchorBox.minX + anchorBox.maxX) / 2.0, cz = (anchorBox.minZ + anchorBox.maxZ) / 2.0;
        int worldBot = mc.world.getBottomY(), worldTop = worldBot + mc.world.getHeight();
        double radius = guardianRadius.get(), rotationRad = (System.currentTimeMillis() % 6000L) / 6000.0 * Math.PI * 2.0;
        for (int i = 0; i < guardianStrands.get(); i++) {
            double angle = rotationRad + (Math.PI * 2.0 / guardianStrands.get()) * i;
            Box strandBox = new Box(cx + Math.cos(angle) * radius - 0.01, worldBot, cz + Math.sin(angle) * radius - 0.01, cx + Math.cos(angle) * radius + 0.01, worldTop, cz + Math.sin(angle) * radius + 0.01);
            event.renderer.box(strandBox, withAlpha(color, guardianStrandAlpha.get() / 2), withAlpha(color, guardianStrandAlpha.get()), ShapeMode.Both, 0);
        }
    }

    // ── Utility Helpers ────────────────────────────────────────────
    private SettingColor getStructureColor(PortalStructure structure) {
        if (structure.gatewayState == GatewayState.BROKEN || structure.gatewayState == GatewayState.FAR_OUT) return anomalyGatewayColor.get();
        if (dynamicColors.get()) {
            float hue = ( (structure.type == PortalType.END_PORTAL ? 0.333f : 0.667f) + (System.currentTimeMillis() % 3000) / 3000f) % 1f;
            int rgb = java.awt.Color.HSBtoRGB(hue, 0.8f, 1.0f);
            return new SettingColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
        }
        return structure.type == PortalType.END_PORTAL ? endPortalColor.get() : endGatewayColor.get();
    }

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    private void sendMessage(String message) {
        long now = System.currentTimeMillis();
        if (now - messageCooldowns.getOrDefault(message, 0L) > MESSAGE_COOLDOWN_MS) {
            info(message); messageCooldowns.put(message, now);
        }
    }

    // ── Public API ─────────────────────────────────────────────────
    public void markChunkDirty(ChunkPos cp) { scannedChunks.remove(cp); dirtyChunks.add(cp); portalsDirty = true; }

    public int getTotalEndPortals() { return (int) portalStructureMap.values().stream().filter(s -> s.type == PortalType.END_PORTAL).count(); }
    public int getTotalGateways()   { return (int) portalStructureMap.values().stream().filter(s -> s.type == PortalType.END_GATEWAY).count(); }
    public int getAnomalousGateways()  { return (int) portalStructureMap.values().stream().filter(s -> s.gatewayState == GatewayState.BROKEN || s.gatewayState == GatewayState.FAR_OUT).count(); }

    // ── Inner Types ────────────────────────────────────────────────
    private enum PortalType { END_PORTAL, END_GATEWAY }
    private enum GatewayState { NATURAL, FAR_OUT, BROKEN }

    private static class PortalStructure {
        final Box boundingBox;
        final Set<BlockPos> portalBlocks;
        final PortalType type;
        final BlockPos destination;
        final GatewayState gatewayState;

        PortalStructure(Box bb, Set<BlockPos> pb, PortalType t, BlockPos dest, GatewayState gs) {
            this.boundingBox = bb; this.portalBlocks = pb; this.type = t; this.destination = dest; this.gatewayState = gs;
        }
    }

    private record BeamData(Box box, SettingColor color) {}
}