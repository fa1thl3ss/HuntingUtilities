package com.example.addon.modules;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

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
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.block.Blocks;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraft.world.chunk.WorldChunk;

public class PearlPulse extends Module {

    // ═══════════════════════════════════════════════════════════════════════════
    // Setting Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgColumns = settings.createGroup("Bubble Columns");
    private final SettingGroup sgCap     = settings.createGroup("Cap Box");
    private final SettingGroup sgSound   = settings.createGroup("Sound");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — General
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Detection radius in blocks.")
        .defaultValue(64)
        .min(16)
        .sliderMax(128)
        .build()
    );

    private final Setting<Integer> scanInterval = sgGeneral.add(new IntSetting.Builder()
        .name("scan-interval")
        .description("Ticks between bubble column background scans. Higher = less CPU.")
        .defaultValue(40)
        .min(10)
        .sliderMax(200)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Bubble Columns (beam)
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> columnsEnabled = sgColumns.add(new BoolSetting.Builder()
        .name("highlight-columns")
        .description("Draw a glowing beam up through each bubble column.")
        .defaultValue(true)
        .build()
    );

    private final Setting<SettingColor> coreColor = sgColumns.add(new ColorSetting.Builder()
        .name("core-color")
        .description("Color of the bright inner beam.")
        .defaultValue(new SettingColor(180, 230, 255, 255))
        .visible(columnsEnabled::get)
        .build()
    );

    private final Setting<SettingColor> glowColor = sgColumns.add(new ColorSetting.Builder()
        .name("glow-color")
        .description("Color of the soft outer bloom. Keep alpha low (30–80) for best results.")
        .defaultValue(new SettingColor(0, 180, 255, 50))
        .visible(columnsEnabled::get)
        .build()
    );

    private final Setting<Double> coreWidth = sgColumns.add(new DoubleSetting.Builder()
        .name("core-width")
        .description("Half-width of the solid inner beam box in blocks.")
        .defaultValue(0.03)
        .min(0.005)
        .sliderMax(0.25)
        .visible(columnsEnabled::get)
        .build()
    );

    private final Setting<Double> glowSpread = sgColumns.add(new DoubleSetting.Builder()
        .name("glow-spread")
        .description("How far each bloom layer expands outward beyond the core (in blocks).")
        .defaultValue(0.08)
        .min(0.01)
        .sliderMax(0.5)
        .visible(columnsEnabled::get)
        .build()
    );

    private final Setting<Integer> glowLayers = sgColumns.add(new IntSetting.Builder()
        .name("glow-layers")
        .description("Number of bloom expansion layers.")
        .defaultValue(4)
        .min(1)
        .sliderMax(8)
        .visible(columnsEnabled::get)
        .build()
    );

    private final Setting<Integer> glowBaseAlpha = sgColumns.add(new IntSetting.Builder()
        .name("glow-base-alpha")
        .description("Alpha of the innermost glow layer (0–255). Outer layers fade to zero.")
        .defaultValue(50)
        .min(4)
        .sliderMax(150)
        .visible(columnsEnabled::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Cap Box
    // ═══════════════════════════════════════════════════════════════════════════

    public enum CapPosition { NONE, BOTTOM, TOP, BOTH }

    private final Setting<CapPosition> capPosition = sgCap.add(new EnumSetting.Builder<CapPosition>()
        .name("cap-position")
        .description("Where to draw the flat marker box: at the bottom of the column, " +
                     "the top (pearl position), both, or none.")
        .defaultValue(CapPosition.BOTTOM)
        .build()
    );

    private final Setting<SettingColor> capColor = sgCap.add(new ColorSetting.Builder()
        .name("cap-color")
        .description("Fill and outline color of the flat cap box.")
        .defaultValue(new SettingColor(0, 200, 255, 160))
        .visible(() -> capPosition.get() != CapPosition.NONE)
        .build()
    );

    private final Setting<Double> capSize = sgCap.add(new DoubleSetting.Builder()
        .name("cap-size")
        .description("Half-width of the cap box on the X/Z axes (in blocks).")
        .defaultValue(0.4)
        .min(0.05)
        .sliderMax(2.0)
        .visible(() -> capPosition.get() != CapPosition.NONE)
        .build()
    );

    private final Setting<Double> capThickness = sgCap.add(new DoubleSetting.Builder()
        .name("cap-thickness")
        .description("Height of the flat cap box (in blocks). Keep small for a 2D-ish disc.")
        .defaultValue(0.04)
        .min(0.01)
        .sliderMax(0.5)
        .visible(() -> capPosition.get() != CapPosition.NONE)
        .build()
    );

    private final Setting<ShapeMode> capShapeMode = sgCap.add(new EnumSetting.Builder<ShapeMode>()
        .name("cap-shape-mode")
        .description("Whether to render the cap as fill, outline, or both.")
        .defaultValue(ShapeMode.Both)
        .visible(() -> capPosition.get() != CapPosition.NONE)
        .build()
    );

    private final Setting<Boolean> capGlow = sgCap.add(new BoolSetting.Builder()
        .name("cap-glow")
        .description("Add the same bloom expansion layers to the cap box.")
        .defaultValue(true)
        .visible(() -> capPosition.get() != CapPosition.NONE)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Sound
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> soundEnabled = sgSound.add(new BoolSetting.Builder()
        .name("sound-ping")
        .description("Play a sound when a new stasis pearl is discovered.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> soundVolume = sgSound.add(new DoubleSetting.Builder()
        .name("volume")
        .defaultValue(1.0).min(0.1).sliderMax(2.0)
        .visible(soundEnabled::get)
        .build()
    );

    private final Setting<Double> soundPitch = sgSound.add(new DoubleSetting.Builder()
        .name("pitch")
        .defaultValue(1.8).min(0.5).sliderMax(2.0)
        .visible(soundEnabled::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════════

    private final AtomicReference<Map<String, Vec3d[]>> columnLines =
        new AtomicReference<>(Collections.emptyMap());

    private final Set<Integer> seenPearlIds = Collections.synchronizedSet(new HashSet<>());

    private final AtomicBoolean scanPending = new AtomicBoolean(false);
    private final AtomicBoolean pingQueued  = new AtomicBoolean(false);

    private int tickCounter = 0;

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════════

    public PearlPulse() {
        super(HuntingUtilities.CATEGORY, "PearlPulse",
            "Detects nearby stasis pearls sitting above bubble columns.");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Public accessors
    // ═══════════════════════════════════════════════════════════════════════════

    /** Returns the current detection range setting value. Used by PearlCounterHud. */
    public int getRange() {
        return range.get();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void onActivate() {
        columnLines.set(Collections.emptyMap());
        seenPearlIds.clear();
        pingQueued.set(false);
        scanPending.set(false);
        tickCounter = 0;
    }

    @Override
    public void onDeactivate() {
        columnLines.set(Collections.emptyMap());
        seenPearlIds.clear();
        pingQueued.set(false);
        scanPending.set(false);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tick
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;
        if (mc.world.getRegistryKey() == World.NETHER) return; // Stasis pearls don't exist in the Nether

        // Fire any queued sound ping on the main thread.
        if (pingQueued.compareAndSet(true, false) && soundEnabled.get()) {
            mc.getSoundManager().play(PositionedSoundInstance.master(
                SoundEvents.BLOCK_BEACON_ACTIVATE,
                soundPitch.get().floatValue(),
                soundVolume.get().floatValue()
            ));
        }

        // Ping only when a pearl is newly seen AND it is above a known column.
        Map<String, Vec3d[]> lines = columnLines.get();
        for (Entity e : mc.world.getEntities()) {
            if (e.getType() != EntityType.ENDER_PEARL) continue;
            if (mc.player.distanceTo(e) > range.get()) continue;

            int    px  = (int) Math.floor(e.getX());
            int    pz  = (int) Math.floor(e.getZ());
            String key = px + "," + pz;

            // Only count it as a new discovery if it is over a known column.
            if (lines.containsKey(key) && seenPearlIds.add(e.getId())) {
                pingQueued.set(true);
            }
        }

        // Trigger background column scan on interval.
        tickCounter++;
        if (tickCounter < scanInterval.get()) return;
        tickCounter = 0;

        if (!scanPending.compareAndSet(false, true)) return;

        final BlockPos origin   = mc.player.getBlockPos();
        final int      r        = range.get();
        final int      chunkR   = (r >> 4) + 1;
        final int      originCX = origin.getX() >> 4;
        final int      originCZ = origin.getZ() >> 4;

        final Map<Long, WorldChunk> chunkSnapshot = new HashMap<>();
        for (int cx = originCX - chunkR; cx <= originCX + chunkR; cx++) {
            for (int cz = originCZ - chunkR; cz <= originCZ + chunkR; cz++) {
                WorldChunk chunk = mc.world.getChunkManager().getWorldChunk(cx, cz);
                if (chunk != null) chunkSnapshot.put(ChunkPos.toLong(cx, cz), chunk);
            }
        }

        Thread.ofVirtual().name("PearlPulse-scan").start(() -> {
            try {
                runColumnScan(origin, r, chunkSnapshot);
            } finally {
                scanPending.set(false);
            }
        });
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Background column scan
    // ═══════════════════════════════════════════════════════════════════════════

    private void runColumnScan(BlockPos origin, int r, Map<Long, WorldChunk> chunks) {
        Map<String, int[]>   yExtents = new HashMap<>();
        Map<String, Vec3d[]> newLines = new LinkedHashMap<>();

        int minX = origin.getX() - r, maxX = origin.getX() + r;
        int minY = Math.max(origin.getY() - r, -64);
        int maxY = Math.min(origin.getY() + r, 320);
        int minZ = origin.getZ() - r, maxZ = origin.getZ() + r;

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                WorldChunk chunk = chunks.get(ChunkPos.toLong(x >> 4, z >> 4));
                if (chunk == null) continue;

                // Collect Y extents of bubble column blocks in this (x, z) stack.
                int lowestY  = Integer.MAX_VALUE;
                int highestY = Integer.MIN_VALUE;
                for (int y = minY; y <= maxY; y++) {
                    if (chunk.getBlockState(new BlockPos(x, y, z)).getBlock()
                            != Blocks.BUBBLE_COLUMN) continue;
                    if (y < lowestY)  lowestY  = y;
                    if (y > highestY) highestY = y;
                }

                if (lowestY == Integer.MAX_VALUE) continue;

                // Walk downward to find the true source block below the column.
                int srcY      = lowestY - 1;
                int srcYfloor = Math.max(srcY - 384, -64);
                while (srcY >= srcYfloor) {
                    if (chunk.getBlockState(new BlockPos(x, srcY, z)).getBlock()
                            == Blocks.BUBBLE_COLUMN) {
                        srcY--;
                    } else {
                        break;
                    }
                }

                // Accept only soul-sand sources (upward columns).
                if (chunk.getBlockState(new BlockPos(x, srcY, z)).getBlock()
                        != Blocks.SOUL_SAND) continue;

                String key = x + "," + z;
                int fx = x, fz = z, flo = lowestY, fhi = highestY;
                yExtents.compute(key, (k, e) -> {
                    if (e == null) return new int[]{ fx, flo, fhi, fz };
                    e[1] = Math.min(e[1], flo);
                    e[2] = Math.max(e[2], fhi);
                    return e;
                });
            }
        }

        for (Map.Entry<String, int[]> entry : yExtents.entrySet()) {
            int[]  e  = entry.getValue();
            double cx = e[0] + 0.5;
            double cz = e[3] + 0.5;
            newLines.put(entry.getKey(), new Vec3d[]{
                new Vec3d(cx, e[1],     cz),
                new Vec3d(cx, e[2] + 1, cz)
            });
        }

        columnLines.set(newLines);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Render
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onRender3D(Render3DEvent event) {
        if (mc.world == null || mc.player == null) return;

        boolean doBeam = columnsEnabled.get();
        CapPosition cap = capPosition.get();
        boolean doCap = cap != CapPosition.NONE;

        if (!doBeam && !doCap) return;

        int r = range.get();

        // Map each column key to the Y position of its stasis pearl (if any).
        Map<String, Double>  colKeyToPearlY = new HashMap<>();
        Map<String, Vec3d[]> lines          = columnLines.get();

        for (Entity e : mc.world.getEntities()) {
            if (e.getType() != EntityType.ENDER_PEARL) continue;
            if (mc.player.distanceTo(e) > r) continue;

            Vec3d  pos = e.getLerpedPos(mc.getRenderTickCounter().getTickDelta(true));
            int    px  = (int) Math.floor(e.getX());
            int    pz  = (int) Math.floor(e.getZ());
            String key = px + "," + pz;

            Vec3d[] line = lines.get(key);
            if (line != null && pos.y >= line[0].y) {
                colKeyToPearlY.put(key, pos.y);
            }
        }

        // Cache settings once per frame.
        SettingColor core      = coreColor.get();
        SettingColor glow      = glowColor.get();
        double       halfCore  = coreWidth.get();
        double       spread    = glowSpread.get();
        int          layers    = glowLayers.get();
        int          baseAlpha = glowBaseAlpha.get();

        SettingColor capCol   = capColor.get();
        double       capHalf  = capSize.get();
        double       capThick = capThickness.get();
        ShapeMode    capMode  = capShapeMode.get();
        boolean      capBloom = capGlow.get();

        for (Map.Entry<String, Vec3d[]> entry : lines.entrySet()) {
            Double pearlY = colKeyToPearlY.get(entry.getKey());

            // Only render columns that have a stasis pearl sitting above them.
            if (pearlY == null) continue;

            Vec3d[] line   = entry.getValue();
            double cx      = line[0].x;
            double cz      = line[0].z;
            double bottomY = line[0].y;
            double topY    = pearlY;

            // ── Beam ─────────────────────────────────────────────────────────
            if (doBeam) {
                drawGlowBeam(event, cx, bottomY, cz, topY,
                    core, glow, halfCore, spread, layers, baseAlpha);
            }

            // ── Cap boxes ────────────────────────────────────────────────────
            if (doCap) {
                boolean drawBottom = (cap == CapPosition.BOTTOM || cap == CapPosition.BOTH);
                boolean drawTop    = (cap == CapPosition.TOP    || cap == CapPosition.BOTH);

                if (drawBottom) drawCapBox(event, cx, bottomY, cz,
                    capHalf, capThick, capCol, capMode, capBloom, spread, layers, baseAlpha);

                if (drawTop)    drawCapBox(event, cx, topY, cz,
                    capHalf, capThick, capCol, capMode, capBloom, spread, layers, baseAlpha);
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Glow beam
    // ═══════════════════════════════════════════════════════════════════════════

    private void drawGlowBeam(Render3DEvent event,
                               double cx, double bottomY, double cz, double topY,
                               SettingColor core, SettingColor glow,
                               double halfCore, double spread, int layers, int baseAlpha) {
        // Bloom rings — sides only, fading outward.
        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            int    alpha     = Math.max(4, (int)(baseAlpha * (1.0 - (double)(i - 1) / layers)));
            event.renderer.box(
                new Box(cx - halfCore - expansion, bottomY, cz - halfCore - expansion,
                        cx + halfCore + expansion, topY,    cz + halfCore + expansion),
                withAlpha(glow, alpha), withAlpha(glow, 0),
                ShapeMode.Sides, 0);
        }

        // Solid core.
        event.renderer.box(
            new Box(cx - halfCore, bottomY, cz - halfCore,
                    cx + halfCore, topY,    cz + halfCore),
            withAlpha(core, 180), core,
            ShapeMode.Both, 0);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Flat cap box
    // ═══════════════════════════════════════════════════════════════════════════

    private void drawCapBox(Render3DEvent event,
                             double cx, double y, double cz,
                             double halfXZ, double halfY,
                             SettingColor color, ShapeMode mode,
                             boolean bloom, double spread, int layers, int baseAlpha) {
        double minY = y - halfY;
        double maxY = y + halfY;

        // Bloom rings — expand XZ only, keep Y fixed so the disc stays flat.
        if (bloom) {
            for (int i = layers; i >= 1; i--) {
                double expansion = spread * i;
                int    alpha     = Math.max(4, (int)(baseAlpha * (1.0 - (double)(i - 1) / layers)));
                event.renderer.box(
                    new Box(cx - halfXZ - expansion, minY, cz - halfXZ - expansion,
                            cx + halfXZ + expansion, maxY, cz + halfXZ + expansion),
                    withAlpha(color, alpha), withAlpha(color, 0),
                    ShapeMode.Sides, 0);
            }
        }

        // The cap disc itself.
        event.renderer.box(
            new Box(cx - halfXZ, minY, cz - halfXZ,
                    cx + halfXZ, maxY, cz + halfXZ),
            withAlpha(color, color.a),
            color,
            mode, 0);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }
}