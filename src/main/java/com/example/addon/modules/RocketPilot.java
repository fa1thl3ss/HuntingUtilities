package com.example.addon.modules;

import com.example.addon.HuntingUtilities;

import java.util.HashSet;
import java.util.Set;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.IntSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.systems.modules.Modules;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.meteorclient.utils.player.InvUtils;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.s2c.play.PlayerPositionLookS2CPacket;
import net.minecraft.stat.Stats;
import net.minecraft.text.Text;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

public class RocketPilot extends Module {

    // ─── Enums ───────────────────────────────────────────────────────────────────
    public enum FlightMode { None, Normal, Oscillation, Pitch40, AltitudeBounce }

    public enum FlightPattern {
        Manual,
        Drunk,
        Grid,
        Circle,
        ZigZag,
        FigureEight,
        Sweep
    }

    public enum DrunkBias { None, North, South, East, West, PositiveOnly, NegativeOnly, NegPos, PosNeg }

    // ─── Constants ───────────────────────────────────────────────────────────────
    private static final int   TAKEOFF_GRACE_TICKS       = 40;
    private static final float ELYTRA_LOW_PERCENT        = 5.0f;
    private static final int   ELYTRA_MIN_SWAP_DUR       = 50;
    private static final long  COLLISION_ROCKET_COOLDOWN = 200L;

    // ─── Setting Groups ───────────────────────────────────────────────────────────
    private final SettingGroup sgFlight       = settings.createGroup("Flight");
    private final SettingGroup sgPitch40      = settings.createGroup("Pitch40");
    private final SettingGroup sgOscillation  = settings.createGroup("Oscillation");
    private final SettingGroup sgBounce       = settings.createGroup("Altitude Bounce");
    private final SettingGroup sgSweep        = settings.createGroup("Sweep Pattern");
    private final SettingGroup sgPatterns     = settings.createGroup("Patterns");
    private final SettingGroup sgDrunk        = settings.createGroup("DrunkPilot");
    private final SettingGroup sgFlightSafety = settings.createGroup("Flight Safety");
    private final SettingGroup sgPlayerSafety = settings.createGroup("Player Safety");

    // ─── Flight Settings ─────────────────────────────────────────────────────────
    public final Setting<Boolean> useTargetY = sgFlight.add(new BoolSetting.Builder()
        .name("use-target-y")
        .description("Whether to maintain a specific Y level.")
        .defaultValue(true)
        .build()
    );

    public final Setting<Double> targetY = sgFlight.add(new DoubleSetting.Builder()
        .name("target-y")
        .description("The Y level to maintain.")
        .defaultValue(120.0)
        .min(-64).max(10000)
        .sliderRange(0, 10000)
        .visible(useTargetY::get)
        .build()
    );

    public final Setting<Double> flightTolerance = sgFlight.add(new DoubleSetting.Builder()
        .name("flight-tolerance")
        .description("Allowable drop below target Y before climbing.")
        .defaultValue(2.0)
        .min(0.5).max(10.0)
        .sliderRange(1.0, 5.0)
        .build()
    );

