package com.example.addon.modules;

import com.example.addon.HuntingUtilities;
import meteordevelopment.meteorclient.settings.*;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;

import java.lang.reflect.Method;

public class Handhold extends Module {
    
    // ─── Enums ────────────────────────────────────────────────────────────────────
    public enum OrbitSide { Left, Right }

    // ─── Setting Groups ───────────────────────────────────────────────────────────
    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgRotation = settings.createGroup("Rotation");
    private final SettingGroup sgSafety = settings.createGroup("Safety");

    // ─── General Settings ─────────────────────────────────────────────────────────
    private final Setting<String> targetName = sgGeneral.add(new StringSetting.Builder()
        .name("target")
        .description("The username to follow")
        .defaultValue("")
        .build()
    );

    private final Setting<Boolean> lookAtTarget = sgGeneral.add(new BoolSetting.Builder()
        .name("look-at-target")
        .description("Always keep your camera aimed at the target, even when they are on the ground.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disableWhenTargetLands = sgGeneral.add(new BoolSetting.Builder()
        .name("disable-when-target-lands")
        .description("Disable Handhold and Rocket Pilot when the target stops flying")
        .defaultValue(true)
        .build()
    );

    // ─── Proximity & Orbiting Settings ────────────────────────────────────────────
    private final Setting<Double> minFollowDistance = sgGeneral.add(new DoubleSetting.Builder()
        .name("min-follow-distance")
        .description("If closer than this, orbit instead of aiming directly at them.")
        .defaultValue(5.0)
        .min(1.0).max(20.0)
        .sliderRange(1.0, 10.0)
        .build()
    );

    private final Setting<Double> orbitOffset = sgGeneral.add(new DoubleSetting.Builder()
        .name("orbit-offset")
        .description("How many degrees to shift your yaw when orbiting too close.")
        .defaultValue(25.0)
        .min(5.0).max(90.0)
        .sliderRange(10.0, 45.0)
        .build()
    );

    private final Setting<OrbitSide> orbitSide = sgGeneral.add(new EnumSetting.Builder<OrbitSide>()
        .name("orbit-side")
        .description("Which side to orbit on when you get too close.")
        .defaultValue(OrbitSide.Left)
        .build()
    );

    // ─── Rotation Settings ────────────────────────────────────────────────────────
    private final Setting<Double> rotationSpeed = sgRotation.add(new DoubleSetting.Builder()
        .name("rotation-speed")
        .description("How smoothly to turn towards the target (lower = smoother).")
        .defaultValue(0.1)
        .min(0.01).max(1.0)
        .sliderRange(0.02, 0.5)
        .build()
    );

    private final Setting<Boolean> limitRotationSpeed = sgRotation.add(new BoolSetting.Builder()
        .name("limit-rotation-speed")
        .description("Caps rotation speed per tick to reduce anti-cheat flags.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxRotationPerTick = sgRotation.add(new DoubleSetting.Builder()
        .name("max-rotation-per-tick")
        .description("Maximum degrees to rotate per tick.")
        .defaultValue(20.0)
        .min(1.0).max(90.0)
        .sliderRange(5.0, 45.0)
        .visible(limitRotationSpeed::get)
        .build()
    );

    // ─── Safety Settings ──────────────────────────────────────────────────────────
    private final Setting<Boolean> pauseOnObstacle = sgSafety.add(new BoolSetting.Builder()
        .name("pause-on-obstacle")
        .description("Stops looking at target if a wall is in the way, letting RocketPilot avoid it.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> obstaclePauseTicks = sgSafety.add(new IntSetting.Builder()
        .name("obstacle-pause-ticks")
        .description("How many ticks to pause tracking when an obstacle is hit.")
        .defaultValue(15)
        .min(5).max(40)
        .sliderRange(5, 40)
        .visible(pauseOnObstacle::get)
        .build()
    );

    private final Setting<Boolean> endVoidSafety = sgSafety.add(new BoolSetting.Builder()
        .name("end-void-safety")
        .description("Forces RocketPilot to climb if you get too close to the void in The End.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> endVoidTriggerY = sgSafety.add(new DoubleSetting.Builder()
        .name("end-void-trigger-y")
        .description("The Y level to trigger emergency climbing in The End.")
        .defaultValue(15.0)
        .min(5.0).max(60.0)
        .sliderRange(5.0, 30.0)
        .visible(endVoidSafety::get)
        .build()
    );

    private final Setting<Double> endRescueY = sgSafety.add(new DoubleSetting.Builder()
        .name("end-rescue-y")
        .description("The Y level RocketPilot will target during an End void rescue.")
        .defaultValue(60.0)
        .min(20.0).max(128.0)
        .sliderRange(30.0, 100.0)
        .visible(endVoidSafety::get)
        .build()
    );

    private final Setting<Boolean> disableOnLowElytra = sgSafety.add(new BoolSetting.Builder()
        .name("disable-on-low-elytra")
        .description("Disables the module if elytra durability gets too low.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> lowElytraThreshold = sgSafety.add(new DoubleSetting.Builder()
        .name("low-elytra-threshold")
        .description("Percentage of durability to trigger disable.")
        .defaultValue(5.0)
        .min(1.0).max(20.0)
        .sliderRange(1.0, 15.0)
        .visible(disableOnLowElytra::get)
        .build()
    );

    // ─── Internal State ───────────────────────────────────────────────────────────
    private static Method getFlagMethod;

    static {
        try {
            getFlagMethod = Entity.class.getDeclaredMethod("getFlag", int.class);
            getFlagMethod.setAccessible(true);
        } catch (NoSuchMethodException e) {
            HuntingUtilities.LOG.error("Failed to find getFlag method", e);
        }
    }

    private boolean wasTargetFlying = false;
    private boolean forcedRocketPilot = false;
    private int obstaclePauseTimer = 0;
    private boolean hasWarnedNotFound = false;

    public Handhold() {
        super(HuntingUtilities.CATEGORY, "handhold", "Follows a player and relies on Rocket Pilot to fly");
    }

    @Override
    public void onActivate() {
        wasTargetFlying = false;
        forcedRocketPilot = false;
        obstaclePauseTimer = 0;
        hasWarnedNotFound = false;
    }

    @Override
    public void onDeactivate() {
        if (forcedRocketPilot) {
            RocketPilot rp = Modules.get().get(RocketPilot.class);
            if (rp != null && rp.isActive()) rp.toggle();
            forcedRocketPilot = false;
        }
    }

    private PlayerEntity getTarget() {
        if (targetName.get() == null || targetName.get().isEmpty()) return null;
        if (mc.world == null) return null;

        for (PlayerEntity player : mc.world.getPlayers()) {
            if (player != mc.player &&
                player.getName().getString().equalsIgnoreCase(targetName.get())) {
                return player;
            }
        }
        return null;
    }

    private boolean isFallFlying(Entity entity) {
        if (getFlagMethod == null) return false;
        try {
            return (boolean) getFlagMethod.invoke(entity, 7);
        } catch (Exception e) {
            return false;
        }
    }

    private double getElytraDurabilityPercent() {
        ItemStack elytra = mc.player.getInventory().getArmorStack(2);
        if (elytra.isEmpty() || elytra.getItem() != Items.ELYTRA) return 0.0;
        return ((elytra.getMaxDamage() - elytra.getDamage()) / (double) elytra.getMaxDamage()) * 100.0;
    }

    private void lookAtSmooth(Vec3d target) {
        Vec3d diff = target.subtract(mc.player.getEyePos());
        if (diff.lengthSquared() < 0.01) return;

        // Calculate exact target yaw
        double targetYawExact = Math.toDegrees(Math.atan2(-diff.x, diff.z));
        float targetYaw = (float) targetYawExact;
        
        // --- PROXIMITY ORBIT LOGIC ---
        // Calculate only horizontal distance (X and Z)
        double horizontalDist = Math.sqrt(diff.x * diff.x + diff.z * diff.z);
        
        if (horizontalDist < minFollowDistance.get()) {
            float offset = orbitOffset.get().floatValue();
            // Right offset is negative in Minecraft's yaw math
            if (orbitSide.get() == OrbitSide.Right) {
                offset = -offset;
            }
            // Applying a constant offset while close naturally creates a smooth orbit curve
            targetYaw += offset;
        }
        // --------------------------------
        
        float currentYaw = mc.player.getYaw();
        float diffYaw = MathHelper.wrapDegrees(targetYaw - currentYaw);
        
        float desiredChange = diffYaw * rotationSpeed.get().floatValue();
        
        // Anti-cheat hard limit
        if (limitRotationSpeed.get()) {
            desiredChange = MathHelper.clamp(desiredChange, 
                -maxRotationPerTick.get().floatValue(), 
                 maxRotationPerTick.get().floatValue());
        }
        
        // Prevent micro-jitter from floating point inaccuracies
        if (Math.abs(desiredChange) < 0.1f) return;
        
        float newYaw = currentYaw + desiredChange;
        
        // ONLY update the client camera. We DO NOT send a manual packet here.
        mc.player.setYaw(newYaw);
        mc.player.bodyYaw = newYaw;
        mc.player.headYaw = newYaw;
    }

    private boolean isObstacleInWay(Vec3d targetPos) {
        if (!pauseOnObstacle.get()) return false;
        
        BlockHitResult hit = mc.world.raycast(new RaycastContext(
            mc.player.getEyePos(), 
            targetPos,
            RaycastContext.ShapeType.COLLIDER, 
            RaycastContext.FluidHandling.NONE, 
            mc.player
        ));
        
        return hit.getType() == HitResult.Type.BLOCK;
    }

    private void handleDimensionSafety() {
        if (mc.world == null) return;

        if (mc.world.getRegistryKey().equals(World.END) && endVoidSafety.get()) {
            if (mc.player.getY() < endVoidTriggerY.get() && mc.player.isGliding()) {
                RocketPilot rp = Modules.get().get(RocketPilot.class);
                if (rp != null && rp.isActive()) {
                    rp.flightMode.set(RocketPilot.FlightMode.Normal);
                    rp.useTargetY.set(true);
                    rp.targetY.set(endRescueY.get());
                }
            }
        }
    }

    @EventHandler
    private void onTick(TickEvent.Post event) {
        if (mc.world == null || mc.player == null) return;

        PlayerEntity target = getTarget();
        
        if (target == null) {
            if (!hasWarnedNotFound && !targetName.get().isEmpty()) {
                warning("Cannot see '%s'. Are they within render distance?", targetName.get());
                hasWarnedNotFound = true;
            }
            
            if (forcedRocketPilot) {
                RocketPilot rp = Modules.get().get(RocketPilot.class);
                if (rp != null && rp.isActive()) rp.toggle();
                forcedRocketPilot = false;
            }
            return;
        } else {
            if (hasWarnedNotFound) {
                info("Target '%s' found. Keeping an eye on them...", target.getName().getString());
                hasWarnedNotFound = false;
            }
        }

        // Safety Checks
        if (disableOnLowElytra.get() && getElytraDurabilityPercent() <= lowElytraThreshold.get()) {
            error("Elytra durability critically low (%.1f%%)! Disabling.", getElytraDurabilityPercent());
            toggle();
            return;
        }

        handleDimensionSafety();

        boolean targetFlying = isFallFlying(target);

        // 1. KEEP AN EYE ON THEM
        if (lookAtTarget.get()) {
            if (obstaclePauseTimer > 0) {
                obstaclePauseTimer--;
            } else {
                Vec3d lookPos = targetFlying ? 
                    target.getPos().add(target.getVelocity().multiply(5)) : 
                    target.getPos();
                    
                if (mc.player.isGliding() && isObstacleInWay(lookPos)) {
                    obstaclePauseTimer = obstaclePauseTicks.get();
                } else {
                    lookAtSmooth(lookPos);
                }
            }
        }

        // 2. FLIGHT HANDLING
        if (targetFlying) {
            RocketPilot rp = Modules.get().get(RocketPilot.class);
            if (rp != null) {
                if (!rp.flightPattern.get().equals(RocketPilot.FlightPattern.Manual)) {
                    rp.flightPattern.set(RocketPilot.FlightPattern.Manual);
                    info("Rocket Pilot pattern set to Manual for Handhold.");
                }

                if (!rp.isActive()) {
                    rp.toggle();
                    forcedRocketPilot = true;
                    info("Target started flying, enabled Rocket Pilot.");
                }
            }
        } else {
            if (wasTargetFlying) {
                if (disableWhenTargetLands.get()) {
                    if (forcedRocketPilot) {
                        RocketPilot rp = Modules.get().get(RocketPilot.class);
                        if (rp != null && rp.isActive()) rp.toggle();
                        forcedRocketPilot = false;
                    }
                    info("Target landed, disabling Handhold.");
                    this.toggle();
                    return;
                } else {
                    if (forcedRocketPilot) {
                        RocketPilot rp = Modules.get().get(RocketPilot.class);
                        if (rp != null && rp.isActive()) rp.toggle();
                        forcedRocketPilot = false;
                        info("Target landed, disabled Rocket Pilot.");
                    }
                }
            }
        }

        wasTargetFlying = targetFlying;
    }

    @Override
    public String getInfoString() {
        PlayerEntity target = getTarget();
        if (target == null) return "Searching...";
        return target.getName().getString() + (isFallFlying(target) ? " ✈" : " 👁");
    }
}