package dev.isorts.client;

import dev.isorts.IsoRts;
import dev.isorts.unit.Units;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.input.Input;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.util.Hand;
import net.minecraft.util.PlayerInput;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec2f;

import java.util.List;

/**
 * The player's combat, reduced to nothing: they turn on the nearest enemy, walk into range, and
 * keep swinging until it is dead.
 * <p>
 * Aiming individual blows in an isometric view is fiddly - the cursor is thirty blocks from the
 * character and everything is moving - and standing still in the middle of a battle looks absurd.
 * Only enemy units are ever chosen as a target, so this can never make the player cut down their
 * own troops or the map's livestock.
 * <p>
 * ponytail: no mixin. Attacks go through vanilla's interaction manager on vanilla's own cooldown,
 * so damage, enchantments, sweeping, knockback and durability behave exactly as Minecraft defines
 * them; movement goes through an {@link Input} wrapper that delegates to the real keyboard and only
 * adds forward movement, so the player never loses manual control.
 */
final class AutoAttack {

    /** Vanilla melee reach is 3; a little more makes an isometric brawl less fussy. */
    private static final double REACH = 3.6;
    /**
     * How far away an enemy can be and still be worth walking at.
     * <p>
     * Was 24, and the player spent whole clips standing alone: his own two dozen troops screen him,
     * so the fighting happens past that radius and drifts further as the line moves. Because the
     * isometric camera is locked to the player, "the player is in the melee" and "the melee is in
     * frame" are the same statement - so he is given a long leash and goes to find the fight.
     */
    private static final double ENGAGE_RANGE = 48.0;

    /**
     * The player may look a little down, never up.
     * <p>
     * Something in the isometric aiming path keeps throwing the character's head at the sky, which
     * looks broken from a top-down camera. Rather than hunt which mod owns the pitch, it is clamped
     * every tick - and in this view pitch is cosmetic anyway, because aiming comes from the cursor.
     */
    private static final float MIN_PITCH = 0.0f;
    private static final float MAX_PITCH = 40.0f;

    private static EngageInput input;

    private AutoAttack() {
    }

    static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.world == null || client.interactionManager == null) {
            return;
        }

        installInput(player);

        LivingEntity enemy = nearestEnemy(client);
        if (enemy == null) {
            player.setPitch(MathHelper.clamp(player.getPitch(), MIN_PITCH, MAX_PITCH));
            if (input != null) {
                input.target = null;
            }
            return;
        }

        double distSq = player.squaredDistanceTo(enemy);

        // Close the distance, then stop and fight rather than shoving through them. The target is
        // handed to the input wrapper rather than acted on here, so that facing and moving happen
        // in the same breath - see EngageInput.
        if (input != null) {
            input.target = distSq > REACH * REACH && client.currentScreen == null ? enemy : null;
        }
        if (input == null || input.target == null) {
            face(player, enemy);            // standing and fighting: still look at them
        }

        if (distSq > REACH * REACH || client.currentScreen != null) {
            return;
        }
        // Vanilla's cooldown gate: swinging early is what turns a sword into a wet noodle.
        if (player.getAttackCooldownProgress(0.0f) < 1.0f) {
            return;
        }
        client.interactionManager.attackEntity(player, enemy);
        player.swingHand(Hand.MAIN_HAND);
    }

    /** Wrap the player's real input once, and re-wrap if something else replaced it. */
    private static void installInput(ClientPlayerEntity player) {
        if (player.input instanceof EngageInput existing) {
            input = existing;
            return;
        }
        input = new EngageInput(player.input);
        player.input = input;
        IsoRts.LOG.info("auto-engage input installed");
    }

    /**
     * Point the whole character at the target - head and body, not just the camera.
     * <p>
     * Called from inside the input tick rather than from the client tick, and that placement is the
     * fix for the player charging AWAY from the enemy. "Forward" is defined by the player's yaw, and
     * Isocraft aims the character at the mouse cursor - so whichever of us wrote the yaw last
     * decided which way forward was. Setting it here, in the same call that produces the movement
     * vector Minecraft is about to apply, means it cannot be overwritten in between.
     */
    private static void face(ClientPlayerEntity player, LivingEntity target) {
        double dx = target.getX() - player.getX();
        double dz = target.getZ() - player.getZ();
        float yaw = (float) (MathHelper.atan2(dz, dx) * 180.0 / Math.PI) - 90.0f;

        double dy = target.getEyeY() - player.getEyeY();
        double flat = Math.sqrt(dx * dx + dz * dz);
        float pitch = (float) (-(MathHelper.atan2(dy, flat) * 180.0 / Math.PI));

        player.setYaw(yaw);
        player.setHeadYaw(yaw);
        player.setBodyYaw(yaw);
        player.setPitch(MathHelper.clamp(pitch, MIN_PITCH, MAX_PITCH));
    }

    private static LivingEntity nearestEnemy(MinecraftClient client) {
        Box area = client.player.getBoundingBox().expand(ENGAGE_RANGE);
        List<LivingEntity> candidates = client.world.getEntitiesByType(
                TypeFilter.instanceOf(LivingEntity.class), area,
                e -> e.isAlive() && Units.RAIDERS.equals(Units.factionOf(e)));

        LivingEntity best = null;
        double bestSq = ENGAGE_RANGE * ENGAGE_RANGE;
        for (LivingEntity candidate : candidates) {
            double d = candidate.squaredDistanceTo(client.player);
            if (d < bestSq) {
                bestSq = d;
                best = candidate;
            }
        }
        return best;
    }

    /**
     * Adds "walk forward" on top of whatever the player is actually pressing.
     * <p>
     * Delegating rather than replacing is the whole point: the keyboard still works, so the player
     * can walk away from a fight mid-charge, and nothing has to be restored afterwards. Forward is
     * relative to where the character is looking, and {@link #face} has already pointed them at the
     * enemy - so "forward" means "at them".
     */
    private static final class EngageInput extends Input {

        private final Input delegate;
        /** Who to walk at, or null to leave movement entirely to the keyboard. */
        LivingEntity target;

        private EngageInput(Input delegate) {
            this.delegate = delegate;
        }

        @Override
        public void tick() {
            delegate.tick();
            playerInput = delegate.playerInput;
            movementVector = delegate.getMovementInput();

            if (target == null || !target.isAlive()) {
                return;
            }
            // Aim the character HERE, immediately before Minecraft turns this movement vector into
            // motion. Doing it a few lines earlier - in the client tick - let Isocraft's
            // cursor-facing overwrite the yaw first, and then "forward" meant wherever the mouse
            // happened to point, which is why the player kept charging out of the battle.
            MinecraftClient client = MinecraftClient.getInstance();
            if (client.player != null) {
                face(client.player, target);
            }
            playerInput = new PlayerInput(true, playerInput.backward(), playerInput.left(),
                    playerInput.right(), playerInput.jump(), playerInput.sneak(), playerInput.sprint());
            movementVector = new Vec2f(movementVector.x, 1.0f);
        }
    }
}
