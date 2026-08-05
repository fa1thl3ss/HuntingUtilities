package com.example.addon.mixin;

import com.example.addon.utils.GlowingRegistry;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.render.entity.state.EntityRenderState;
import net.minecraft.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Overrides the spectral outline color for entities registered in GlowingRegistry.
 */
@Mixin(WorldRenderer.class)
public class EntityGlowingColorMixin {

    @Inject(
        method = "getAndUpdateRenderState",
        at = @At("RETURN")
    )
    private void illushine_overrideOutlineColor(Entity entity, float tickDelta, CallbackInfoReturnable<EntityRenderState> cir) {
        EntityRenderState state = cir.getReturnValue();
        if (state == null) return;
        if (!GlowingRegistry.isGlowing(entity.getId())) return;

        state.outlineColor = GlowingRegistry.getColor(entity.getId());
    }
}
