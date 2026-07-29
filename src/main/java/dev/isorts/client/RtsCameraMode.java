package dev.isorts.client;

import dev.isorts.IsoRts;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWScrollCallbackI;

/**
 * State 3: an RTS camera.
 * <p>
 * Controls, deliberately matching what RTS games do:
 * <ul>
 *   <li><b>Right-drag</b> or <b>WASD</b> - pan the map</li>
 *   <li><b>Middle-drag</b> - look around (yaw and pitch)</li>
 *   <li><b>Wheel</b> - zoom, and the <i>only</i> thing that changes camera height</li>
 * </ul>
 * The focus point's height is fixed when the mode is entered and never tracks terrain: panning
 * over a hill must not lift the camera.
 * <p>
 * ponytail: no mixins. Movement is suppressed by swapping {@code player.input} for a plain
 * {@link Input} (whose {@code tick()} never reads the keyboard); scroll is captured by chaining
 * GLFW's callback and forwarding to the previous one when inactive.
 */
public final class RtsCameraMode {

    private static final double PAN_SPEED = 0.75;
    private static final double SPRINT_MULTIPLIER = 2.5;
    private static final double DRAG_PAN_SCALE = 0.045;
    private static final double LOOK_SENSITIVITY = 0.22;

    private static final double MIN_DISTANCE = 8.0;
    private static final double MAX_DISTANCE = 90.0;
    private static final double DEFAULT_DISTANCE = 34.0;
    private static final double ZOOM_STEP = 3.0;

    private static final float DEFAULT_PITCH = 35.264f;   // true isometric
    private static final float MIN_PITCH = 15.0f;
    private static final float MAX_PITCH = 80.0f;

    /** Below this much right-drag, treat the release as a click (a move order), not a pan. */
    private static final double CLICK_SLOP = 3.0;

    /** Input sets the target*; rendered values ease toward them so nothing snaps. */
    private static final double EASE = 0.4;

    private boolean active;
    private ArmorStandEntity camera;
    private Input savedInput;

    private double focusX;
    private double focusY;
    private double focusZ;
    private double distance = DEFAULT_DISTANCE;
    private float yaw = 45.0f;
    private float pitch = DEFAULT_PITCH;

    private double targetFocusX;
    private double targetFocusZ;
    private double targetDistance = DEFAULT_DISTANCE;
    private float targetYaw = 45.0f;
    private float targetPitch = DEFAULT_PITCH;

    private boolean rightWasDown;
    private boolean middleWasDown;
    private double rightDragDistance;
    private double lastMouseX;
    private double lastMouseY;

    private GLFWScrollCallbackI previousScrollCallback;
    private boolean scrollHooked;

    public boolean isActive() {
        return active;
    }

    /** True if the last right-button press moved far enough to be a pan rather than a click. */
    public boolean consumedRightDrag() {
        return rightDragDistance > CLICK_SLOP;
    }

    public void hookScroll(MinecraftClient client) {
        if (scrollHooked) {
            return;
        }
        try {
            long window = client.getWindow().getHandle();
            previousScrollCallback = GLFW.glfwSetScrollCallback(window, (win, dx, dy) -> {
                if (active) {
                    targetDistance = MathHelper.clamp(
                            targetDistance - dy * ZOOM_STEP, MIN_DISTANCE, MAX_DISTANCE);
                    return;                              // swallow: don't scroll the hotbar
                }
                if (previousScrollCallback != null) {
                    previousScrollCallback.invoke(win, dx, dy);
                }
            });
            scrollHooked = true;
        } catch (Exception e) {
            IsoRts.LOG.error("could not hook scroll for RTS zoom", e);
        }
    }

    public void enable(MinecraftClient client) {
        if (active || client.player == null || client.world == null) {
            return;
        }
        try {
            Vec3d p = client.player.getEntityPos();
            targetFocusX = focusX = p.x;
            targetFocusZ = focusZ = p.z;
            focusY = p.y;                                // fixed for the session; wheel changes height
            targetDistance = distance = DEFAULT_DISTANCE;
            targetYaw = yaw;
            targetPitch = pitch = DEFAULT_PITCH;
            rightDragDistance = 0.0;

            camera = new ArmorStandEntity(EntityType.ARMOR_STAND, client.world);
            camera.setInvisible(true);
            camera.setNoGravity(true);
            camera.setInvulnerable(true);
            client.world.addEntity(camera);
            applyCameraTransform();
            camera.setLastPositionAndAngles(camera.getEntityPos(), camera.getYaw(), camera.getPitch());
            client.setCameraEntity(camera);

            savedInput = client.player.input;
            client.player.input = new Input();           // inert: never reads the keyboard

            // An RTS needs a pointer. Releasing the grab also stops mouse motion turning
            // the player, which is what made this feel like a shooter.
            client.mouse.unlockCursor();

            active = true;
            say(client, "RTS camera - right-drag/WASD pan, middle-drag look, wheel zoom");
        } catch (Exception e) {
            IsoRts.LOG.error("failed to enter RTS camera mode", e);
            disable(client);
        }
    }

