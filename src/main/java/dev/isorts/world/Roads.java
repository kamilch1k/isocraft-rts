package dev.isorts.world;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;

import java.util.Set;

/**
 * Finds the paths and streets a hand-built map is laid out along, so armies arrive on the road
 * instead of out of a hedge.
 * <p>
 * ponytail: a block whitelist and a spiral search. Real path-following (tracing a road graph,
 * walking it to a junction) would be a lot of machinery to answer one question - "where near here
 * can a squad stand?" - that a few dozen block reads already answer.
 */
public final class Roads {

    /** What a road is paved with, across the styles a village or castle map tends to use. */
    private static final Set<Block> PAVING = Set.of(
            Blocks.DIRT_PATH, Blocks.GRAVEL, Blocks.COBBLESTONE, Blocks.MOSSY_COBBLESTONE,
            Blocks.STONE, Blocks.STONE_BRICKS, Blocks.MOSSY_STONE_BRICKS, Blocks.SMOOTH_STONE,
            Blocks.ANDESITE, Blocks.POLISHED_ANDESITE, Blocks.STONE_SLAB, Blocks.COBBLESTONE_SLAB,
            Blocks.STONE_BRICK_SLAB, Blocks.SANDSTONE, Blocks.COARSE_DIRT, Blocks.PACKED_MUD,
            Blocks.BRICKS, Blocks.DEEPSLATE_BRICKS, Blocks.POLISHED_DEEPSLATE);

    private Roads() {
    }

    /** True if this column's surface block is paving, and there is room to stand on it. */
    private static boolean isRoad(ServerWorld world, int x, int z) {
        int y = Structures.terrainY(world, x, z);
        BlockState surface = world.getBlockState(new BlockPos(x, y - 1, z));
        if (!PAVING.contains(surface.getBlock())) {
            return false;
        }
        // Two blocks of clearance, or a unit spawns inside a wall.
        return world.getBlockState(new BlockPos(x, y, z)).isAir()
                && world.getBlockState(new BlockPos(x, y + 1, z)).isAir();
    }

    /**
     * The nearest road tile to the given point, or null if there is none within {@code radius}.
     * Searched in rings so the first hit is the closest.
     */
    public static BlockPos nearestRoad(ServerWorld world, int cx, int cz, int radius) {
        if (isRoad(world, cx, cz)) {
            return new BlockPos(cx, Structures.terrainY(world, cx, cz), cz);
        }
        for (int r = 2; r <= radius; r += 2) {
            for (int i = -r; i <= r; i += 2) {
                for (int[] candidate : new int[][] {
                        {cx + i, cz - r}, {cx + i, cz + r}, {cx - r, cz + i}, {cx + r, cz + i}}) {
                    if (isRoad(world, candidate[0], candidate[1])) {
                        return new BlockPos(candidate[0],
                                Structures.terrainY(world, candidate[0], candidate[1]), candidate[1]);
                    }
                }
            }
        }
        return null;
    }

    /**
     * Somewhere a squad can stand near the given point: a road if there is one, otherwise open
     * ground above sea level.
     */
    public static BlockPos musterPoint(ServerWorld world, int cx, int cz, int radius) {
        BlockPos road = nearestRoad(world, cx, cz, radius);
        if (road != null) {
            return road;
        }
        BlockPos flat = Structures.findFlatSite(world, cx, cz, Math.min(radius, 24));
        return flat.up();
    }
}
