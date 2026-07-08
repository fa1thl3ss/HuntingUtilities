package com.example.addon.modules;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import com.example.addon.HuntingUtilities;
import com.example.addon.utils.GlowingRegistry;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.renderer.ShapeMode;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EntityTypeListSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.WireframeEntityRenderer;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.Angerable;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.util.math.Box;

public class Illushine extends Module {

    // ═══════════════════════════════════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════════════════════════════════

    private enum MobCategory { PASSIVE, NEUTRAL, HOSTILE }

    /**
     * Wireframe — custom geometry outline drawn by WireframeEntityRenderer.
     * Spectral  — vanilla glowing outline (spectral arrow effect) driven by
     *             GlowingRegistry → existing EntityGlowingMixin pipeline.
     * Both modes render the bloom box-expand halo on top.
     */
    public enum HighlightMode {
        Wireframe("Wireframe"),
        Spectral("Spectral");

        private final String title;
        HighlightMode(String title) { this.title = title; }

        @Override public String toString() { return title; }
    }

    public enum CrosshairMode {
        None("None"),
        WhiteDot("White Dot"),
        Normal("Normal");

        private final String title;
        CrosshairMode(String title) { this.title = title; }

        @Override public String toString() { return title; }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Category override table
    //
    // Runtime instanceof checks used as the base classification:
    //   HostileEntity  → HOSTILE
    //   Angerable      → NEUTRAL
    //   PassiveEntity  → PASSIVE
    //
    // Entries here are ONLY needed when a mob would be mis-classified by those
    // checks, or when the correct category needs to be stated explicitly.
    //
    // Passive overrides:
    //   Fox        → PassiveEntity in code, correct by default. Explicit for clarity.
    //   Baby Piglin→ shares EntityType.PIGLIN with adult; handled in categorise()
    //                via isBaby() since EntityType alone cannot distinguish them.
    //
    // Neutral overrides (extend HostileEntity but are neutral toward players):
    //   Zombified Piglin, Enderman, Goat.
    //   Spider / Cave Spider are handled dynamically in categorise() — neutral
    //   during the day, hostile at night.
    //
    // Hostile overrides that need explicit entries:
    //   Piglin — extends HostileEntity, hostile by default. Listed explicitly
    //   to document Baby Piglin exception handled in categorise().
    //
    // Hostile overrides (do NOT extend HostileEntity):
    //   Ghast, Shulker, Phantom, Slime, Magma Cube, Hoglin.
    //   Piglin Brute extends HostileEntity correctly but is listed explicitly
    //   to document that it is always hostile (unlike regular Piglins).
    // ═══════════════════════════════════════════════════════════════════════════

    private static final Map<EntityType<?>, MobCategory> CATEGORY_OVERRIDES = new HashMap<>(Map.ofEntries(
        // ── Passive ──────────────────────────────────────────────────────────
        Map.entry(EntityType.FOX,              MobCategory.PASSIVE),

        // ── Neutral ──────────────────────────────────────────────────────────
        // PIGLIN: hostile toward players not wearing gold armour. Since we cannot
        // know whether the player is wearing gold, we treat them as hostile by default.
        // Baby Piglins share this EntityType but are passive — see categorise().
        Map.entry(EntityType.PIGLIN,           MobCategory.HOSTILE),
        // ZOMBIFIED_PIGLIN: neutral, only retaliates when attacked.
        Map.entry(EntityType.ZOMBIFIED_PIGLIN, MobCategory.NEUTRAL),
        // ENDERMAN: neutral unless looked at directly.
        Map.entry(EntityType.ENDERMAN,         MobCategory.NEUTRAL),
        // SPIDER / CAVE_SPIDER: handled dynamically in categorise() based on
        // time of day — neutral in daylight, hostile at night.
        // GOAT: charges players and mobs unprovoked; extends AnimalEntity (PassiveEntity).
        Map.entry(EntityType.GOAT,             MobCategory.NEUTRAL),

        // ── Hostile ──────────────────────────────────────────────────────────
        // PIGLIN_BRUTE: always hostile regardless of gold armour; extends HostileEntity.
        // Listed explicitly to distinguish from neutral adult Piglins.
        Map.entry(EntityType.PIGLIN_BRUTE,     MobCategory.HOSTILE),
        // These do NOT extend HostileEntity so the instanceof check misses them.
        Map.entry(EntityType.GHAST,            MobCategory.HOSTILE),
        Map.entry(EntityType.SHULKER,          MobCategory.HOSTILE),
        Map.entry(EntityType.PHANTOM,          MobCategory.HOSTILE),
        Map.entry(EntityType.SLIME,            MobCategory.HOSTILE),
        Map.entry(EntityType.MAGMA_CUBE,       MobCategory.HOSTILE),
        Map.entry(EntityType.HOGLIN,           MobCategory.HOSTILE)
    ));