    public final Setting<Boolean> useFreeLookY = sgFlight.add(new BoolSetting.Builder()
        .name("use-freelook-y")
        .description("Render the camera at a specific Y level while flying.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Double> freeLookY = sgFlight.add(new DoubleSetting.Builder()
        .name("freelook-y")
        .description("The Y level to render the camera at.")
        .defaultValue(120.0)
        .min(-64).max(320)
        .sliderRange(0, 256)
        .visible(useFreeLookY::get)
        .build()
    );

    private final Setting<Keybind> toggleFreeLookY = sgFlight.add(new KeybindSetting.Builder()
        .name("toggle-freelook-y")
        .description("Key to toggle the freelook Y feature.")
        .defaultValue(Keybind.none())
        .action(() -> {
            if (mc.currentScreen != null) return;
            boolean newVal = !useFreeLookY.get();
            useFreeLookY.set(newVal);
            info("Freelook Y " + (newVal ? "enabled" : "disabled") + ".");
        })
        .build()
    );

    private final Setting<Boolean> autoTakeoff = sgFlight.add(new BoolSetting.Builder()
        .name("auto-takeoff")
        .description("Automatically jump and fire a rocket to start elytra flight.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> disableOnLand = sgFlight.add(new BoolSetting.Builder()
        .name("disable-on-land")
        .description("Automatically disable the module when you land.")
        .defaultValue(false)
        .build()
    );

    public final Setting<Integer> rocketDelay = sgFlight.add(new IntSetting.Builder()
        .name("rocket-delay")
        .description("Delay in milliseconds between rockets.")
        .defaultValue(2000)
        .min(100)
        .sliderRange(500, 5000)
        .build()
    );

    public final Setting<Boolean> silentRockets = sgFlight.add(new BoolSetting.Builder()
        .name("silent-rockets")
        .description("Suppresses the hand swing animation when firing rockets.")
        .defaultValue(true)
        .build()
    );

    public final Setting<FlightMode> flightMode = sgFlight.add(new EnumSetting.Builder<FlightMode>()
        .name("flight-mode")
        .description("The primary flight mode for pitch control.")
        .defaultValue(FlightMode.Normal)
        .onChanged(v -> {
            if (!isActive() || mc.world == null) return;
            resetPatternState();
            switch (v) {
                case Oscillation    -> info("Oscillation mode enabled.");
                case Pitch40        -> info("Pitch40 mode enabled.");
                case AltitudeBounce -> info("Altitude Bounce mode enabled.");
                case None           -> info("Flight pitch control disabled.");
                default             -> info("Normal flight mode enabled.");
            }
        })
        .build()
    );

    public final Setting<Double> pitchSmoothing = sgFlight.add(new DoubleSetting.Builder()
        .name("pitch-smoothing")
        .description("How smoothly pitch changes in Normal and Pattern modes (lower = smoother).")
        .defaultValue(0.15)
        .min(0.01).max(1.0)
        .sliderRange(0.05, 0.5)
        .visible(() -> flightMode.get() == FlightMode.Normal)
        .build()
    );

    // ─── Pitch40 Settings ────────────────────────────────────────────────────────
    private final Setting<Double> pitch40UpperY = sgPitch40.add(new DoubleSetting.Builder()
        .name("upper-y")
        .description("Upper Y-level ceiling; stop climbing above this.")
        .defaultValue(120.0)
        .min(-64).max(320)
        .sliderRange(0, 256)
        .visible(() -> flightMode.get() == FlightMode.Pitch40)
        .build()
    );

    private final Setting<Double> pitch40LowerY = sgPitch40.add(new DoubleSetting.Builder()
        .name("lower-y")
        .description("Lower Y-level floor; start climbing below this.")
        .defaultValue(110.0)
        .min(-64).max(320)
        .sliderRange(0, 256)
        .visible(() -> flightMode.get() == FlightMode.Pitch40)
        .build()
    );

    private final Setting<Double> pitch40Smoothing = sgPitch40.add(new DoubleSetting.Builder()
        .name("smoothing")
        .description("How smoothly to adjust pitch in Pitch40 mode.")
        .defaultValue(0.05)
        .min(0.01).max(1.0)
        .visible(() -> flightMode.get() == FlightMode.Pitch40)
        .build()
    );

    private final Setting<Integer> pitch40BelowMinDelay = sgPitch40.add(new IntSetting.Builder()
        .name("below-min-delay")
        .description("Time in ms to remain below lower-y before firing rockets.")
        .defaultValue(8000)
        .min(1000)
        .sliderRange(1000, 10000)
        .visible(() -> flightMode.get() == FlightMode.Pitch40)
        .build()
    );

    // ─── Pattern Settings ─────────────────────────────────────────────────────────
    public final Setting<FlightPattern> flightPattern = sgPatterns.add(new EnumSetting.Builder<FlightPattern>()
        .name("flight-pattern")
        .description("The flight pattern to follow. Manual allows free mouse look.")
        .defaultValue(FlightPattern.Manual)
        .onChanged(v -> { if (isActive()) resetPatternState(); })
        .build()
    );

    // ─── Sweep Pattern Settings ──────────────────────────────────────────────────
    private final Setting<Integer> sweepWidth = sgSweep.add(new IntSetting.Builder()
        .name("sweep-width")
        .description("Total side-to-side distance in chunks.")
        .defaultValue(10).min(1).sliderRange(1, 50)
        .visible(() -> flightPattern.get() == FlightPattern.Sweep)
        .build()
    );

    private final Setting<Integer> sweepAdvance = sgSweep.add(new IntSetting.Builder()
        .name("sweep-advance")
        .description("Forward distance moved per sweep in chunks.")
        .defaultValue(2).min(1).sliderRange(1, 20)
        .visible(() -> flightPattern.get() == FlightPattern.Sweep)
        .build()
    );

    private final Setting<Double> sweepExpansionRate = sgSweep.add(new DoubleSetting.Builder()
        .name("sweep-expansion-rate")
        .description("Percentage increase in sweep width/advance per full cycle (e.g., 0.1 for 10% increase).")
        .defaultValue(0.0)
        .min(0.0).max(0.5)
        .sliderRange(0.0, 0.2)
        .visible(() -> flightPattern.get() == FlightPattern.Sweep)
        .build()
    );

    private final Setting<Double> sweepMaxFactor = sgSweep.add(new DoubleSetting.Builder()
        .name("sweep-max-factor")
        .description("Maximum multiplier for sweep width/advance (e.g., 2.0 for double the initial size).")
        .defaultValue(1.0)
        .min(1.0).max(5.0)
        .sliderRange(1.0, 3.0)
        .visible(() -> flightPattern.get() == FlightPattern.Sweep && sweepExpansionRate.get() > 0.0)
        .build()
    );

    private final Setting<Boolean> sweepAutoUpdate = sgSweep.add(new BoolSetting.Builder()
        .name("auto-update-origin")
        .description("Relocates the sweep pattern origin to your position if you manually fly too far from the current target.")
        .defaultValue(true)
        .visible(() -> flightPattern.get() == FlightPattern.Sweep)
        .build()
    );

    // ─── Oscillation Settings ────────────────────────────────────────────────────
    public final Setting<Double> oscillationSpeed = sgOscillation.add(new DoubleSetting.Builder()
        .name("oscillation-speed")
        .description("Speed of the pitch wave cycle (higher = faster).")
        .defaultValue(0.08)
        .min(0.01).max(0.5)
        .sliderRange(0.02, 0.2)
        .visible(() -> flightMode.get() == FlightMode.Oscillation)
        .build()
    );

    private final Setting<Integer> oscillationRocketDelay = sgOscillation.add(new IntSetting.Builder()
        .name("oscillation-rocket-delay")
        .description("Minimum delay between rockets in oscillation mode (ms).")
        .defaultValue(350)
        .min(0)
        .visible(() -> flightMode.get() == FlightMode.Oscillation)
        .build()
    );

    private final Setting<Boolean> oscillationRockets = sgOscillation.add(new BoolSetting.Builder()
        .name("oscillation-rockets")
        .description("Fire rockets at the peak of the upward pitch cycle.")
        .defaultValue(true)
        .visible(() -> flightMode.get() == FlightMode.Oscillation)
        .build()
    );

    // ─── Altitude Bounce Settings ─────────────────────────────────────────────────
    private final Setting<Double> bounceClimbPitch = sgBounce.add(new DoubleSetting.Builder()
        .name("climb-pitch")
        .description("Pitch angle while climbing aggressively (negative = nose up).")
        .defaultValue(-35.0)
        .min(-60.0).max(-5.0)
        .sliderRange(-50.0, -10.0)
        .visible(() -> flightMode.get() == FlightMode.AltitudeBounce)
        .build()
    );

    private final Setting<Double> bounceGlidePitch = sgBounce.add(new DoubleSetting.Builder()
        .name("glide-pitch")
        .description("Pitch angle during the glide descent phase (positive = nose down).")
        .defaultValue(20.0)
        .min(5.0).max(60.0)
        .sliderRange(5.0, 45.0)
        .visible(() -> flightMode.get() == FlightMode.AltitudeBounce)
        .build()
    );

    private final Setting<Double> bouncePeakY = sgBounce.add(new DoubleSetting.Builder()
        .name("peak-y")
        .description("Y level to reach before cutting rockets and beginning the glide.")
        .defaultValue(130.0)
        .min(-64.0).max(10000.0)
        .sliderRange(64.0, 256.0)
        .visible(() -> flightMode.get() == FlightMode.AltitudeBounce)
        .build()
    );

    private final Setting<Double> bounceFloorY = sgBounce.add(new DoubleSetting.Builder()
        .name("floor-y")
        .description("Y level at which the glide ends and the climb begins again.")
        .defaultValue(100.0)
        .min(-64.0).max(320.0)
        .sliderRange(64.0, 256.0)
        .visible(() -> flightMode.get() == FlightMode.AltitudeBounce)
        .build()
    );

    private final Setting<Double> bouncePitchSmoothing = sgBounce.add(new DoubleSetting.Builder()
        .name("pitch-smoothing")
        .description("How smoothly to transition between climb and glide pitches.")
        .defaultValue(0.08)
        .min(0.01).max(1.0)
        .sliderRange(0.02, 0.3)
        .visible(() -> flightMode.get() == FlightMode.AltitudeBounce)
        .build()
    );

    // ─── Pattern Settings ─────────────────────────────────────────────────────────
    private final Setting<Keybind> pauseKey = sgPatterns.add(new KeybindSetting.Builder()
        .name("pause-key")
        .description("Pauses/resumes the current flight pattern.")
        .defaultValue(Keybind.none())
        .action(this::togglePause)
        .visible(() -> isPatternMode())
        .build()
    );

    private final Setting<Double> patternTurnSpeed = sgPatterns.add(new DoubleSetting.Builder()
        .name("turn-speed")
        .description("How quickly to yaw toward pattern waypoints.")
        .defaultValue(0.1)
        .min(0.01).max(1.0)
        .sliderRange(0.05, 0.5)
        .visible(() -> flightPattern.get() != FlightPattern.Manual && flightPattern.get() != FlightPattern.Drunk)
        .build()
    );

    private final Setting<Integer> waypointReachRadius = sgPatterns.add(new IntSetting.Builder()
        .name("waypoint-reach-radius")
        .description("Horizontal distance (blocks) to a waypoint before advancing.")
        .defaultValue(30)
        .min(5)
        .sliderRange(10, 100)
        .visible(() -> flightPattern.get() != FlightPattern.Manual && flightPattern.get() != FlightPattern.Drunk)
        .build()
    );

    private final Setting<Integer> gridSpacing = sgPatterns.add(new IntSetting.Builder()
        .name("grid-spacing")
        .description("Distance between grid lines in chunks.")
        .defaultValue(8)
        .min(1)
        .sliderRange(1, 32)
        .visible(() -> flightPattern.get() == FlightPattern.Grid)
        .build()
    );

    private final Setting<Integer> circleSegments = sgPatterns.add(new IntSetting.Builder()
        .name("circle-segments")
        .description("Number of waypoints per full spiral rotation.")
        .defaultValue(32)
        .min(4)
        .sliderRange(8, 128)
        .visible(() -> flightPattern.get() == FlightPattern.Circle)
        .build()
    );

    private final Setting<Integer> circleExpansion = sgPatterns.add(new IntSetting.Builder()
        .name("circle-expansion")
        .description("How many chunks the radius increases per rotation.")
        .defaultValue(4)
        .min(1)
        .sliderRange(1, 16)
        .visible(() -> flightPattern.get() == FlightPattern.Circle)
        .build()
    );

    private final Setting<Integer> zigzagLegLength = sgPatterns.add(new IntSetting.Builder()
        .name("zigzag-leg-length")
        .description("Length of each zigzag leg in chunks.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 50)
        .visible(() -> flightPattern.get() == FlightPattern.ZigZag)
        .build()
    );

    private final Setting<Double> zigzagAngle = sgPatterns.add(new DoubleSetting.Builder()
        .name("zigzag-angle")
        .description("Turn angle at each ZigZag corner (degrees from forward).")
        .defaultValue(45.0)
        .min(10.0).max(80.0)
        .sliderRange(10.0, 80.0)
        .visible(() -> flightPattern.get() == FlightPattern.ZigZag)
        .build()
    );

    private final Setting<Integer> figureEightRadius = sgPatterns.add(new IntSetting.Builder()
        .name("figure-eight-radius")
        .description("Radius of the loops in chunks.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 20)
        .visible(() -> flightPattern.get() == FlightPattern.FigureEight)
        .build()
    );

    // ─── DrunkPilot Settings ──────────────────────────────────────────────────────
    private final Setting<Integer> drunkInterval = sgDrunk.add(new IntSetting.Builder()
        .name("change-interval")
        .description("Ticks between direction changes.")
        .defaultValue(5)
        .min(1)
        .sliderRange(1, 20)
        .visible(() -> flightPattern.get() == FlightPattern.Drunk)
        .build()
    );

    private final Setting<Double> drunkIntensity = sgDrunk.add(new DoubleSetting.Builder()
        .name("intensity")
        .description("Maximum yaw change per update (degrees). Applied when coordinate-bias is None.")
        .defaultValue(120.0)
        .min(1.0).max(180.0)
        .sliderRange(50.0, 180.0)
        .visible(() -> flightPattern.get() == FlightPattern.Drunk)
        .build()
    );

    public final Setting<DrunkBias> drunkBias = sgDrunk.add(new EnumSetting.Builder<DrunkBias>()
        .name("coordinate-bias")
        .description("Constrains drunk-pilot heading. None = fully random.")
        .defaultValue(DrunkBias.None)
        .visible(() -> flightPattern.get() == FlightPattern.Drunk)
        .build()
    );

    private final Setting<Boolean> drunkAvoidVisited = sgDrunk.add(new BoolSetting.Builder()
        .name("avoid-visited")
        .description("Attempts to steer the Drunk Pilot away from chunks it has already flown over.")
        .defaultValue(true)
        .visible(() -> flightPattern.get() == FlightPattern.Drunk)
        .build()
    );

    private final Setting<Double> drunkSmoothing = sgDrunk.add(new DoubleSetting.Builder()
        .name("smoothing")
        .description("How smoothly to rotate to the new heading (lower = smoother).")
        .defaultValue(0.05)
        .min(0.01).max(1.0)
        .visible(() -> flightPattern.get() == FlightPattern.Drunk)
        .build()
    );

    // ─── Flight Safety Settings ───────────────────────────────────────────────────
    private final Setting<Boolean> collisionAvoidance = sgFlightSafety.add(new BoolSetting.Builder()
        .name("collision-avoidance")
        .description("Attempts to avoid flying straight into walls.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> avoidanceDistance = sgFlightSafety.add(new IntSetting.Builder()
        .name("avoidance-distance")
        .description("How far ahead to look for obstacles (blocks).")
        .defaultValue(10)
        .min(3)
        .sliderRange(5, 20)
        .visible(collisionAvoidance::get)
        .build()
    );

    private final Setting<Boolean> netherCeilingSafety = sgFlightSafety.add(new BoolSetting.Builder()
        .name("nether-ceiling-safety")
        .description("Automatically pitches down when approaching the nether bedrock ceiling (Y=128) to prevent death.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> netherCeilingBuffer = sgFlightSafety.add(new IntSetting.Builder()
        .name("nether-ceiling-buffer")
        .description("How many blocks below the ceiling to start diving.")
        .defaultValue(15)
        .min(3)
        .sliderRange(5, 30)
        .visible(netherCeilingSafety::get)
        .build()
    );

    private final Setting<Boolean> limitRotationSpeed = sgFlightSafety.add(new BoolSetting.Builder()
        .name("limit-rotation-speed")
        .description("Caps rotation speed per tick to reduce anti-cheat flags.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> maxRotationPerTick = sgFlightSafety.add(new DoubleSetting.Builder()
        .name("max-rotation-per-tick")
        .description("Maximum degrees to rotate per tick.")
        .defaultValue(20.0)
        .min(1.0).max(90.0)
        .sliderRange(5.0, 45.0)
        .visible(limitRotationSpeed::get)
        .build()
    );

    private final Setting<Keybind> panicKey = sgFlightSafety.add(new KeybindSetting.Builder()
        .name("panic-key")
        .description("Immediately disconnects from the server and disables the module.")
        .defaultValue(Keybind.none())
        .action(this::panicDisconnect)
        .build()
    );

    // ─── Player Safety Settings ───────────────────────────────────────────────────
    private final Setting<Boolean> autoDisableOnLowHealth = sgPlayerSafety.add(new BoolSetting.Builder()
        .name("auto-disable-on-low-health")
        .description("Disables the module if health is critically low while a totem is equipped.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Integer> lowHealthThreshold = sgPlayerSafety.add(new IntSetting.Builder()
        .name("low-health-threshold")
        .description("Health level (hearts) to trigger auto-disable.")
        .defaultValue(3)
        .min(1).max(10)
        .sliderRange(1, 5)
        .visible(autoDisableOnLowHealth::get)
        .build()
    );

    private final Setting<Boolean> disconnectOnTotemPop = sgPlayerSafety.add(new BoolSetting.Builder()
        .name("disconnect-on-totem-pop")
        .description("Disconnect from the server if a totem of undying is consumed.")
        .defaultValue(false)
        .build()
    );

    // ─── Internal State ───────────────────────────────────────────────────────────
    public  long    lastRocketTime           = 0;
    private boolean needsTakeoffRocket       = false;
    private boolean ascentMode               = false;
    private final Set<Long> drunkVisitedChunks = new HashSet<>();
    private boolean pitch40Climbing          = false;
    private boolean pitch40Rocketing         = false;
    private long    pitch40BelowMinStartTime = -1;
    private long    lastLagbackTime          = 0;
    private boolean bounceClimbing           = true;

    private float   targetPitch              = 0;
    private int     waveTicks                = 0;
    private int     drunkTimer               = 0;
    private float   targetDrunkYaw           = 0;
    private int     currentDrunkDuration     = 0;
    private boolean ceilingWarningSent       = false;
    private int     totemPops                = 0;
    private int     takeoffTimer             = 0;
    private int     takeoffWaitTicks         = 0;

    // Pattern flight state
    private boolean paused              = false;
    private Vec3d   origin              = null;
    private Vec3d   currentTarget       = null;
    private int     gridStep            = 1;
    private int     gridStepsInLeg      = 0;
    private int     gridDirection       = 0;
    private float   zigzagCurrentYaw    = 0;
    private boolean zigzagTurnRight     = true;
    private boolean zigzagFirstLeg      = true;
    private double  circleAngle         = 0;
    private int     sweepStep           = 0;
    private double  currentSweepFactor  = 1.0;
    private float   sweepInitialYaw     = 0;
    private int     figureEightWaypoint = 0;

    // ─── Constructor ─────────────────────────────────────────────────────────────
    public RocketPilot() {
        super(HuntingUtilities.CATEGORY, "rocket-pilot",
            "Automatic elytra + rocket flight with height maintenance, auto-takeoff, and pattern flight.");
    }

    // ─── Utilities ───────────────────────────────────────────────────────────────
    private boolean isPatternMode() {
        return flightPattern.get() != FlightPattern.Manual && flightPattern.get() != FlightPattern.Drunk;
    }

    private void togglePause() {
        if (mc.currentScreen != null) return;
        if (flightPattern.get() == FlightPattern.Manual || flightPattern.get() == FlightPattern.Drunk) return;
        paused = !paused;
        info("Pattern flight %s.", paused ? "paused" : "resumed");
    }

    private void panicDisconnect() {
        if (mc.currentScreen != null) return;
        if (mc.player == null) return;
        info("Panic disconnect triggered!");
        disconnect("[RocketPilot] Panic disconnect.");
    }

    private void resetPatternState() {
        paused              = false;
        origin              = null;
        currentTarget       = null;
        gridStep            = 1;
        gridStepsInLeg      = 0;
        gridDirection       = 0;
        zigzagCurrentYaw    = 0;
        zigzagTurnRight     = true;
        zigzagFirstLeg      = true;
        circleAngle         = 0;
        sweepStep           = 0;
        currentSweepFactor  = 1.0;
        sweepInitialYaw     = 0;
        drunkVisitedChunks.clear();
        figureEightWaypoint = 0;
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────────
    @Override
    public void onActivate() {
        lastRocketTime           = 0;
        needsTakeoffRocket       = false;
        waveTicks                = 0;
        drunkTimer               = 0;
        currentDrunkDuration     = 0;
        ascentMode               = false;
        pitch40Climbing          = false;
        pitch40Rocketing         = false;
        pitch40BelowMinStartTime = -1;
        bounceClimbing           = true;
        lastLagbackTime          = 0;
        ceilingWarningSent       = false;
        takeoffTimer             = 0;
        takeoffWaitTicks         = 0;
        drunkVisitedChunks.clear();

        resetPatternState();

        if (mc.player == null || mc.world == null) { toggle(); return; }

        totemPops      = mc.player.getStatHandler().getStat(Stats.USED, Items.TOTEM_OF_UNDYING);
        targetPitch    = mc.player.getPitch();
        targetDrunkYaw = mc.player.getYaw();

        if (mc.player.isGliding()) return;
        if (!autoTakeoff.get())    return;

        ItemStack elytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.isOf(Items.ELYTRA)) {
            error("No elytra equipped.");
            toggle();
            return;
        }
        if (countFireworks() == 0) {
            error("No fireworks in inventory.");
            toggle();
            return;
        }
        if (!isNearGround()) {
            warning("Not on ground — auto-takeoff skipped.");
            return;
        }

        targetPitch = -28.0f;
        mc.player.setPitch(targetPitch);
        mc.player.jump();
        needsTakeoffRocket = true;
        info("Taking off!");
    }

    @Override
    public void onDeactivate() {
        needsTakeoffRocket = false;
        takeoffWaitTicks   = 0;
        resetPatternState();
    }

    // ─── Main Tick ────────────────────────────────────────────────────────────────
    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (System.currentTimeMillis() - lastLagbackTime < 500) return;
        if (mc.player == null || mc.world == null) return;

        replenishRockets();

        if (disconnectOnTotemPop.get()) {
            int currentPops = mc.player.getStatHandler().getStat(Stats.USED, Items.TOTEM_OF_UNDYING);
            if (currentPops > totemPops) {
                error("Totem popped! Disconnecting...");
                disconnect("[RocketPilot] Disconnected on totem pop.");
                return;
            }
        }

        if (autoDisableOnLowHealth.get()) {
            boolean hasTotem = mc.player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)
                            || mc.player.getMainHandStack().isOf(Items.TOTEM_OF_UNDYING);
            if (hasTotem && mc.player.getHealth() <= lowHealthThreshold.get() * 2f) {
                error("Health critical (%.1f hp), disabling.", mc.player.getHealth());
                toggle();
                return;
            }
        }

        ItemStack elytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.isOf(Items.ELYTRA)) {
            error("Elytra missing — disabling.");
            toggle();
            return;
        }

        if (takeoffTimer > 0) takeoffTimer--;

        if (disableOnLand.get() && mc.player.isOnGround() && !needsTakeoffRocket && takeoffTimer == 0) {
            info("Landed — disabling.");
            toggle();
            return;
        }

        if (isNearGround() && !mc.player.isGliding()
                && (!useTargetY.get() || mc.player.getY() < targetY.get())
                && autoTakeoff.get() && countFireworks() > 0 && !needsTakeoffRocket) {
            targetPitch = -28.0f;
            mc.player.setPitch(targetPitch);
            if (mc.player.isOnGround()) mc.player.jump();
            needsTakeoffRocket = true;
            takeoffWaitTicks   = 0;
            info("Re-launching!");
        }

        if (needsTakeoffRocket) {
            handleTakeoff();
            return;
        }

        if (!mc.player.isGliding()) return;

        handleElytraHealth();

        if (ceilingWarningSent && mc.player.getY() < 128.0 - netherCeilingBuffer.get() - 5) {
            ceilingWarningSent = false;
        }

        Float desiredPitch  = null;
        boolean safetyOverride = false;

        // Priority 1: Nether ceiling avoidance
        if (desiredPitch == null && netherCeilingSafety.get()) {
            desiredPitch = handleNetherCeiling();
            if (desiredPitch != null) safetyOverride = true;
        }

        // Priority 2: Collision avoidance
        if (desiredPitch == null && collisionAvoidance.get()) {
            desiredPitch = handleCollisionAvoidance();
            if (desiredPitch != null) safetyOverride = true;
        }

        // Priority 3: Normal flight modes
        if (desiredPitch == null) {
            desiredPitch = switch (flightMode.get()) {
                case Pitch40        -> handlePitch40Mode();
                case Oscillation    -> handleOscillationMode();
                case AltitudeBounce -> handleAltitudeBounceMode();
                case None           -> null;
                default             -> handleNormalMode();
            };
        }

        if (!safetyOverride) {
            FlightPattern currentPattern = flightPattern.get();
            if (currentPattern == FlightPattern.Drunk) {
                drunkVisitedChunks.add(mc.player.getChunkPos().toLong());
                handleDrunkMode();
            } else if (currentPattern != FlightPattern.Manual) {
                handlePatternYaw();
            }
        }

        applyPitch(desiredPitch);
    }

    @EventHandler
    private void onPacketReceive(meteordevelopment.meteorclient.events.packets.PacketEvent.Receive event) {
        if (event.packet instanceof PlayerPositionLookS2CPacket) {
            lastLagbackTime = System.currentTimeMillis();
            mc.options.forwardKey.setPressed(false);
        }
    }

    // ─── Takeoff ─────────────────────────────────────────────────────────────────
    private void handleTakeoff() {
        if (mc.player.isOnGround()) {
            mc.player.jump();
            return;
        }
        if (!mc.player.isGliding()) {
            if (mc.player.networkHandler != null) {
                mc.player.networkHandler.sendPacket(
                    new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING)
                );
            }
            return;
        }
        boolean rocketInHotbar = hotbarHasRocket();
        if (!rocketInHotbar) {
            takeoffWaitTicks++;
            if (takeoffWaitTicks < 10) return;
        }
        if (shouldFireRocket() && countFireworks() > 0) {
            fireRocket();
            lastRocketTime = System.currentTimeMillis();
        }
        needsTakeoffRocket = false;
        takeoffWaitTicks   = 0;
        takeoffTimer       = TAKEOFF_GRACE_TICKS;
    }

    private boolean hotbarHasRocket() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) return true;
        }
        return false;
    }

    // ─── Elytra Health ───────────────────────────────────────────────────────────
    private void handleElytraHealth() {
        boolean assistantHandling = false;
        ElytraAssistant assistant = Modules.get().get(ElytraAssistant.class);
        if (assistant != null && assistant.isAutoSwapEnabled()) assistantHandling = true;

        if (!assistantHandling && getDurabilityPercent() <= ELYTRA_LOW_PERCENT) {
            Integer newDura = swapToFreshElytra();
            if (newDura != null) {
                info("Auto-swapped elytra (durability was low).");
            } else {
                warning("No replacement elytra found!");
            }
        }
    }

    // ─── Collision Avoidance ──────────────────────────────────────────────────────
    private Float handleCollisionAvoidance() {
        if (!mc.player.isGliding() || mc.player.getPitch() >= 30) return null;

        Vec3d camPos   = mc.player.getCameraPosVec(1.0f);
        Vec3d velocity = mc.player.getVelocity();
        if (velocity.lengthSquared() < 0.01) return null;

        Vec3d fwd    = velocity.normalize();
        Vec3d[] rays = { fwd, fwd.rotateY(0.5f), fwd.rotateY(-0.5f) };

        boolean obstacleDetected = false;
        for (Vec3d dir : rays) {
            BlockHitResult hit = mc.world.raycast(new RaycastContext(
                camPos, camPos.add(dir.multiply(avoidanceDistance.get())),
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                mc.player
            ));
            if (hit.getType() == HitResult.Type.BLOCK) { obstacleDetected = true; break; }
        }
        if (!obstacleDetected) return null;

        Vec3d leftDir  = fwd.rotateY(1.5f);
        Vec3d rightDir = fwd.rotateY(-1.5f);
        double checkDist = avoidanceDistance.get() * 1.5;

        boolean leftClear = mc.world.raycast(new RaycastContext(
            camPos, camPos.add(leftDir.multiply(checkDist)),
            RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player
        )).getType() == HitResult.Type.MISS;

        boolean rightClear = mc.world.raycast(new RaycastContext(
            camPos, camPos.add(rightDir.multiply(checkDist)),
            RaycastContext.ShapeType.COLLIDER, RaycastContext.FluidHandling.NONE, mc.player
        )).getType() == HitResult.Type.MISS;

        float yawSpeed = 5.0f;
        if (limitRotationSpeed.get()) yawSpeed = Math.min(yawSpeed, maxRotationPerTick.get().floatValue());

        if (leftClear && !rightClear) {
            mc.player.setYaw(mc.player.getYaw() + yawSpeed);
        } else if (rightClear && !leftClear) {
            mc.player.setYaw(mc.player.getYaw() - yawSpeed);
        } else if (leftClear) {
            if (mc.player.age % 2 == 0) mc.player.setYaw(mc.player.getYaw() + yawSpeed);
            else mc.player.setYaw(mc.player.getYaw() - yawSpeed);
        }

        float currentPitch = mc.player.getPitch();
        double speed       = mc.player.getVelocity().horizontalLength();
        float pullUpStr    = (float) MathHelper.clamp(speed * 20, 20, 60);

        if (shouldFireRocket() && countFireworks() > 0 && mc.player.getVelocity().y < 0.2) {
            long now = System.currentTimeMillis();
            if (now - lastRocketTime >= COLLISION_ROCKET_COOLDOWN) {
                fireRocket();
                lastRocketTime = now;
            }
        }
        return MathHelper.lerp(0.3f, currentPitch, -pullUpStr);
    }

    // ─── Nether Ceiling Safety ────────────────────────────────────────────────────
    private Float handleNetherCeiling() {
        if (mc.world == null || mc.player == null) return null;
        if (!mc.world.getRegistryKey().getValue().getPath().equals("the_nether")) return null;

        double currentY   = mc.player.getY();
        double netherRoof = 128.0;
        int    buffer     = netherCeilingBuffer.get();
        double triggerY   = netherRoof - buffer;

        if (currentY < triggerY) return null;

        double danger = MathHelper.clamp((currentY - triggerY) / buffer, 0.0, 1.0);
        float targetDivePitch = (float) MathHelper.lerp(danger, 10.0, 60.0);
        float lerpSpeed       = (float) MathHelper.lerp(danger, 0.08, 0.35);

        if (danger > 0.1 && !ceilingWarningSent) {
            warning("Nether ceiling! Diving to avoid bedrock.");
            ceilingWarningSent = true;
        }
        return MathHelper.lerp(lerpSpeed, mc.player.getPitch(), targetDivePitch);
    }

    // ─── Normal Mode ─────────────────────────────────────────────────────────────
    private Float handleNormalMode() {
        if (!useTargetY.get()) {
            long now = System.currentTimeMillis();
            if (now - lastRocketTime >= rocketDelay.get()
                    && mc.player.getVelocity().y < 0.5
                    && shouldFireRocket() && countFireworks() > 0) {
                fireRocket();
                lastRocketTime = now;
            }
            return null;
        }

        double currentY  = mc.player.getY();
        double target    = targetY.get();
        double tolerance = flightTolerance.get();
        double diff      = target - currentY;

        if      (diff > tolerance) ascentMode = true;
        else if (diff <= 0)        ascentMode = false;

        if (ascentMode) {
            long now = System.currentTimeMillis();
            if (now - lastRocketTime >= rocketDelay.get()
                    && mc.player.getVelocity().y < 0.5
                    && shouldFireRocket() && countFireworks() > 0) {
                fireRocket();
                lastRocketTime = now;
            }
        }

        float calculatedPitch;
        if (Math.abs(diff) < 0.5) {
            calculatedPitch = 0.0f;
        } else {
            calculatedPitch = (float) (-Math.tanh(diff / 10.0) * 45.0);
            calculatedPitch = MathHelper.clamp(calculatedPitch, -45.0f, 40.0f);
        }

        targetPitch = calculatedPitch;
        float smooth = pitchSmoothing.get().floatValue();
        return mc.player.getPitch() + (targetPitch - mc.player.getPitch()) * smooth;
    }

    // ─── Oscillation Mode ────────────────────────────────────────────────────────
    private Float handleOscillationMode() {
        waveTicks++;
        float calculatedPitch = (float) (40.0 * Math.sin(waveTicks * oscillationSpeed.get()));

        if (oscillationRockets.get() && countFireworks() > 0 && calculatedPitch < -38.0f) {
            long now = System.currentTimeMillis();
            if (shouldFireRocket() && now - lastRocketTime >= oscillationRocketDelay.get()) {
                fireRocket();
                lastRocketTime = now;
            }
        }
        return calculatedPitch;
    }

    // ─── Pitch40 Mode ────────────────────────────────────────────────────────────
    private Float handlePitch40Mode() {
        double currentY = mc.player.getY();
        double upperY   = pitch40UpperY.get();
        double lowerY   = pitch40LowerY.get();
        float  smooth   = pitch40Smoothing.get().floatValue();

        if      (currentY <= lowerY) { pitch40Climbing = true; }
        else if (currentY >= upperY) { pitch40Climbing = false; pitch40Rocketing = false; }

        if (currentY < lowerY) {
            if (pitch40BelowMinStartTime < 0) pitch40BelowMinStartTime = System.currentTimeMillis();
            if (System.currentTimeMillis() - pitch40BelowMinStartTime > pitch40BelowMinDelay.get()) {
                pitch40Rocketing = true;
            }
        } else {
            pitch40BelowMinStartTime = -1;
        }

        float pitch = pitch40Climbing
            ? MathHelper.lerp(smooth, mc.player.getPitch(), -40f)
            : MathHelper.lerp(smooth, mc.player.getPitch(),  40f);

        if (pitch40Rocketing) {
            long now = System.currentTimeMillis();
            if (now - lastRocketTime >= rocketDelay.get() && shouldFireRocket() && countFireworks() > 0) {
                fireRocket();
                lastRocketTime = now;
            }
        }
        return pitch;
    }

    // ─── Altitude Bounce Mode ─────────────────────────────────────────────────────
    private Float handleAltitudeBounceMode() {
        double currentY = mc.player.getY();
        double peakY    = bouncePeakY.get();
        double floorY   = bounceFloorY.get();
        float  smooth   = bouncePitchSmoothing.get().floatValue();

        if (bounceClimbing && currentY >= peakY)  bounceClimbing = false;
        if (!bounceClimbing && currentY <= floorY) bounceClimbing = true;

        if (bounceClimbing) {
            long now = System.currentTimeMillis();
            if (now - lastRocketTime >= rocketDelay.get()
                    && mc.player.getVelocity().y < 0.5
                    && shouldFireRocket() && countFireworks() > 0) {
                fireRocket();
                lastRocketTime = now;
            }
            return MathHelper.lerp(smooth, mc.player.getPitch(), bounceClimbPitch.get().floatValue());
        } else {
            return MathHelper.lerp(smooth, mc.player.getPitch(), bounceGlidePitch.get().floatValue());
        }
    }

    // ─── Pattern Flight ───────────────────────────────────────────────────────────
    private void handlePatternYaw() {
        if (paused) return;

        if (flightPattern.get() != FlightPattern.Manual && flightPattern.get() != FlightPattern.Drunk) {
            if (origin == null) origin = mc.player.getPos();

            if (currentTarget == null) {
                calculateNextTarget();
            } else {
                double dx = currentTarget.x - mc.player.getX();
                double dz = currentTarget.z - mc.player.getZ();

                // If auto-update is enabled and we are more than 4 chunks away from the target,
                // reset the pattern to follow the player's current location.
                if (sweepAutoUpdate.get() && flightPattern.get() == FlightPattern.Sweep && (dx * dx + dz * dz) > 4096.0) {
                    resetPatternState();
                    return;
                }

                int    radius = waypointReachRadius.get();
                if (dx * dx + dz * dz < (double)(radius * radius)) calculateNextTarget();
            }

            if (currentTarget != null) {
                double dx = currentTarget.x - mc.player.getX();
                double dz = currentTarget.z - mc.player.getZ();
                float targetYaw  = (float) Math.toDegrees(Math.atan2(-dx, dz));
                float currentYaw = mc.player.getYaw();
                float diffYaw    = MathHelper.wrapDegrees(targetYaw - currentYaw);
                float yawChange  = diffYaw * patternTurnSpeed.get().floatValue();
                if (limitRotationSpeed.get()) {
                    yawChange = MathHelper.clamp(yawChange,
                        -maxRotationPerTick.get().floatValue(),
                         maxRotationPerTick.get().floatValue());
                }
                mc.player.setYaw(currentYaw + yawChange);
            }
        } else {
            currentTarget = null;
        }
    }

    private void calculateNextTarget() {
        if (origin == null) origin = mc.player.getPos();

        double targetYValue  = useTargetY.get() ? targetY.get() : mc.player.getY();
        double nextX, nextZ;
        FlightPattern currentPattern = flightPattern.get();

        if (currentPattern == FlightPattern.Manual || currentPattern == FlightPattern.Drunk) { currentTarget = null; return; }

        if (currentPattern == FlightPattern.Grid) {
            int spacing = gridSpacing.get() * 16;
            if (currentTarget == null) {
                gridDirection  = 3;
                gridStepsInLeg = 0;
                Vec3d offset = getGridDirectionOffset(gridDirection, spacing);
                nextX = origin.x + offset.x;
                nextZ = origin.z + offset.z;
                gridStepsInLeg = 1;
            } else {
                if (gridStepsInLeg >= gridStep) {
                    gridDirection  = (gridDirection + 1) % 4;
                    gridStepsInLeg = 0;
                    if (gridDirection == 0 || gridDirection == 2) gridStep++;
                }
                Vec3d offset = getGridDirectionOffset(gridDirection, spacing);
                nextX = currentTarget.x + offset.x;
                nextZ = currentTarget.z + offset.z;
                gridStepsInLeg++;
            }
        } else if (currentPattern == FlightPattern.ZigZag) {
            double legLength = zigzagLegLength.get() * 16.0;
            if (currentTarget == null) {
                zigzagCurrentYaw = mc.player.getYaw();
                zigzagTurnRight  = true;
                zigzagFirstLeg   = true;
            }
            if (zigzagFirstLeg) {
                zigzagFirstLeg = false;
            } else {
                double turnAmount = zigzagAngle.get() * 2.0;
                zigzagCurrentYaw = MathHelper.wrapDegrees(
                    zigzagCurrentYaw + (float)(zigzagTurnRight ? turnAmount : -turnAmount)
                );
                zigzagTurnRight = !zigzagTurnRight;
            }
            double radYaw    = Math.toRadians(zigzagCurrentYaw);
            Vec3d startPoint = (currentTarget != null) ? currentTarget : origin;
            nextX = startPoint.x + (-Math.sin(radYaw) * legLength);
            nextZ = startPoint.z + ( Math.cos(radYaw) * legLength);
        } else if (currentPattern == FlightPattern.FigureEight) {
            double r = figureEightRadius.get() * 16.0;
            double x_off, z_off;
            switch (figureEightWaypoint) {
                case 0: x_off =  r; z_off =  r;    break;
                case 1: x_off =  0; z_off =  2*r;  break;
                case 2: x_off = -r; z_off =  r;    break;
                case 3: x_off =  0; z_off =  0;    break;
                case 4: x_off = -r; z_off = -r;    break;
                case 5: x_off =  0; z_off = -2*r;  break;
                case 6: x_off =  r; z_off = -r;    break;
                default: x_off = 0; z_off =  0;    break;
            }
            nextX = origin.x + x_off;
            nextZ = origin.z + z_off;
            figureEightWaypoint = (figureEightWaypoint + 1) % 8;
        } else if (currentPattern == FlightPattern.Circle) {
            double angleStep       = 2.0 * Math.PI / circleSegments.get();
            double expansionBlocks = circleExpansion.get() * 16.0;
            double b               = expansionBlocks / (2.0 * Math.PI);
            double radius          = b * circleAngle;
            nextX = origin.x + radius * Math.cos(circleAngle);
            nextZ = origin.z + radius * Math.sin(circleAngle);
            circleAngle += angleStep;
        } else if (currentPattern == FlightPattern.Sweep) {
            if (currentTarget == null) {
                sweepInitialYaw = mc.player.getYaw();
                sweepStep = 0;
                currentSweepFactor = 1.0; // Reset on new pattern start
            }

            // Apply expansion after every full cycle (4 steps)
            if (sweepExpansionRate.get() > 0.0 && sweepStep > 0 && sweepStep % 4 == 0) {
                currentSweepFactor = Math.min(sweepMaxFactor.get(), currentSweepFactor * (1.0 + sweepExpansionRate.get()));
            }

            double width   = sweepWidth.get() * 16.0 * currentSweepFactor;
            double advance = sweepAdvance.get() * 16.0 * currentSweepFactor;

            float rad = (float) Math.toRadians(sweepInitialYaw);
            Vec3d fwd  = new Vec3d(-Math.sin(rad), 0, Math.cos(rad));
            Vec3d side = new Vec3d(-Math.cos(rad), 0, -Math.sin(rad)); // Right vector
            Vec3d base = (currentTarget != null) ? currentTarget : origin;

            Vec3d move;
            switch (sweepStep % 4) {
                case 0:  move = side.multiply(sweepStep == 0 ? -width : -width * 2.0); break; // Sweep Left
                case 1:  move = fwd.multiply(advance); break; // Advance
                case 2:  move = side.multiply(width * 2.0);  break; // Sweep Right
                default: move = fwd.multiply(advance); break; // Advance
            }

            nextX = base.x + move.x;
            nextZ = base.z + move.z;

            sweepStep++;
        } else {
            return;
        }

        currentTarget = new Vec3d(nextX, targetYValue, nextZ);
    }

    private Vec3d getGridDirectionOffset(int dir, int dist) {
        return switch (dir) {
            case 0 -> new Vec3d( dist, 0,    0);
            case 1 -> new Vec3d(   0, 0, -dist);
            case 2 -> new Vec3d(-dist, 0,    0);
            case 3 -> new Vec3d(   0, 0,  dist);
            default -> Vec3d.ZERO;
        };
    }

    // ─── Drunk Mode ──────────────────────────────────────────────────────────────
    private void handleDrunkMode() {
        if (drunkTimer++ >= currentDrunkDuration) {
            float intensity = drunkIntensity.get().floatValue();
            DrunkBias bias  = drunkBias.get();

            if (bias == DrunkBias.None) {
                if (drunkAvoidVisited.get()) {
                    float bestCandidate = mc.player.getYaw();

                    // Try multiple random directions and pick one that doesn't point at a visited chunk
                    for (int i = 0; i < 10; i++) {
                        float candidate = mc.player.getYaw() + (float)((Math.random() - 0.5) * 2.0 * intensity);
                        double rad = Math.toRadians(candidate);
                        boolean pathVisited = false;

                        // Check points roughly 1, 2, and 3 chunks ahead (16, 32, 48 blocks)
                        for (int dist : new int[]{16, 32, 48}) {
                            int cx = (int) Math.floor((mc.player.getX() - Math.sin(rad) * dist) / 16.0);
                            int cz = (int) Math.floor((mc.player.getZ() + Math.cos(rad) * dist) / 16.0);
                            if (drunkVisitedChunks.contains(ChunkPos.toLong(cx, cz))) {
                                pathVisited = true;
                                break;
                            }
                        }
                        if (!pathVisited) {
                            bestCandidate = candidate;
                            break;
                        }
                        if (i == 0) bestCandidate = candidate; // Fallback to first random
                    }
                    targetDrunkYaw = bestCandidate;
                } else {
                    targetDrunkYaw = mc.player.getYaw() + (float)((Math.random() - 0.5) * 2.0 * intensity);
                }
            } else {
                float minYaw, maxYaw;
                boolean isNorth = false;
                switch (bias) {
                    case North        -> { isNorth = true; minYaw = 0; maxYaw = 0; }
                    case South        -> { minYaw = -22.5f; maxYaw =  22.5f; }
                    case East         -> { minYaw = -112.5f; maxYaw = -67.5f; }
                    case West         -> { minYaw =  67.5f; maxYaw = 112.5f; }
                    case PositiveOnly -> { minYaw = -90f;  maxYaw =   0f; }
                    case NegativeOnly -> { minYaw =  90f;  maxYaw = 180f; }
                    case NegPos       -> { minYaw =   0f;  maxYaw =  90f; }
                    case PosNeg       -> { minYaw = -180f; maxYaw = -90f; }
                    default           -> { minYaw = -180f; maxYaw = 180f; }
                }

                if (isNorth) {
                    targetDrunkYaw = 180f + ((float)Math.random() * 45f - 22.5f);
                } else {
                    targetDrunkYaw = minYaw + (float)(Math.random() * (maxYaw - minYaw));
                }
            }

            drunkTimer           = 0;
            currentDrunkDuration = drunkInterval.get() + (int)(Math.random() * 10);
        }

        float currentYaw = mc.player.getYaw();
        float diffYaw    = MathHelper.wrapDegrees(targetDrunkYaw - currentYaw);
        float change     = diffYaw * drunkSmoothing.get().floatValue();

        if (limitRotationSpeed.get()) {
            float max = maxRotationPerTick.get().floatValue();
            change = MathHelper.clamp(change, -max, max);
        }
        mc.player.setYaw(currentYaw + change);
    }

    // ─── Apply Pitch ─────────────────────────────────────────────────────────────
    private void applyPitch(Float desiredPitch) {
        if (desiredPitch == null) return;
        float current = mc.player.getPitch();
        if (limitRotationSpeed.get()) {
            float max  = maxRotationPerTick.get().floatValue();
            float diff = MathHelper.clamp(desiredPitch - current, -max, max);
            mc.player.setPitch(current + diff);
        } else {
            mc.player.setPitch(desiredPitch);
        }
    }

    // ─── Public Accessors ────────────────────────────────────────────────────────
    public boolean shouldFireRocket() {
        if (mc.player == null) return false;
        ItemStack elytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.isOf(Items.ELYTRA)) return false;
        if (Math.abs(mc.player.getPitch()) > 70) return false;
        if (ceilingWarningSent) return false;
        if (!needsTakeoffRocket && mc.player.getVelocity().horizontalLength() < 0.3) return false;
        return elytra.getDamage() < elytra.getMaxDamage() - 1;
    }

