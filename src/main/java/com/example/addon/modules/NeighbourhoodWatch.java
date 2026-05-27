package com.example.addon.modules;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.game.GameJoinedEvent;
import meteordevelopment.meteorclient.events.packets.PacketEvent;
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
import meteordevelopment.meteorclient.settings.StringListSetting;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.render.color.Color;
import meteordevelopment.meteorclient.utils.render.color.SettingColor;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.network.packet.s2c.play.PlayerListS2CPacket;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;

public class NeighbourhoodWatch extends Module {

    // ═══════════════════════════════════════════════════════════════════════════
    // Enums
    // ═══════════════════════════════════════════════════════════════════════════

    public enum PlayerStatus { Friend, Enemy, Proxy, Other }

    public enum TabEvent   { Join, Leave, Both }
    public enum TabFilter  { Friends, Enemies, Proxies, Others, All }
    public enum FilterMode { Censor, AutoIgnore }

    // ═══════════════════════════════════════════════════════════════════════════
    // Setting Groups
    // ═══════════════════════════════════════════════════════════════════════════

    private final SettingGroup sgSafety      = settings.createGroup("Safety");
    private final SettingGroup sgMsgControl  = settings.createGroup("Message Control");
    private final SettingGroup sgTracking    = settings.createGroup("Player Tracking");
    private final SettingGroup sgFriends     = settings.createGroup("Friends & Enemies");
    private final SettingGroup sgTabList     = settings.createGroup("Tab List Monitoring");

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Safety
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> disconnectOnPlayer = sgSafety.add(new BoolSetting.Builder()
        .name("disconnect-on-player")
        .description("Disconnects when another player is detected nearby.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> playerDetectionRange = sgSafety.add(new IntSetting.Builder()
        .name("player-detection-range")
        .description("Distance within which a player triggers a disconnect.")
        .defaultValue(32).min(1).sliderMax(128)
        .visible(disconnectOnPlayer::get)
        .build()
    );

    private final Setting<Boolean> ignoreFriendsOnDisconnect = sgSafety.add(new BoolSetting.Builder()
        .name("ignore-friends-on-disconnect")
        .description("Does not disconnect if the nearby player is a friend.")
        .defaultValue(true)
        .visible(disconnectOnPlayer::get)
        .build()
    );

    private final Setting<Boolean> ignoreProxiesOnDisconnect = sgSafety.add(new BoolSetting.Builder()
        .name("ignore-proxies-on-disconnect")
        .description("Does not disconnect if the nearby player is a proxy.")
        .defaultValue(true)
        .visible(disconnectOnPlayer::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Message Control
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<FilterMode> filterMode = sgMsgControl.add(new EnumSetting.Builder<FilterMode>()
        .name("mode")
        .description("Censor: replaces matched keywords with XXXX. AutoIgnore: runs /ignorehard on the sender.")
        .defaultValue(FilterMode.Censor)
        .build()
    );

    private final Setting<List<String>> ignoreKeywords = sgMsgControl.add(new StringListSetting.Builder()
        .name("keywords")
        .description("Words to act on. Censor mode redacts them; AutoIgnore mode silences the sender.")
        .defaultValue(List.of())
        .build()
    );

    private final Setting<Boolean> ignoreCaseSensitive = sgMsgControl.add(new BoolSetting.Builder()
        .name("case-sensitive")
        .description("Match keywords with case sensitivity.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Boolean> ignoreNotify = sgMsgControl.add(new BoolSetting.Builder()
        .name("notify")
        .description("Print a local message when a player is auto-ignored (AutoIgnore mode only).")
        .defaultValue(true)
        .visible(() -> filterMode.get() == FilterMode.AutoIgnore)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Player Tracking
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> trackPlayers = sgTracking.add(new BoolSetting.Builder()
        .name("track-players")
        .description("Highlights and notifies when players enter visual range.")
        .defaultValue(false)
        .build()
    );

    private final Setting<Integer> trackRange = sgTracking.add(new IntSetting.Builder()
        .name("track-range")
        .description("Distance within which players are tracked.")
        .defaultValue(128).min(1).sliderMax(256)
        .visible(trackPlayers::get)
        .build()
    );

    private final Setting<TabFilter> trackFilter = sgTracking.add(new EnumSetting.Builder<TabFilter>()
        .name("track-filter")
        .description("Which player category to highlight and notify for.")
        .defaultValue(TabFilter.Enemies)
        .visible(trackPlayers::get)
        .build()
    );

    private final Setting<Boolean> notifyChat = sgTracking.add(new BoolSetting.Builder()
        .name("notify-chat").description("Send a chat message when a player enters range.")
        .defaultValue(true).visible(trackPlayers::get)
        .build()
    );

    private final Setting<String> customMessage = sgTracking.add(new StringSetting.Builder()
        .name("custom-message")
        .description("Notification message. Use {player} for name and {status} for relation.")
        .defaultValue("Warning: {status} {player} is in visual range!")
        .visible(() -> trackPlayers.get() && notifyChat.get())
        .build()
    );

    private final Setting<Boolean> playSound = sgTracking.add(new BoolSetting.Builder()
        .name("play-sound").description("Play a sound when a player enters range.")
        .defaultValue(false).visible(trackPlayers::get)
        .build()
    );

    // ── Highlight rendering ───────────────────────────────────────────────────

    private final Setting<Boolean> glowEnabled = sgTracking.add(new BoolSetting.Builder()
        .name("glow")
        .description("Render a bloom halo around each tracked player in addition to the outline.")
        .defaultValue(true)
        .visible(trackPlayers::get)
        .build()
    );

    private final Setting<Integer> glowLayers = sgTracking.add(new IntSetting.Builder()
        .name("glow-layers").description("Number of bloom layers rendered around each player.")
        .defaultValue(4).min(1).sliderMax(8)
        .visible(() -> trackPlayers.get() && glowEnabled.get())
        .build()
    );

    private final Setting<Double> glowSpread = sgTracking.add(new DoubleSetting.Builder()
        .name("glow-spread").description("How far each bloom layer expands outward (in blocks).")
        .defaultValue(0.05).min(0.01).sliderMax(0.2)
        .visible(() -> trackPlayers.get() && glowEnabled.get())
        .build()
    );

    private final Setting<Integer> glowBaseAlpha = sgTracking.add(new IntSetting.Builder()
        .name("glow-base-alpha").description("Alpha of the innermost glow layer (0-255).")
        .defaultValue(60).min(10).sliderMax(150)
        .visible(() -> trackPlayers.get() && glowEnabled.get())
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Friends & Enemies
    // ═══════════════════════════════════════════════════════════════════════════

    // ── Friends ───────────────────────────────────────────────────────────────

    private final Setting<List<String>> friends = sgFriends.add(new StringListSetting.Builder()
        .name("friends").description("Players treated as friends. Case-insensitive.")
        .defaultValue(List.of()).onChanged(l -> updateFriendEnemySets())
        .visible(() -> isFriendCategoryVisible())
        .build()
    );

    private final Setting<SettingColor> friendColor = sgFriends.add(new ColorSetting.Builder()
        .name("friend-color").description("Highlight color for friends.")
        .defaultValue(new SettingColor(0, 255, 0, 255))
        .visible(() -> trackPlayers.get() && isFriendCategoryVisible())
        .build()
    );

    // ── Enemies ───────────────────────────────────────────────────────────────

    private final Setting<List<String>> enemies = sgFriends.add(new StringListSetting.Builder()
        .name("enemies").description("Players treated as enemies. Case-insensitive.")
        .defaultValue(List.of()).onChanged(l -> updateFriendEnemySets())
        .visible(() -> isEnemyCategoryVisible())
        .build()
    );

    private final Setting<SettingColor> enemyColor = sgFriends.add(new ColorSetting.Builder()
        .name("enemy-color").description("Highlight color for enemies.")
        .defaultValue(new SettingColor(255, 0, 0, 255))
        .visible(() -> trackPlayers.get() && isEnemyCategoryVisible())
        .build()
    );

    // ── Proxies ───────────────────────────────────────────────────────────────

    private final Setting<List<String>> proxies = sgFriends.add(new StringListSetting.Builder()
        .name("proxies").description("Players treated as proxies. Case-insensitive.")
        .defaultValue(List.of()).onChanged(l -> updateFriendEnemySets())
        .visible(() -> isProxyCategoryVisible())
        .build()
    );

    private final Setting<SettingColor> proxyColor = sgFriends.add(new ColorSetting.Builder()
        .name("proxy-color").description("Highlight color for proxies.")
        .defaultValue(new SettingColor(255, 140, 0, 255))
        .visible(() -> trackPlayers.get() && isProxyCategoryVisible())
        .build()
    );

    // ── Others ────────────────────────────────────────────────────────────────

    private final Setting<SettingColor> otherColor = sgFriends.add(new ColorSetting.Builder()
        .name("other-color").description("Highlight color for unknown players.")
        .defaultValue(new SettingColor(139, 0, 0, 255))
        .visible(() -> trackPlayers.get() && isOtherCategoryVisible())
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // Settings — Tab List Monitoring
    // ═══════════════════════════════════════════════════════════════════════════

    private final Setting<Boolean> monitorTabList = sgTabList.add(new BoolSetting.Builder()
        .name("monitor-tab-list")
        .description("Notifies when players join or leave the server via the tab list.")
        .defaultValue(false).build()
    );

    private final Setting<TabEvent> tabEvent = sgTabList.add(new EnumSetting.Builder<TabEvent>()
        .name("event")
        .description("Which tab-list event to notify on.")
        .defaultValue(TabEvent.Join)
        .visible(monitorTabList::get)
        .build()
    );

    private final Setting<TabFilter> tabFilter = sgTabList.add(new EnumSetting.Builder<TabFilter>()
        .name("notify-for")
        .description("Which player category triggers a notification.")
        .defaultValue(TabFilter.Enemies)
        .visible(monitorTabList::get)
        .build()
    );

    // ═══════════════════════════════════════════════════════════════════════════
    // State
    // ═══════════════════════════════════════════════════════════════════════════

    private final Set<Integer> notifiedPlayers    = new HashSet<>();
    private final Set<Integer> activelyOutlined   = new HashSet<>();
    private final Set<String>  ignoredThisSession = new HashSet<>();
    private final Set<String>  playersInTab       = new HashSet<>();
    private final Set<String>  friendSet          = new HashSet<>();
    private final Set<String>  enemySet           = new HashSet<>();
    private final Set<String>  proxySet           = new HashSet<>();

    // ═══════════════════════════════════════════════════════════════════════════
    // Constructor
    // ═══════════════════════════════════════════════════════════════════════════

    public NeighbourhoodWatch() {
        super(HuntingUtilities.CATEGORY, "neighbourhood-watch",
            "Manages player tracking, safety, server monitoring, and keyword alerts.");
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ═══════════════════════════════════════════════════════════════════════════

    @Override
    public void onActivate() {
        resetState();
        updateFriendEnemySets();
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.getPlayerList().forEach(entry -> {
                String name = entry.getProfile().getName();
                if (name != null && !name.isEmpty()) playersInTab.add(name);
            });
        }
    }

    @Override
    public void onDeactivate() {
        if (mc.world != null) {
            for (Entity entity : mc.world.getEntities()) {
                if (activelyOutlined.contains(entity.getId())) {
                    entity.setGlowing(false);
                }
            }
        }
        activelyOutlined.clear();
        resetState();
    }

    @EventHandler
    private void onGameJoined(GameJoinedEvent event) {
        resetState();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tick
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.world == null) return;

        if (tickDisconnectOnPlayer()) return;
        tickPlayerTracking();
        tickOutlineShader();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Outline shader management
    // ═══════════════════════════════════════════════════════════════════════════

    private void tickOutlineShader() {
        if (!trackPlayers.get()) {
            clearAllOutlines();
            return;
        }

        Set<Integer> newlyActive = new HashSet<>();

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isSpectator()) continue;
            if (mc.player.distanceTo(player) > trackRange.get()) continue;

            String       name   = player.getName().getString();
            PlayerStatus status = getPlayerStatusPublic(name);

            boolean shouldHighlight = trackFilter.get() == TabFilter.All || switch (status) {
                case Friend -> trackFilter.get() == TabFilter.Friends;
                case Enemy  -> trackFilter.get() == TabFilter.Enemies;
                case Proxy  -> trackFilter.get() == TabFilter.Proxies;
                case Other  -> trackFilter.get() == TabFilter.Others;
            };
            if (!shouldHighlight) continue;

            SettingColor color = switch (status) {
                case Friend -> friendColor.get();
                case Enemy  -> enemyColor.get();
                case Proxy  -> proxyColor.get();
                case Other  -> otherColor.get();
            };

            player.setGlowing(true);
            setOutlineColor(player, color);
            newlyActive.add(player.getId());
        }

        for (int id : activelyOutlined) {
            if (!newlyActive.contains(id)) {
                Entity e = mc.world.getEntityById(id);
                if (e != null) e.setGlowing(false);
            }
        }

        activelyOutlined.clear();
        activelyOutlined.addAll(newlyActive);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Render 3D — bloom halo
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onRender(Render3DEvent event) {
        if (!glowEnabled.get() || !trackPlayers.get()) return;
        if (mc.world == null || mc.player == null) return;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (!activelyOutlined.contains(player.getId())) continue;

            String       name   = player.getName().getString();
            PlayerStatus status = getPlayerStatusPublic(name);
            SettingColor color  = switch (status) {
                case Friend -> friendColor.get();
                case Enemy  -> enemyColor.get();
                case Proxy  -> proxyColor.get();
                case Other  -> otherColor.get();
            };

            renderGlowLayers(event, player.getBoundingBox(), color);
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Packet Handler — Tab list
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onPacketReceive(PacketEvent.Receive event) {
        if (!monitorTabList.get() || !(event.packet instanceof PlayerListS2CPacket packet)) return;

        for (PlayerListS2CPacket.Entry entry : packet.getEntries()) {
            if (entry.profile() == null) continue;
            String name = entry.profile().getName();
            if (name == null || name.isEmpty()) continue;

            if (packet.getActions().contains(PlayerListS2CPacket.Action.ADD_PLAYER)) {
                if (playersInTab.add(name)) handleTabListChange(name, "joined");
            } else if (packet.getActions().contains(PlayerListS2CPacket.Action.UPDATE_LISTED) && !entry.listed()) {
                if (playersInTab.remove(name)) handleTabListChange(name, "left");
            }
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Chat message listener — Message Control
    // ═══════════════════════════════════════════════════════════════════════════

    @EventHandler
    private void onReceiveMessage(meteordevelopment.meteorclient.events.game.ReceiveMessageEvent event) {
        if (mc.player == null || mc.player.networkHandler == null) return;
        if (ignoreKeywords.get().isEmpty()) return;

        if (filterMode.get() == FilterMode.AutoIgnore) {
            parseMessageForAutoIgnore(event.getMessage().getString());
        } else {
            String censored = censorMessage(event.getMessage().getString());
            if (censored != null) event.setMessage(net.minecraft.text.Text.literal(censored));
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tick Logic
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean tickDisconnectOnPlayer() {
        if (!disconnectOnPlayer.get()) return false;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isCreative() || player.isSpectator()) continue;
            if (ignoreFriendsOnDisconnect.get() && isFriend(player.getName().getString())) continue;
            if (ignoreProxiesOnDisconnect.get() && isProxy(player.getName().getString())) continue;
            if (mc.player.distanceTo(player) <= playerDetectionRange.get()) {
                disconnect("[NeighbourhoodWatch] Player detected: " + player.getName().getString());
                return true;
            }
        }
        return false;
    }

    private void tickPlayerTracking() {
        if (!trackPlayers.get()) return;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player == mc.player || player.isSpectator()) continue;
            if (mc.player.distanceTo(player) > trackRange.get()) continue;

            if (notifiedPlayers.add(player.getId())) {
                if (notifyChat.get()) {
                    String playerName = player.getName().getString();
                    String status     = getPlayerStatusPublic(playerName).name().toLowerCase();
                    String msg        = customMessage.get()
                        .replace("{player}", playerName)
                        .replace("{status}", status);
                    info(msg);
                }
                if (playSound.get()) {
                    mc.player.playSound(SoundEvents.BLOCK_NOTE_BLOCK_BELL.value(), 1.0f, 1.0f);
                }
            }
        }
        notifiedPlayers.removeIf(id -> mc.world.getEntityById(id) == null);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Tab List
    // ═══════════════════════════════════════════════════════════════════════════

    private void handleTabListChange(String playerName, String action) {
        PlayerStatus status = getPlayerStatusPublic(playerName);

        // Check event type filter (Join / Leave / Both)
        if (tabEvent.get() != TabEvent.Both) {
            TabEvent eventType = action.equals("joined") ? TabEvent.Join : TabEvent.Leave;
            if (tabEvent.get() != eventType) return;
        }

        // Check player category filter
        boolean shouldNotify = tabFilter.get() == TabFilter.All || switch (status) {
            case Friend -> tabFilter.get() == TabFilter.Friends;
            case Enemy  -> tabFilter.get() == TabFilter.Enemies;
            case Proxy  -> tabFilter.get() == TabFilter.Proxies;
            case Other  -> tabFilter.get() == TabFilter.Others;
        };
        if (!shouldNotify) return;

        String label = switch (status) {
            case Friend -> "§aFriend";
            case Enemy  -> "§cEnemy";
            case Proxy  -> "§6Proxy";
            case Other  -> "Player";
        };
        info("%s %s has %s the server.", label, playerName, action);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Bloom Rendering
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
                withAlpha(color, layerAlpha),
                withAlpha(color, 0),
                ShapeMode.Sides, 0
            );
        }
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Chat Parsing — Message Control
    // ═══════════════════════════════════════════════════════════════════════════

    /** Extracts sender + body from a raw chat string. Returns null if not parseable. */
    private String[] parseSenderAndBody(String rawMessage) {
        if (rawMessage.startsWith("<")) {
            int close = rawMessage.indexOf('>');
            if (close < 1) return null;
            return new String[]{ rawMessage.substring(1, close).trim(),
                                 rawMessage.substring(close + 1).trim() };
        }
        int colon = rawMessage.indexOf(':');
        if (colon < 1 || colon >= 20) return null;
        String name = rawMessage.substring(0, colon);
        if (name.contains(" ")) return null;
        return new String[]{ name.trim(), rawMessage.substring(colon + 1).trim() };
    }

    /** Returns the first matched keyword in body, or null if none match. */
    private String findKeyword(String body) {
        boolean cs = ignoreCaseSensitive.get();
        String search = cs ? body : body.toLowerCase();
        for (String kw : ignoreKeywords.get()) {
            if (kw.isBlank()) continue;
            if (search.contains(cs ? kw : kw.toLowerCase())) return kw;
        }
        return null;
    }

    /** Censors every matched keyword in rawMessage with Xs. Returns null if nothing matched. */
    private String censorMessage(String rawMessage) {
        boolean cs      = ignoreCaseSensitive.get();
        String  working = rawMessage;
        boolean changed = false;
        for (String kw : ignoreKeywords.get()) {
            if (kw.isBlank()) continue;
            String replacement = "X".repeat(kw.length());
            String replaced = cs
                ? working.replace(kw, replacement)
                : working.replaceAll("(?i)" + java.util.regex.Pattern.quote(kw), replacement);
            if (!replaced.equals(working)) { working = replaced; changed = true; }
        }
        return changed ? working : null;
    }

    private void parseMessageForAutoIgnore(String rawMessage) {
        String[] parts = parseSenderAndBody(rawMessage);
        if (parts == null) return;
        String sender = parts[0], messageBody = parts[1];

        if (sender.equalsIgnoreCase(mc.player.getName().getString())) return;
        if (isFriend(sender) || isProxy(sender)) return;
        if (ignoredThisSession.contains(sender.toLowerCase())) return;
        if (findKeyword(messageBody) == null) return;

        mc.player.networkHandler.sendChatCommand("ignorehard " + sender);
        ignoredThisSession.add(sender.toLowerCase());
        if (ignoreNotify.get()) info("Auto-ignored %s (keyword match).", sender);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Outline Color Injection
    // ═══════════════════════════════════════════════════════════════════════════

    private void setOutlineColor(Entity entity, SettingColor color) {
        try {
            var field = meteordevelopment.meteorclient.utils.render.RenderUtils.class
                .getDeclaredField("entityOutlineColors");
            field.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<Integer, Color> map =
                (java.util.Map<Integer, Color>) field.get(null);
            if (map != null) {
                map.put(entity.getId(), new Color(color.r, color.g, color.b, color.a));
            }
        } catch (NoSuchFieldException | IllegalAccessException ignored) {}
    }

    private void clearAllOutlines() {
        if (mc.world != null) {
            for (int id : activelyOutlined) {
                Entity e = mc.world.getEntityById(id);
                if (e != null) e.setGlowing(false);
            }
        }
        activelyOutlined.clear();
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // General Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private void resetState() {
        notifiedPlayers.clear();
        ignoredThisSession.clear();
        playersInTab.clear();
    }

    private void updateFriendEnemySets() {
        friendSet.clear();
        for (String name : friends.get()) friendSet.add(name.toLowerCase());
        enemySet.clear();
        for (String name : enemies.get()) enemySet.add(name.toLowerCase());
        proxySet.clear();
        for (String name : proxies.get()) proxySet.add(name.toLowerCase());
    }

    private void disconnect(String reason) {
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.getConnection().disconnect(Text.literal(reason));
        }
        this.toggle();
    }

    private SettingColor withAlpha(SettingColor color, int alpha) {
        return new SettingColor(color.r, color.g, color.b, Math.min(255, Math.max(0, alpha)));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Category Visibility Helpers
    // ═══════════════════════════════════════════════════════════════════════════

    private boolean isFriendCategoryVisible() {
        return trackFilter.get() == TabFilter.Friends || trackFilter.get() == TabFilter.All
            || tabFilter.get() == TabFilter.Friends   || tabFilter.get() == TabFilter.All;
    }

    private boolean isEnemyCategoryVisible() {
        return trackFilter.get() == TabFilter.Enemies || trackFilter.get() == TabFilter.All
            || tabFilter.get() == TabFilter.Enemies   || tabFilter.get() == TabFilter.All;
    }

    private boolean isProxyCategoryVisible() {
        return trackFilter.get() == TabFilter.Proxies || trackFilter.get() == TabFilter.All
            || tabFilter.get() == TabFilter.Proxies   || tabFilter.get() == TabFilter.All;
    }

    private boolean isOtherCategoryVisible() {
        return trackFilter.get() == TabFilter.Others || trackFilter.get() == TabFilter.All
            || tabFilter.get() == TabFilter.Others    || tabFilter.get() == TabFilter.All;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // Public API
    // ═══════════════════════════════════════════════════════════════════════════

    public boolean isFriend(String name) { return name != null && friendSet.contains(name.toLowerCase()); }
    public boolean isEnemy(String name)  { return name != null && enemySet.contains(name.toLowerCase()); }
    public boolean isProxy(String name)  { return name != null && proxySet.contains(name.toLowerCase()); }

    public PlayerStatus getPlayerStatusPublic(String name) {
        if (isFriend(name)) return PlayerStatus.Friend;
        if (isEnemy(name))  return PlayerStatus.Enemy;
        if (isProxy(name))  return PlayerStatus.Proxy;
        return PlayerStatus.Other;
    }
}