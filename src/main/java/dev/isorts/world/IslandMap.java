package dev.isorts.world;

import dev.isorts.IsoRts;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.random.Random;

import java.util.ArrayList;
import java.util.List;

/**
 * Raises islands out of a flat ocean world, giving an RTS-shaped map: mostly level ground,
 * clearly separated territories, open water between them.
 * <p>
 * ponytail: built by loops rather than a worldgen datapack. Custom noise settings would mean
 * shipping and debugging JSON worldgen for terrain we want to be deliberately simple and
 * predictable - flat, with islands exactly where the factions need them.
 */
public final class IslandMap {

    /** Water surface in the ocean preset: floor(-64) + 1 bedrock + 110 stone + 15 water. */
    public static final int SEA_LEVEL = 61;
    private static final int SEA_FLOOR = 46;

    /** Islands: x offset, z offset, radius, peak height above sea level. */
    private static final int[][] ISLANDS = {
            {-110,  -80, 46, 7},   // 0: faction A
            { 110,   80, 46, 7},   // 1: faction B
            {   0,    0, 34, 5},   // 2: centre, contested
            {-120,  110, 26, 9},   // 3: minor
            { 120, -110, 26, 9},   // 4: minor
            {   0, -170, 22, 6},   // 5: minor
            {   0,  170, 22, 6},   // 6: minor
    };

    private IslandMap() {
    }

    /** Island centres without building anything - used when re-attaching to an existing map. */
    public static List<BlockPos> centres(BlockPos origin) {
        List<BlockPos> centres = new ArrayList<>();
        for (int[] spec : ISLANDS) {
            centres.add(new BlockPos(origin.getX() + spec[0], SEA_LEVEL + spec[3], origin.getZ() + spec[1]));
        }
        return centres;
    }

    /** @return centre of each island, on its surface, in the order declared above */
    public static List<BlockPos> build(ServerWorld world, BlockPos origin) {
        List<BlockPos> centres = new ArrayList<>();
        Random random = world.getRandom();

        for (int[] spec : ISLANDS) {
            int cx = origin.getX() + spec[0];
            int cz = origin.getZ() + spec[1];
            int radius = spec[2];
            int peak = SEA_LEVEL + spec[3];

            buildIsland(world, cx, cz, radius, peak, random);
            centres.add(new BlockPos(cx, peak, cz));
        }
        IsoRts.LOG.info("raised {} islands around {}", centres.size(), origin.toShortString());
        return centres;
    }

    /**
     * One island: a squashed dome, sand at the waterline, grass above.
     * <p>
     * The profile is {@code 1 - (d/r)^2}, which is flat across the middle and steepens at the
     * rim - buildable in the centre, and it still reads as an island from the shore.
     */
    private static void buildIsland(ServerWorld world, int cx, int cz, int radius, int peak, Random random) {
        int rise = peak - SEA_FLOOR;

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > radius) {
                    continue;
                }
                double t = d / radius;
                int top = SEA_FLOOR + (int) Math.round(rise * (1.0 - t * t));
                if (top <= SEA_FLOOR) {
                    continue;
                }

                int x = cx + dx;
                int z = cz + dz;

                for (int y = SEA_FLOOR; y <= top; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    if (y == top) {
                        // Sand where the land meets the water, grass further up.
                        world.setBlockState(p, top <= SEA_LEVEL + 1
                                ? Blocks.SAND.getDefaultState()
                                : Blocks.GRASS_BLOCK.getDefaultState());
                    } else if (y > top - 4) {
                        world.setBlockState(p, top <= SEA_LEVEL + 1
                                ? Blocks.SAND.getDefaultState()
                                : Blocks.DIRT.getDefaultState());
                    } else {
                        world.setBlockState(p, Blocks.STONE.getDefaultState());
                    }
                }
                // Clear any water left standing above the new land.
                for (int y = top + 1; y <= SEA_LEVEL + 2; y++) {
                    world.setBlockState(new BlockPos(x, y, z), Blocks.AIR.getDefaultState());
                }
            }
        }

        scatterTrees(world, cx, cz, radius, peak, random);
    }

    /** A handful of trees, kept clear of the centre so bases have room. */
    private static void scatterTrees(ServerWorld world, int cx, int cz, int radius, int peak, Random random) {
        int attempts = radius / 2;
        for (int i = 0; i < attempts; i++) {
            int dx = random.nextInt(radius * 2) - radius;
            int dz = random.nextInt(radius * 2) - radius;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < radius * 0.45 || d > radius * 0.9) {
                continue;                       // leave the middle free, keep off the shoreline
            }
            int x = cx + dx;
            int z = cz + dz;
            int y = Structures.groundY(world, x, z);
            if (y <= SEA_LEVEL + 1) {
                continue;
            }
            if (!world.getBlockState(new BlockPos(x, y - 1, z)).isOf(Blocks.GRASS_BLOCK)) {
                continue;
            }
            buildTree(world, new BlockPos(x, y, z), 4 + random.nextInt(3), random);
        }
    }

    private static void buildTree(ServerWorld world, BlockPos base, int height, Random random) {
        boolean cherry = random.nextInt(3) == 0;
        var log = cherry ? Blocks.CHERRY_LOG : Blocks.OAK_LOG;
        var leaves = cherry ? Blocks.CHERRY_LEAVES : Blocks.OAK_LEAVES;

        for (int y = 0; y < height; y++) {
            world.setBlockState(base.up(y), log.getDefaultState());
        }
        for (int dy = height - 2; dy <= height + 1; dy++) {
            int r = (dy >= height) ? 1 : 2;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    if (dx * dx + dz * dz > r * r + 1) {
                        continue;
                    }
                    BlockPos p = base.add(dx, dy, dz);
                    if (world.getBlockState(p).isAir()) {
                        world.setBlockState(p, leaves.getDefaultState());
                    }
                }
            }
        }
    }
}
