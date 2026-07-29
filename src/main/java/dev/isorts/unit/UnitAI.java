package dev.isorts.unit;

import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Makes the armies actually fight.
 * <p>
 * Vanilla mob AI only notices an enemy inside its follow range (~16-32 blocks), so two armies
 * parked on a map never meet: this walks them together. Every second, any unit without a live
 * enemy target picks the nearest enemy and either engages it (close) or paths one hop towards it
 * (far - Minecraft's pathfinder cannot compute a route hundreds of blocks long, so the approach is
 * done in {@value #STEP_DISTANCE}-block hops).
 * <p>
 * ponytail: goal-based combat is left entirely to vanilla. Once {@code setTarget} is set, the
 * mob's own MeleeAttackGoal does the approach, swing timing, damage and animation - all of which
 * would otherwise be ours to write and balance.
 */
public final class UnitAI {

    /** How often to reconsider targets. Combat itself runs on vanilla goals every tick. */
    private static final int RETARGET_INTERVAL = 20;
    /** Inside this range, hand the enemy to vanilla AI and let it close the distance. */
    private static final double ENGAGE_RANGE = 24.0;
    /** How far to path in one hop while marching towards a distant enemy. */
    private static final double STEP_DISTANCE = 20.0;
    /** Give up on enemies further than this so units do not march across the whole map. */
    private static final double SEEK_RANGE = 400.0;
    private static final double MARCH_SPEED = 1.0;

    /** Ticks a player's order outranks the AI, so commanded units are not immediately overridden. */
    private static final long ORDER_HOLD_TICKS = 200;

    private static final Map<UUID, Long> orderedUntil = new HashMap<>();

    // The whole-world query returns List<? extends T>, so the wildcard has to be carried here.
    private static List<? extends MobEntity> units = new ArrayList<>();
    private static int countdown;

    private UnitAI() {
    }

    /** Note that a player has just given this unit an order. */
    public static void markOrdered(MobEntity unit) {
        orderedUntil.put(unit.getUuid(), unit.getEntityWorld().getTime() + ORDER_HOLD_TICKS);
    }

    private static boolean underPlayerOrder(MobEntity unit, long now) {
        Long until = orderedUntil.get(unit.getUuid());
        if (until == null) {
            return false;
        }
        if (now >= until) {
            orderedUntil.remove(unit.getUuid());
            return false;
        }
        // An order that has been carried out (navigation finished) stops holding the unit back.
        return !unit.getNavigation().isIdle();
    }

    public static void tick(ServerWorld world) {
        long now = world.getTime();

        if (--countdown <= 0) {
            countdown = RETARGET_INTERVAL;
            units = world.getEntitiesByType(TypeFilter.instanceOf(MobEntity.class), Units::isUnit);
            for (MobEntity unit : units) {
                if (!underPlayerOrder(unit, now)) {
                    think(world, unit);
                }
            }
            if (orderedUntil.size() > 256) {
                orderedUntil.clear();           // paranoia: never let the order map grow unbounded
            }
        }

        // Every tick, and cheap: vanilla goals re-target players on their own (illagers hunt
        // players by default), and an AFK player must never be attacked by the simulation.
        for (MobEntity unit : units) {
            if (unit.isAlive() && unit.getTarget() instanceof PlayerEntity) {
                unit.setTarget(null);
            }
        }
    }

    /**
     * Push aside leaves a unit is jammed in.
     * <p>
     * Minecraft's pathfinder treats leaves as solid and will not route through them, so a squad
     * that walks into a hedge or under a low canopy simply stops and stands there for the rest of
     * the battle. Rather than replace the navigator, the leaves in the unit's own two blocks of
     * space are cleared - they are brushing through the foliage. Only leaves: logs, walls and
     * anything else built stay exactly where they are.
     */
    private static void clearLeaves(ServerWorld world, MobEntity unit) {
        BlockPos at = unit.getBlockPos();
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockPos p = at.add(dx, dy, dz);
                    if (world.getBlockState(p).isIn(BlockTags.LEAVES)) {
                        world.breakBlock(p, false);
                    }
                }
            }
        }
    }

    /** True if this unit is wedged: told to go somewhere, but its navigation has given up. */
    private static boolean isStuck(MobEntity unit) {
        return unit.getNavigation().isIdle() && unit.getTarget() != null;
    }

    private static void think(ServerWorld world, MobEntity unit) {
        if (isStuck(unit)) {
            clearLeaves(world, unit);
        }

        LivingEntity current = unit.getTarget();
        if (current != null && current.isAlive() && Units.areEnemies(unit, current)
                && unit.squaredDistanceTo(current) < ENGAGE_RANGE * ENGAGE_RANGE * 4.0) {
            return;                             // already fighting something valid
        }

        MobEntity enemy = nearestEnemy(unit);
        if (enemy == null) {
            return;
        }

        double distSq = unit.squaredDistanceTo(enemy);
        if (distSq <= ENGAGE_RANGE * ENGAGE_RANGE) {
            unit.setTarget(enemy);
            return;
        }

        // Too far for vanilla to path: march one hop towards them.
        Vec3d towards = enemy.getEntityPos().subtract(unit.getEntityPos()).normalize()
                .multiply(STEP_DISTANCE);
        Vec3d waypoint = unit.getEntityPos().add(towards);
        int x = (int) Math.floor(waypoint.x);
        int z = (int) Math.floor(waypoint.z);
        int y = world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, x, z);
        boolean moving = unit.getNavigation().startMovingTo(x + 0.5, y, z + 0.5, MARCH_SPEED);
        if (!moving) {
            // No route to the next waypoint: usually foliage in the way on a wooded map.
            clearLeaves(world, unit);
        }
        unit.setTarget(null);
    }

    private static MobEntity nearestEnemy(MobEntity unit) {
        MobEntity best = null;
        double bestSq = SEEK_RANGE * SEEK_RANGE;
        for (MobEntity other : units) {
            if (!other.isAlive() || !Units.areEnemies(unit, other)) {
                continue;
            }
            double d = unit.squaredDistanceTo(other);
            if (d < bestSq) {
                bestSq = d;
                best = other;
            }
        }
        return best;
    }

    public static void reset() {
        orderedUntil.clear();
        units = new ArrayList<>();
        countdown = 0;
    }
}
