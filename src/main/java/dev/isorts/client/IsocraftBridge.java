package dev.isorts.client;

import dev.isorts.IsoRts;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.Vec3d;

import java.lang.reflect.Method;

/**
 * Drives Isocraft's camera through its own public API, found by reflection.
 * <p>
 * The previous approach synthesised key presses on Isocraft's toggle binding. That is a parity
 * guess: if its state and ours ever disagree - a manual press of its real binding, a toggle
 * landing during a transition - every toggle after that does the opposite of what we meant, and
 * its player-relative camera then fights the detached RTS camera. Reading and setting
 * {@code CameraController#isEnabled/setEnabled} directly makes the state a fact, not a guess.
 * <p>
 * ponytail: reflection instead of a compile-time dependency. Isocraft's class and method names
 * are plain Java (only Minecraft's are obfuscated), so this works in production, and the mod
 * still loads if Isocraft is absent or renames things - it just logs and does nothing.
 */
public final class IsocraftBridge {

    private static Object controller;
    private static Method isEnabled;
    private static Method setEnabled;
    private static Method setRotationInput;
    private static Method cursorOf;
    private static Method isoFov;
    private static Method cursorX;
    private static Method cursorY;
    private static Method cursorReady;
    private static Method aimPoint;
    private static boolean broken;

    private IsocraftBridge() {
    }

    private static boolean resolve() {
        if (controller != null) {
            return true;
        }
        if (broken) {
            return false;
        }
        try {
            Class<?> isoCamera = Class.forName("com.runefist.isocraft.camera.IsoCamera");
            Object found = isoCamera.getMethod("controller").invoke(null);
            if (found == null) {
                return false;                        // Isocraft not initialised yet; retry later
            }
            Class<?> c = found.getClass();
            isEnabled = c.getMethod("isEnabled");
            setEnabled = c.getMethod("setEnabled", boolean.class);
            setRotationInput = c.getMethod("setRotationInput", int.class);
            isoFov = c.getMethod("isoFov");
            cursorOf = c.getMethod("cursor");
            Class<?> cur = Class.forName("com.runefist.isocraft.input.IsoCursor");
            cursorX = cur.getMethod("x");
            cursorY = cur.getMethod("y");
            cursorReady = cur.getMethod("isInitialized");
            controller = found;
            IsoRts.LOG.info("Isocraft bridge connected");
            return true;
        } catch (Exception e) {
            broken = true;
            IsoRts.LOG.error("Isocraft bridge unavailable - view control degraded", e);
            return false;
        }
    }

    public static boolean available() {
        return resolve();
    }

    public static boolean isIso() {
        try {
            return resolve() && (Boolean) isEnabled.invoke(controller);
        } catch (Exception e) {
            return false;
        }
    }

    public static void setIso(boolean on) {
        try {
            if (resolve()) {
                setEnabled.invoke(controller, on);
            }
        } catch (Exception e) {
            IsoRts.LOG.warn("Isocraft setEnabled({}) failed", on, e);
        }
    }

    /**
     * The isometric view's own field of view, which is NOT the vanilla FOV option.
     * <p>
     * This matters for picking: unprojecting the cursor with the wrong FOV puts the ray at the
     * wrong angle, so clicks land somewhere other than what is under the pointer - the reason
     * selection in the isometric view never felt like it was hitting anything.
     *
     * @return Isocraft's FOV in degrees, or 0 if unavailable
     */
    public static double isoFov() {
        try {
            return resolve() ? ((Number) isoFov.invoke(controller)).doubleValue() : 0.0;
        } catch (Exception e) {
            return 0.0;
        }
    }

    /** Isocraft's own free cursor position in window pixels, or null if it has none yet. */
    public static double[] cursor() {
        try {
            if (!resolve()) {
                return null;
            }
            Object cursor = cursorOf.invoke(controller);
            if (cursor == null || !(Boolean) cursorReady.invoke(cursor)) {
                return null;
            }
            return new double[] {
                    ((Number) cursorX.invoke(cursor)).doubleValue(),
                    ((Number) cursorY.invoke(cursor)).doubleValue() };
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The world point Isocraft's cursor is aiming at, or null.
     * <p>
     * This is the answer to a problem that was not worth solving ourselves. Isocraft does not
     * render a plain perspective at its stated FOV - it leases and expands the projection - so
     * unprojecting the cursor with any single FOV value lands in the wrong place. Isocraft already
     * computes the cursor's world target for its own aiming, and that number is exact by
     * construction.
     */
    public static Vec3d cursorAimPoint(PlayerEntity player) {
        try {
            if (broken) {
                return null;
            }
            if (aimPoint == null) {
                aimPoint = Class.forName("com.runefist.isocraft.input.IsoTargeting")
                        .getMethod("cursorAimPoint", PlayerEntity.class);
            }
            return (Vec3d) aimPoint.invoke(null, player);
        } catch (Exception e) {
            return null;
        }
    }

    /** Continuous rotation input: -1, 0 or 1, same contract as holding Isocraft's rotate keys. */
    public static void setRotationInput(int direction) {
        try {
            if (resolve()) {
                setRotationInput.invoke(controller, direction);
            }
        } catch (Exception e) {
            // rotation is cosmetic; stay quiet
        }
    }
}
