package com.example.addon.modules;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.example.addon.HuntingUtilities;
import com.example.addon.utils.XaeroPortalBridge;
import com.example.addon.utils.XaerosPlusPortalDB;
import com.example.addon.utils.XaerosWaypointHelper;
import com.example.addon.utils.XaerosWaypointHelper.WaypointEntry;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.BlockUpdateEvent;
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
import meteordevelopment.meteorclient.utils.player.FindItemResult;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.meteorclient.utils.player.Rotations;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.block.entity.EndGatewayBlockEntity;
import net.minecraft.block.entity.EndPortalBlockEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ChunkStatus;
import net.minecraft.world.chunk.WorldChunk;

public class PortalTracker extends Module {

    private static final int    DIMENSION_SETTLE_TICKS           = 40;
    private static final int    ENTRY_EXCLUSION_COOLDOWN_TICKS   = 200;
    private static final int    ENTRY_EXCLUSION_RADIUS           = 5;
    private static final double ENTRY_EXCLUSION_RADIUS_SQ        = ENTRY_EXCLUSION_RADIUS * ENTRY_EXCLUSION_RADIUS;
    private static final int    CHUNK_SCAN_LIMIT_PER_TICK        = 10;
    private static final int    STRUCTURE_REBUILD_INTERVAL_TICKS = 5;
    private static final int    CLEANUP_INTERVAL_TICKS           = 60;
    private static final long   MESSAGE_COOLDOWN_MS              = 2000;
    private static final int    WAYPOINT_DEDUP_RADIUS            = 8;

    /**
     * Classification strategy: TWO-SCAN CORNER CHECK.
     *
     * Scan 1 — Portal air blocks: flood-fill connected NETHER_PORTAL blocks to
     *           find the component and its axis-aligned bounding box.
     *
     * Scan 2 — Corner obsidian: test the 4 corner positions of the portal's
     *           bounding rectangle (in the plane of the portal). If obsidian is
     *           present at ALL 4 corners → EXIT (full-size) portal.
     *           If any corner is missing obsidian → CUSTOM (built) portal.
     *
     * Corner positions are derived from the component bounding box, not from an
     * arbitrary obsidian count, so the result is geometry-stable: adding or
     * removing interior obsidian blocks cannot change the classification, and
     * there is no overlap window between "full-size" and "custom" colour because
     * the result is a single boolean derived from a clean geometric predicate.
     */

    // ── Highlight Style ────────────────────────────────────────────
    public enum HighlightStyle {
        GLOW("Glow"),
        SPECTRAL("Spectral");

        private final String displayName;
        HighlightStyle(String name) { this.displayName = name; }

        @Override public String toString() { return displayName; }
    }

    // ── Beam Style ────────────────────────────────────────────────
    public enum BeamStyle {
        BOX("Box"),
        GUARDIAN("Guardian");

        private final String displayName;
        BeamStyle(String name) { this.displayName = name; }

        @Override public String toString() { return displayName; }
    }

    // ── Setting Groups ─────────────────────────────────────────────
    private final SettingGroup sgGeneral       = settings.getDefaultGroup();
    private final SettingGroup sgNetherPortals = settings.createGroup("Nether Portals");
    private final SettingGroup sgEndDimension  = settings.createGroup("End Dimension");
    private final SettingGroup sgRender        = settings.createGroup("Render");
    private final SettingGroup sgGlow          = settings.createGroup("Glow");
    private final SettingGroup sgSpectral      = settings.createGroup("Spectral");
    private final SettingGroup sgBeam          = settings.createGroup("Beam");
    private final SettingGroup sgPlatform      = settings.createGroup("Platform 9\u00BE");

