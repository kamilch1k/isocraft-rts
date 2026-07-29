package dev.isorts.client;

import dev.isorts.IsoRts;

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
