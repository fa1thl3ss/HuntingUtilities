package com.example.addon.mixin;

import com.example.addon.modules.NeighbourhoodWatch;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.hud.PlayerListHud;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.text.Text;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Colors friends/enemies in the tab list.
 */
@Mixin(PlayerListHud.class)
public class PlayerListHudMixin {

    @Redirect(
        method = "render",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/client/gui/DrawContext;drawTextWithShadow(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/Text;III)V"
        )
    )
    private void huntingUtilities$colorName(DrawContext instance, TextRenderer textRenderer, Text text, int x, int y, int color) {
        instance.drawTextWithShadow(textRenderer, text, x, y, huntingUtilities$resolveColor(text, color));
    }

    private int huntingUtilities$resolveColor(Text text, int fallback) {
        NeighbourhoodWatch nw = Modules.get().get(NeighbourhoodWatch.class);
        if (nw == null || !nw.isActive()) return fallback;

        boolean wantFriends = nw.shouldColorFriendInTab();
        boolean wantEnemies = nw.shouldColorEnemyInTab();
        if (!wantFriends && !wantEnemies) return fallback;

        String name = huntingUtilities$profileNameFor(text);
        if (name == null) return fallback;

        if (wantFriends && nw.isFriend(name)) return nw.getFriendTabColor().getPacked();
        if (wantEnemies && nw.isEnemy(name))  return nw.getEnemyTabColor().getPacked();
        return fallback;
    }

    /**
     * Maps the drawn display text back to a real profile name. The tab list may show a
     * decorated display name (team prefixes, nicknames), so we compare against each
     * entry's rendered display name rather than assuming the text is the raw name.
     */
    private String huntingUtilities$profileNameFor(Text text) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.getNetworkHandler() == null) return null;

        String drawn = text.getString();
        String fallback = null;

        for (PlayerListEntry entry : mc.getNetworkHandler().getPlayerList()) {
            String profileName = entry.getProfile().name();
            if (profileName == null) continue;

            Text display = entry.getDisplayName();
            if (display != null && drawn.equals(display.getString())) return profileName;
            if (drawn.equals(profileName)) return profileName;
            // Decorated names (prefix/suffix) still contain the profile name.
            if (fallback == null && drawn.contains(profileName)) fallback = profileName;
        }
        return fallback;
    }
}