    public double getDurabilityPercent() {
        if (mc.player == null) return 100.0;
        ItemStack elytra = mc.player.getEquippedStack(EquipmentSlot.CHEST);
        if (elytra.isEmpty() || !elytra.isOf(Items.ELYTRA)) return 100.0;
        return 100.0 * (elytra.getMaxDamage() - elytra.getDamage()) / (double) elytra.getMaxDamage();
    }

    // ─── Private Helpers ─────────────────────────────────────────────────────────
    private void replenishRockets() {
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) return;
        }
        int invSlot = -1;
        for (int i = 9; i < 36; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) { invSlot = i; break; }
        }
        if (invSlot == -1) return;

        int hotbarSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isEmpty()) { hotbarSlot = i; break; }
        }
        if (hotbarSlot == -1) hotbarSlot = mc.player.getInventory().selectedSlot;
        InvUtils.move().from(invSlot).toHotbar(hotbarSlot);
    }

    private int countFireworks() {
        if (mc.player == null) return 0;
        int count = 0;
        for (int i = 0; i < 36; i++) {
            ItemStack s = mc.player.getInventory().getStack(i);
            if (s.isOf(Items.FIREWORK_ROCKET)) count += s.getCount();
        }
        ItemStack offhand = mc.player.getOffHandStack();
        if (offhand.isOf(Items.FIREWORK_ROCKET)) count += offhand.getCount();
        return count;
    }

    private Integer swapToFreshElytra() {
        int bestSlot = -1, bestDurability = -1;
        for (int i = 0; i < 36; i++) {
            ItemStack stack = mc.player.getInventory().getStack(i);
            if (stack.isOf(Items.ELYTRA)) {
                int dur = stack.getMaxDamage() - stack.getDamage();
                if (dur > bestDurability && dur > ELYTRA_MIN_SWAP_DUR) {
                    bestSlot = i; bestDurability = dur;
                }
            }
        }
        if (bestSlot == -1) return null;
        InvUtils.move().from(bestSlot).toArmor(2);
        return bestDurability;
    }

    private boolean isNearGround() {
        if (mc.player == null || mc.world == null) return false;
        if (mc.player.isOnGround()) return true;
        BlockPos.Mutable pos = new BlockPos.Mutable();
        for (int i = 1; i <= 3; i++) {
            pos.set(mc.player.getX(), mc.player.getY() - i, mc.player.getZ());
            if (mc.world.getBlockState(pos).isSolidBlock(mc.world, pos)) return true;
        }
        return false;
    }

    private void fireRocket() {
        if (mc.player == null || mc.interactionManager == null) return;

        int rocketSlot = -1;
        for (int i = 0; i < 9; i++) {
            if (mc.player.getInventory().getStack(i).isOf(Items.FIREWORK_ROCKET)) { rocketSlot = i; break; }
        }

        if (rocketSlot == -1) {
            if (mc.player.getOffHandStack().isOf(Items.FIREWORK_ROCKET)) {
                mc.interactionManager.interactItem(mc.player, Hand.OFF_HAND);
                if (!silentRockets.get()) mc.player.swingHand(Hand.OFF_HAND);
            }
            return;
        }

        InvUtils.swap(rocketSlot, true);
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
        if (!silentRockets.get()) mc.player.swingHand(Hand.MAIN_HAND);
        InvUtils.swapBack();
    }

    private void disconnect(String reason) {
        if (mc.player != null && mc.player.networkHandler != null) {
            mc.player.networkHandler.getConnection().disconnect(Text.literal(reason));
        }
        toggle();
    }
}