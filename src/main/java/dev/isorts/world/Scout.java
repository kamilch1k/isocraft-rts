package dev.isorts.world;

import dev.isorts.IsoRts;
import net.minecraft.block.BlockState;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Finds places worth filming a battle in: level, open ground with a clear line to where the
 * isometric camera actually sits.
 * <p>
 * "Somewhere without trees" is not a property of a location on its own - it depends on which way
 * the camera looks. Isocraft parks the camera roughly 27 blocks east, 27 north and 23 up of the
 * player (measured, not guessed, from the mod's own probe output), so what matters is whether that
 * one diagonal corridor is clear. A clearing with a single oak on its north-east edge is a bad
 * shot; a courtyard with a wall behind the player is a good one.
 */
public final class Scout {

    /** Where the isometric camera sits relative to the player, normalised. */
    private static final Vec3d CAMERA_DIR = new Vec3d(27.0, 23.0, -27.0).normalize();
    /** How far along that line to check for obstructions. */
    private static final double SIGHT_LENGTH = 34.0;

    private static final int SEARCH_RADIUS = 96;
    private static final int STEP = 8;
    /** Footprint the battle needs: roughly this much flat ground around the spot. */
    private static final int[][] FLATNESS_SAMPLES = {
            {-8, -8}, {8, -8}, {-8, 8}, {8, 8}, {0, 10}, {10, 0}, {-10, 0}, {0, -10},
    };

    private Scout() {
    }

    public record Spot(BlockPos pos, int blocked, int roughness, int scenery) {
        public int score() {
            // Obstruction dominates. Scenery is a bonus, capped so that a spot cannot buy its way
            // up the list by being surrounded by walls - which is how the first version put the
            // camera inside the side of a timber hall.
            return blocked * 15 + roughness - Math.min(scenery, 30);
        }
    }

    /** The most filmable spots near the given position, best first. */
    public static List<Spot> survey(ServerWorld world, BlockPos around, int limit) {
        List<Spot> spots = new ArrayList<>();

        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx += STEP) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz += STEP) {
                int x = around.getX() + dx;
                int z = around.getZ() + dz;
                int y = Structures.terrainY(world, x, z);
                if (y <= world.getSeaLevel() + 1) {
                    continue;                       // underwater or on the shoreline
                }
                if (!standable(world, x, y, z)) {
                    continue;                       // inside a building or a bush
                }

                int roughness = 0;
                for (int[] o : FLATNESS_SAMPLES) {
                    roughness += Math.abs(Structures.terrainY(world, x + o[0], z + o[1]) - y);
                }
                spots.add(new Spot(new BlockPos(x, y, z),
                        obstructions(world, x, y, z), roughness, scenery(world, x, y, z)));
            }
        }

        spots.sort(Comparator.comparingInt(Spot::score));
        return spots.subList(0, Math.min(limit, spots.size()));
    }

    /** Two blocks of air to stand in. */
    private static boolean standable(ServerWorld world, int x, int y, int z) {
        return world.getBlockState(new BlockPos(x, y, z)).isAir()
                && world.getBlockState(new BlockPos(x, y + 1, z)).isAir();
    }

    /**
     * How much solid matter sits on the line between the player and the camera.
     * <p>
     * Anything on that line counts, not just foliage. Counting only trees was a mistake worth
     * recording: it scored a spot pressed against the side of a timber hall as perfect, because the
     * hall was "scenery" - and the resulting shot was two thirds wall. A wall BEHIND the player is
     * never on this line, so it is never penalised; a wall in front of the camera always is.
     * <p>
     * Grass and flowers have no collision box and are skipped, so a meadow does not read as solid.
     */
    private static int obstructions(ServerWorld world, int x, int y, int z) {
        Vec3d from = new Vec3d(x + 0.5, y + 1.5, z + 0.5);
        int blocked = 0;
        for (double t = 2.0; t <= SIGHT_LENGTH; t += 1.0) {
            BlockPos p = BlockPos.ofFloored(from.add(CAMERA_DIR.multiply(t)));
            BlockState state = world.getBlockState(p);
            if (!state.isAir() && !state.getCollisionShape(world, p).isEmpty()) {
                blocked++;
            }
        }
        return blocked;
    }

    /**
     * How much built-up surroundings there are - the difference between a battle in a town and a
     * battle in an empty field.
     * <p>
     * ponytail: measured as "solid blocks standing above head height nearby", not a list of
     * building materials. Buildings and walls stand up; grass and paving do not. The camera's own
     * corridor is excluded so that scenery can never be counted as scenery and obstruction at once.
     */
    private static int scenery(ServerWorld world, int x, int y, int z) {
        int count = 0;
        for (int dx = -15; dx <= 15; dx += 3) {
            for (int dz = -15; dz <= 15; dz += 3) {
                if (dx > 2 && dz < -2) {
                    continue;                       // the camera's quadrant
                }
                for (int dy = 3; dy <= 12; dy += 3) {
                    if (!world.getBlockState(new BlockPos(x + dx, y + dy, z + dz)).isAir()) {
                        count++;
                    }
                }
            }
        }
        return count;
    }

    public static void log(ServerWorld world, BlockPos around, int limit) {
        List<Spot> spots = survey(world, around, limit);
        IsoRts.LOG.info("[scout] {} candidate spot(s) near {}", spots.size(), around.toShortString());
        for (Spot spot : spots) {
            IsoRts.LOG.info("[scout] {} blocked={} rough={} scenery={} score={}",
                    spot.pos().toShortString(), spot.blocked(), spot.roughness(),
                    spot.scenery(), spot.score());
        }
    }
}
