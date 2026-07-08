package com.example.addon.mixin;

import com.example.addon.modules.Illushine;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.entity.PlayerEntityRenderer;
import net.minecraft.client.render.entity.state.PlayerEntityRenderState;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerEntityRenderer.class)
public class PlayerEntityRendererMixin {

    /**
     * Targets the NEW 1.21.4 RenderState scaling method.
     * This purely scales the 3D model without touching hitboxes or physics!
     */
    @Inject(method = "scale(Lnet/minecraft/client/render/entity/state/PlayerEntityRenderState;Lnet/minecraft/client/util/math/MatrixStack;)V", at = @At("TAIL"))
    private void onScale(PlayerEntityRenderState state, MatrixStack matrices, CallbackInfo ci) {
        if (MinecraftClient.getInstance().world == null) return;

        // In 1.21.4, RenderState holds the entity ID instead of the entity object directly.
        Entity entity = MinecraftClient.getInstance().world.getEntityById(state.id);
        if (!(entity instanceof PlayerEntity player)) return;

        Illushine illushine = Modules.get().get(Illushine.class);
        if (illushine == null || !illushine.isActive()) return;

        float scale = 1.0f;

        // Check if it's the local player (you)
        if (player.equals(MinecraftClient.getInstance().player)) {
            scale = (float) illushine.getPlayerScale();
        } 
        // Check if it's someone else
        else if (illushine.getScaleOtherPlayers()) {
            scale = (float) illushine.getOtherPlayerScale();
        }

        if (scale != 1.0f) {
            matrices.scale(scale, scale, scale);
        }
    }
}