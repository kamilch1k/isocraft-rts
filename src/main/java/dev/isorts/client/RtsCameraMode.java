package dev.isorts.client;

import dev.isorts.IsoRts;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWScrollCallback;
import org.lwjgl.glfw.GLFWScrollCallbackI;

/**
 * State 3: a proper RTS camera.
 * <p>
 * Not a freecam. The camera orbits a <em>focus point</em> on the ground at a fixed isometric
 * pitch: WASD and middle-drag pan that focus across the XZ plane, the wheel zooms, and Z/C rotate
 * yaw in steps. The camera angle never follows the mouse, which leaves the pointer free.
 * <p>
 * ponytail: no mixins. Movement is suppressed by swapping {@code player.input} for a plain
 * {@link Input} (whose {@code tick()} never reads the keyboard); zeroing the field fails because
 * KeyboardInput rewrites it every tick. Scroll is captured by chaining GLFW's scroll callback and
 * forwarding to the previous one when inactive, so the hotbar still works normally.
 */
public final class RtsCameraMode {

    private static final double PAN_SPEED = 0.75;
    private static final double SPRINT_MULTIPLIER = 2.5;
    private static final double DRAG_PAN_SCALE = 0.06;

    private static final double MIN_DISTANCE = 8.0;
    private static final double MAX_DISTANCE = 90.0;
    private static final double DEFAULT_DISTANCE = 34.0;
    private static final double ZOOM_STEP = 3.0;

    /** True isometric pitch - equal foreshortening on all three axes. */
    private static final float ISO_PITCH = 35.264f;
    private static final float YAW_STEP = 45.0f;
    private static final int ROTATE_COOLDOWN_TICKS = 6;

    private boolean active;
    private ArmorStandEntity camera;
    private Input savedInput;

    /**
     * Input drives the {@code target*} values; the rendered values ease toward them each tick.
     * Without this the camera snaps, which is what makes a tick-rate camera feel cheap.
     */
    private static final double EASE = 0.35;

    private double focusX;
    private double focusY;
    private double focusZ;
    private double distance = DEFAULT_DISTANCE;
    private float yaw = 45.0f;

    private double targetFocusX;
    private double targetFocusY;
    private double targetFocusZ;
    private double targetDistance = DEFAULT_DISTANCE;
    private float targetYaw = 45.0f;

    private int rotateCooldown;
    private boolean middleWasDown;
    private double lastMouseX;
    private double lastMouseY;

    private GLFWScrollCallbackI previousScrollCallback;
    private boolean scrollHooked;

    public boolean isActive() {
        return active;
    }

    /** Chain into GLFW's scroll callback once, at client init. */
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
                    return;                                  // swallow: don't scroll the hotbar
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
            targetFocusY = focusY = groundAt(client, focusX, focusZ);
            targetDistance = distance = DEFAULT_DISTANCE;
            targetYaw = yaw;

            camera = new ArmorStandEntity(EntityType.ARMOR_STAND, client.world);
            camera.setInvisible(true);
            camera.setNoGravity(true);
            camera.setInvulnerable(true);
            client.world.addEntity(camera);
            applyCameraTransform();
            client.setCameraEntity(camera);

            savedInput = client.player.input;
            client.player.input = new Input();       // inert: never reads the keyboard

            active = true;
            say(client, "RTS camera - WASD/drag pan, wheel zoom, Z/C rotate");
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
        } catch (Exception e) {
            IsoRts.LOG.error("failed to leave RTS camera mode cleanly", e);
        } finally {
            camera = null;
            savedInput = null;
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

        handleRotate(client);
        handlePan(client);

        // Ease the rendered values toward the input targets.
        focusX = MathHelper.lerp(EASE, focusX, targetFocusX);
        focusY = MathHelper.lerp(EASE, focusY, targetFocusY);
        focusZ = MathHelper.lerp(EASE, focusZ, targetFocusZ);
        distance = MathHelper.lerp(EASE, distance, targetDistance);
        yaw += (float) (MathHelper.wrapDegrees(targetYaw - yaw) * EASE);

        applyCameraTransform();
    }

    private void handleRotate(MinecraftClient client) {
        if (rotateCooldown > 0) {
            rotateCooldown--;
            return;
        }
        long window = client.getWindow().getHandle();
        boolean ccw = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_Z) == GLFW.GLFW_PRESS;
        boolean cw = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_C) == GLFW.GLFW_PRESS;
        if (ccw ^ cw) {
            targetYaw = MathHelper.wrapDegrees(targetYaw + (cw ? YAW_STEP : -YAW_STEP));
            rotateCooldown = ROTATE_COOLDOWN_TICKS;
        }
    }

    private void handlePan(MinecraftClient client) {
        double speed = PAN_SPEED * (client.options.sprintKey.isPressed() ? SPRINT_MULTIPLIER : 1.0);
        // Zoomed out, the same screen distance covers more ground - scale panning with it.
        speed *= distance / DEFAULT_DISTANCE;

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
            targetFocusX += dx / len * speed;
            targetFocusZ += dz / len * speed;
        }

        // Middle-drag pans, matching how most RTS games grab the map.
        long window = client.getWindow().getHandle();
        boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
        double mx = client.mouse.getX();
        double my = client.mouse.getY();
        if (down) {
            if (middleWasDown) {
                double ddx = (mx - lastMouseX) * DRAG_PAN_SCALE * (distance / DEFAULT_DISTANCE);
                double ddy = (my - lastMouseY) * DRAG_PAN_SCALE * (distance / DEFAULT_DISTANCE);
                targetFocusX += -fx * ddy - fz * ddx;
                targetFocusZ += -fz * ddy + fx * ddx;
            }
            middleWasDown = true;
        } else {
            middleWasDown = false;
        }
        lastMouseX = mx;
        lastMouseY = my;

        // Target the terrain height under the focus; the ease in tick() keeps it from popping
        // as you pan across cliffs.
        targetFocusY = groundAt(client, targetFocusX, targetFocusZ);
    }

    /** Place the camera on a fixed isometric orbit around the focus point. */
    private void applyCameraTransform() {
        if (camera == null) {
            return;
        }
        float yawRad = yaw * MathHelper.RADIANS_PER_DEGREE;
        float pitchRad = ISO_PITCH * MathHelper.RADIANS_PER_DEGREE;

        double horizontal = distance * MathHelper.cos(pitchRad);
        double vertical = distance * MathHelper.sin(pitchRad);

        double cx = focusX + MathHelper.sin(yawRad) * horizontal;
        double cz = focusZ - MathHelper.cos(yawRad) * horizontal;
        double cy = focusY + vertical;

        // Record where the camera WAS, then move it. Minecraft renders the camera interpolated
        // between last* and current using the frame's tick delta, so overwriting last* with the
        // new position (as this did originally) destroys interpolation and the camera moves in 20
        // visible steps per second no matter the framerate.
        camera.setLastPositionAndAngles(camera.getEntityPos(), camera.getYaw(), camera.getPitch());
        camera.setPosition(cx, cy, cz);
        camera.setYaw(yaw);
        camera.setPitch(ISO_PITCH);
    }

    private static double groundAt(MinecraftClient client, double x, double z) {
        if (client.world == null) {
            return 64.0;
        }
        BlockPos pos = BlockPos.ofFloored(x, 0.0, z);
        return client.world.getTopY(Heightmap.Type.WORLD_SURFACE, pos.getX(), pos.getZ());
    }

    private static void say(MinecraftClient client, String msg) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[rts] " + msg), true);
        }
    }
}
