package dev.isorts.unit;

import dev.isorts.IsoRts;
import dev.isorts.world.Roads;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.List;

/**
 * Keeps a battle going around the player.
 * <p>
 * Both sides are spawned relative to the player on opposite ends of one axis, on a road where the
 * map has one, and {@link UnitAI} does the rest - each side marches at the other and they clash in
 * the middle, which is where the player is standing.
 * <p>
 * ponytail: no bases, no economy, no spawn buildings. The whole director is two counts and a
 * distance; a battle that never stops is what was actually asked for, and the timers deliver it.
 */
public final class Skirmish {

    /**
     * Where each side musters, relative to the player.
     * <p>
     * Not symmetrical, and that is the point. With both sides equidistant the player - who charges
     * the nearest enemy - arrived first and fought alone while his own troops were still crossing
     * the field behind him. Friendlies now form up practically on top of him and the enemy comes
     * from further out, so the whole line advances together and he is fighting *with* his army.
     */
    private static final int FRIENDLY_DISTANCE = 7;
    /** 22, not 30: the two lines have to meet near the player, not a screen away from him. */
    private static final int ENEMY_DISTANCE = 22;
    /**
     * How far to hunt for a road near the muster point. Tight for friendlies - snapping them to a
     * road twenty blocks away would undo the point of forming up next to the player.
     */
    private static final int FRIENDLY_ROAD_SEARCH = 8;
    private static final int ENEMY_ROAD_SEARCH = 22;

    /** Ticks between reinforcement checks. */
    private static final int WAVE_INTERVAL = 120;                 // 6s
    private static final int SQUAD_SIZE = 6;
    /**
     * Units per side. Two dozen each makes a battle rather than a scuffle; the ceiling is vanilla
     * pathfinding, which is what starts costing frames long before the rendering does.
     */
    private static final int SIDE_CAP = 24;
    /** Only count and reinforce around the player - this is a skirmish, not a world war. */
    private static final double THEATRE_RADIUS = 160.0;

    private static int countdown = WAVE_INTERVAL;
    /** Rotates so successive waves come from different directions instead of one corridor. */
    private static double axis;
    /**
     * Stops reinforcements entirely.
     * <p>
     * Needed to walk the player anywhere on purpose: with waves arriving every six seconds and
     * auto-engage steering him at the nearest raider, choosing a spot by hand is a fight against
     * the game. Paused plus a clear leaves no enemies, so auto-engage has nothing to chase and the
     * keyboard is fully in charge again.
     */
    private static boolean paused;

    private Skirmish() {
    }

    public static boolean isPaused() {
        return paused;
    }

    /** @return the new paused state */
    public static boolean togglePause() {
        paused = !paused;
        countdown = WAVE_INTERVAL;
        return paused;
    }

    public static void tick(ServerWorld world) {
        if (paused) {
            return;
        }
        if (--countdown > 0) {
            return;
        }
        countdown = WAVE_INTERVAL;

        for (ServerPlayerEntity player : world.getPlayers()) {
            reinforce(world, player);
        }
    }

    private static void reinforce(ServerWorld world, ServerPlayerEntity player) {
        Box theatre = player.getBoundingBox().expand(THEATRE_RADIUS);
        int friends = count(world, theatre, Units.KINGDOM);
        int foes = count(world, theatre, Units.RAIDERS);
        if (friends >= SIDE_CAP && foes >= SIDE_CAP) {
            return;
        }

        axis += 0.7;                                              // ~40 degrees per wave
        double ux = Math.cos(axis);
        double uz = Math.sin(axis);
        BlockPos centre = player.getBlockPos();
        IsoRts.LOG.info("wave: {} friendly / {} hostile in theatre", friends, foes);

        if (friends < SIDE_CAP) {
            BlockPos at = Roads.musterPoint(world,
                    centre.getX() + (int) (ux * FRIENDLY_DISTANCE),
                    centre.getZ() + (int) (uz * FRIENDLY_DISTANCE), FRIENDLY_ROAD_SEARCH);
            spawnSquad(world, at, Units.KINGDOM, Math.min(SQUAD_SIZE, SIDE_CAP - friends));
        }
        if (foes < SIDE_CAP) {
            BlockPos at = Roads.musterPoint(world,
                    centre.getX() - (int) (ux * ENEMY_DISTANCE),
                    centre.getZ() - (int) (uz * ENEMY_DISTANCE), ENEMY_ROAD_SEARCH);
            spawnSquad(world, at, Units.RAIDERS, Math.min(SQUAD_SIZE, SIDE_CAP - foes));
        }
    }

    private static int count(ServerWorld world, Box area, String faction) {
        List<? extends MobEntity> present = world.getEntitiesByType(
                TypeFilter.instanceOf(MobEntity.class), area,
                e -> faction.equals(Units.factionOf(e)));
        return present.size();
    }

    private static void spawnSquad(ServerWorld world, BlockPos at, String faction, int count) {
        int spawned = 0;
        for (int i = 0; i < count; i++) {
            EntityType<? extends MobEntity> type = pick(world, faction, i);
            MobEntity unit = type.create(world, SpawnReason.EVENT);
            if (unit == null) {
                continue;
            }
            int x = at.getX() + world.getRandom().nextInt(5) - 2;
            int z = at.getZ() + world.getRandom().nextInt(5) - 2;
            unit.refreshPositionAndAngles(x + 0.5, at.getY(), z + 0.5,
                    world.getRandom().nextFloat() * 360.0f, 0.0f);
            Units.enlist(unit, faction);
            arm(unit);
            if (world.spawnEntity(unit)) {
                spawned++;
            }
        }
        if (spawned > 0) {
            IsoRts.LOG.info("[{}] {} reinforcement(s) arrived at {}", faction, spawned, at.toShortString());
        }
    }

    /**
     * Squad composition. The player's side is mostly human soldiers with a golem and a war dog
     * mixed in; the raiders are pillagers stiffened with vindicators, because pillagers hang back
     * with crossbows and something has to actually close the distance.
     */
    private static EntityType<? extends MobEntity> pick(ServerWorld world, String faction, int index) {
        if (Units.KINGDOM.equals(faction)) {
            return switch (index % 4) {
                case 3 -> EntityType.IRON_GOLEM;
                case 2 -> EntityType.WOLF;
                default -> IsoUnits.SOLDIER;
            };
        }
        return index % 3 == 2 ? EntityType.VINDICATOR : EntityType.PILLAGER;
    }

    /** Put a weapon in the hand of anything that renders one. */
    private static void arm(MobEntity unit) {
        if (unit instanceof SoldierEntity) {
            unit.equipStack(EquipmentSlot.MAINHAND, new ItemStack(Items.IRON_SWORD));
            unit.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
            unit.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        }
    }

    public static void reset() {
        countdown = WAVE_INTERVAL;
        axis = 0.0;
        paused = false;
    }
}