    // ── General ────────────────────────────────────────────────────
    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range").description("Portal detection range in chunks.")
        .defaultValue(32).min(16).max(64).sliderMin(16).sliderMax(64).build());

    private final Setting<Integer> autoMarkRange = sgGeneral.add(new IntSetting.Builder()
        .name("auto-mark-range").description("Auto-mark Nether portals within this many blocks of the player as created by you.")
        .defaultValue(10).min(0).max(50).sliderMin(0).sliderMax(50).build());

    private final Setting<Boolean> trackOverworld = sgGeneral.add(new BoolSetting.Builder()
        .name("track-overworld").description("Also auto-mark portals found in the Overworld as created by you.")
        .defaultValue(false).build());

    private final Setting<Boolean> showCreatedCount = sgGeneral.add(new BoolSetting.Builder()
        .name("show-created-count").description("Show a chat message each time a new portal you created is discovered.")
        .defaultValue(true).build());

    private final Setting<Boolean> onlyShowCreated = sgGeneral.add(new BoolSetting.Builder()
        .name("only-show-created").description("Only highlight portals you've created.")
        .defaultValue(false).build());

    private final Setting<Boolean> dynamicColors = sgGeneral.add(new BoolSetting.Builder()
        .name("dynamic-colors").description("Animate portal colors. Each type uses a distinct hue offset so types stay visually distinguishable.")
        .defaultValue(false).build());

    private final Setting<Boolean> highlightFrame = sgGeneral.add(new BoolSetting.Builder()
        .name("highlight-frame").description("Highlights the obsidian frame of Nether portals.")
        .defaultValue(true).build());

    private final Setting<Boolean> xaeroIntegration = sgGeneral.add(new BoolSetting.Builder()
        .name("xaero-integration").description("Enables all Xaero integration.")
        .defaultValue(true).build());

    private final Setting<Boolean> resetButton = sgGeneral.add(new BoolSetting.Builder()
        .name("reset").description("Reset the current session and clear all created-portal records.")
        .defaultValue(false).onChanged(this::handleReset).build());

    // ── Nether Portals ─────────────────────────────────────────────
    private final Setting<Boolean> scanNetherPortals = sgNetherPortals.add(new BoolSetting.Builder()
        .name("scan-nether").description("Scan lit Nether portals.").defaultValue(true).build());

    private final Setting<Boolean> differentiatePortalSizes = sgNetherPortals.add(new BoolSetting.Builder()
        .name("differentiate-sizes")
        .description("Give exit portals (obsidian on all 4 corners) and custom/built portals (any corner missing) different colors.")
        .defaultValue(true)
        .visible(scanNetherPortals::get).build());

    private final Setting<SettingColor> netherColorFull = sgNetherPortals.add(new ColorSetting.Builder()
        .name("color-exit-portal")
        .description("Color for exit portals — obsidian present at all 4 frame corners.")
        .defaultValue(new SettingColor(180, 60, 255, 255))
        .visible(scanNetherPortals::get).build());

    private final Setting<SettingColor> netherColorCustom = sgNetherPortals.add(new ColorSetting.Builder()
        .name("color-custom-built")
        .description("Color for custom/built portals — at least one frame corner is missing obsidian.")
        .defaultValue(new SettingColor(255, 140, 0, 255))
        .visible(() -> scanNetherPortals.get() && differentiatePortalSizes.get()).build());

    // ── End Dimension ──────────────────────────────────────────────
    private final Setting<Boolean> scanEndPortals = sgEndDimension.add(new BoolSetting.Builder()
        .name("end-portals").description("Scan End portal blocks.").defaultValue(true).build());

    private final Setting<SettingColor> endPortalColor = sgEndDimension.add(new ColorSetting.Builder()
        .name("end-portal-color").defaultValue(new SettingColor(0, 255, 128, 255))
        .visible(scanEndPortals::get).build());

    private final Setting<Boolean> scanEndGateways = sgEndDimension.add(new BoolSetting.Builder()
        .name("end-gateways").description("Scan End gateways.").defaultValue(true).build());

    private final Setting<SettingColor> endGatewayColor = sgEndDimension.add(new ColorSetting.Builder()
        .name("end-gateway-color").defaultValue(new SettingColor(255, 0, 255, 255))
        .visible(scanEndGateways::get).build());

    // ── Render ─────────────────────────────────────────────────────
    private final Setting<ShapeMode> shapeMode = sgRender.add(new EnumSetting.Builder<ShapeMode>()
        .name("shape-mode").description("Render style for portal highlights.")
        .defaultValue(ShapeMode.Both).build());

    private final Setting<HighlightStyle> highlightStyle = sgRender.add(new EnumSetting.Builder<HighlightStyle>()
        .name("highlight-style")
        .description("GLOW renders layered bloom around the unified portal box. SPECTRAL renders a crisp outline only, like the spectral arrow effect.")
        .defaultValue(HighlightStyle.GLOW).build());

    // ── Glow ───────────────────────────────────────────────────────
    private final Setting<Integer> glowLayers = sgGlow.add(new IntSetting.Builder()
        .name("glow-layers").description("Number of bloom layers rendered around each portal.")
        .defaultValue(4).min(1).sliderMax(8)
        .visible(() -> highlightStyle.get() == HighlightStyle.GLOW).build());

    private final Setting<Double> glowSpread = sgGlow.add(new DoubleSetting.Builder()
        .name("glow-spread").description("How far each bloom layer expands outward (in blocks).")
        .defaultValue(0.05).min(0.01).sliderMax(0.2)
        .visible(() -> highlightStyle.get() == HighlightStyle.GLOW).build());

    private final Setting<Integer> glowBaseAlpha = sgGlow.add(new IntSetting.Builder()
        .name("glow-base-alpha").description("Alpha of the innermost glow layer (0-255).")
        .defaultValue(50).min(4).sliderMax(150)
        .visible(() -> highlightStyle.get() == HighlightStyle.GLOW).build());

    // ── Spectral ───────────────────────────────────────────────────
    private final Setting<Integer> spectralLineAlpha = sgSpectral.add(new IntSetting.Builder()
        .name("line-alpha").description("Opacity of the spectral outline (0-255).")
        .defaultValue(255).min(30).sliderMax(255)
        .visible(() -> highlightStyle.get() == HighlightStyle.SPECTRAL).build());

    private final Setting<Integer> spectralFillAlpha = sgSpectral.add(new IntSetting.Builder()
        .name("fill-alpha").description("Opacity of the spectral fill (0 = pure outline, higher = tinted fill).")
        .defaultValue(15).min(0).sliderMax(80)
        .visible(() -> highlightStyle.get() == HighlightStyle.SPECTRAL).build());

    private final Setting<Double> spectralExpand = sgSpectral.add(new DoubleSetting.Builder()
        .name("expand").description("How much to expand the outline box beyond the portal edge (in blocks).")
        .defaultValue(0.05).min(0.0).sliderMax(0.3)
        .visible(() -> highlightStyle.get() == HighlightStyle.SPECTRAL).build());

    private final Setting<Boolean> spectralPulse = sgSpectral.add(new BoolSetting.Builder()
        .name("pulse").description("Pulsate the spectral outline alpha over time, like the vanilla glowing effect.")
        .defaultValue(true)
        .visible(() -> highlightStyle.get() == HighlightStyle.SPECTRAL).build());

    // ── Beam ───────────────────────────────────────────────────────
    private final Setting<Boolean> showBeam = sgBeam.add(new BoolSetting.Builder()
        .name("show-beam").description("Show a vertical beam above each tracked portal.")
        .defaultValue(true).build());

    private final Setting<Boolean> onlyNearestBeam = sgBeam.add(new BoolSetting.Builder()
        .name("only-nearest-beam").description("Only render the beam for the portal closest to the player.")
        .defaultValue(false).visible(showBeam::get).build());

    private final Setting<BeamStyle> beamStyle = sgBeam.add(new EnumSetting.Builder<BeamStyle>()
        .name("beam-style")
        .description("BOX = simple axis-aligned box beam. GUARDIAN = spinning guardian-style beam.")
        .defaultValue(BeamStyle.GUARDIAN)
        .visible(showBeam::get).build());

    // ── BOX beam ──
    private final Setting<Integer> beamWidth = sgBeam.add(new IntSetting.Builder()
        .name("beam-width").description("Box beam width in hundredths of a block.")
        .defaultValue(15).min(5).max(50).sliderMin(5).sliderMax(50)
        .visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.BOX).build());

    private final Setting<Boolean> mergeBeams = sgBeam.add(new BoolSetting.Builder()
        .name("merge-beams").description("Merge beams for nearby portals to reduce clutter.")
        .defaultValue(false).visible(showBeam::get).build());

    private final Setting<Double> mergeDistance = sgBeam.add(new DoubleSetting.Builder()
        .name("merge-distance").description("Distance within which beams are merged.")
        .defaultValue(3.0).min(0).sliderMax(16)
        .visible(() -> showBeam.get() && mergeBeams.get()).build());

    // ── GUARDIAN beam ──
    private final Setting<Double> guardianRadius = sgBeam.add(new DoubleSetting.Builder()
        .name("guardian-radius")
        .description("Radius of the guardian beam strands from centre (blocks). Higher = thicker looking beam.")
        .defaultValue(0.08).min(0.01).max(0.6).sliderMax(0.3)
        .visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.GUARDIAN).build());

    private final Setting<Integer> guardianStrands = sgBeam.add(new IntSetting.Builder()
        .name("guardian-strands")
        .description("Number of spinning strands that make up the beam (2-8). 4 looks like a true guardian beam.")
        .defaultValue(4).min(2).max(8).sliderMax(8)
        .visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.GUARDIAN).build());

    private final Setting<Double> guardianSpinSpeed = sgBeam.add(new DoubleSetting.Builder()
        .name("guardian-spin-speed")
        .description("How fast the beam rotates. 1.0 = one full revolution every ~6 seconds.")
        .defaultValue(1.0).min(0.1).max(5.0).sliderMax(3.0)
        .visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.GUARDIAN).build());

    private final Setting<Integer> guardianCoreAlpha = sgBeam.add(new IntSetting.Builder()
        .name("guardian-core-alpha")
        .description("Alpha of the solid centre core of the guardian beam (0 = no core).")
        .defaultValue(90).min(0).max(255).sliderMax(200)
        .visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.GUARDIAN).build());

    private final Setting<Integer> guardianStrandAlpha = sgBeam.add(new IntSetting.Builder()
        .name("guardian-strand-alpha")
        .description("Alpha of the outer spinning strands.")
        .defaultValue(160).min(10).max(255).sliderMax(255)
        .visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.GUARDIAN).build());

    private final Setting<Boolean> guardianGlow = sgBeam.add(new BoolSetting.Builder()
        .name("guardian-glow")
        .description("Add a soft bloom halo around the guardian beam.")
        .defaultValue(true)
        .visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.GUARDIAN).build());

    private final Setting<Double> guardianGlowRadius = sgBeam.add(new DoubleSetting.Builder()
        .name("guardian-glow-radius")
        .description("Radius of the bloom halo around the guardian beam.")
        .defaultValue(0.18).min(0.02).max(1.0).sliderMax(0.5)
        .visible(() -> showBeam.get() && beamStyle.get() == BeamStyle.GUARDIAN && guardianGlow.get()).build());

    // ── Platform 9¾ ───────────────────────────────────────────────
    private final Setting<Keybind> platformKey = sgPlatform.add(new KeybindSetting.Builder()
        .name("platform-key").description("Key to build the 5x5 platform.")
        .defaultValue(Keybind.none()).action(this::startPlatformBuild).build());

    private final Setting<Integer> platformDelay = sgPlatform.add(new IntSetting.Builder()
        .name("platform-delay").description("Ticks between block placements.")
        .defaultValue(2).min(1).build());

    // ── State ──────────────────────────────────────────────────────
    private final Map<BlockPos, PortalType>      portals            = new ConcurrentHashMap<>();
    private final Set<BlockPos>                  createdPortals     = ConcurrentHashMap.newKeySet();
    private final Map<BlockPos, PortalStructure> portalStructureMap = new ConcurrentHashMap<>();
    private volatile boolean                     portalsDirty       = false;

    private final Set<ChunkPos> scannedChunks = new HashSet<>();
    private final Set<ChunkPos> dirtyChunks   = new HashSet<>();

    private final Set<String>       notifiedStructures = ConcurrentHashMap.newKeySet();
    private final Map<String, Long> messageCooldowns   = new ConcurrentHashMap<>();

    private String   lastDimension           = "";
    private int      dimensionChangeCooldown = 0;
    private BlockPos entryPortalPos          = null;
    private int      exclusionTimer          = 0;
    private boolean  manuallyActivated       = false;
    private long     sessionStartTime        = 0;
    private int      totalCreated            = 0;
    private int      structureTimer          = 0;
    private int      cleanupTimer            = 0;

    private final List<BlockPos> platformPositions = new ArrayList<>();
    private int platformIndex = 0;
    private int platformTimer = 0;

    private final Map<BlockPos, WaypointEntry> xaeroTrackedPortals = new ConcurrentHashMap<>();
    private int xaerosPlusWatchCount = 0;

    /**
     * Caches the corner-based isFullSize result for each portal anchor.
     *
     * Key:   component anchor BlockPos (lowest Y→X→Z corner of the component).
     * Value: true  = exit portal   (obsidian at ALL 4 frame corners)
     *        false = custom portal (at least one corner missing obsidian)
     *
     * An entry is only written once both of these are true:
     *   1. Every chunk that contains a block of this component is in scannedChunks.
     *   2. dimensionChangeCooldown has reached 0 (world data fully settled).
     *
     * Until then the portal renders with the conservative default (full-size /
     * exit colour) so the player never sees an unexpected colour flash.
     *
     * Evicted by retainAll(activeAnchors) inside groupPortals() so stale entries
     * from demolished portals or drifted anchors are removed each rebuild pass.
     *
     * Also cleared on dimension change (unlike crossDimensionSizeCache).
     */
    private final Map<BlockPos, Boolean> sizeConfirmedPortals = new ConcurrentHashMap<>();

    /**
     * Anchors that have not yet been classified because their chunks were not
     * fully loaded or the world was still settling. groupPortals() sets
     * portalsDirty = true whenever this set is non-empty so the next pass
     * will retry classification.
     */
    private final Set<BlockPos> pendingSizeRecheck = ConcurrentHashMap.newKeySet();

    /**
     * Persistent corner-classification cache that survives dimension changes.
     * Key:   "dimension:x,y,z" of the anchor block.
     * Value: true = exit portal, false = custom portal.
     *
     * Prevents the overworld entry portal flickering from exit→custom on every
     * Nether return, because sizeConfirmedPortals is wiped on dimension change
     * but this map is not. Only cleared on full session reset.
     */
    private final Map<String, Boolean> crossDimensionSizeCache = new ConcurrentHashMap<>();

    public PortalTracker() {
        super(HuntingUtilities.CATEGORY, "portal-tracker", "Automatically tracks and highlights portals.");
    }

    // ── Lifecycle ──────────────────────────────────────────────────
    @Override
    public void onActivate() {
        clearAllState();
        sessionStartTime = System.currentTimeMillis();
        if (mc.player != null && mc.world != null && mc.world.getRegistryKey() != null) {
            lastDimension = mc.world.getRegistryKey().getValue().toString();
            loadXaeroWaypointsForDimension(lastDimension);
        }
    }

    @Override
    public void onDeactivate() {
        if (manuallyActivated && mc.player != null) {
            long elapsed = System.currentTimeMillis() - sessionStartTime;
            if (elapsed > 0)
                sendMessage("§7Session ended — §f" + portalStructureMap.size()
                    + " §7portals discovered §8| §a" + totalCreated + " §7created");
        }
        clearAllState();
    }

    private void clearAllState() {
        portals.clear(); createdPortals.clear(); portalStructureMap.clear();
        notifiedStructures.clear(); messageCooldowns.clear();
        scannedChunks.clear(); dirtyChunks.clear();
        xaeroTrackedPortals.clear(); XaeroPortalBridge.clearWatched();
        sizeConfirmedPortals.clear(); pendingSizeRecheck.clear(); crossDimensionSizeCache.clear();
        portalsDirty = false; manuallyActivated = false; sessionStartTime = 0;
        structureTimer = 0; cleanupTimer = 0;
        platformPositions.clear(); platformIndex = 0; platformTimer = 0;
    }

    // ── Tick ───────────────────────────────────────────────────────
    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.player == null || mc.world == null) return;
        try { if (mc.world.getRegistryKey() == null) return; } catch (Exception e) { return; }

        if (dimensionChangeCooldown > 0) { dimensionChangeCooldown--; return; }
        if (exclusionTimer > 0) exclusionTimer--;
        if (handleDimensionChange()) return;

        if (!dirtyChunks.isEmpty()) { scannedChunks.removeAll(dirtyChunks); dirtyChunks.clear(); }

        BlockPos playerPos    = mc.player.getBlockPos();
        int      centerChunkX = playerPos.getX() >> 4;
        int      centerChunkZ = playerPos.getZ() >> 4;

        scanBlockEntities(centerChunkX, centerChunkZ);
        scanNewChunks(centerChunkX, centerChunkZ);

        if (xaeroIntegration.get()) XaeroPortalBridge.tick();

        if (portalsDirty && ++structureTimer >= STRUCTURE_REBUILD_INTERVAL_TICKS) {
            structureTimer = 0; portalsDirty = false; groupPortals();
        }

        if (++cleanupTimer >= CLEANUP_INTERVAL_TICKS) {
            cleanupTimer = 0;
            cleanupDistantPortals(); cleanupTrackedData(); cleanupDistantChunks(centerChunkX, centerChunkZ);
        }

        if (!manuallyActivated) manuallyActivated = true;
        handlePlatformBuilding();
    }

    private boolean handleDimensionChange() {
        try {
            String currDim = mc.world.getRegistryKey().getValue().toString();
            if (currDim.equals(lastDimension)) return false;

            dimensionChangeCooldown = DIMENSION_SETTLE_TICKS;
            exclusionTimer          = ENTRY_EXCLUSION_COOLDOWN_TICKS;
            lastDimension           = currDim;
            entryPortalPos          = mc.player.getBlockPos();

            portals.clear(); createdPortals.clear(); portalStructureMap.clear();
            notifiedStructures.clear(); scannedChunks.clear(); dirtyChunks.clear();
            xaeroTrackedPortals.clear(); XaeroPortalBridge.clearWatched();
            // Per-session classification cache cleared on each dimension change.
            // crossDimensionSizeCache is intentionally NOT cleared here — it lets
            // previously-confirmed portals skip the corner scan on re-entry.
            sizeConfirmedPortals.clear(); pendingSizeRecheck.clear();
            portalsDirty = false;

            loadXaeroWaypointsForDimension(currDim);

            boolean notify =
                (currDim.equals("minecraft:the_nether") && scanNetherPortals.get()) ||
                (currDim.equals("minecraft:overworld")   && scanNetherPortals.get()) ||
                (currDim.equals("minecraft:the_end")     && (scanEndPortals.get() || scanEndGateways.get()));
            if (notify) sendMessage("§7Entered " + getDimensionName(currDim) + " — scanning started");
            return true;
        } catch (Exception ignored) { return true; }
    }

    // ── Xaero Integration ──────────────────────────────────────────
    private void loadXaeroWaypointsForDimension(String dimensionId) {
        if (!xaeroIntegration.get()) return;
        List<WaypointEntry> entries = XaerosWaypointHelper.loadPortalWaypoints(dimensionId);
        for (WaypointEntry entry : entries) {
            if (entry.name.contains(XaerosWaypointHelper.REMOVED_TAG)) continue;
            BlockPos pos = entry.toBlockPos();
            xaeroTrackedPortals.put(pos, entry);
            XaeroPortalBridge.watchPosition(pos, (watchedPos, stillExists) -> {
                if (!isActive()) return;
                if (stillExists) { xaeroTrackedPortals.remove(watchedPos); }
                else {
                    if (!portals.containsKey(watchedPos)) {
                        WaypointEntry e = xaeroTrackedPortals.remove(watchedPos);
                        if (e != null) confirmPortalRemoved(watchedPos, e, dimensionId);
                    }
                }
            });
        }
        if (!xaeroTrackedPortals.isEmpty())
            sendMessage("§7Xaero: watching §f" + xaeroTrackedPortals.size()
                + " §7saved portal" + (xaeroTrackedPortals.size() == 1 ? "" : "s")
                + " for removal" + (XaeroPortalBridge.XAERO_PRESENT ? "" : " §8(Xaero not detected)"));
        loadXaerosPlusPortals(dimensionId);
    }

    private void loadXaerosPlusPortals(String dimensionId) {
        if (!xaeroIntegration.get()) return;
        if (!XaerosPlusPortalDB.isAvailable()) return;
        if (!dimensionId.equals("minecraft:the_nether") && !dimensionId.equals("minecraft:overworld")) return;
        List<XaerosPlusPortalDB.PortalEntry> entries = XaerosPlusPortalDB.loadForDimension(dimensionId, 50000);
        xaerosPlusWatchCount = 0;
        for (XaerosPlusPortalDB.PortalEntry entry : entries) {
            XaeroPortalBridge.watchColumn(entry.x, entry.z, (foundPos, present) -> {
                if (!isActive()) return;
                if (present) { if (!portals.containsKey(foundPos)) { portals.put(foundPos, PortalType.NETHER); portalsDirty = true; } }
                else sendMessage("§c\u26A0 XaeroPlus portal removed §8[" + getDimensionName(dimensionId) + "]");
            });
            xaerosPlusWatchCount++;
        }
        if (xaerosPlusWatchCount > 0)
            sendMessage("§7XaeroPlus: watching §f" + xaerosPlusWatchCount
                + " §7portal" + (xaerosPlusWatchCount == 1 ? "" : "s")
                + " from database §8[" + getDimensionName(dimensionId) + "]");
    }

    private void confirmPortalRemoved(BlockPos pos, WaypointEntry entry, String dimensionId) {
        String newName = entry.name.replace(XaerosWaypointHelper.PORTAL_TAG, XaerosWaypointHelper.REMOVED_TAG);
        if (newName.equals(entry.name)) newName = XaerosWaypointHelper.REMOVED_TAG + " " + entry.name;
        boolean updated = XaerosWaypointHelper.replaceWaypoint(dimensionId, entry, newName, XaerosWaypointHelper.COLOR_GRAY);
        if (updated) sendMessage("§c\u26A0 Portal removed §7— waypoint updated §8[" + getDimensionName(dimensionId) + "]");
        else          sendMessage("§c\u26A0 Portal removed §8[" + getDimensionName(dimensionId) + "]");
    }

    // ── Scanning ───────────────────────────────────────────────────
    private void scanBlockEntities(int centerChunkX, int centerChunkZ) {
        int chunkRange = range.get(), chunkRangeSq = chunkRange * chunkRange;
        int maxDistSq  = (chunkRange * 16) * (chunkRange * 16);
        String dimId = mc.world.getRegistryKey().getValue().toString();
        BlockPos playerPos = mc.player.getBlockPos();
        for (int cx = centerChunkX - chunkRange; cx <= centerChunkX + chunkRange; cx++) {
            for (int cz = centerChunkZ - chunkRange; cz <= centerChunkZ + chunkRange; cz++) {
                int dx = cx - centerChunkX, dz = cz - centerChunkZ;
                if (dx*dx + dz*dz > chunkRangeSq) continue;
                ChunkPos cp = new ChunkPos(cx, cz);
                if (scannedChunks.contains(cp)) continue;
                WorldChunk chunk = mc.world.getChunkManager().getChunk(cx, cz, ChunkStatus.FULL, false);
                if (chunk == null) continue;
                for (BlockEntity be : chunk.getBlockEntities().values()) {
                    BlockPos pos = be.getPos();
                    if (pos.getSquaredDistance(playerPos) > maxDistSq) continue;
                    PortalType type = classifyBlockEntity(be);
                    if (type != null && !portals.containsKey(pos)) {
                        portals.put(pos, type); portalsDirty = true;
                        processNewDiscovery(pos, type, dimId);
                    }
                }
            }
        }
    }

    private void scanNewChunks(int centerChunkX, int centerChunkZ) {
        int r = range.get(), rSq = r * r, scanned = 0;
        outer:
        for (int d = 0; d <= r; d++) {
            for (int x = -d; x <= d; x++) {
                for (int side = 0; side < 2; side++) {
                    int z = (side == 0) ? -d : d;
                    if (processChunk(centerChunkX+x, centerChunkZ+z, rSq, centerChunkX, centerChunkZ))
                        if (++scanned >= CHUNK_SCAN_LIMIT_PER_TICK) break outer;
                }
            }
            for (int z = -d+1; z < d; z++) {
                for (int side = 0; side < 2; side++) {
                    int x = (side == 0) ? -d : d;
                    if (processChunk(centerChunkX+x, centerChunkZ+z, rSq, centerChunkX, centerChunkZ))
                        if (++scanned >= CHUNK_SCAN_LIMIT_PER_TICK) break outer;
                }
            }
        }
    }

    private boolean processChunk(int cx, int cz, int rSq, int centerChunkX, int centerChunkZ) {
        int dx = cx - centerChunkX, dz = cz - centerChunkZ;
        if (dx*dx + dz*dz > rSq) return false;
        ChunkPos cp = new ChunkPos(cx, cz);
        if (scannedChunks.contains(cp)) return false;
        if (!mc.world.getChunkManager().isChunkLoaded(cx, cz)) return false;
        scanChunk(mc.world.getChunk(cx, cz));
        scannedChunks.add(cp);
        return true;
    }

    private void scanChunk(WorldChunk chunk) {
        String dimId = mc.world.getRegistryKey().getValue().toString();
        ChunkSection[] sections = chunk.getSectionArray();
        for (int i = 0; i < sections.length; i++) {
            ChunkSection section = sections[i];
            if (section == null || section.isEmpty()) continue;
            int sectionMinY = (chunk.getBottomSectionCoord() + i) * 16;
            for (int x = 0; x < 16; x++) for (int y = 0; y < 16; y++) for (int z = 0; z < 16; z++) {
                PortalType type = classifyBlock(section.getBlockState(x, y, z).getBlock());
                if (type == null) continue;
                BlockPos pos = new BlockPos((chunk.getPos().x << 4)+x, sectionMinY+y, (chunk.getPos().z << 4)+z);
                if (!portals.containsKey(pos)) {
                    portals.put(pos, type); portalsDirty = true;
                    processNewDiscovery(pos, type, dimId);
                }
            }
        }
    }

    private PortalType classifyBlock(Block block) {
        if (scanNetherPortals.get() && block == Blocks.NETHER_PORTAL) return PortalType.NETHER;
        return null;
    }

    private PortalType classifyBlockEntity(BlockEntity be) {
        if (scanEndGateways.get() && be instanceof EndGatewayBlockEntity) return PortalType.END_GATEWAY;
        if (scanEndPortals.get()  && be instanceof EndPortalBlockEntity)  return PortalType.END_PORTAL;
        return null;
    }

    private boolean isTrackedPortalBlock(Block block) {
        return block == Blocks.NETHER_PORTAL || block == Blocks.END_PORTAL || block == Blocks.END_GATEWAY;
    }

    // ── Discovery ──────────────────────────────────────────────────
    private void processNewDiscovery(BlockPos pos, PortalType type, String dimensionId) {
        if (autoMarkRange.get() <= 0 || mc.player == null) return;
        if (type != PortalType.NETHER) return;
        if (dimensionId.equals("minecraft:overworld") && !trackOverworld.get()) return;
        if (pos.getSquaredDistance(mc.player.getPos()) > (double) autoMarkRange.get() * autoMarkRange.get()) return;
        if (exclusionTimer > 0 && entryPortalPos != null
                && pos.getSquaredDistance(entryPortalPos) <= ENTRY_EXCLUSION_RADIUS_SQ) return;
        boolean added = createdPortals.add(pos);
        if (added) { portalsDirty = true; if (xaeroIntegration.get()) tryWriteXaeroWaypoint(pos, type, dimensionId); }
    }

    private void tryWriteXaeroWaypoint(BlockPos pos, PortalType type, String dimensionId) {
        String name = XaerosWaypointHelper.PORTAL_TAG + " " + type.getDisplayName()
            + " (" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
        int color = switch (type) {
            case NETHER      -> XaerosWaypointHelper.COLOR_PURPLE;
            case END_PORTAL  -> XaerosWaypointHelper.COLOR_LIME;
            case END_GATEWAY -> XaerosWaypointHelper.COLOR_CYAN;
        };
        boolean written = XaerosWaypointHelper.addWaypoint(pos, name, dimensionId, color, WAYPOINT_DEDUP_RADIUS);
        if (written) {
            WaypointEntry synth = new WaypointEntry(
                "waypoint:" + name + ":PT:" + pos.getX() + ":" + pos.getY() + ":" + pos.getZ()
                    + ":" + color + ":false:0:gui.xaero_default:0.0:false",
                name, pos.getX(), pos.getY(), pos.getZ(), color);
            xaeroTrackedPortals.put(pos, synth);
        }
    }

    // ── Grouping ───────────────────────────────────────────────────

    /**
     * TWO-SCAN CORNER CHECK — the sole size-classification method.
     *
     * Scan 1 (already done by caller): the flood-fill that produced {@code component}
     * gives us the set of NETHER_PORTAL air blocks and their bounding box.
     *
     * Scan 2 (this method): derive the portal plane and check the 4 corners of
     * the bounding rectangle for obsidian.
     *
     * Portal orientation:
     *   - X-axis portal (portal blocks share the same X, vary in Y and Z):
     *       corners are at (minX, minY-1, minZ-1), (minX, minY-1, maxZ+1),
     *                      (minX, maxY+1, minZ-1), (minX, maxY+1, maxZ+1)
     *   - Z-axis portal (portal blocks vary in X, share the same Z):
     *       corners are at (minX-1, minY-1, minZ), (maxX+1, minY-1, minZ),
     *                      (minX-1, maxY+1, minZ), (maxX+1, maxY+1, minZ)
     *
     * We test for obsidian at all 4 derived corner positions.
     * ALL 4 present → exit portal (isFullSize = true).
     * Any corner missing → custom portal (isFullSize = false).
     *
     * @param component the set of portal-air blocks in this structure
     * @return true if obsidian is present at all 4 frame corners, false otherwise
     */
    private boolean hasObsidianOnAllCorners(Set<BlockPos> component) {
        if (mc.world == null || component.isEmpty()) return false;

        // Compute bounding box of the portal-air blocks.
        int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : component) {
            if (pos.getX() < minX) minX = pos.getX();
            if (pos.getX() > maxX) maxX = pos.getX();
            if (pos.getY() < minY) minY = pos.getY();
            if (pos.getY() > maxY) maxY = pos.getY();
            if (pos.getZ() < minZ) minZ = pos.getZ();
            if (pos.getZ() > maxZ) maxZ = pos.getZ();
        }

        // Determine portal axis.
        // Nether portals are either in the X-Z plane (face North/South, blocks
        // span X & Y and are all at the same Z) or face East/West (blocks span
        // Z & Y at the same X).  We identify the axis by which horizontal
        // dimension is flat (span == 0).
        boolean flatX = (minX == maxX); // all blocks at the same X → East/West portal
        boolean flatZ = (minZ == maxZ); // all blocks at the same Z → North/South portal

        BlockPos[] corners;
        if (flatZ) {
            // North/South portal — frame corners are at the 4 XY extremes of the same Z plane.
            // The obsidian corner blocks sit one step below minY, one step above maxY,
            // one step left of minX, and one step right of maxX, all at the portal's Z.
            corners = new BlockPos[]{
                new BlockPos(minX - 1, minY - 1, minZ),
                new BlockPos(maxX + 1, minY - 1, minZ),
                new BlockPos(minX - 1, maxY + 1, minZ),
                new BlockPos(maxX + 1, maxY + 1, minZ)
            };
        } else if (flatX) {
            // East/West portal — frame corners are at the 4 ZY extremes of the same X plane.
            corners = new BlockPos[]{
                new BlockPos(minX, minY - 1, minZ - 1),
                new BlockPos(minX, minY - 1, maxZ + 1),
                new BlockPos(minX, maxY + 1, minZ - 1),
                new BlockPos(minX, maxY + 1, maxZ + 1)
            };
        } else {
            // Ambiguous / multi-axis (shouldn't happen with vanilla portals).
            // Fall back to treating it as a full-size portal to avoid false custom tags.
            return true;
        }

        for (BlockPos corner : corners) {
            if (!mc.world.getBlockState(corner).isOf(Blocks.OBSIDIAN)) return false;
        }
        return true;
    }

    /**
     * Returns the stable anchor for a component: the block with the lowest Y,
     * breaking ties by lowest X, then lowest Z. This is geometrically stable
     * because portal blocks exist at fixed Y levels — new blocks arriving at
     * chunk load edges cannot undercut the Y minimum.
     */
    private BlockPos componentAnchor(Set<BlockPos> component) {
        BlockPos anchor = null;
        for (BlockPos pos : component) {
            if (anchor == null
                    || pos.getY() < anchor.getY()
                    || (pos.getY() == anchor.getY() && pos.getX() < anchor.getX())
                    || (pos.getY() == anchor.getY() && pos.getX() == anchor.getX() && pos.getZ() < anchor.getZ())) {
                anchor = pos;
            }
        }
        return anchor;
    }

    /**
     * Returns true only if every chunk that contains at least one block of the
     * component is present in scannedChunks. When this returns false the corner
     * scan is deferred — we might be missing some portal blocks and therefore
     * have the wrong bounding box.
     */
    private boolean componentChunksFullyScanned(Set<BlockPos> component) {
        for (BlockPos pos : component) {
            if (!scannedChunks.contains(new ChunkPos(pos))) return false;
        }
        return true;
    }

    /**
     * Rebuilds the structure map from the current portal block set.
     *
     * Size classification uses the TWO-SCAN CORNER CHECK:
     *   Pass 1 — flood-fill builds each component and its bounding box.
     *   Pass 2 — {@link #hasObsidianOnAllCorners} checks exactly 4 positions.
     *
     * Classification is deferred (kept at the conservative full-size default)
     * until BOTH of:
     *   • every chunk in the component is fully scanned, AND
     *   • dimensionChangeCooldown == 0 (world data settled after a transition).
     *
     * Additionally, the entry portal area is excluded while exclusionTimer > 0
     * to avoid classifying a portal before its obsidian has propagated to the
     * client.
     *
     * Once confirmed, the result is cached in sizeConfirmedPortals AND
     * crossDimensionSizeCache so it survives future dimension changes without
     * re-running the scan.
     *
     * Stale entries are evicted by retainAll(activeAnchors) before the
     * structure map is updated, eliminating any overlap window between an old
     * anchor's cached value and a new anchor's fresh default.
     */
    private void groupPortals() {
        Set<BlockPos> visited       = new HashSet<>();
        Set<BlockPos> activeAnchors = new HashSet<>();

        pendingSizeRecheck.clear();

        for (BlockPos startPos : portals.keySet()) {
            if (visited.contains(startPos)) continue;
            PortalType type = portals.get(startPos);
            if (type == null) continue;

            Set<BlockPos>   component    = new HashSet<>();
            Queue<BlockPos> queue        = new LinkedList<>();
            Box             structureBox = new Box(startPos);
            boolean         isCreated    = false;

            queue.add(startPos); visited.add(startPos);
            while (!queue.isEmpty()) {
                BlockPos current = queue.poll();
                component.add(current);
                if (createdPortals.contains(current)) isCreated = true;
                for (Direction dir : Direction.values()) {
                    BlockPos neighbor = current.offset(dir);
                    if (portals.get(neighbor) == type && visited.add(neighbor)) {
                        queue.add(neighbor);
                        structureBox = structureBox.union(new Box(neighbor));
                    }
                }
            }
            if (component.isEmpty()) continue;

            BlockPos anchor = componentAnchor(component);
            activeAnchors.add(anchor);

            // ── TWO-SCAN SIZE CLASSIFICATION (Nether portals only) ──────────
            // Default to PENDING — the portal will be in the structure map but
            // the renderer skips PENDING portals entirely, so no colour is shown
            // until we have a confirmed answer. This eliminates the flash.
            SizeState sizeState = (type == PortalType.NETHER) ? SizeState.PENDING : SizeState.EXIT;

            if (type == PortalType.NETHER) {
                Boolean confirmed = sizeConfirmedPortals.get(anchor);
                if (confirmed != null) {
                    // Already classified in a previous pass.
                    sizeState = confirmed ? SizeState.EXIT : SizeState.CUSTOM;
                } else {
                    // Check the cross-dimension persistent cache first.
                    String crossKey = lastDimension + ":" + anchor.getX() + "," + anchor.getY() + "," + anchor.getZ();
                    Boolean crossCached = crossDimensionSizeCache.get(crossKey);
                    if (crossCached != null) {
                        sizeState = crossCached ? SizeState.EXIT : SizeState.CUSTOM;
                        sizeConfirmedPortals.put(anchor, crossCached);
                    } else {
                        // Gate classification on chunk readiness AND world settle.
                        boolean chunkReady   = componentChunksFullyScanned(component);
                        boolean worldSettled = dimensionChangeCooldown <= 0;

                        // Also defer if this component overlaps the entry portal exclusion zone.
                        boolean isEntryPortal = exclusionTimer > 0 && entryPortalPos != null
                            && component.stream().anyMatch(
                                p -> p.getSquaredDistance(entryPortalPos) <= ENTRY_EXCLUSION_RADIUS_SQ);

                        if (chunkReady && worldSettled && !isEntryPortal) {
                            // SCAN 2: corner obsidian check — authoritative, no fallback.
                            boolean allCorners = hasObsidianOnAllCorners(component);
                            sizeState = allCorners ? SizeState.EXIT : SizeState.CUSTOM;
                            sizeConfirmedPortals.put(anchor, allCorners);
                            crossDimensionSizeCache.put(crossKey, allCorners);
                        } else {
                            // Not ready — stay PENDING and schedule a recheck next pass.
                            pendingSizeRecheck.add(anchor);
                            portalsDirty = true;
                        }
                    }
                }
            }

            portalStructureMap.put(anchor,
                new PortalStructure(structureBox.expand(0.02), component, isCreated, sizeState, type));

            if (isCreated && showCreatedCount.get()) {
                String id = String.format("%s_%.1f_%.1f_%.1f",
                    type.name(), structureBox.minX, structureBox.minY, structureBox.minZ);
                if (notifiedStructures.add(id)) {
                    totalCreated++;
                    String sizeTag = (type == PortalType.NETHER && sizeState != SizeState.PENDING)
                        ? (sizeState == SizeState.EXIT ? " §8[Exit portal]" : " §8[Custom]")
                        : "";
                    sendMessage("§aCreated Portal #" + totalCreated
                        + " §7(" + type.getDisplayName() + ")" + sizeTag);
                }
            }
        }

        // Evict stale classification entries BEFORE updating the structure map.
        // This closes the window where an old anchor's cached value could coexist
        // with the new anchor's conservative default and produce two overlapping
        // highlight boxes of different colours.
        sizeConfirmedPortals.keySet().retainAll(activeAnchors);
        pendingSizeRecheck.retainAll(activeAnchors);

        // Remove render entries for anchors that are no longer active.
        portalStructureMap.keySet().retainAll(activeAnchors);

        if (!pendingSizeRecheck.isEmpty()) portalsDirty = true;
    }

    // ── Cleanup ────────────────────────────────────────────────────
    private void cleanupDistantPortals() {
        if (mc.player == null) return;
        BlockPos playerPos  = mc.player.getBlockPos();
        int      renderDist = range.get() * 16;
        double   distSq     = (double)(renderDist + 64) * (renderDist + 64);
        if (portals.entrySet().removeIf(e -> playerPos.getSquaredDistance(e.getKey()) > distSq)) {
            portalsDirty = true;
            sizeConfirmedPortals.keySet().removeIf(anchor -> !portalStructureMap.containsKey(anchor));
            pendingSizeRecheck.removeIf(anchor -> !portalStructureMap.containsKey(anchor));
        }
    }

    private void cleanupTrackedData() {
        if (mc.player == null) return;
        BlockPos playerPos = mc.player.getBlockPos();
        int dist = range.get() * 16 + 32;
        createdPortals.removeIf(pos -> pos.getSquaredDistance(playerPos) > (double) dist * dist);
    }

    private void cleanupDistantChunks(int centerChunkX, int centerChunkZ) {
        int r = range.get(), rSq = r * r;
        scannedChunks.removeIf(cp -> {
            int dx = cp.x - centerChunkX, dz = cp.z - centerChunkZ;
            return dx*dx + dz*dz > rSq;
        });
    }

    // ── Block Update ───────────────────────────────────────────────
    @EventHandler
    private void onBlockUpdate(BlockUpdateEvent event) {
        if (mc.player == null || mc.world == null) return;
        double threshold = range.get() * 16.0 + 32;
        if (event.pos.getSquaredDistance(mc.player.getPos()) > threshold * threshold) return;
        boolean wasPortal = isTrackedPortalBlock(event.oldState.getBlock());
        boolean isPortal  = isTrackedPortalBlock(event.newState.getBlock());

        // Also invalidate classification if an obsidian block near a known portal
        // changed — a corner block being removed/added changes the classification.
        boolean wasObsidian = event.oldState.isOf(Blocks.OBSIDIAN);
        boolean isObsidian  = event.newState.isOf(Blocks.OBSIDIAN);
        boolean obsidianChanged = wasObsidian != isObsidian;

        if (!wasPortal && !isPortal && !obsidianChanged) return;

        ChunkPos cp = new ChunkPos(event.pos);
        dirtyChunks.add(cp); scannedChunks.remove(cp);

        if (obsidianChanged) {
            // An obsidian block changed near a portal — invalidate any cached
            // corner classifications whose component chunks touch this chunk so
            // they are re-evaluated on the next groupPortals() pass.
            sizeConfirmedPortals.entrySet().removeIf(entry -> {
                // We don't have direct component membership here, so we use
                // proximity: if the anchor's chunk neighbours the dirty chunk,
                // evict its classification to force a fresh corner scan.
                ChunkPos anchorChunk = new ChunkPos(entry.getKey());
                int dx = Math.abs(anchorChunk.x - cp.x);
                int dz = Math.abs(anchorChunk.z - cp.z);
                return dx <= 1 && dz <= 1;
            });
            crossDimensionSizeCache.entrySet().removeIf(e -> {
                // Parse out the coordinates from "dim:x,y,z"
                String[] parts = e.getKey().split(":");
                if (parts.length < 2) return false;
                String[] coords = parts[parts.length - 1].split(",");
                if (coords.length < 3) return false;
                try {
                    int ax = Integer.parseInt(coords[0]) >> 4;
                    int az = Integer.parseInt(coords[2]) >> 4;
                    return Math.abs(ax - cp.x) <= 1 && Math.abs(az - cp.z) <= 1;
                } catch (NumberFormatException ex) { return false; }
            });
            portalsDirty = true;
        }

        if (!isPortal) {
            portals.remove(event.pos); portalsDirty = true;
            sizeConfirmedPortals.keySet().removeIf(anchor -> !portalStructureMap.containsKey(anchor));
            pendingSizeRecheck.remove(event.pos);
            if (xaeroIntegration.get()) {
                WaypointEntry entry = xaeroTrackedPortals.remove(event.pos);
                if (entry != null) { XaeroPortalBridge.unwatch(event.pos); confirmPortalRemoved(event.pos, entry, lastDimension); }
            }
        }
    }

    // ── Render ─────────────────────────────────────────────────────
    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.player == null || mc.world == null) return;

        List<PortalStructure> snapshot = new ArrayList<>(portalStructureMap.values());
        List<BeamData>        beamsToRender = new ArrayList<>();

        PortalStructure nearest = null;
        if (showBeam.get() && onlyNearestBeam.get()) {
            double minSq = Double.MAX_VALUE;
            for (PortalStructure structure : snapshot) {
                if (onlyShowCreated.get() && !structure.isCreated) continue;
                if (structure.type == PortalType.NETHER && !structure.isClassified()) continue;
                double cx = (structure.boundingBox.minX + structure.boundingBox.maxX) * 0.5;
                double cy = (structure.boundingBox.minY + structure.boundingBox.maxY) * 0.5;
                double cz = (structure.boundingBox.minZ + structure.boundingBox.maxZ) * 0.5;
                double sq = mc.player.squaredDistanceTo(cx, cy, cz);
                if (sq < minSq) { minSq = sq; nearest = structure; }
            }
        }

        for (PortalStructure structure : snapshot) {
            if (onlyShowCreated.get() && !structure.isCreated) continue;
            // Skip portals whose size classification is not yet confirmed — this
            // prevents the exit→custom colour flash on dimension entry.
            if (structure.type == PortalType.NETHER && !structure.isClassified()) continue;

            SettingColor color = getSettingColor(structure.type, structure.isFullSize());
            if (color == null) continue;

            if (highlightStyle.get() == HighlightStyle.SPECTRAL) {
                renderSpectral(event, structure, color);
            } else {
                if (highlightFrame.get() && structure.type == PortalType.NETHER) {
                    renderNetherFrame(event, structure, color);
                } else {
                    renderGlowLayers(event, structure.boundingBox, color);
                    event.renderer.box(structure.boundingBox, withAlpha(color, 0), color, shapeMode.get(), 0);
                }
            }

            if (showBeam.get() && (!onlyNearestBeam.get() || structure == nearest))
                beamsToRender.add(new BeamData(structure.boundingBox, color));
        }

        if (!beamsToRender.isEmpty()) renderBeams(event, beamsToRender);
    }

    // ── Beam Dispatch ──────────────────────────────────────────────
    private void renderBeams(Render3DEvent event, List<BeamData> beams) {
        if (mergeBeams.get()) {
            List<BeamData> merged = new ArrayList<>();
            double distSq = Math.pow(mergeDistance.get(), 2);
            for (BeamData beam : beams) {
                boolean skip = false;
                double bx = (beam.box.minX + beam.box.maxX) / 2.0;
                double bz = (beam.box.minZ + beam.box.maxZ) / 2.0;
                for (BeamData m : merged) {
                    double mx = (m.box.minX + m.box.maxX) / 2.0;
                    double mz = (m.box.minZ + m.box.maxZ) / 2.0;
                    if (Math.pow(bx - mx, 2) + Math.pow(bz - mz, 2) <= distSq) { skip = true; break; }
                }
                if (!skip) merged.add(beam);
            }
            beams = merged;
        }

        for (BeamData beam : beams) {
            if (beamStyle.get() == BeamStyle.GUARDIAN)
                renderGuardianBeam(event, beam.box, beam.color);
            else
                renderBoxBeam(event, beam.box, beam.color);
        }
    }

    // ── Box Beam ───────────────────────────────────────────────────
    private void renderBoxBeam(Render3DEvent event, Box anchorBox, SettingColor color) {
        double beamSize = beamWidth.get() / 100.0;
        double centerX  = (anchorBox.minX + anchorBox.maxX) / 2.0;
        double centerZ  = (anchorBox.minZ + anchorBox.maxZ) / 2.0;
        int worldBot = mc.world.getBottomY(), worldTop = worldBot + mc.world.getHeight();
        Box beamBox = new Box(
            centerX - beamSize, worldBot, centerZ - beamSize,
            centerX + beamSize, worldTop, centerZ + beamSize);
        renderGlowLayers(event, beamBox, color);
        event.renderer.box(beamBox, withAlpha(color, 60), color, ShapeMode.Both, 0);
    }

    // ── Guardian Beam ──────────────────────────────────────────────
    private void renderGuardianBeam(Render3DEvent event, Box anchorBox, SettingColor color) {
        if (mc.world == null) return;

        double cx       = (anchorBox.minX + anchorBox.maxX) / 2.0;
        double cz       = (anchorBox.minZ + anchorBox.maxZ) / 2.0;
        int    worldBot = mc.world.getBottomY();
        int    worldTop = worldBot + mc.world.getHeight();

        double radius  = guardianRadius.get();
        int    strands = guardianStrands.get();
        double speed   = guardianSpinSpeed.get();

        double period      = 6000.0 / speed;
        double rotationRad = (System.currentTimeMillis() % (long) period) / period * Math.PI * 2.0;

        double strandHalf  = Math.max(0.005, radius * 0.15);
        int    strandAlpha = guardianStrandAlpha.get();

        for (int i = 0; i < strands; i++) {
            double angle = rotationRad + (Math.PI * 2.0 / strands) * i;
            double cos   = Math.cos(angle);
            double sin   = Math.sin(angle);

            double scx = cx + cos * radius;
            double scz = cz + sin * radius;

            Box strandBox = new Box(
                scx - strandHalf, worldBot, scz - strandHalf,
                scx + strandHalf, worldTop, scz + strandHalf
            );
            event.renderer.box(strandBox,
                withAlpha(color, strandAlpha / 2),
                withAlpha(color, strandAlpha),
                ShapeMode.Both, 0);
        }

        int coreAlpha = guardianCoreAlpha.get();
        if (coreAlpha > 0) {
            double coreHalf = strandHalf * 1.5;
            Box coreBox = new Box(
                cx - coreHalf, worldBot, cz - coreHalf,
                cx + coreHalf, worldTop, cz + coreHalf
            );
            event.renderer.box(coreBox,
                withAlpha(color, coreAlpha),
                withAlpha(color, Math.min(255, coreAlpha + 60)),
                ShapeMode.Both, 0);
        }

        if (guardianGlow.get()) {
            double glowR = guardianGlowRadius.get();
            for (int ring = 1; ring <= 3; ring++) {
                double expansion = glowR * ring * 0.5;
                int    alpha     = Math.max(3, 28 / (ring * ring));
                Box bloomBox = new Box(
                    cx - radius - expansion, worldBot, cz - radius - expansion,
                    cx + radius + expansion, worldTop, cz + radius + expansion
                );
                event.renderer.box(bloomBox,
                    withAlpha(color, alpha),
                    withAlpha(color, 0),
                    ShapeMode.Sides, 0);
            }
        }
    }

    // ── Spectral ───────────────────────────────────────────────────
    private void renderSpectral(Render3DEvent event, PortalStructure structure, SettingColor color) {
        double expand    = spectralExpand.get();
        Box    renderBox = structure.boundingBox.expand(expand);

        int lineAlpha = spectralLineAlpha.get();
        int fillAlpha = spectralFillAlpha.get();
        if (spectralPulse.get()) {
            double pulse = 0.6 + 0.4 * (0.5 + 0.5 * Math.sin(System.currentTimeMillis() / 750.0 * Math.PI));
            lineAlpha = (int)(lineAlpha * pulse);
            fillAlpha = (int)(fillAlpha * pulse);
        }

        if (fillAlpha > 0)
            event.renderer.box(renderBox, withAlpha(color, fillAlpha), withAlpha(color, 0), ShapeMode.Sides, 0);

        event.renderer.box(renderBox, withAlpha(color, 0), withAlpha(color, lineAlpha), ShapeMode.Lines, 0);

        if (highlightFrame.get() && structure.type == PortalType.NETHER) {
            Box frameBox = buildFrameBox(structure);
            if (frameBox != null)
                event.renderer.box(frameBox.expand(expand), withAlpha(color, 0),
                    withAlpha(color, lineAlpha / 2), ShapeMode.Lines, 0);
        }
    }

    // ── Glow / Frame Helpers ───────────────────────────────────────
    private void renderNetherFrame(Render3DEvent event, PortalStructure structure, SettingColor color) {
        Box frameBox = buildFrameBox(structure);
        if (frameBox != null) {
            renderGlowLayers(event, frameBox, color);
            event.renderer.box(frameBox, withAlpha(color, 0), color, shapeMode.get(), 0);
        }
        renderGlowLayers(event, structure.boundingBox, color);
        event.renderer.box(structure.boundingBox, withAlpha(color, 0), color, shapeMode.get(), 0);
    }

    private Box buildFrameBox(PortalStructure structure) {
        Box frameBox = null;
        for (BlockPos portalPos : structure.portalBlocks) {
            for (Direction dir : Direction.values()) {
                BlockPos neighborPos = portalPos.offset(dir);
                if (structure.portalBlocks.contains(neighborPos)) continue;
                if (mc.world.getBlockState(neighborPos).isOf(Blocks.OBSIDIAN)) {
                    Box nb = new Box(neighborPos);
                    frameBox = (frameBox == null) ? nb : frameBox.union(nb);
                }
            }
        }
        return frameBox != null ? frameBox.expand(0.02) : null;
    }

    private void renderGlowLayers(Render3DEvent event, Box box, SettingColor color) {
        int layers = glowLayers.get(); double spread = glowSpread.get(); int baseAlpha = glowBaseAlpha.get();
        for (int i = layers; i >= 1; i--) {
            int layerAlpha = Math.max(4, (int)(baseAlpha * (1.0 - (double)(i-1) / layers)));
            event.renderer.box(box.expand(spread * i), withAlpha(color, layerAlpha),
                withAlpha(color, 0), ShapeMode.Sides, 0);
        }
    }

    // ── Color Helpers ──────────────────────────────────────────────
    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    private SettingColor getSettingColor(PortalType type, boolean isFullSize) {
        if (dynamicColors.get()) {
            float baseHue = switch (type) {
                case NETHER      -> isFullSize ? 0.78f : 0.08f;
                case END_PORTAL  -> 0.333f;
                case END_GATEWAY -> 0.667f;
            };
            float hue = (baseHue + (System.currentTimeMillis() % 3000) / 3000f) % 1f;
            int rgb = java.awt.Color.HSBtoRGB(hue, 0.8f, 1.0f);
            return new SettingColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF, 255);
        }
        return switch (type) {
            case NETHER -> (differentiatePortalSizes.get() && !isFullSize)
                ? netherColorCustom.get()
                : netherColorFull.get();
            case END_PORTAL  -> endPortalColor.get();
            case END_GATEWAY -> endGatewayColor.get();
        };
    }

    // ── Setting Handlers ───────────────────────────────────────────
    private void handleReset(boolean value) {
        if (!value) return;
        int old = totalCreated; totalCreated = 0;
        createdPortals.clear(); notifiedStructures.clear();
        xaeroTrackedPortals.clear(); XaeroPortalBridge.clearWatched();
        sessionStartTime = System.currentTimeMillis(); portalsDirty = true;
        info("Session cleared. Reset " + old + " created portal" + (old == 1 ? "" : "s") + ".");
        resetButton.set(false);
    }

    // ── Public API ─────────────────────────────────────────────────
    public boolean isPortalGuiEnabled() { return isActive(); }
    public int getTotalPortals()         { return portalStructureMap.size(); }
    public int getTotalCreated()         { return totalCreated; }

    public void markChunkDirty(ChunkPos chunkPos) {
        if (chunkPos == null) return;
        dirtyChunks.add(chunkPos); scannedChunks.remove(chunkPos);
    }

    // ── Utilities ──────────────────────────────────────────────────
    private void sendMessage(String message) {
        long now = System.currentTimeMillis();
        Long last = messageCooldowns.get(message);
        if (last == null || now - last > MESSAGE_COOLDOWN_MS) {
            super.info(message); messageCooldowns.put(message, now);
        }
    }

    private String getDimensionName(String id) {
        return switch (id) {
            case "minecraft:the_nether" -> "Nether";
            case "minecraft:overworld"  -> "Overworld";
            case "minecraft:the_end"    -> "End";
            default -> id;
        };
    }

    // ── Inner Types ────────────────────────────────────────────────
    private enum PortalType {
        NETHER("Nether Portal"), END_PORTAL("End Portal"), END_GATEWAY("End Gateway");
        private final String displayName;
        PortalType(String displayName) { this.displayName = displayName; }
        public String getDisplayName()  { return displayName; }
    }

    /**
     * Three-state size classification for Nether portals.
     *
     * PENDING  — corner scan not yet possible (chunks still loading or world
     *            still settling after a dimension change). The portal is
     *            registered in portalStructureMap but skipped by the renderer
     *            so the player never sees a wrong colour flash.
     * EXIT     — obsidian confirmed at all 4 frame corners.
     * CUSTOM   — at least one frame corner is missing obsidian.
     *
     * Non-Nether portals always use EXIT (the value is ignored by the colour
     * picker for End portals / gateways, but a concrete value avoids null).
     */
    private enum SizeState { PENDING, EXIT, CUSTOM }

    private static class PortalStructure {
        final Box           boundingBox;
        final Set<BlockPos> portalBlocks;
        final boolean       isCreated;
        final SizeState     sizeState;   // replaces boolean isFullSize
        final PortalType    type;

        PortalStructure(Box bb, Set<BlockPos> pb, boolean ic, SizeState ss, PortalType t) {
            this.boundingBox  = bb;
            this.portalBlocks = pb;
            this.isCreated    = ic;
            this.sizeState    = ss;
            this.type         = t;
        }

        /** Convenience — true only when classification is confirmed and portal is exit-sized. */
        boolean isFullSize() { return sizeState == SizeState.EXIT; }

        /** True when the portal has a confirmed classification and can be rendered. */
        boolean isClassified() { return sizeState != SizeState.PENDING; }
    }

    private record BeamData(Box box, SettingColor color) {}

    // ── Platform 9¾ ───────────────────────────────────────────────
    private void startPlatformBuild() {
        if (mc.player == null || mc.currentScreen != null) return;
        platformPositions.clear(); platformIndex = 0; platformTimer = 0;
        BlockPos center;
        if (mc.crosshairTarget instanceof BlockHitResult bhr && bhr.getType() == HitResult.Type.BLOCK)
            center = bhr.getBlockPos();
        else center = mc.player.getBlockPos().down();
        int r = 2;
        for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) {
            if (x == 0 && z == 0) continue;
            platformPositions.add(center.add(x, 0, z));
        }
        BlockPos finalCenter = center;
        platformPositions.sort(java.util.Comparator.comparingDouble(p -> p.getSquaredDistance(finalCenter)));
        info("Building Platform 9\u00BE...");
    }

    private void handlePlatformBuilding() {
        if (platformPositions.isEmpty()) return;
        if (platformIndex >= platformPositions.size()) { platformPositions.clear(); info("Platform 9\u00BE complete."); return; }
        if (platformTimer > 0) { platformTimer--; return; }
        if (!mc.player.getMainHandStack().isOf(Items.OBSIDIAN)) {
            FindItemResult obsidian = InvUtils.find(Items.OBSIDIAN);
            if (!obsidian.found()) { error("No obsidian found for platform."); platformPositions.clear(); return; }
            if (obsidian.isHotbar()) InvUtils.swap(obsidian.slot(), false);
            else InvUtils.move().from(obsidian.slot()).toHotbar(mc.player.getInventory().selectedSlot);
        }
        BlockPos target = platformPositions.get(platformIndex);
        if (!mc.world.getBlockState(target).isReplaceable()) { platformIndex++; platformTimer = 0; return; }
        if (placeBlock(target)) { platformIndex++; platformTimer = platformDelay.get(); }
        else platformIndex++;
    }

    private boolean placeBlock(BlockPos pos) {
        for (Direction side : Direction.values()) {
            BlockPos neighbor = pos.offset(side);
            if (!mc.world.getBlockState(neighbor).isReplaceable()) {
                Direction placeFace = side.getOpposite();
                Vec3d hitPos = Vec3d.ofCenter(neighbor).add(Vec3d.of(placeFace.getVector()).multiply(0.5));
                Rotations.rotate(Rotations.getYaw(hitPos), Rotations.getPitch(hitPos), () -> {
                    mc.interactionManager.interactBlock(mc.player, Hand.MAIN_HAND,
                        new BlockHitResult(hitPos, placeFace, neighbor, false));
                    mc.player.swingHand(Hand.MAIN_HAND);
                });
                return true;
            }
        }
        return false;
    }
}