package com.example.addon.mixin;

import com.example.addon.modules.Datamine;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerInteractionManager;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ClientPlayerInteractionManager.class)
public abstract class DatamineMixin {
    @Inject(method = "attackBlock", at = @At("HEAD"), cancellable = true)
    private void onAttack(BlockPos pos, Direction side,
        CallbackInfoReturnable<Boolean> info) {
        this.huntingUtilities$mine(pos, side, info);
    }

    @Inject(method = "updateBlockBreakingProgress",
        at = @At("HEAD"), cancellable = true)
    private void onUpdate(BlockPos pos, Direction side,
        CallbackInfoReturnable<Boolean> info) {
        this.huntingUtilities$mine(pos, side, info);
    }

    @Unique
    private void huntingUtilities$mine(BlockPos pos, Direction side,
        CallbackInfoReturnable<Boolean> info) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Datamine mine = Modules.get().get(Datamine.class);

        if (mine == null || !mine.isActive() || mc.player == null ||
            mc.player.isCreative()) return;

        mine.mine(pos, side);
        info.setReturnValue(true);
    }
}