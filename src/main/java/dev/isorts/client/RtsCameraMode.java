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
 * The camera has a position and a rotation, and that is all. Earlier versions orbited a focus
 * point on the ground, which meant rotating the view physically swung the camera through the
 * world - it looked like the camera was flying off sideways instead of turning. It now turns in
 * place, like a head.
 * <ul>
 *   <li><b>WASD</b> - move on the horizontal plane, relative to where you are facing</li>
 *   <li><b>Right-drag</b> - pan on the same plane</li>
 *   <li><b>Middle-drag</b> - turn in place (yaw and pitch)</li>
 *   <li><b>Wheel</b> - height, and the only thing that changes it</li>
 * </ul>
 * ponytail: no mixins. Movement is suppressed by swapping {@code player.input} for a plain
 * {@link Input}; scroll chains GLFW's callback and forwards to the previous one when inactive.
 */
public final class RtsCameraMode {

    private static final double MOVE_SPEED = 0.9;
    private static final double SPRINT_MULTIPLIER = 2.5;
    private static final double DRAG_PAN_SCALE = 0.09;
    private static final double LOOK_SENSITIVITY = 0.18;
    private static final double ZOOM_STEP = 6.0;

    /**
     * Narrow FOV is what makes the view read as isometric.
     * <p>
     * Minecraft cannot do a true orthographic projection without breaking Iris shaderpacks, which
     * derive shadows and screen-space effects from the perspective matrix. Narrowing the FOV and
     * pulling the camera far back flattens perspective towards parallel instead.
     * <p>
     * 30 is the floor: Minecraft's FOV option is clamped to 30-110 and rejects anything lower
     * ("Illegal option value 18 for FOV"). Isocraft reaches 18 because that is its own config
     * applied in its own camera code, not the vanilla option. Going below 30 here would mean
     * overriding the projection matrix directly, which is what breaks shaderpacks - so instead
     * the camera simply sits further back.
     */
    private static final int ISO_FOV = 30;

    private static final double ENTRY_HEIGHT = 110.0;
    private static final double MIN_HEIGHT_ABOVE_GROUND = 12.0;
    private static final double MAX_HEIGHT = 380.0;

    /** True isometric: equal foreshortening on all three axes. */
    private static final float DEFAULT_PITCH = 35.264f;
    private static final float MIN_PITCH = 10.0f;
    private static final float MAX_PITCH = 89.0f;

    /** Below this much right-drag, a release counts as a click (a move order) not a pan. */
    private static final double CLICK_SLOP = 3.0;

    /** Input sets target*; rendered values ease toward them so nothing snaps. */
    private static final double EASE = 0.45;

    private boolean active;
    private ArmorStandEntity camera;
    private Input savedInput;

    private double camX;
    private double camY;
    private double camZ;
    private float yaw = 45.0f;
    private float pitch = DEFAULT_PITCH;

    private double targetX;
    private double targetY;
    private double targetZ;
    private float targetYaw = 45.0f;
    private float targetPitch = DEFAULT_PITCH;

    private boolean rightWasDown;
    private boolean middleWasDown;
    private double rightDragDistance;
    private double lastMouseX;
    private double lastMouseY;

    private GLFWScrollCallbackI previousScrollCallback;
    private boolean scrollHooked;
    private Integer savedFov;
    private int debugTicks;

    public boolean isActive() {
        return active;
    }

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
                    // Zoom = dolly along the view axis. Moving Y alone is not zoom; it just
                    // raises the camera while keeping the same ground point off-centre.
                    Vec3d look = Vec3d.fromPolar(targetPitch, targetYaw);
                    double step = dy * ZOOM_STEP;
                    targetX += look.x * step;
                    targetY = MathHelper.clamp(targetY + look.y * step, -64.0, MAX_HEIGHT);
                    targetZ += look.z * step;
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
            targetX = camX = p.x;
            targetY = camY = p.y + ENTRY_HEIGHT;
            targetZ = camZ = p.z;
            targetYaw = yaw = client.player.getYaw();
            targetPitch = pitch = DEFAULT_PITCH;
            rightDragDistance = 0.0;

            camera = new ArmorStandEntity(EntityType.ARMOR_STAND, client.world);
            camera.setInvisible(true);
            camera.setNoGravity(true);
            camera.setInvulnerable(true);
            client.world.addEntity(camera);
            camera.setPosition(camX, camY, camZ);
            camera.setYaw(yaw);
            camera.setPitch(pitch);
            camera.setLastPositionAndAngles(camera.getEntityPos(), yaw, pitch);
            client.setCameraEntity(camera);

            savedInput = client.player.input;
            client.player.input = new Input();           // inert: never reads the keyboard
            client.mouse.unlockCursor();                 // an RTS needs a pointer

            // Narrow the FOV so the view actually reads as isometric.
            savedFov = client.options.getFov().getValue();
            try {
                client.options.getFov().setValue(ISO_FOV);
            } catch (Exception e) {
                IsoRts.LOG.warn("could not narrow FOV to {}", ISO_FOV, e);
                savedFov = null;
            }

            active = true;
            say(client, "RTS camera - WASD/right-drag move, middle-drag look, wheel height");
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
            if (savedFov != null) {
                client.options.getFov().setValue(savedFov);
                savedFov = null;
            }
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

        // Clicking in the world makes Minecraft re-grab the pointer; take it back - unless we
        // grabbed it deliberately for a look-drag.
        if (client.currentScreen == null && client.mouse.isCursorLocked() && !middleWasDown) {
            client.mouse.unlockCursor();
        }

