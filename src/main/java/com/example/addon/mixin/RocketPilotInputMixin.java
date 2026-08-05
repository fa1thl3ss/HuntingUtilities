package com.example.addon.mixin;

import com.example.addon.modules.RocketPilot;
import meteordevelopment.meteorclient.systems.modules.Modules;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.input.KeyboardInput;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.math.Vec2f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(KeyboardInput.class)
public abstract class RocketPilotInputMixin extends Input {

    @Inject(method = "tick", at = @At("TAIL"))
    private void onTick(CallbackInfo ci) {
        RocketPilot rocketPilot = Modules.get().get(RocketPilot.class);
        if (rocketPilot != null && rocketPilot.isActive() && rocketPilot.useFreeLookY.get()) {
            MinecraftClient mc = MinecraftClient.getInstance();
            if (mc.player != null && mc.player.isGliding()) {
                PlayerInput pi = this.playerInput;
                this.playerInput = new PlayerInput(false, false, false, false, pi.jump(), pi.sneak(), pi.sprint());
                this.movementVector = Vec2f.ZERO;
            }
        }
    }
}