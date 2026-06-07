package com.example.addon.hud;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.example.addon.HuntingUtilities;
import com.example.addon.modules.NeighbourhoodWatch;
import com.example.addon.modules.NeighbourhoodWatch.PlayerStatus;

import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.ColorSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.hud.HudElement;
import meteordevelopment.meteorclient.systems.hud.HudElementInfo;
import meteordevelopment.meteorclient.systems.hud.HudRenderer;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.boss.WitherEntity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.PassiveEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.projectile.FireworkRocketEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.entity.vehicle.BoatEntity;
import net.minecraft.entity.vehicle.ChestMinecartEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.Box;

public class NeighbourhoodWatchHUD extends HudElement {

    public static final HudElementInfo<NeighbourhoodWatchHUD> INFO = new HudElementInfo<>(
        HuntingUtilities.HUD_GROUP,
        "neighbourhood-watch",
        "Displays nearby players, items, entities, and server tab-list with friend/enemy/proxy classifications.",
        NeighbourhoodWatchHUD::new
    );

    private static final MinecraftClient mc = MinecraftClient.getInstance();

    // ── Enums ─────────────────────────────────────────────────────────────────

    public enum Alignment { Left, Center, Right }

    public enum TrackingMode { All, Individual }

    public enum EntityDisplayMode { Category, Flat }

    // ── Setting Groups ────────────────────────────────────────────────────────

    private final SettingGroup sgGeneral   = settings.getDefaultGroup();
    private final SettingGroup sgFireworks = settings.createGroup("Firework Tracking");
    private final SettingGroup sgPearls    = settings.createGroup("Pearl Tracking");
    private final SettingGroup sgLogouts   = settings.createGroup("Logout Tracking");
    private final SettingGroup sgItems     = settings.createGroup("Item Tracking");
    private final SettingGroup sgEntities  = settings.createGroup("Entity Tracking");