    // ═══════════════════════════════════════════════════════════════════════════
    // Setting Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgGeneral   = settings.getDefaultGroup();
    private final SettingGroup sgCrosshair = settings.createGroup("Crosshair");
    private final SettingGroup sgPassive   = settings.createGroup("Passive");
    private final SettingGroup sgNeutral   = settings.createGroup("Neutral");
    private final SettingGroup sgHostile   = settings.createGroup("Hostile");
    private final SettingGroup sgGlow      = settings.createGroup("Glow");
    private final SettingGroup sgPlayer    = settings.createGroup("Player Scale");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — General
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<HighlightMode> highlightMode = sgGeneral.add(new EnumSetting.Builder<HighlightMode>()
        .name("highlight-mode")
        .description("How mobs are outlined. Wireframe draws custom geometry; Spectral uses the vanilla glow pipeline.")
        .defaultValue(HighlightMode.Wireframe)
        .build()
    );

    private final Setting<Integer> range = sgGeneral.add(new IntSetting.Builder()
        .name("range")
        .description("Range to highlight mobs.")
        .defaultValue(64).min(1).sliderMax(256)
        .build()
    );

    private final Setting<Set<EntityType<?>>> ignoredEntities = sgGeneral.add(new EntityTypeListSetting.Builder()
        .name("ignored-entities")
        .description("Entities to ignore.")
        .defaultValue(new HashSet<>())
        .build()
    );

