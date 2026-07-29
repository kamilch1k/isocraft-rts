package dev.isorts.client;

import dev.isorts.IsoRts;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * State 3: a detached free-flying RTS camera.
 * <p>
 * The player stays put and keeps their body rotation (which the camera copies, so the mouse still
 * looks around); WASD/space/sneak fly the camera instead of walking the character.
 * <p>
 * ponytail: no mixins. Player movement is suppressed by swapping {@code player.input} for a plain
 * {@link Input}, whose {@code tick()} does not read the keyboard, so {@code playerInput} stays
 * DEFAULT. Zeroing the field instead would not work - KeyboardInput.tick() overwrites it every
 * tick from the live key state.
 */
public final class RtsCameraMode {

    private static final double BASE_SPEED = 0.55;
    private static final double SPRINT_MULTIPLIER = 2.5;
    /** Camera starts this far above the player so you open onto the scene, not the ground. */
    private static final double ENTRY_HEIGHT = 12.0;

    private boolean active;
    private ArmorStandEntity camera;
    private Input savedInput;

    public boolean isActive() {
        return active;
    }

    public void toggle(MinecraftClient client) {
        if (active) {
            disable(client);
        } else {
            enable(client);
        }
    }

    private void enable(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            return;
        }
        try {
            camera = new ArmorStandEntity(EntityType.ARMOR_STAND, client.world);
            camera.setInvisible(true);
            camera.setNoGravity(true);
            camera.setInvulnerable(true);
            Vec3d start = client.player.getEntityPos().add(0.0, ENTRY_HEIGHT, 0.0);
            camera.refreshPositionAndAngles(start.x, start.y, start.z,
                    client.player.getYaw(), client.player.getPitch());
            client.world.addEntity(camera);
            client.setCameraEntity(camera);

            savedInput = client.player.input;
            client.player.input = new Input();      // inert: never reads the keyboard

            active = true;
            say(client, "RTS camera - WASD fly, space/shift up-down, M to exit");
        } catch (Exception e) {
            IsoRts.LOG.error("failed to enter RTS camera mode", e);
            disable(client);                        // never strand the player in a broken state
        }
    }

    private void disable(MinecraftClient client) {
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
            if (active) {
                active = false;
                say(client, "RTS camera off");
            }
        }
    }

    /** Called every client tick. Flies the camera and keeps it pointed where the mouse looks. */
    public void tick(MinecraftClient client) {
        if (!active) {
            return;
        }
        if (client.player == null || client.world == null || camera == null) {
            disable(client);
            return;
        }

        // The mouse still turns the player's body; mirror that onto the camera so look works.
        camera.setYaw(client.player.getYaw());
        camera.setPitch(client.player.getPitch());
        camera.updateLastAngles();      // 1.21.11 renamed prevYaw/prevPitch -> lastYaw/lastPitch

        double speed = BASE_SPEED * (client.options.sprintKey.isPressed() ? SPRINT_MULTIPLIER : 1.0);

        float yawRad = camera.getYaw() * MathHelper.RADIANS_PER_DEGREE;
        double fx = -MathHelper.sin(yawRad);
        double fz = MathHelper.cos(yawRad);

        double dx = 0.0;
        double dy = 0.0;
        double dz = 0.0;

        if (client.options.forwardKey.isPressed())  { dx += fx; dz += fz; }
        if (client.options.backKey.isPressed())     { dx -= fx; dz -= fz; }
        if (client.options.leftKey.isPressed())     { dx += fz; dz -= fx; }
        if (client.options.rightKey.isPressed())    { dx -= fz; dz += fx; }
        if (client.options.jumpKey.isPressed())     { dy += 1.0; }
        if (client.options.sneakKey.isPressed())    { dy -= 1.0; }

        double len = Math.sqrt(dx * dx + dz * dz);
        if (len > 1.0e-4) {
            dx = dx / len * speed;
            dz = dz / len * speed;
        }
        dy *= speed;

        if (dx != 0.0 || dy != 0.0 || dz != 0.0) {
            Vec3d pos = camera.getEntityPos();
            camera.setPosition(pos.x + dx, pos.y + dy, pos.z + dz);
        }
    }

    private static void say(MinecraftClient client, String msg) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[rts] " + msg), true);
        }
    }
}