    // ═══════════════════════════════════════════════════════════════════════════
    // General Settings
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> showNearby = sgGeneral.add(new BoolSetting.Builder()
        .name("show-nearby")
        .description("Show the list of players currently within tracking range.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxNearbyRows = sgGeneral.add(new IntSetting.Builder()
        .name("max-nearby-rows")
        .description("Maximum number of nearby players to list.")
        .defaultValue(5).min(1).sliderMax(20)
        .visible(showNearby::get)
        .build()
    );

    private final Setting<Boolean> showOnline = sgGeneral.add(new BoolSetting.Builder()
        .name("show-online")
        .description("Show the tab-list player breakdown by category.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showSafetyStatus = sgGeneral.add(new BoolSetting.Builder()
        .name("show-safety-status")
        .description("Show a warning when the disconnect-on-player safety feature is armed.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showLogouts = sgLogouts.add(new BoolSetting.Builder()
        .name("show-logouts")
        .description("Show the list of recent logout spots.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> maxLogoutRows = sgLogouts.add(new IntSetting.Builder()
        .name("max-logout-rows")
        .description("Maximum number of logout spots to list.")
        .defaultValue(5)
        .min(1)
        .sliderMax(20)
        .visible(showLogouts::get)
        .build()
    );

    private final Setting<Boolean> showDistance = sgGeneral.add(new BoolSetting.Builder()
        .name("show-distance")
        .description("Show each nearby player's distance in the list.")
        .defaultValue(true)
        .visible(showNearby::get)
        .build()
    );

    private final Setting<EntityDisplayMode> nearbyDisplayMode = sgGeneral.add(new EnumSetting.Builder<EntityDisplayMode>()
        .name("nearby-display-mode")
        .description("Category shows a header with total count; Flat lists players directly.")
        .defaultValue(EntityDisplayMode.Category)
        .visible(showNearby::get)
        .build()
    );

    // ── Appearance ────────────────────────────────────────────────────────────

    private final Setting<Double> scale = sgGeneral.add(new DoubleSetting.Builder()
        .name("scale")
        .description("Scale of the HUD element.")
        .defaultValue(1.0).min(0.25).sliderRange(0.25, 4.0)
        .build()
    );

    private final Setting<SettingColor> labelColor = sgGeneral.add(new ColorSetting.Builder()
        .name("label-color")
        .defaultValue(new SettingColor(170, 170, 170, 255))
        .build()
    );

    private final Setting<SettingColor> valueColor = sgGeneral.add(new ColorSetting.Builder()
        .name("value-color")
        .defaultValue(new SettingColor(255, 255, 255, 255))
        .build()
    );

    private final Setting<SettingColor> friendColor = sgGeneral.add(new ColorSetting.Builder()
        .name("friend-color")
        .description("Color for players classified as friends.")
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .build()
    );

    private final Setting<SettingColor> enemyColor = sgGeneral.add(new ColorSetting.Builder()
        .name("enemy-color")
        .description("Color for players classified as enemies.")
        .defaultValue(new SettingColor(255, 60, 60, 255))
        .build()
    );

    private final Setting<SettingColor> proxyColor = sgGeneral.add(new ColorSetting.Builder()
        .name("proxy-color")
        .description("Color for players classified as proxies.")
        .defaultValue(new SettingColor(255, 140, 0, 255))
        .build()
    );

    private final Setting<SettingColor> otherColor = sgGeneral.add(new ColorSetting.Builder()
        .name("other-color")
        .description("Color for unknown players (nearby list only).")
        .defaultValue(new SettingColor(200, 200, 200, 255))
        .build()
    );

    private final Setting<SettingColor> safetyColor = sgGeneral.add(new ColorSetting.Builder()
        .name("safety-color")
        .description("Color shown when the disconnect safety is armed.")
        .defaultValue(new SettingColor(255, 200, 0, 255))
        .build()
    );

    private final Setting<SettingColor> separatorColor = sgGeneral.add(new ColorSetting.Builder()
        .name("separator-color")
        .description("Color of the | separator used in paired values.")
        .defaultValue(new SettingColor(100, 100, 100, 255))
        .build()
    );

    private final Setting<SettingColor> headerColor = sgGeneral.add(new ColorSetting.Builder()
        .name("header-color")
        .description("Color for section header labels.")
        .defaultValue(new SettingColor(130, 130, 130, 255))
        .build()
    );

    private final Setting<Boolean> showBackground = sgGeneral.add(new BoolSetting.Builder()
        .name("background")
        .description("Show a background highlight behind each line.")
        .defaultValue(false)
        .build()
    );

    private final Setting<SettingColor> backgroundColor = sgGeneral.add(new ColorSetting.Builder()
        .name("background-color")
        .defaultValue(new SettingColor(0, 0, 0, 80))
        .visible(showBackground::get)
        .build()
    );

    private final Setting<Alignment> alignment = sgGeneral.add(new EnumSetting.Builder<Alignment>()
        .name("alignment")
        .description("Align text to the left, center, or right.")
        .defaultValue(Alignment.Left)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Firework Tracking Settings
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> showFireworks = sgFireworks.add(new BoolSetting.Builder()
        .name("show-fireworks")
        .description("Show nearby active firework rockets on the HUD.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> fireworkRange = sgFireworks.add(new IntSetting.Builder()
        .name("firework-range")
        .description("Radius (in blocks) to scan for firework rockets.")
        .defaultValue(64).min(1).sliderMax(256)
        .visible(showFireworks::get)
        .build()
    );

    private final Setting<Boolean> showFireworkShotBy = sgFireworks.add(new BoolSetting.Builder()
        .name("show-shot-by")
        .description("Show the name of the player who launched each firework (if known).")
        .defaultValue(true)
        .visible(showFireworks::get)
        .build()
    );

    private final Setting<Boolean> showFireworkDistance = sgFireworks.add(new BoolSetting.Builder()
        .name("show-firework-distance")
        .description("Show the distance to the nearest firework rocket.")
        .defaultValue(true)
        .visible(showFireworks::get)
        .build()
    );

    private final Setting<Boolean> showFireworkCount = sgFireworks.add(new BoolSetting.Builder()
        .name("show-firework-count")
        .description("Show the total number of active firework rockets nearby.")
        .defaultValue(true)
        .visible(showFireworks::get)
        .build()
    );

    private final Setting<EntityDisplayMode> fireworkDisplayMode = sgFireworks.add(new EnumSetting.Builder<EntityDisplayMode>()
        .name("firework-display-mode")
        .description("Category shows a header with total count; Flat lists each launcher directly.")
        .defaultValue(EntityDisplayMode.Category)
        .visible(showFireworks::get)
        .build()
    );

    private final Setting<Integer> maxFireworkRows = sgFireworks.add(new IntSetting.Builder()
        .name("max-firework-rows")
        .description("Maximum number of firework launcher entries to list.")
        .defaultValue(5).min(1).sliderMax(20)
        .visible(showFireworks::get)
        .build()
    );

    private final Setting<SettingColor> fireworkColor = sgFireworks.add(new ColorSetting.Builder()
        .name("firework-color")
        .description("Color for firework rocket entries.")
        .defaultValue(new SettingColor(255, 100, 180, 255))
        .visible(showFireworks::get)
        .build()
    );

    private final Setting<SettingColor> fireworkMetaColor = sgFireworks.add(new ColorSetting.Builder()
        .name("firework-meta-color")
        .description("Color for firework count and distance values.")
        .defaultValue(new SettingColor(200, 200, 200, 255))
        .visible(showFireworks::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Pearl Tracking Settings
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> showPearls = sgPearls.add(new BoolSetting.Builder()
        .name("show-pearls")
        .description("Show nearby stasis ender pearls on the HUD.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> pearlRange = sgPearls.add(new IntSetting.Builder()
        .name("pearl-range")
        .description("Radius (in blocks) to scan for ender pearls.")
        .defaultValue(64).min(1).sliderMax(256)
        .visible(showPearls::get)
        .build()
    );

    private final Setting<Boolean> showPearlDistance = sgPearls.add(new BoolSetting.Builder()
        .name("show-pearl-distance")
        .description("Show the distance to the nearest ender pearl.")
        .defaultValue(true)
        .visible(showPearls::get)
        .build()
    );

    private final Setting<EntityDisplayMode> pearlDisplayMode = sgPearls.add(new EnumSetting.Builder<EntityDisplayMode>()
        .name("pearl-display-mode")
        .description("Category shows a header with total count; Flat shows the pearl entry directly.")
        .defaultValue(EntityDisplayMode.Category)
        .visible(showPearls::get)
        .build()
    );

    private final Setting<SettingColor> pearlColor = sgPearls.add(new ColorSetting.Builder()
        .name("pearl-color")
        .description("Color for the ender pearl entry.")
        .defaultValue(new SettingColor(80, 200, 120, 255))
        .visible(showPearls::get)
        .build()
    );

    private final Setting<SettingColor> pearlMetaColor = sgPearls.add(new ColorSetting.Builder()
        .name("pearl-meta-color")
        .description("Color for pearl count and distance values.")
        .defaultValue(new SettingColor(200, 200, 200, 255))
        .visible(showPearls::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Item Tracking Settings
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> showItems = sgItems.add(new BoolSetting.Builder()
        .name("show-items")
        .description("Show nearby dropped items on the HUD.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> itemRange = sgItems.add(new IntSetting.Builder()
        .name("item-range")
        .description("Radius (in blocks) to scan for dropped items.")
        .defaultValue(64).min(1).sliderMax(256)
        .visible(showItems::get)
        .build()
    );

    private final Setting<Integer> maxItemRows = sgItems.add(new IntSetting.Builder()
        .name("max-item-rows")
        .description("Maximum number of distinct item types to list.")
        .defaultValue(8).min(1).sliderMax(32)
        .visible(showItems::get)
        .build()
    );

    private final Setting<Boolean> showItemDistance = sgItems.add(new BoolSetting.Builder()
        .name("show-item-distance")
        .description("Show the distance to the nearest stack of each item type.")
        .defaultValue(true)
        .visible(showItems::get)
        .build()
    );

    private final Setting<Boolean> showItemCount = sgItems.add(new BoolSetting.Builder()
        .name("show-item-count")
        .description("Show the total count of each item type found nearby.")
        .defaultValue(true)
        .visible(showItems::get)
        .build()
    );

    private final Setting<EntityDisplayMode> itemDisplayMode = sgItems.add(new EnumSetting.Builder<EntityDisplayMode>()
        .name("item-display-mode")
        .description("Category shows a header with total count; Flat lists item types directly.")
        .defaultValue(EntityDisplayMode.Category)
        .visible(showItems::get)
        .build()
    );

    private final Setting<SettingColor> itemColor = sgItems.add(new ColorSetting.Builder()
        .name("item-color")
        .description("Color for item names in the HUD.")
        .defaultValue(new SettingColor(255, 215, 0, 255))
        .visible(showItems::get)
        .build()
    );

    private final Setting<SettingColor> itemCountColor = sgItems.add(new ColorSetting.Builder()
        .name("item-count-color")
        .description("Color for item count and distance values.")
        .defaultValue(new SettingColor(200, 200, 200, 255))
        .visible(showItems::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Entity Tracking Settings
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<TrackingMode> entityTrackingMode = sgEntities.add(new EnumSetting.Builder<TrackingMode>()
        .name("tracking-mode")
        .description("All tracks every sub-type; Individual honours the toggles below.")
        .defaultValue(TrackingMode.Individual)
        .build()
    );

    private final Setting<EntityDisplayMode> entityDisplayMode = sgEntities.add(new EnumSetting.Builder<EntityDisplayMode>()
        .name("display-mode")
        .description("Category shows a header per sub-type group; Flat lists entity types directly.")
        .defaultValue(EntityDisplayMode.Category)
        .build()
    );

    private final Setting<Boolean> showEndCrystals = sgEntities.add(new BoolSetting.Builder()
        .name("show-end-crystals")
        .description("Track placed End Crystals nearby.")
        .defaultValue(true)
        .visible(() -> entityTrackingMode.get() == TrackingMode.Individual)
        .build()
    );

    private final Setting<Boolean> showChestMinecarts = sgEntities.add(new BoolSetting.Builder()
        .name("show-chest-minecarts")
        .description("Track Chest Minecarts nearby.")
        .defaultValue(true)
        .visible(() -> entityTrackingMode.get() == TrackingMode.Individual)
        .build()
    );

    private final Setting<Boolean> showVehicles = sgEntities.add(new BoolSetting.Builder()
        .name("show-vehicles")
        .description("Track rideable vehicles (boats, minecarts) nearby. Chest Minecarts are tracked separately.")
        .defaultValue(true)
        .visible(() -> entityTrackingMode.get() == TrackingMode.Individual)
        .build()
    );

    private final Setting<Boolean> showPassiveMobs = sgEntities.add(new BoolSetting.Builder()
        .name("show-passive-mobs")
        .description("Track passive and neutral mobs nearby.")
        .defaultValue(true)
        .visible(() -> entityTrackingMode.get() == TrackingMode.Individual)
        .build()
    );

    private final Setting<Boolean> showHostileMobs = sgEntities.add(new BoolSetting.Builder()
        .name("show-hostile-mobs")
        .description("Track hostile mobs nearby.")
        .defaultValue(true)
        .visible(() -> entityTrackingMode.get() == TrackingMode.Individual)
        .build()
    );

    private final Setting<Integer> entityRange = sgEntities.add(new IntSetting.Builder()
        .name("entity-range")
        .description("Radius (in blocks) to scan for entities.")
        .defaultValue(64).min(1).sliderMax(256)
        .build()
    );

    private final Setting<Integer> maxEntityRows = sgEntities.add(new IntSetting.Builder()
        .name("max-entity-rows")
        .description("Maximum number of entity types to list per category.")
        .defaultValue(8).min(1).sliderMax(32)
        .build()
    );

    private final Setting<Boolean> showEntityDistance = sgEntities.add(new BoolSetting.Builder()
        .name("show-entity-distance")
        .description("Show the distance to the nearest entity of each type.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> showEntityCount = sgEntities.add(new BoolSetting.Builder()
        .name("show-entity-count")
        .description("Show the total count of each entity type found nearby.")
        .defaultValue(true)
        .build()
    );

    // Entity colors

    private final Setting<SettingColor> endCrystalColor = sgEntities.add(new ColorSetting.Builder()
        .name("end-crystal-color")
        .description("Color for End Crystal entries.")
        .defaultValue(new SettingColor(220, 80, 255, 255))
        .visible(() -> entityTrackingMode.get() == TrackingMode.Individual && showEndCrystals.get())
        .build()
    );

    private final Setting<SettingColor> chestMinecartColor = sgEntities.add(new ColorSetting.Builder()
        .name("chest-minecart-color")
        .description("Color for Chest Minecart entries.")
        .defaultValue(new SettingColor(180, 130, 60, 255))
        .visible(() -> entityTrackingMode.get() == TrackingMode.Individual && showChestMinecarts.get())
        .build()
    );

    private final Setting<SettingColor> vehicleColor = sgEntities.add(new ColorSetting.Builder()
        .name("vehicle-color")
        .description("Color for vehicle (boat, minecart) entries.")
        .defaultValue(new SettingColor(150, 200, 255, 255))
        .visible(() -> entityTrackingMode.get() == TrackingMode.Individual && showVehicles.get())
        .build()
    );

    private final Setting<SettingColor> passiveMobColor = sgEntities.add(new ColorSetting.Builder()
        .name("passive-mob-color")
        .description("Color for passive/neutral mob entries.")
        .defaultValue(new SettingColor(100, 220, 100, 255))
        .visible(() -> entityTrackingMode.get() == TrackingMode.Individual && showPassiveMobs.get())
        .build()
    );

    private final Setting<SettingColor> hostileMobColor = sgEntities.add(new ColorSetting.Builder()
        .name("hostile-mob-color")
        .description("Color for hostile mob entries.")
        .defaultValue(new SettingColor(255, 80, 80, 255))
        .visible(() -> entityTrackingMode.get() == TrackingMode.Individual && showHostileMobs.get())
        .build()
    );

    private final Setting<SettingColor> allEntitiesColor = sgEntities.add(new ColorSetting.Builder()
        .name("all-entities-color")
        .description("Color for entity name entries when tracking mode is All.")
        .defaultValue(new SettingColor(180, 180, 255, 255))
        .visible(() -> entityTrackingMode.get() == TrackingMode.All)
        .build()
    );

    private final Setting<SettingColor> entityMetaColor = sgEntities.add(new ColorSetting.Builder()
        .name("entity-meta-color")
        .description("Color for entity count and distance values.")
        .defaultValue(new SettingColor(200, 200, 200, 255))
        .build()
    );

    // ── Constructor ───────────────────────────────────────────────────────────

    public NeighbourhoodWatchHUD() {
        super(INFO);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Data containers
    // ─────────────────────────────────────────────────────────────────────────

    private record TrackedEntry(String typeName, int count, float nearestDist) {}

    private record TrackedCategory(
        String header,
        int total,
        List<TrackedEntry> entries,
        SettingColor nameColor
    ) {}

    private record FireworkEntry(String shooterName, int count, float nearestDist) {}

    // ─────────────────────────────────────────────────────────────────────────
    // Helper: position-based search box clamped to valid world height
    // ─────────────────────────────────────────────────────────────────────────

    private Box playerBox(double range) {
        double px   = mc.player.getX();
        double py   = mc.player.getY();
        double pz   = mc.player.getZ();
        double minY = Math.max(py - range, mc.world.getDimension().minY());
        double maxY = Math.min(py + range, mc.world.getDimension().minY() + mc.world.getDimension().height());
        return new Box(px - range, minY, pz - range,
                       px + range, maxY, pz + range);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Render
    // ─────────────────────────────────────────────────────────────────────────

    @Override
    public void render(HudRenderer renderer) {
        if (mc.player == null || mc.world == null) {
            if (isInEditor()) {
                setSize(120, 20);
                renderer.text("Neighbourhood Watch", x, y, labelColor.get(), false, scale.get());
            } else {
                setSize(0, 0);
            }
            return;
        }

        NeighbourhoodWatch module = Modules.get().get(NeighbourhoodWatch.class);
        boolean moduleActive = module != null && module.isActive();

        double s          = scale.get();
        double padH       = 4 * s;
        double padV       = 2 * s;
        double rowGap     = 2 * s;
        double lineHeight = renderer.textHeight(false, s);
        Alignment align   = alignment.get();

        // ── Gather nearby players ─────────────────────────────────────────────

        record NearbyPlayer(String name, float dist, PlayerStatus status) {}
        List<NearbyPlayer> nearbyList = new ArrayList<>();

        if (showNearby.get()) {
            for (PlayerEntity player : mc.world.getPlayers()) {
                if (player == mc.player || player.isSpectator()) continue;
                float dist = mc.player.distanceTo(player);
                String name = player.getName().getString();
                PlayerStatus status = moduleActive
                    ? module.getPlayerStatusPublic(name)
                    : PlayerStatus.Other;
                nearbyList.add(new NearbyPlayer(name, dist, status));
            }
            nearbyList.sort(Comparator.comparingDouble(NearbyPlayer::dist));
        }

        int nearbyTotal = nearbyList.size();
        List<NearbyPlayer> nearbyShown = nearbyList.subList(
            0, Math.min(nearbyList.size(), maxNearbyRows.get()));

        List<TrackedEntry> nearbyEntries = new ArrayList<>();
        for (NearbyPlayer np : nearbyShown) {
            nearbyEntries.add(new TrackedEntry(
                statusTag(np.status()) + " " + np.name(), 1, np.dist()));
        }

        // ── Gather tab-list counts ────────────────────────────────────────────

        int onlineFriends = 0, onlineEnemies = 0, onlineProxies = 0;

        if (moduleActive && showOnline.get() && mc.getNetworkHandler() != null) {
            for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
                String name = entry.getProfile().getName();
                if (name == null || name.isEmpty()) continue;
                switch (module.getPlayerStatusPublic(name)) {
                    case Friend -> onlineFriends++;
                    case Enemy  -> onlineEnemies++;
                    case Proxy  -> onlineProxies++;
                    default     -> {}
                }
            }
        }

        // ── Gather nearby fireworks ───────────────────────────────────────────

        List<FireworkEntry> fireworkEntries = new ArrayList<>();
        int fireworkTotal = 0;

        if (showFireworks.get()) {
            double fRange = fireworkRange.get();
            Box fBox = playerBox(fRange);

            Map<String, int[]>   fwCountMap = new LinkedHashMap<>();
            Map<String, float[]> fwDistMap  = new LinkedHashMap<>();

            var rockets = mc.world.getEntitiesByClass(
                FireworkRocketEntity.class, fBox, e -> true);

            for (FireworkRocketEntity rocket : rockets) {
                float dist = (float) mc.player.getPos().distanceTo(rocket.getPos());
                if (dist > fRange) continue;

                String label;
                if (showFireworkShotBy.get() && rocket.getOwner() instanceof PlayerEntity shooter) {
                    label = shooter.getName().getString();
                } else {
                    label = "Firework Rocket";
                }

                fwCountMap.computeIfAbsent(label, k -> new int[]{0})[0]++;
                fwDistMap.computeIfAbsent(label, k -> new float[]{Float.MAX_VALUE});
                if (dist < fwDistMap.get(label)[0]) fwDistMap.get(label)[0] = dist;
            }

            for (String label : fwCountMap.keySet()) {
                int   cnt  = fwCountMap.get(label)[0];
                float near = fwDistMap.get(label)[0];
                fireworkEntries.add(new FireworkEntry(label, cnt, near));
                fireworkTotal += cnt;
            }
            fireworkEntries.sort(Comparator.comparingDouble(FireworkEntry::nearestDist));
        }

        List<FireworkEntry> fireworksShown = fireworkEntries.subList(
            0, Math.min(fireworkEntries.size(), maxFireworkRows.get()));

        // ── Gather nearby pearls ──────────────────────────────────────────────

        int     pearlCount   = 0;
        float   pearlNearest = Float.MAX_VALUE;

        if (showPearls.get()) {
            double pRange = pearlRange.get();
            Box pBox = playerBox(pRange);

            for (Entity e : mc.world.getEntitiesByClass(Entity.class, pBox, en -> en.getType() == EntityType.ENDER_PEARL)) {
                float dist = (float) mc.player.getPos().distanceTo(e.getPos());
                if (dist > pRange) continue;
                pearlCount++;
                if (dist < pearlNearest) pearlNearest = dist;
            }
        }

        // ── Gather nearby items ───────────────────────────────────────────────

        TrackedCategory itemCategory = null;

        if (showItems.get()) {
            double range = itemRange.get();
            Box searchBox = playerBox(range);

            Map<String, int[]>   countMap = new LinkedHashMap<>();
            Map<String, float[]> distMap  = new LinkedHashMap<>();

            for (ItemEntity itemEntity : mc.world.getEntitiesByClass(
                    ItemEntity.class, searchBox, e -> true)) {

                ItemStack stack = itemEntity.getStack();
                if (stack.isEmpty()) continue;

                float dist = (float) mc.player.getPos().distanceTo(itemEntity.getPos());
                if (dist > range) continue;

                String itemName = stack.getName().getString();

                countMap.computeIfAbsent(itemName, k -> new int[]{0})[0] += stack.getCount();
                distMap.computeIfAbsent(itemName, k -> new float[]{Float.MAX_VALUE});
                if (dist < distMap.get(itemName)[0]) distMap.get(itemName)[0] = dist;
            }

            if (!countMap.isEmpty()) {
                List<TrackedEntry> entries = new ArrayList<>();
                int totalItems = 0;
                for (String name : countMap.keySet()) {
                    int   cnt  = countMap.get(name)[0];
                    float near = distMap.get(name)[0];
                    entries.add(new TrackedEntry(name, cnt, near));
                    totalItems += cnt;
                }
                entries.sort(Comparator.comparingDouble(TrackedEntry::nearestDist));

                int typeCount = entries.size();
                String header = "Items: " + typeCount + (typeCount == 1 ? " type" : " types")
                    + "  (" + totalItems + " total)";
                itemCategory = new TrackedCategory(header, typeCount, entries, itemColor.get());
            }
        }

        // ── Gather entity categories ──────────────────────────────────────────

        List<TrackedCategory> entityCategories = new ArrayList<>();
        TrackingMode tMode = entityTrackingMode.get();

        double eRange = entityRange.get();
        Box expandedBox = playerBox(eRange);

        boolean doEndCrystals    = tMode == TrackingMode.All || showEndCrystals.get();
        boolean doChestMinecarts = tMode == TrackingMode.All || showChestMinecarts.get();
        boolean doVehicles       = tMode == TrackingMode.All || showVehicles.get();
        boolean doPassiveMobs    = tMode == TrackingMode.All || showPassiveMobs.get();
        boolean doHostileMobs    = tMode == TrackingMode.All || showHostileMobs.get();

        if (doEndCrystals || doChestMinecarts || doVehicles || doPassiveMobs || doHostileMobs) {

            if (doEndCrystals) {
                var crystals = mc.world.getEntitiesByClass(
                    EndCrystalEntity.class, expandedBox, e -> true);
                if (!crystals.isEmpty()) {
                    float nearest = Float.MAX_VALUE;
                    for (EndCrystalEntity e : crystals) {
                        float d = (float) mc.player.getPos().distanceTo(e.getPos());
                        if (d < nearest) nearest = d;
                    }
                    List<TrackedEntry> entries = List.of(
                        new TrackedEntry("End Crystal", crystals.size(), nearest));
                    SettingColor col = tMode == TrackingMode.All
                        ? allEntitiesColor.get() : endCrystalColor.get();
                    entityCategories.add(new TrackedCategory(
                        "Crystals: " + crystals.size(),
                        crystals.size(), entries, col));
                }
            }

            if (doChestMinecarts) {
                var carts = mc.world.getEntitiesByClass(
                    ChestMinecartEntity.class, expandedBox, e -> true);
                if (!carts.isEmpty()) {
                    float nearest = Float.MAX_VALUE;
                    for (ChestMinecartEntity e : carts) {
                        float d = (float) mc.player.getPos().distanceTo(e.getPos());
                        if (d < nearest) nearest = d;
                    }
                    List<TrackedEntry> entries = List.of(
                        new TrackedEntry("Chest Minecart", carts.size(), nearest));
                    SettingColor col = tMode == TrackingMode.All
                        ? allEntitiesColor.get() : chestMinecartColor.get();
                    entityCategories.add(new TrackedCategory(
                        "Carts: " + carts.size(),
                        carts.size(), entries, col));
                }
            }

            if (doVehicles) {
                var vehicles = mc.world.getEntitiesByClass(
                    net.minecraft.entity.Entity.class, expandedBox,
                    e -> (e instanceof BoatEntity)
                      || (e instanceof AbstractMinecartEntity
                          && !(e instanceof ChestMinecartEntity))
                );

                if (!vehicles.isEmpty()) {
                    Map<String, int[]>   vCountMap = new LinkedHashMap<>();
                    Map<String, float[]> vDistMap  = new LinkedHashMap<>();

                    for (var e : vehicles) {
                        String typeName = e.getType().getName().getString();
                        float  dist     = (float) mc.player.getPos().distanceTo(e.getPos());
                        vCountMap.computeIfAbsent(typeName, k -> new int[]{0})[0]++;
                        vDistMap.computeIfAbsent(typeName, k -> new float[]{Float.MAX_VALUE});
                        if (dist < vDistMap.get(typeName)[0]) vDistMap.get(typeName)[0] = dist;
                    }

                    List<TrackedEntry> entries   = new ArrayList<>();
                    int                totalVehicles = 0;
                    for (String name : vCountMap.keySet()) {
                        int   cnt  = vCountMap.get(name)[0];
                        float near = vDistMap.get(name)[0];
                        entries.add(new TrackedEntry(name, cnt, near));
                        totalVehicles += cnt;
                    }
                    entries.sort(Comparator.comparingDouble(TrackedEntry::nearestDist));

                    SettingColor col = tMode == TrackingMode.All
                        ? allEntitiesColor.get() : vehicleColor.get();

                    entityCategories.add(new TrackedCategory(
                        "Vehicles: " + totalVehicles,
                        totalVehicles, entries, col));
                }
            }

            if (doPassiveMobs) {
                var passives = mc.world.getEntitiesByClass(
                    MobEntity.class, expandedBox, e -> e instanceof PassiveEntity);
                SettingColor col = tMode == TrackingMode.All
                    ? allEntitiesColor.get() : passiveMobColor.get();
                TrackedCategory cat = buildMobCategory(passives, "Passive", col);
                if (cat != null) entityCategories.add(cat);
            }

            if (doHostileMobs) {
                var hostiles = mc.world.getEntitiesByClass(
                    MobEntity.class, expandedBox,
                    e -> (e instanceof HostileEntity) || (e instanceof WitherEntity));
                SettingColor col = tMode == TrackingMode.All
                    ? allEntitiesColor.get() : hostileMobColor.get();
                TrackedCategory cat = buildMobCategory(hostiles, "Hostile", col);
                if (cat != null) entityCategories.add(cat);
            }
        }

        // ── Gather logouts ────────────────────────────────────────────────────

        List<NeighbourhoodWatch.LogoutRecord> logoutList = new ArrayList<>();
        if (showLogouts.get() && moduleActive) {
            String currentDim = mc.world.getRegistryKey().getValue().toString();
            List<NeighbourhoodWatch.LogoutRecord> allLogouts = module.getLogouts();

            for (int i = allLogouts.size() - 1; i >= 0; i--) {
                NeighbourhoodWatch.LogoutRecord r = allLogouts.get(i);
                if (r.dimension().equals(currentDim)) {
                    logoutList.add(r);
                    if (logoutList.size() >= maxLogoutRows.get()) break;
                }
            }
        }

        // ── Safety armed? ─────────────────────────────────────────────────────

        boolean safetyArmed = moduleActive
            && showSafetyStatus.get()
            && module.isDisconnectOnPlayerArmed();

        // ── Determine which sections are visible ──────────────────────────────

        boolean hasNearbySection   = showNearby.get() && !nearbyList.isEmpty();
        boolean hasOnlineSection   = moduleActive && showOnline.get()
            && (onlineFriends > 0 || onlineEnemies > 0 || onlineProxies > 0);
        boolean hasFireworkSection = showFireworks.get() && !fireworkEntries.isEmpty();
        boolean hasPearlSection    = showPearls.get() && pearlCount > 0;
        boolean hasItemSection     = showItems.get() && itemCategory != null;
        boolean hasEntitySection   = !entityCategories.isEmpty();
        boolean hasLogoutSection   = !logoutList.isEmpty();
        boolean hasSafetyLine      = safetyArmed;

        if (!hasNearbySection && !hasFireworkSection && !hasOnlineSection && !hasLogoutSection
                && !hasPearlSection && !hasItemSection && !hasEntitySection && !hasSafetyLine) {
            setSize(0, 0);
            return;
        }

        EntityDisplayMode nDMode  = nearbyDisplayMode.get();
        EntityDisplayMode iDMode  = itemDisplayMode.get();
        EntityDisplayMode eDMode  = entityDisplayMode.get();
        EntityDisplayMode fwDMode = fireworkDisplayMode.get();
        EntityDisplayMode pDMode  = pearlDisplayMode.get();

        // ── Pre-measure widths ────────────────────────────────────────────────

        double maxW = 0;

        // Nearby widths
        double nearbyHeaderW = 0;
        double[] nearbyRowWidths = new double[nearbyEntries.size()];

        if (hasNearbySection) {
            if (nDMode == EntityDisplayMode.Category) {
                String hdr = "Nearby: " + nearbyTotal + (nearbyTotal == 1 ? " player" : " players");
                nearbyHeaderW = renderer.textWidth(hdr, false, s);
                maxW = Math.max(maxW, nearbyHeaderW);
            }
            for (int i = 0; i < nearbyEntries.size(); i++) {
                TrackedEntry e  = nearbyEntries.get(i);
                String distStr  = showDistance.get() ? String.format(" %.0fm", e.nearestDist()) : "";
                double w = renderer.textWidth(e.typeName(), false, s)
                         + renderer.textWidth(distStr, false, s);
                nearbyRowWidths[i] = w;
                maxW = Math.max(maxW, w);
            }
        }

        // Online widths
        double onlineFriendW  = 0;
        double onlineEnemyW   = 0;
        double onlineProxyW   = 0;

        if (hasOnlineSection) {
            if (onlineFriends > 0) {
                onlineFriendW = renderer.textWidth("Friends", false, s)
                              + renderer.textWidth("  " + onlineFriends, false, s);
                maxW = Math.max(maxW, onlineFriendW);
            }
            if (onlineEnemies > 0) {
                onlineEnemyW = renderer.textWidth("Enemies", false, s)
                             + renderer.textWidth("  " + onlineEnemies, false, s);
                maxW = Math.max(maxW, onlineEnemyW);
            }
            if (onlineProxies > 0) {
                onlineProxyW = renderer.textWidth("Proxies", false, s)
                             + renderer.textWidth("  " + onlineProxies, false, s);
                maxW = Math.max(maxW, onlineProxyW);
            }
        }

        // Firework widths
        double fwHeaderW = 0;
        double[] fwRowWidths = new double[fireworksShown.size()];

        if (hasFireworkSection) {
            if (fwDMode == EntityDisplayMode.Category) {
                String fwHdr = "Fireworks: " + fireworkTotal
                    + (fireworkTotal == 1 ? " rocket" : " rockets");
                fwHeaderW = renderer.textWidth(fwHdr, false, s);
                maxW = Math.max(maxW, fwHeaderW);
            }
            for (int i = 0; i < fireworksShown.size(); i++) {
                FireworkEntry fe = fireworksShown.get(i);
                String cntStr   = showFireworkCount.get()    ? " x" + fe.count()                        : "";
                String distStr  = showFireworkDistance.get() ? String.format(" %.0fm", fe.nearestDist()) : "";
                double w = renderer.textWidth(fe.shooterName(), false, s)
                         + renderer.textWidth(cntStr, false, s)
                         + renderer.textWidth(distStr, false, s);
                fwRowWidths[i] = w;
                maxW = Math.max(maxW, w);
            }
        }

        // Pearl widths
        double pearlHeaderW = 0;
        double pearlRowW    = 0;

        if (hasPearlSection) {
            if (pDMode == EntityDisplayMode.Category) {
                String pHdr = "Pearls: " + pearlCount + (pearlCount == 1 ? " pearl" : " pearls");
                pearlHeaderW = renderer.textWidth(pHdr, false, s);
                maxW = Math.max(maxW, pearlHeaderW);
            }
            String distStr = showPearlDistance.get() ? String.format(" %.0fm", pearlNearest) : "";
            pearlRowW = renderer.textWidth("Nearby Pearls", false, s)
                      + renderer.textWidth(" x" + pearlCount, false, s)
                      + renderer.textWidth(distStr, false, s);
            maxW = Math.max(maxW, pearlRowW);
        }

        // Item widths
        double itemHeaderW = 0;
        List<TrackedEntry> itemsShown = List.of();
        double[] itemRowWidths = new double[0];

        if (hasItemSection) {
            if (iDMode == EntityDisplayMode.Category) {
                itemHeaderW = renderer.textWidth(itemCategory.header(), false, s);
                maxW = Math.max(maxW, itemHeaderW);
            }
            itemsShown = itemCategory.entries().subList(
                0, Math.min(itemCategory.entries().size(), maxItemRows.get()));
            itemRowWidths = new double[itemsShown.size()];
            for (int i = 0; i < itemsShown.size(); i++) {
                TrackedEntry e   = itemsShown.get(i);
                String cntStr    = showItemCount.get()    ? " x" + e.count()                         : "";
                String distStr   = showItemDistance.get() ? String.format(" %.0fm", e.nearestDist())  : "";
                double w = renderer.textWidth(e.typeName(), false, s)
                         + renderer.textWidth(cntStr, false, s)
                         + renderer.textWidth(distStr, false, s);
                itemRowWidths[i] = w;
                maxW = Math.max(maxW, w);
            }
        }

        // Entity widths
        double[]   entityCatHeaderW = new double[entityCategories.size()];
        double[][] entityEntryW     = new double[entityCategories.size()][];

        for (int ci = 0; ci < entityCategories.size(); ci++) {
            TrackedCategory cat = entityCategories.get(ci);

            if (eDMode == EntityDisplayMode.Category) {
                entityCatHeaderW[ci] = renderer.textWidth(cat.header(), false, s);
                maxW = Math.max(maxW, entityCatHeaderW[ci]);
            }

            int shown = Math.min(cat.entries().size(), maxEntityRows.get());
            entityEntryW[ci] = new double[shown];
            for (int ei = 0; ei < shown; ei++) {
                TrackedEntry ee  = cat.entries().get(ei);
                String cntStr    = showEntityCount.get()    ? " x" + ee.count()                         : "";
                String distStr   = showEntityDistance.get() ? String.format(" %.0fm", ee.nearestDist()) : "";
                double w = renderer.textWidth(ee.typeName(), false, s)
                         + renderer.textWidth(cntStr, false, s)
                         + renderer.textWidth(distStr, false, s);
                entityEntryW[ci][ei] = w;
                maxW = Math.max(maxW, w);
            }
        }

        // Logout widths
        double[] logoutRowWidths = new double[logoutList.size()];
        if (hasLogoutSection) {
            for (int i = 0; i < logoutList.size(); i++) {
                NeighbourhoodWatch.LogoutRecord r = logoutList.get(i);
                long elapsed = (System.currentTimeMillis() - r.time()) / 1000;
                String label = String.format("%s %s %s", r.name(), module.formatPos(r.pos()), formatTime(elapsed));
                double w = renderer.textWidth(label, false, s);
                logoutRowWidths[i] = w;
                maxW = Math.max(maxW, w);
            }
        }

        double safetyW = 0;
        if (hasSafetyLine) {
            safetyW = renderer.textWidth("! Safety Armed", false, s);
            maxW = Math.max(maxW, safetyW);
        }

        // ── Count total rows ──────────────────────────────────────────────────
        // Order: Nearby → Online → Fireworks → Pearls → Logouts → Items → Entities → Safety

        int lineCount = 0;
        if (hasNearbySection) {
            if (nDMode == EntityDisplayMode.Category) lineCount += 1;
            lineCount += nearbyEntries.size();
        }
        if (hasOnlineSection) {
            if (onlineFriends > 0) lineCount += 1;
            if (onlineEnemies > 0) lineCount += 1;
            if (onlineProxies > 0) lineCount += 1;
        }
        if (hasFireworkSection) {
            if (fwDMode == EntityDisplayMode.Category) lineCount += 1;
            lineCount += fireworksShown.size();
        }
        if (hasPearlSection) {
            if (pDMode == EntityDisplayMode.Category) lineCount += 1;
            lineCount += 1; // single "Ender Pearl" entry row
        }
        if (hasLogoutSection) lineCount += logoutList.size();
        if (hasItemSection) {
            if (iDMode == EntityDisplayMode.Category) lineCount += 1;
            lineCount += itemsShown.size();
        }
        for (int ci = 0; ci < entityCategories.size(); ci++) {
            if (eDMode == EntityDisplayMode.Category) lineCount += 1;
            lineCount += Math.min(entityCategories.get(ci).entries().size(), maxEntityRows.get());
        }
        if (hasSafetyLine) lineCount += 1;

        if (lineCount == 0) { setSize(0, 0); return; }

        double totalW = maxW + padH * 2;
        double totalH = lineCount * lineHeight + (lineCount - 1) * rowGap + padV * 2;
        int lineIdx = 0;

        // ── Draw: Nearby section ──────────────────────────────────────────────

        if (hasNearbySection) {
            if (nDMode == EntityDisplayMode.Category) {
                String hdr = "Nearby: " + nearbyTotal + (nearbyTotal == 1 ? " player" : " players");
                lineIdx = drawSingleText(renderer, s, padH, padV, rowGap, lineHeight,
                    align, totalW, nearbyHeaderW, lineIdx, hdr, headerColor.get());
            }

            for (int i = 0; i < nearbyEntries.size(); i++) {
                TrackedEntry e  = nearbyEntries.get(i);
                NearbyPlayer np = nearbyShown.get(i);
                String distStr  = showDistance.get() ? String.format(" %.0fm", e.nearestDist()) : "";
                String cntStr   = "";
                SettingColor col = statusColor(np.status());
                double rowW     = nearbyRowWidths[i];
                double rowY     = y + padV + lineIdx * (lineHeight + rowGap);

                if (showBackground.get()) {
                    double bgW = rowW + padH * 2;
                    renderer.quad(bgStartX(align, totalW, bgW), rowY - 1, bgW, lineHeight + 2, backgroundColor.get());
                }

                lineIdx = drawTrackedEntryRow(renderer, s, align, totalW, padH, rowY,
                    e.typeName(), cntStr, distStr, rowW,
                    col, labelColor.get(), lineIdx);
            }
        }

        // ── Draw: Online section ──────────────────────────────────────────────

        if (hasOnlineSection) {
            // Friends row
            if (onlineFriends > 0) {
                double rowY = y + padV + lineIdx * (lineHeight + rowGap);
                if (showBackground.get()) {
                    double bgW = onlineFriendW + padH * 2;
                    renderer.quad(bgStartX(align, totalW, bgW), rowY - 1, bgW, lineHeight + 2, backgroundColor.get());
                }
                lineIdx = drawTrackedEntryRow(renderer, s, align, totalW, padH, rowY,
                    "Friends", "", "  " + onlineFriends,
                    onlineFriendW, friendColor.get(), valueColor.get(), lineIdx);
            }

            // Enemies row
            if (onlineEnemies > 0) {
                double rowY = y + padV + lineIdx * (lineHeight + rowGap);
                if (showBackground.get()) {
                    double bgW = onlineEnemyW + padH * 2;
                    renderer.quad(bgStartX(align, totalW, bgW), rowY - 1, bgW, lineHeight + 2, backgroundColor.get());
                }
                lineIdx = drawTrackedEntryRow(renderer, s, align, totalW, padH, rowY,
                    "Enemies", "", "  " + onlineEnemies,
                    onlineEnemyW, enemyColor.get(), valueColor.get(), lineIdx);
            }

            // Proxies row
            if (onlineProxies > 0) {
                double rowY = y + padV + lineIdx * (lineHeight + rowGap);
                if (showBackground.get()) {
                    double bgW = onlineProxyW + padH * 2;
                    renderer.quad(bgStartX(align, totalW, bgW), rowY - 1, bgW, lineHeight + 2, backgroundColor.get());
                }
                lineIdx = drawTrackedEntryRow(renderer, s, align, totalW, padH, rowY,
                    "Proxies", "", "  " + onlineProxies,
                    onlineProxyW, proxyColor.get(), valueColor.get(), lineIdx);
            }
        }

        // ── Draw: Firework section ────────────────────────────────────────────

        if (hasFireworkSection) {
            if (fwDMode == EntityDisplayMode.Category) {
                String fwHdr = "Fireworks: " + fireworkTotal
                    + (fireworkTotal == 1 ? " rocket" : " rockets");
                lineIdx = drawSingleText(renderer, s, padH, padV, rowGap, lineHeight,
                    align, totalW, fwHeaderW, lineIdx, fwHdr, headerColor.get());
            }

            for (int i = 0; i < fireworksShown.size(); i++) {
                FireworkEntry fe = fireworksShown.get(i);
                String cntStr   = showFireworkCount.get()    ? " x" + fe.count()                        : "";
                String distStr  = showFireworkDistance.get() ? String.format(" %.0fm", fe.nearestDist()) : "";
                double rowW     = fwRowWidths[i];
                double rowY     = y + padV + lineIdx * (lineHeight + rowGap);

                if (showBackground.get()) {
                    double bgW = rowW + padH * 2;
                    renderer.quad(bgStartX(align, totalW, bgW), rowY - 1, bgW, lineHeight + 2, backgroundColor.get());
                }

                lineIdx = drawTrackedEntryRow(renderer, s, align, totalW, padH, rowY,
                    fe.shooterName(), cntStr, distStr, rowW,
                    fireworkColor.get(), fireworkMetaColor.get(), lineIdx);
            }
        }

        // ── Draw: Pearl section ───────────────────────────────────────────────

        if (hasPearlSection) {
            if (pDMode == EntityDisplayMode.Category) {
                String pHdr = "Pearls: " + pearlCount + (pearlCount == 1 ? " pearl" : " pearls");
                lineIdx = drawSingleText(renderer, s, padH, padV, rowGap, lineHeight,
                    align, totalW, pearlHeaderW, lineIdx, pHdr, headerColor.get());
            }

            String cntStr  = " x" + pearlCount;
            String distStr = showPearlDistance.get() ? String.format(" %.0fm", pearlNearest) : "";
            double rowY    = y + padV + lineIdx * (lineHeight + rowGap);

            if (showBackground.get()) {
                double bgW = pearlRowW + padH * 2;
                renderer.quad(bgStartX(align, totalW, bgW), rowY - 1, bgW, lineHeight + 2, backgroundColor.get());
            }

            lineIdx = drawTrackedEntryRow(renderer, s, align, totalW, padH, rowY,
                "Nearby Pearls", cntStr, distStr, pearlRowW,
                pearlColor.get(), pearlMetaColor.get(), lineIdx);
        }

        // ── Draw: Logout section ──────────────────────────────────────────────

        if (hasLogoutSection) {
            for (int i = 0; i < logoutList.size(); i++) {
                NeighbourhoodWatch.LogoutRecord r = logoutList.get(i);
                long elapsed = (System.currentTimeMillis() - r.time()) / 1000;
                String timeStr = formatTime(elapsed);
                String posStr = module.formatPos(r.pos());
                String label = r.name();
                
                double rowW = logoutRowWidths[i];
                double rowY = y + padV + lineIdx * (lineHeight + rowGap);

                if (showBackground.get()) {
                    double bgW = rowW + padH * 2;
                    renderer.quad(bgStartX(align, totalW, bgW), rowY - 1, bgW, lineHeight + 2, backgroundColor.get());
                }

                lineIdx = drawTrackedEntryRow(renderer, s, align, totalW, padH, rowY,
                    label, posStr, " " + timeStr, rowW, enemyColor.get(), labelColor.get(), lineIdx);
            }
        }

        // ── Draw: Item section ────────────────────────────────────────────────

        if (hasItemSection) {
            if (iDMode == EntityDisplayMode.Category) {
                lineIdx = drawSingleText(renderer, s, padH, padV, rowGap, lineHeight,
                    align, totalW, itemHeaderW, lineIdx, itemCategory.header(), headerColor.get());
            }

            for (int i = 0; i < itemsShown.size(); i++) {
                TrackedEntry e  = itemsShown.get(i);
                String countStr = showItemCount.get()    ? " x" + e.count()                         : "";
                String distStr  = showItemDistance.get() ? String.format(" %.0fm", e.nearestDist()) : "";
                double rowW     = itemRowWidths[i];
                double rowY     = y + padV + lineIdx * (lineHeight + rowGap);

                if (showBackground.get()) {
                    double bgW = rowW + padH * 2;
                    renderer.quad(bgStartX(align, totalW, bgW), rowY - 1, bgW, lineHeight + 2, backgroundColor.get());
                }

                lineIdx = drawTrackedEntryRow(renderer, s, align, totalW, padH, rowY,
                    e.typeName(), countStr, distStr, rowW,
                    itemColor.get(), itemCountColor.get(), lineIdx);
            }
        }

        // ── Draw: Entity categories ───────────────────────────────────────────

        for (int ci = 0; ci < entityCategories.size(); ci++) {
            TrackedCategory cat  = entityCategories.get(ci);
            SettingColor nameCol = cat.nameColor();

            if (eDMode == EntityDisplayMode.Category) {
                lineIdx = drawSingleText(renderer, s, padH, padV, rowGap, lineHeight,
                    align, totalW, entityCatHeaderW[ci], lineIdx, cat.header(), headerColor.get());
            }

            int shown = Math.min(cat.entries().size(), maxEntityRows.get());
            for (int ei = 0; ei < shown; ei++) {
                TrackedEntry ee = cat.entries().get(ei);
                String cntStr  = showEntityCount.get()    ? " x" + ee.count()                         : "";
                String distStr = showEntityDistance.get() ? String.format(" %.0fm", ee.nearestDist()) : "";
                double rowW    = entityEntryW[ci][ei];
                double rowY    = y + padV + lineIdx * (lineHeight + rowGap);

                if (showBackground.get()) {
                    double bgW = rowW + padH * 2;
                    renderer.quad(bgStartX(align, totalW, bgW), rowY - 1, bgW, lineHeight + 2, backgroundColor.get());
                }

                lineIdx = drawTrackedEntryRow(renderer, s, align, totalW, padH, rowY,
                    ee.typeName(), cntStr, distStr, rowW,
                    nameCol, entityMetaColor.get(), lineIdx);
            }
        }

        // ── Draw: Safety notice ───────────────────────────────────────────────

        if (hasSafetyLine) {
            double rowY = y + padV + lineIdx * (lineHeight + rowGap);
            if (showBackground.get()) {
                double bgW = safetyW + padH * 2;
                renderer.quad(bgStartX(align, totalW, bgW), rowY - 1, bgW, lineHeight + 2, backgroundColor.get());
            }
            double tx = switch (align) {
                case Right  -> x + totalW - padH - safetyW;
                case Center -> x + (totalW - safetyW) / 2.0;
                case Left   -> x + padH;
            };
            renderer.text("! Safety Armed", tx, rowY, safetyColor.get(), false, s);
            lineIdx++;
        }

        setSize(totalW, totalH);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Entity/item building helpers
    // ─────────────────────────────────────────────────────────────────────────

    private TrackedCategory buildMobCategory(
            List<? extends MobEntity> mobs,
            String categoryLabel,
            SettingColor nameColor) {

        if (mobs.isEmpty()) return null;

        Map<String, int[]>   countMap = new LinkedHashMap<>();
        Map<String, float[]> distMap  = new LinkedHashMap<>();

        for (MobEntity mob : mobs) {
            String typeName = mob.getType().getName().getString();
            float  dist     = (float) mc.player.getPos().distanceTo(mob.getPos());
            countMap.computeIfAbsent(typeName, k -> new int[]{0})[0]++;
            distMap.computeIfAbsent(typeName, k -> new float[]{Float.MAX_VALUE});
            if (dist < distMap.get(typeName)[0]) distMap.get(typeName)[0] = dist;
        }

        List<TrackedEntry> entries = new ArrayList<>();
        for (String name : countMap.keySet()) {
            entries.add(new TrackedEntry(name, countMap.get(name)[0], distMap.get(name)[0]));
        }
        entries.sort(Comparator.comparingDouble(TrackedEntry::nearestDist));

        String header = categoryLabel + ": " + mobs.size()
            + (mobs.size() == 1 ? " mob" : " mobs");

        return new TrackedCategory(header, mobs.size(), entries, nameColor);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Draw helpers
    // ─────────────────────────────────────────────────────────────────────────

    private int drawTrackedEntryRow(HudRenderer renderer, double s,
                                    Alignment align, double totalW, double padH,
                                    double rowY,
                                    String typeName, String cntStr, String distStr,
                                    double rowW,
                                    SettingColor nameColor, SettingColor metaColor,
                                    int lineIdx) {
        if (align == Alignment.Right) {
            double cx = x + totalW - padH;
            double dw = renderer.textWidth(distStr,  false, s);
            double cw = renderer.textWidth(cntStr,   false, s);
            double nw = renderer.textWidth(typeName, false, s);
            cx -= dw; renderer.text(distStr,  cx, rowY, metaColor,  false, s);
            cx -= cw; renderer.text(cntStr,   cx, rowY, metaColor,  false, s);
            cx -= nw; renderer.text(typeName, cx, rowY, nameColor,  false, s);
        } else if (align == Alignment.Center) {
            double cx = x + (totalW - rowW) / 2.0;
            renderer.text(typeName, cx, rowY, nameColor, false, s);
            cx += renderer.textWidth(typeName, false, s);
            renderer.text(cntStr,   cx, rowY, metaColor, false, s);
            cx += renderer.textWidth(cntStr, false, s);
            renderer.text(distStr,  cx, rowY, metaColor, false, s);
        } else {
            double cx = x + padH;
            renderer.text(typeName, cx, rowY, nameColor, false, s);
            cx += renderer.textWidth(typeName, false, s);
            renderer.text(cntStr,   cx, rowY, metaColor, false, s);
            cx += renderer.textWidth(cntStr, false, s);
            renderer.text(distStr,  cx, rowY, metaColor, false, s);
        }
        return lineIdx + 1;
    }

    private String formatTime(long seconds) {
        if (seconds < 60) return seconds + "s";
        if (seconds < 3600) return (seconds / 60) + "m";
        return (seconds / 3600) + "h " + ((seconds % 3600) / 60) + "m";
    }

    private double bgStartX(Alignment align, double totalW, double bgW) {
        return switch (align) {
            case Right  -> x + totalW - bgW;
            case Center -> x + (totalW - bgW) / 2.0;
            case Left   -> x;
        };
    }

    private int drawSingleText(HudRenderer renderer, double s,
                               double padH, double padV, double rowGap, double lineHeight,
                               Alignment align, double totalW, double lineW, int lineIdx,
                               String text, SettingColor color) {
        double rowY = y + padV + lineIdx * (lineHeight + rowGap);
        if (showBackground.get()) {
            double bgW = lineW + padH * 2;
            renderer.quad(bgStartX(align, totalW, bgW), rowY - 1, bgW, lineHeight + 2, backgroundColor.get());
        }
        double tx = switch (align) {
            case Right  -> x + totalW - padH - lineW;
            case Center -> x + (totalW - lineW) / 2.0;
            case Left   -> x + padH;
        };
        renderer.text(text, tx, rowY, color, false, s);
        return lineIdx + 1;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Status helpers
    // ─────────────────────────────────────────────────────────────────────────

    private String statusTag(PlayerStatus status) {
        return switch (status) {
            case Friend -> "[F]";
            case Enemy  -> "[E]";
            case Proxy  -> "[P]";
            case Other  -> "[?]";
        };
    }

    private SettingColor statusColor(PlayerStatus status) {
        return switch (status) {
            case Friend -> friendColor.get();
            case Enemy  -> enemyColor.get();
            case Proxy  -> proxyColor.get();
            case Other  -> otherColor.get();
        };
    }
}