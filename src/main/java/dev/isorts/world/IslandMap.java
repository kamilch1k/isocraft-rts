package dev.isorts.world;

import dev.isorts.IsoRts;
import net.minecraft.block.Block;
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

    /**
     * Proof that this world is ours to terraform, buried deep in the stone at the origin.
     * <p>
     * Detecting our own map by probing for land where an island should be was a guess, and on an
     * imported map it guessed wrong: a hand-built kingdom map happened to have ground at the probe
     * position, so the mod treated it as our island map and started dropping village houses on it.
     * A block nothing else places is a fact rather than an inference.
     */
    public static final BlockPos MARKER_POS = new BlockPos(0, -60, 0);
    public static final Block MARKER_BLOCK = Blocks.LODESTONE;

    /** True only for a world this mod raised the islands in. */
    public static boolean isOurs(ServerWorld world) {
        world.getChunk(0, 0);
        return world.getBlockState(MARKER_POS).isOf(MARKER_BLOCK);
    }

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
        world.setBlockState(MARKER_POS, MARKER_BLOCK.getDefaultState());
        IsoRts.LOG.info("raised {} islands around {}", centres.size(), origin.toShortString());
        return centres;
    }

    /**
     * One island: a squashed dome with an irregular coastline, sand at the waterline, grass above.
     * <p>
     * The height profile is {@code 1 - (d/r)^2}, which is flat across the middle and steepens at
     * the rim - buildable in the centre, and it still reads as an island from the shore. The
     * radius is modulated around the perimeter by two sine waves (and the surface by low bumps),
     * because a perfect circle of grass reads as a plate, not a place. Phases derive from the
     * island's position, so regeneration is deterministic.
     */
    private static void buildIsland(ServerWorld world, int cx, int cz, int radius, int peak, Random random) {
        int rise = peak - SEA_FLOOR;
        double p1 = (cx * 31 + cz * 17) * 0.05;
        double p2 = (cx * 13 - cz * 7) * 0.05;

        int reach = (int) Math.ceil(radius * 1.35);
        for (int dx = -reach; dx <= reach; dx++) {
            for (int dz = -reach; dz <= reach; dz++) {
                double theta = Math.atan2(dz, dx);
                double wobble = 1.0 + 0.22 * Math.sin(3.0 * theta + p1)
                        + 0.12 * Math.sin(7.0 * theta + p2);
                double r = radius * wobble;
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > r) {
                    continue;
                }
                double t = d / r;
                // Low rolling bumps, faded towards the shore so beaches stay clean.
                double bump = 1.4 * Math.sin(dx * 0.23 + p2) * Math.sin(dz * 0.19 + p1) * (1.0 - t);
                int top = SEA_FLOOR + (int) Math.round(rise * (1.0 - t * t) + bump);
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

        scatterTrees(world, cx, cz, radius, random);
        scatterFlora(world, cx, cz, radius, random);
    }

    /** Trees, kept clear of the centre so bases have room. */
    private static void scatterTrees(ServerWorld world, int cx, int cz, int radius, Random random) {
        int attempts = radius;
        for (int i = 0; i < attempts; i++) {
            int dx = random.nextInt(radius * 2) - radius;
            int dz = random.nextInt(radius * 2) - radius;
            double d = Math.sqrt(dx * dx + dz * dz);
            if (d < radius * 0.45 || d > radius * 0.95) {
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

    /** Grass and the odd flower on bare turf - cheap, and it stops the ground reading as a lawn. */
    private static void scatterFlora(ServerWorld world, int cx, int cz, int radius, Random random) {
        var flowers = new net.minecraft.block.Block[] {
                Blocks.POPPY, Blocks.DANDELION, Blocks.CORNFLOWER };
        int attempts = radius * 3;
        for (int i = 0; i < attempts; i++) {
            int x = cx + random.nextInt(radius * 2) - radius;
            int z = cz + random.nextInt(radius * 2) - radius;
            int y = Structures.groundY(world, x, z);
            BlockPos p = new BlockPos(x, y, z);
            if (!world.getBlockState(p).isAir()
                    || !world.getBlockState(p.down()).isOf(Blocks.GRASS_BLOCK)) {
                continue;
            }
            var block = random.nextInt(5) == 0
                    ? flowers[random.nextInt(flowers.length)]
                    : Blocks.SHORT_GRASS;
            world.setBlockState(p, block.getDefaultState());
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
