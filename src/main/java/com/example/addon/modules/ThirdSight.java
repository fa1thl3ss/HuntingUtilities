package com.example.addon.modules;

import com.example.addon.HuntingUtilities;

import meteordevelopment.meteorclient.events.render.Render3DEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.DoubleSetting;
import meteordevelopment.meteorclient.settings.EnumSetting;
import meteordevelopment.meteorclient.settings.KeybindSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.misc.Keybind;
import meteordevelopment.orbit.EventHandler;
import net.minecraft.client.option.Perspective;


public class ThirdSight extends Module {

    /**
     * Selects the active camera feature.
     * Zoom is always keybind-driven and is independent of this setting.
     */
    public enum CameraFeature { ThirdPerson, BirdsEye }

    private final SettingGroup sgGeneral = settings.getDefaultGroup();
    private final SettingGroup sgZoom    = settings.createGroup("Zoom");
    private final SettingGroup sgATW     = settings.createGroup("Around the World");

    // ── General ──────────────────────────────────────────────────────────────

    private final Setting<Keybind> noDistanceKey = sgGeneral.add(new KeybindSetting.Builder()
        .name("no-distance-key")
        .description("Toggles a mode that disables camera distance modifications, allowing vanilla third person unless zooming.")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<CameraFeature> cameraFeature = sgGeneral.add(new EnumSetting.Builder<CameraFeature>()
        .name("camera-feature")
        .description("Active camera feature. Zoom is always keybind-driven.")
        .defaultValue(CameraFeature.ThirdPerson)
        .build()
    );

    public final Setting<Double> distance = sgGeneral.add(new DoubleSetting.Builder()
        .name("distance")
        .description("Camera distance from the player. In BirdsEye mode this controls how far below.")
        .defaultValue(4.0)
        .min(1.0)
        .max(30.0)
        .sliderRange(1.0, 30.0)
        .build()
    );

    public final Setting<Boolean> freeLook = sgGeneral.add(new BoolSetting.Builder()
        .name("free-look")
        .description("Orbit the camera around the player without affecting movement direction. Disabled in BirdsEye mode.")
        .defaultValue(true)
        .visible(() -> cameraFeature.get() == CameraFeature.ThirdPerson)
        .build()
    );

    public final Setting<Double> sensitivity = sgGeneral.add(new DoubleSetting.Builder()
        .name("sensitivity")
        .description("Free-look mouse sensitivity.")
        .defaultValue(1.0)
        .min(1.0)
        .max(20.0)
        .sliderRange(1.0, 20.0)
        .visible(() -> cameraFeature.get() == CameraFeature.ThirdPerson && freeLook.get())
        .build()
    );

    public final Setting<Double> followSpeed = sgGeneral.add(new DoubleSetting.Builder()
        .name("follow-speed")
        .description("How quickly the camera yaw catches up to the direction you're looking when free-look is off. 1.0 = instant.")
        .defaultValue(0.12)
        .min(0.01)
        .max(1.0)
        .sliderRange(0.02, 0.5)
        .visible(() -> !freeLook.get() && cameraFeature.get() == CameraFeature.ThirdPerson)
        .build()
    );

    // ── Zoom ─────────────────────────────────────────────────────────────────

    public final Setting<Double> zoomDistance = sgZoom.add(new DoubleSetting.Builder()
        .name("zoom-distance")
        .description("Camera distance when zoomed in.")
        .defaultValue(2.0)
        .min(0.5)
        .max(30.0)
        .sliderRange(0.5, 10.0)
        .build()
    );

    public final Setting<Double> zoomFov = sgZoom.add(new DoubleSetting.Builder()
        .name("zoom-fov")
        .description("Field of View when zooming in First Person.")
        .defaultValue(30.0)
        .min(1.0)
        .max(110.0)
        .sliderRange(10.0, 110.0)
        .build()
    );

    public final Setting<Keybind> zoomKey = sgZoom.add(new KeybindSetting.Builder()
        .name("zoom-key")
        .description("Key to activate zoom.")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<Boolean> zoomToggle = sgZoom.add(new BoolSetting.Builder()
        .name("toggle-mode")
        .description("If true, press to toggle zoom. If false, hold to zoom.")
        .defaultValue(false)
        .build()
    );

    // ── Around the World ──────────────────────────────────────────────────────

    private final Setting<Keybind> atwKey = sgATW.add(new KeybindSetting.Builder()
        .name("key")
        .description("Press to toggle the Around the World spin. Module must already be active.")
        .defaultValue(Keybind.none())
        .build()
    );

    public final Setting<Double> atwSpeed = sgATW.add(new DoubleSetting.Builder()
        .name("speed")
        .description("Yaw degrees added per tick. 1 = lazy drift, 360 = full rotation per tick (extreme).")
        .defaultValue(6.0)
        .min(0.1)
        .max(360.0)
        .sliderRange(0.5, 72.0)
        .build()
    );

    private final Setting<Boolean> atwClockwise = sgATW.add(new BoolSetting.Builder()
        .name("clockwise")
        .description("Spin direction. True = clockwise (yaw increases), false = counter-clockwise.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Boolean> atwLockPitch = sgATW.add(new BoolSetting.Builder()
        .name("lock-pitch")
        .description("Keep the camera at a fixed pitch angle while spinning.")
        .defaultValue(true)
        .build()
    );

    private final Setting<Double> atwPitch = sgATW.add(new DoubleSetting.Builder()
        .name("pitch")
        .description("Camera pitch to hold during the spin. 0 = level, positive = look down, negative = look up.")
        .defaultValue(15.0)
        .min(-89.9)
        .max(89.9)
        .sliderRange(-60.0, 60.0)
        .visible(atwLockPitch::get)
        .build()
    );

    // ── State ─────────────────────────────────────────────────────────────────

    // Free-look / BirdsEye camera angles
    public float cameraYaw   = 0f;
    public float cameraPitch = 0f;

    private double  currentDistance         = 4.0;
    private boolean isZooming               = false;
    private boolean wasZoomKeyPressed       = false;
    private boolean noDistanceActive        = false;
    private boolean wasNoDistanceKeyPressed = false;
    private double  originalFov             = -1;
    private double  currentFov              = 0;

    private Perspective previousPerspective = null;

    // ── Around the World state ────────────────────────────────────────────────

    private boolean atwActive        = false;
    private boolean wasAtwKeyPressed = false;
    private float   atwCurrentYaw    = 0f;

    // ── Constructor ───────────────────────────────────────────────────────────

    public ThirdSight() {
        super(HuntingUtilities.CATEGORY, "third-sight",
            "Third-person camera with configurable distance, no block clipping, free look, BirdsEye mode, and Around the World spin.");
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onActivate() {
        if (mc.player == null || mc.options == null) return;

        cameraYaw   = mc.player.getYaw();
        cameraPitch = Math.max(-89.9f, Math.min(89.9f, mc.player.getPitch()));

        previousPerspective = mc.options.getPerspective();
        if (previousPerspective == Perspective.FIRST_PERSON)
            mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);

        currentDistance = distance.get();
        isZooming               = false;
        wasZoomKeyPressed       = false;
        noDistanceActive        = false;
        wasNoDistanceKeyPressed = false;
        originalFov = -1;

        atwActive        = false;
        wasAtwKeyPressed = false;
        atwCurrentYaw    = cameraYaw;
    }

    @Override
    public void onDeactivate() {
        if (mc.options != null) {
            if (previousPerspective != null)
                mc.options.setPerspective(previousPerspective);
            if (originalFov != -1)
                mc.options.getFov().setValue((int) originalFov);
        }

        previousPerspective = null;
        originalFov = -1;

        atwActive = false;
    }

    // ── Tick ──────────────────────────────────────────────────────────────────

    @EventHandler
    private void onTick(TickEvent.Pre event) {
        if (mc.player == null || mc.options == null) return;

        if (mc.currentScreen == null) {
            // No Distance toggle
            boolean noDistPressed = noDistanceKey.get().isPressed();
            if (noDistPressed && !wasNoDistanceKeyPressed) {
                noDistanceActive = !noDistanceActive;
                info("No Distance mode %s.", noDistanceActive ? "§aenabled" : "§cdisabled");
            }
            wasNoDistanceKeyPressed = noDistPressed;

            // ── Zoom keybind ─────────────────────────────────────────────────
            boolean zoomPressed = zoomKey.get().isPressed();
            if (cameraFeature.get() != CameraFeature.BirdsEye) {
                if (zoomToggle.get()) {
                    if (zoomPressed && !wasZoomKeyPressed) isZooming = !isZooming;
                } else {
                    isZooming = zoomPressed;
                }
            } else {
                isZooming = false;
            }
            wasZoomKeyPressed = zoomPressed;

            // ── Around the World keybind ──────────────────────────────────────
            boolean atwPressed = atwKey.get().isPressed();
            if (atwPressed && !wasAtwKeyPressed) {
                atwActive = !atwActive;
                if (atwActive) {
                    atwCurrentYaw = cameraYaw;
                    info("Around the World §aon§r.");
                } else {
                    info("Around the World §coff§r.");
                }
            }
            wasAtwKeyPressed = atwPressed;

        } else {
            wasNoDistanceKeyPressed = false;
            wasZoomKeyPressed       = false;
            wasAtwKeyPressed        = false;
            if (!zoomToggle.get()) isZooming = false;
        }

        // ── Normal camera tick ────────────────────────────────────────────────
        if (noDistanceActive) {
            if (previousPerspective != null) {
                mc.options.setPerspective(previousPerspective);
                previousPerspective = null;
            }
        } else {
            if (previousPerspective == null)
                previousPerspective = mc.options.getPerspective();
            if (mc.options.getPerspective() != Perspective.THIRD_PERSON_BACK)
                mc.options.setPerspective(Perspective.THIRD_PERSON_BACK);

            if (cameraFeature.get() == CameraFeature.BirdsEye) {
                cameraYaw   = mc.player.getYaw();
                cameraPitch = 90f;
            }
        }
    }

    @EventHandler
    private void onRender(Render3DEvent event) {
        float speed;
        if (atwActive) {
            float delta = (float)(atwSpeed.get() * event.tickDelta);
            if (!atwClockwise.get()) delta = -delta;
            atwCurrentYaw += delta;
            if      (atwCurrentYaw >  180f) atwCurrentYaw -= 360f;
            else if (atwCurrentYaw < -180f) atwCurrentYaw += 360f;

            cameraYaw   = atwCurrentYaw;
            cameraPitch = atwLockPitch.get()
                ? (float) atwPitch.get().doubleValue()
                : cameraPitch;

            speed = 1.0f;
            double targetDist = isZooming ? zoomDistance.get() : distance.get();
            currentDistance += (targetDist - currentDistance) * speed;
            if (Math.abs(targetDist - currentDistance) < 0.01) currentDistance = targetDist;

        } else {
            double targetDist = isZooming ? zoomDistance.get() : distance.get();
            speed = 1.0f;

            // When free-look is off, smoothly chase the player's look direction.
            boolean shouldFollow = !freeLook.get()
                && mc.player != null
                && cameraFeature.get() == CameraFeature.ThirdPerson;
            if (shouldFollow) {
                float playerYaw = mc.player.getYaw();
                float yawDiff = playerYaw - cameraYaw;
                if (yawDiff >  180f) yawDiff -= 360f;
                if (yawDiff < -180f) yawDiff += 360f;
                float fs = (float) followSpeed.get().doubleValue();
                cameraYaw += yawDiff * fs;
            }

            currentDistance += (targetDist - currentDistance) * speed;
            if (Math.abs(targetDist - currentDistance) < 0.01) currentDistance = targetDist;
        }

        // FOV smoothing (first-person zoom)
        if (!atwActive) {
            if (noDistanceActive && mc.options.getPerspective().isFirstPerson()) {
                if (isZooming) {
                    if (originalFov == -1) {
                        originalFov = mc.options.getFov().getValue();
                        currentFov  = originalFov;
                    }
                    double targetFov = zoomFov.get();
                    currentFov += (targetFov - currentFov) * speed;
                    if (Math.abs(targetFov - currentFov) < 0.1) currentFov = targetFov;
                    mc.options.getFov().setValue((int) currentFov);
                } else if (originalFov != -1) {
                    currentFov += (originalFov - currentFov) * speed;
                    if (Math.abs(originalFov - currentFov) < 0.1) {
                        currentFov  = originalFov;
                        mc.options.getFov().setValue((int) originalFov);
                        originalFov = -1;
                    } else {
                        mc.options.getFov().setValue((int) currentFov);
                    }
                }
            } else if (originalFov != -1) {
                mc.options.getFov().setValue((int) originalFov);
                originalFov = -1;
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    public double getDistance() { return currentDistance; }

    public boolean isZooming() { return isZooming; }

    public void setZooming(boolean z) { this.isZooming = z; }

    public boolean isNoDistanceActive() { return noDistanceActive; }

    public boolean isAtwActive() { return atwActive; }

    /**
     * Called by ThirdSightMouseMixin — free look is active in ThirdPerson mode only,
     * never in BirdsEye.
     */
    public boolean isFreeLookActive() {
        if (!isActive()) return false;
        if (mc.options.getPerspective().isFirstPerson()) return false;
        if (noDistanceActive && !isZooming()) return false;
        return cameraFeature.get() == CameraFeature.ThirdPerson && freeLook.get();
    }
}