    private final Setting<Double> outlineScale = sgGeneral.add(new DoubleSetting.Builder()
        .name("outline-scale")
        .description("Scale of the wireframe outline (Wireframe mode only).")
        .defaultValue(1.0).min(0.1).sliderMax(2.0)
        .visible(() -> highlightMode.get() == HighlightMode.Wireframe)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Crosshair
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<CrosshairMode> crosshairMode = sgCrosshair.add(new EnumSetting.Builder<CrosshairMode>()
        .name("crosshair-mode")
        .description("The crosshair style to display while Illushine is active.")
        .defaultValue(CrosshairMode.Normal)
        .build()
    );

    private final Setting<SettingColor> crosshairColor = sgCrosshair.add(new ColorSetting.Builder()
        .name("crosshair-color")
        .description("Color of the Normal crosshair lines.")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .visible(() -> crosshairMode.get() == CrosshairMode.Normal)
        .build()
    );

    private final Setting<Integer> crosshairSize = sgCrosshair.add(new IntSetting.Builder()
        .name("crosshair-size").description("Half-length of each crosshair arm in pixels.")
        .defaultValue(5).min(1).sliderMax(20)
        .visible(() -> crosshairMode.get() == CrosshairMode.Normal)
        .build()
    );

    private final Setting<Integer> crosshairGap = sgCrosshair.add(new IntSetting.Builder()
        .name("crosshair-gap").description("Gap (in pixels) between center and each arm.")
        .defaultValue(2).min(0).sliderMax(10)
        .visible(() -> crosshairMode.get() == CrosshairMode.Normal)
        .build()
    );

    private final Setting<Integer> crosshairThickness = sgCrosshair.add(new IntSetting.Builder()
        .name("crosshair-thickness").description("Thickness of the crosshair lines in pixels.")
        .defaultValue(1).min(1).sliderMax(5)
        .visible(() -> crosshairMode.get() == CrosshairMode.Normal)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Passive / Neutral / Hostile
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> highlightPassive = sgPassive.add(new BoolSetting.Builder()
        .name("highlight-passive").description("Highlight passive mobs.").defaultValue(true).build());

    private final Setting<SettingColor> passiveColor = sgPassive.add(new ColorSetting.Builder()
        .name("passive-color").description("Outline color for passive mobs.")
        .defaultValue(new SettingColor(0, 255, 100, 255)).visible(highlightPassive::get).build());

    private final Setting<Double> passiveScale = sgPassive.add(new DoubleSetting.Builder()
        .name("passive-scale").description("Visual scale for passive mobs.")
        .defaultValue(1.0).min(0.1).sliderMax(3.0).visible(highlightPassive::get).build());

    private final Setting<Boolean> highlightNeutral = sgNeutral.add(new BoolSetting.Builder()
        .name("highlight-neutral").description("Highlight neutral mobs.").defaultValue(true).build());

    private final Setting<SettingColor> neutralColor = sgNeutral.add(new ColorSetting.Builder()
        .name("neutral-color").description("Outline color for neutral mobs.")
        .defaultValue(new SettingColor(255, 200, 0, 255)).visible(highlightNeutral::get).build());

    private final Setting<Double> neutralScale = sgNeutral.add(new DoubleSetting.Builder()
        .name("neutral-scale").description("Visual scale for neutral mobs.")
        .defaultValue(1.0).min(0.1).sliderMax(3.0).visible(highlightNeutral::get).build());

    private final Setting<Boolean> highlightHostile = sgHostile.add(new BoolSetting.Builder()
        .name("highlight-hostile").description("Highlight hostile mobs.").defaultValue(true).build());

    private final Setting<SettingColor> hostileColor = sgHostile.add(new ColorSetting.Builder()
        .name("hostile-color").description("Outline color for hostile mobs.")
        .defaultValue(new SettingColor(255, 50, 50, 255)).visible(highlightHostile::get).build());

    private final Setting<Double> hostileScale = sgHostile.add(new DoubleSetting.Builder()
        .name("hostile-scale").description("Visual scale for hostile mobs.")
        .defaultValue(1.0).min(0.1).sliderMax(3.0).visible(highlightHostile::get).build());

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Glow (Wireframe mode only)
    // In Spectral mode the vanilla glow pipeline handles the outline itself;
    // bloom boxes on top would clash with it and look wrong.
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> glowEnabled = sgGlow.add(new BoolSetting.Builder()
        .name("glow")
        .description("Render a bloom halo around each mob (Wireframe mode only).")
        .defaultValue(true)
        .visible(() -> highlightMode.get() == HighlightMode.Wireframe)
        .build());

    private final Setting<Integer> glowLayers = sgGlow.add(new IntSetting.Builder()
        .name("glow-layers").description("Number of bloom layers.")
        .defaultValue(4).min(1).sliderMax(8)
        .visible(() -> highlightMode.get() == HighlightMode.Wireframe && glowEnabled.get())
        .build());

    private final Setting<Double> glowSpread = sgGlow.add(new DoubleSetting.Builder()
        .name("glow-spread").description("How far each bloom layer expands (blocks).")
        .defaultValue(0.05).min(0.01).sliderMax(0.2)
        .visible(() -> highlightMode.get() == HighlightMode.Wireframe && glowEnabled.get())
        .build());

    private final Setting<Integer> glowBaseAlpha = sgGlow.add(new IntSetting.Builder()
        .name("glow-base-alpha").description("Alpha of the innermost glow layer.")
        .defaultValue(60).min(10).sliderMax(150)
        .visible(() -> highlightMode.get() == HighlightMode.Wireframe && glowEnabled.get())
        .build());

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Player Scale
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Double> playerScale = sgPlayer.add(new DoubleSetting.Builder()
        .name("player-scale")
        .description("Visually scales your own player model and camera height.")
        .defaultValue(1.0).min(0.1).sliderMax(3.0)
        .build()
    );

    private final Setting<Boolean> scaleOtherPlayers = sgPlayer.add(new BoolSetting.Builder()
        .name("scale-other-players")
        .description("Apply a visual scale to other players.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Double> otherPlayerScale = sgPlayer.add(new DoubleSetting.Builder()
        .name("other-player-scale")
        .description("Visual scale applied to other players.")
        .defaultValue(1.0).min(0.1).sliderMax(3.0)
        .visible(scaleOtherPlayers::get)
        .build()
    );

    // DEBUG SETTING
    private final Setting<Boolean> debugPlayerScale = sgPlayer.add(new BoolSetting.Builder()
        .name("debug-player-scale")
        .description("Spams console logs to figure out why scaling isn't working.")
        .defaultValue(false)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════════

    private final Map<Integer, MobCategory> activelyOutlined = new HashMap<>();

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════════

    public Illushine() {
        super(HuntingUtilities.CATEGORY, "illushine",
            "Highlights mobs with a wireframe or spectral outline by hostility type.");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════════

    public CrosshairMode getCrosshairMode() { return crosshairMode.get(); }
    public double getPlayerScale() { return playerScale.get(); }
    public boolean getScaleOtherPlayers() { return scaleOtherPlayers.get(); }
    public double getOtherPlayerScale() { return otherPlayerScale.get(); }
    public boolean isDebugPlayerScale() { return debugPlayerScale.get(); }

    public double getMobScale(MobEntity mob) {
        MobCategory cat = categorise(mob);
        return switch (cat) {
            case PASSIVE -> passiveScale.get();
            case NEUTRAL -> neutralScale.get();
            case HOSTILE -> hostileScale.get();
        };
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void onActivate() {
        activelyOutlined.clear();
        // Do not clear the whole registry — other modules may own entries.
    }

    @Override
    public void onDeactivate() {
        // Remove only the entries this module registered.
        for (Integer id : activelyOutlined.keySet()) {
            GlowingRegistry.remove(id);
        }
        activelyOutlined.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tick — rebuild active set, sync GlowingRegistry for Spectral mode
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        boolean spectral = highlightMode.get() == HighlightMode.Spectral;

        // Build the new active set first, then atomically swap the registry.
        // This avoids the window between clear() and add() where isGlowing()
        // would return false and cause a flicker or missed frame.
        Map<Integer, MobCategory> newOutlined = new HashMap<>();

        for (Entity entity : mc.world.getEntities()) {
            if (!(entity instanceof MobEntity mob)) continue;
            if (ignoredEntities.get().contains(mob.getType())) continue;
            if (mc.player.distanceTo(mob) > range.get()) continue;

            MobCategory category = categorise(mob);
            boolean shouldHighlight = switch (category) {
                case PASSIVE -> highlightPassive.get();
                case NEUTRAL -> highlightNeutral.get();
                case HOSTILE -> highlightHostile.get();
            };
            if (!shouldHighlight) continue;

            newOutlined.put(mob.getId(), category);
        }

        if (spectral) {
            // Remove entries for IDs that were active last tick but aren't now.
            // Do this BEFORE updating activelyOutlined so we still have the old set.
            for (Integer id : activelyOutlined.keySet()) {
                if (!newOutlined.containsKey(id)) GlowingRegistry.remove(id);
            }
            // Add/update all currently active entries.
            for (Map.Entry<Integer, MobCategory> entry : newOutlined.entrySet()) {
                SettingColor c = colorForCategory(entry.getValue());
                GlowingRegistry.add(entry.getKey(), (255 << 24) | (c.r << 16) | (c.g << 8) | c.b);
            }
        } else {
            // Wireframe mode — remove any registry entries we left behind from
            // a previous Spectral session.
            for (Integer id : activelyOutlined.keySet()) {
                GlowingRegistry.remove(id);
            }
        }

        activelyOutlined.clear();
        activelyOutlined.putAll(newOutlined);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Render
    // Wireframe mode  — WireframeEntityRenderer draws the outline + bloom here.
    // Spectral mode   — outline is drawn entirely by the vanilla glow pipeline
    //                   via GlowingRegistry → EntityGlowingMixin. No extra
    //                   geometry is drawn here; bloom is intentionally skipped
    //                   as it would clash with the spectral outline shader.
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (mc.world == null || mc.player == null || activelyOutlined.isEmpty()) return;

        boolean wireframe = highlightMode.get() == HighlightMode.Wireframe;

        for (Map.Entry<Integer, MobCategory> entry : activelyOutlined.entrySet()) {
            Entity entity = mc.world.getEntityById(entry.getKey());
            if (!(entity instanceof MobEntity mob)) continue;

            SettingColor color = colorForCategory(entry.getValue());

            if (wireframe) {
                // Bloom halo behind the wireframe outline.
                if (glowEnabled.get()) {
                    renderGlowLayers(event, mob.getBoundingBox(), color);
                }
                WireframeEntityRenderer.render(
                    event, mob, outlineScale.get(),
                    withAlpha(color, 25), color, ShapeMode.Both
                );
            }
            // Spectral: the vanilla glow pipeline draws the coloured outline via
            // GlowingRegistry → EntityGlowingMixin. Nothing extra needed here —
            // adding bloom boxes on top would clash with the spectral effect.
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Bloom
    // ═══════════════════════════════════════════════════════════════════════════

    private void renderGlowLayers(Render3DEvent event, Box box, SettingColor color) {
        int    layers    = glowLayers.get();
        double spread    = glowSpread.get();
        int    baseAlpha = glowBaseAlpha.get();

        for (int i = layers; i >= 1; i--) {
            double expansion = spread * i;
            double t          = (double)(i - 1) / layers;
            int    layerAlpha = Math.max(4, (int)(baseAlpha * (1.0 - t * t)));
            event.renderer.box(
                box.expand(expansion),
                withAlpha(color, layerAlpha), withAlpha(color, 0),
                ShapeMode.Sides, 0
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Crosshair
    // ═══════════════════════════════════════════════════════════════════════════

    public void drawCrosshair(DrawContext context) {
        if (mc.getWindow() == null) return;
        if (!mc.options.getPerspective().isFirstPerson()) return;
        if (mc.currentScreen != null) return;

        int cx = mc.getWindow().getScaledWidth()  / 2;
        int cy = mc.getWindow().getScaledHeight() / 2;

        switch (crosshairMode.get()) {
            case WhiteDot -> context.fill(cx - 1, cy - 1, cx + 1, cy + 1, 0xFFFFFFFF);
            case Normal   -> drawNormalCrosshair(context, cx, cy);
            default       -> {}
        }
    }

    private void drawNormalCrosshair(DrawContext context, int cx, int cy) {
        int arm = crosshairSize.get();
        int gap = crosshairGap.get();
        int th  = crosshairThickness.get();
        int col = toARGB(crosshairColor.get());

        int halfU = th / 2;
        int halfD = th - halfU;

        context.fill(cx - arm - gap, cy - halfU, cx - gap,       cy + halfD, col);
        context.fill(cx + gap,       cy - halfU, cx + arm + gap, cy + halfD, col);
        context.fill(cx - halfU,     cy - arm - gap, cx + halfD, cy - gap,   col);
        context.fill(cx - halfU,     cy + gap,       cx + halfD, cy + arm + gap, col);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Categorisation
    // ═══════════════════════════════════════════════════════════════════════════

    private MobCategory categorise(MobEntity mob) {
        // Baby Piglin shares EntityType.PIGLIN with adults but is always passive —
        // it never attacks and does not grow up. Check before the override table
        // so it does not inherit the adult HOSTILE classification.
        if (mob.getType() == EntityType.PIGLIN && mob.isBaby()) return MobCategory.PASSIVE;

        // Spider / Cave Spider are neutral during the day and hostile at night.
        // Vanilla threshold: time of day < 13000 (daytime) → neutral, else hostile.
        // Also check canSeeSky so spiders deep underground (always hostile) are
        // correctly flagged regardless of surface time.
        if (mob.getType() == EntityType.SPIDER || mob.getType() == EntityType.CAVE_SPIDER) {
            long time = mc.world.getTimeOfDay() % 24000;
            boolean isDay = time < 13000;
            boolean canSeeSky = mc.world.isSkyVisible(mob.getBlockPos());
            return (isDay && canSeeSky) ? MobCategory.NEUTRAL : MobCategory.HOSTILE;
        }

        MobCategory override = CATEGORY_OVERRIDES.get(mob.getType());
        if (override != null) return override;

        if (mob instanceof HostileEntity) return MobCategory.HOSTILE;
        if (mob instanceof Angerable)     return MobCategory.NEUTRAL;
        if (mob instanceof PassiveEntity) return MobCategory.PASSIVE;
        return MobCategory.NEUTRAL;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private SettingColor colorForCategory(MobCategory cat) {
        return switch (cat) {
            case PASSIVE -> passiveColor.get();
            case NEUTRAL -> neutralColor.get();
            case HOSTILE -> hostileColor.get();
        };
    }

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    private static int toARGB(SettingColor c) {
        return (c.a << 24) | (c.r << 16) | (c.g << 8) | c.b;
    }
}