    public void disable(MinecraftClient client) {
        try {
            if (client.player != null) {
                client.setCameraEntity(client.player);
                if (savedInput != null) {
                    client.player.input = savedInput;
                }
            }
            if (camera != null) {
                camera.discard();
            }
            client.mouse.lockCursor();
        } catch (Exception e) {
            IsoRts.LOG.error("failed to leave RTS camera mode cleanly", e);
        } finally {
            camera = null;
            savedInput = null;
            rightWasDown = false;
            middleWasDown = false;
            active = false;
        }
    }

    public void tick(MinecraftClient client) {
        if (!active) {
            return;
        }
        if (client.player == null || client.world == null || camera == null) {
            disable(client);
            return;
        }

        // Clicking in the world makes Minecraft re-grab the pointer; take it back each tick.
        if (client.currentScreen == null && client.mouse.isCursorLocked()) {
            client.mouse.unlockCursor();
        }

        long window = client.getWindow().getHandle();
        double mx = client.mouse.getX();
        double my = client.mouse.getY();
        double dmx = mx - lastMouseX;
        double dmy = my - lastMouseY;
        lastMouseX = mx;
        lastMouseY = my;

        handleLook(window, dmx, dmy);
        handlePan(client, window, dmx, dmy);

        focusX = MathHelper.lerp(EASE, focusX, targetFocusX);
        focusZ = MathHelper.lerp(EASE, focusZ, targetFocusZ);
        distance = MathHelper.lerp(EASE, distance, targetDistance);
        yaw += (float) (MathHelper.wrapDegrees(targetYaw - yaw) * EASE);
        pitch += (float) ((targetPitch - pitch) * EASE);

        applyCameraTransform();
    }

    /** Middle-drag looks around. */
    private void handleLook(long window, double dmx, double dmy) {
        boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
        if (down && middleWasDown) {
            targetYaw = MathHelper.wrapDegrees(targetYaw + (float) (dmx * LOOK_SENSITIVITY));
            targetPitch = MathHelper.clamp(
                    targetPitch + (float) (dmy * LOOK_SENSITIVITY), MIN_PITCH, MAX_PITCH);
        }
        middleWasDown = down;
    }

    /** Right-drag and WASD pan the map. Neither touches height. */
    private void handlePan(MinecraftClient client, long window, double dmx, double dmy) {
        double zoomScale = distance / DEFAULT_DISTANCE;
        float yawRad = yaw * MathHelper.RADIANS_PER_DEGREE;
        double fx = -MathHelper.sin(yawRad);
        double fz = MathHelper.cos(yawRad);

        double dx = 0.0;
        double dz = 0.0;
        if (client.options.forwardKey.isPressed()) { dx += fx; dz += fz; }
        if (client.options.backKey.isPressed())    { dx -= fx; dz -= fz; }
        if (client.options.leftKey.isPressed())    { dx += fz; dz -= fx; }
        if (client.options.rightKey.isPressed())   { dx -= fz; dz += fx; }

        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 1.0e-4) {
            double speed = PAN_SPEED * zoomScale
                    * (client.options.sprintKey.isPressed() ? SPRINT_MULTIPLIER : 1.0);
            targetFocusX += dx / len * speed;
            targetFocusZ += dz / len * speed;
        }

        boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        if (down) {
            if (rightWasDown) {
                rightDragDistance += Math.abs(dmx) + Math.abs(dmy);
                double px = dmx * DRAG_PAN_SCALE * zoomScale;
                double pz = dmy * DRAG_PAN_SCALE * zoomScale;
                // Drag the map with the cursor: opposite to pointer motion.
                targetFocusX += -fx * pz - fz * px;
                targetFocusZ += -fz * pz + fx * px;
            } else {
                rightDragDistance = 0.0;
            }
        }
        rightWasDown = down;
    }

    /** Orbit the fixed-height focus point. Height comes from distance and pitch only. */
    private void applyCameraTransform() {
        if (camera == null) {
            return;
        }
        float yawRad = yaw * MathHelper.RADIANS_PER_DEGREE;
        float pitchRad = pitch * MathHelper.RADIANS_PER_DEGREE;

        double horizontal = distance * MathHelper.cos(pitchRad);
        double vertical = distance * MathHelper.sin(pitchRad);

        double cx = focusX + MathHelper.sin(yawRad) * horizontal;
        double cz = focusZ - MathHelper.cos(yawRad) * horizontal;
        double cy = focusY + vertical;

        // Keep the previous position so Minecraft can interpolate between ticks - overwriting it
        // pins the camera to 20 visible steps per second whatever the framerate.
        camera.setLastPositionAndAngles(camera.getEntityPos(), camera.getYaw(), camera.getPitch());
        camera.setPosition(cx, cy, cz);
        camera.setYaw(yaw);
        camera.setPitch(pitch);
    }

    private static void say(MinecraftClient client, String msg) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[rts] " + msg), true);
        }
    }
}