        long window = client.getWindow().getHandle();
        double mx = client.mouse.getX();
        double my = client.mouse.getY();
        double dmx = mx - lastMouseX;
        double dmy = my - lastMouseY;
        lastMouseX = mx;
        lastMouseY = my;

        handleLook(client, window, dmx, dmy);
        handleMove(client, window, dmx, dmy);
        clampHeight(client);

        camX = MathHelper.lerp(EASE, camX, targetX);
        camY = MathHelper.lerp(EASE, camY, targetY);
        camZ = MathHelper.lerp(EASE, camZ, targetZ);
        yaw += (float) (MathHelper.wrapDegrees(targetYaw - yaw) * EASE);
        pitch += (float) ((targetPitch - pitch) * EASE);

        // Keep the previous transform so Minecraft interpolates between ticks; overwriting it
        // pins the camera to 20 visible steps per second whatever the framerate.
        camera.setLastPositionAndAngles(camera.getEntityPos(), camera.getYaw(), camera.getPitch());
        camera.setPosition(camX, camY, camZ);
        camera.setYaw(yaw);
        camera.setPitch(pitch);

        // Live readout once a second, so a misbehaving camera can be described precisely
        // rather than guessed at.
        if (++debugTicks >= 20) {
            debugTicks = 0;
            say(client, String.format("xyz %.0f %.0f %.0f | yaw %.0f pitch %.0f | fov %d",
                    camX, camY, camZ, yaw, pitch, client.options.getFov().getValue()));
        }
    }

    /** Middle-drag turns the camera in place. It must not move the camera. */
    private void handleLook(MinecraftClient client, long window, double dmx, double dmy) {
        boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;

        // Grab the pointer for the drag: with a free cursor the position stops at the screen edge,
        // deltas go to zero and the turn stalls part-way round.
        if (down && !middleWasDown) {
            client.mouse.lockCursor();
        } else if (!down && middleWasDown) {
            client.mouse.unlockCursor();
        }

        if (down && middleWasDown) {
            targetYaw = MathHelper.wrapDegrees(targetYaw + (float) (dmx * LOOK_SENSITIVITY));
            targetPitch = MathHelper.clamp(
                    targetPitch + (float) (dmy * LOOK_SENSITIVITY), MIN_PITCH, MAX_PITCH);
        }
        middleWasDown = down;
    }

    /**
     * WASD and right-drag move the camera across the horizontal plane. Neither changes height.
     * <p>
     * The ground basis, derived rather than guessed: Minecraft yaw 0 faces south (+Z) and
     * increasing yaw turns right, so the direction at yaw is {@code (-sin, cos)} and the
     * direction at yaw+90 - screen right - is {@code (-cos, -sin)}.
     * <p>
     * Sanity check at yaw 0: facing south, right should be west {@code (-1, 0)}. This gives
     * {@code (-cos 0, -sin 0) = (-1, 0)}. The earlier {@code (cos, sin)} gave {@code (1, 0)},
     * east - i.e. left - which is why strafing and drag-panning went the wrong way.
     */
    private void handleMove(MinecraftClient client, long window, double dmx, double dmy) {
        float yawRad = yaw * MathHelper.RADIANS_PER_DEGREE;
        double forwardX = -MathHelper.sin(yawRad);
        double forwardZ = MathHelper.cos(yawRad);
        double rightX = -MathHelper.cos(yawRad);
        double rightZ = -MathHelper.sin(yawRad);

        // Higher up, the same screen distance covers more ground.
        double heightScale = MathHelper.clamp(targetY / 40.0, 0.5, 4.0);

        double dx = 0.0;
        double dz = 0.0;
        if (client.options.forwardKey.isPressed()) { dx += forwardX; dz += forwardZ; }
        if (client.options.backKey.isPressed())    { dx -= forwardX; dz -= forwardZ; }
        if (client.options.leftKey.isPressed())    { dx -= rightX;   dz -= rightZ; }
        if (client.options.rightKey.isPressed())   { dx += rightX;   dz += rightZ; }

        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 1.0e-4) {
            double speed = MOVE_SPEED * heightScale
                    * (client.options.sprintKey.isPressed() ? SPRINT_MULTIPLIER : 1.0);
            targetX += dx / len * speed;
            targetZ += dz / len * speed;
        }

        boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        if (down) {
            if (rightWasDown) {
                rightDragDistance += Math.abs(dmx) + Math.abs(dmy);
                double px = dmx * DRAG_PAN_SCALE * heightScale;
                double pz = dmy * DRAG_PAN_SCALE * heightScale;
                // Grab-the-map: terrain follows the cursor, so the camera moves opposite.
                // Cursor right -> camera left; cursor down -> camera forward.
                targetX -= rightX * px;
                targetZ -= rightZ * px;
                targetX += forwardX * pz;
                targetZ += forwardZ * pz;
            } else {
                rightDragDistance = 0.0;
            }
        }
        rightWasDown = down;
    }

    /** Never let the camera sink into the ground while panning over hills. */
    private void clampHeight(MinecraftClient client) {
        if (client.world == null) {
            return;
        }
        int ground = client.world.getTopY(
                net.minecraft.world.Heightmap.Type.WORLD_SURFACE,
                MathHelper.floor(targetX), MathHelper.floor(targetZ));
        double floor = ground + MIN_HEIGHT_ABOVE_GROUND;
        if (targetY < floor) {
            targetY = floor;
        }
    }

    private static void say(MinecraftClient client, String msg) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[rts] " + msg), true);
        }
    }
}
