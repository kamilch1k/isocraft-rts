package dev.isorts.world;

import net.minecraft.block.Block;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.Heightmap;

/**
 * Procedural structures, built block by block.
 * <p>
 * ponytail: raw block placement, no structure NBT files, no jigsaw pieces. These are simple
 * geometric shapes; a structure pipeline would be far more machinery than a castle made of boxes
 * needs. Swap to real structures when buildings stop being describable as loops.
 */
public final class Structures {

    private Structures() {
    }

    /**
     * Ground height at x/z, so buildings sit on the surface rather than floating or buried.
     * <p>
     * Force-loads the chunk first: querying a heightmap in an ungenerated chunk returns the world
     * minimum, which put the first castle at y=-64 - in the bedrock, 130 blocks below spawn.
     */
    public static int groundY(ServerWorld world, int x, int z) {
        world.getChunk(x >> 4, z >> 4);
        return world.getTopY(Heightmap.Type.WORLD_SURFACE, x, z);
    }

    /** Flatten an area to one height and clear what is above it, so a build has somewhere to sit. */
    public static void levelArea(ServerWorld world, BlockPos centre, int radius, Block ground) {
        int y = centre.getY();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                BlockPos floor = new BlockPos(centre.getX() + dx, y - 1, centre.getZ() + dz);
                world.setBlockState(floor, ground.getDefaultState());
                for (int dy = 0; dy < 12; dy++) {
                    world.setBlockState(floor.up(dy + 1), Blocks.AIR.getDefaultState());
                }
            }
        }
    }

    /**
     * A castle: square curtain wall with crenellations, a taller tower at each corner, and a gate
     * in the south wall.
     */
    public static void buildCastle(ServerWorld world, BlockPos centre, int halfWidth) {
        Block wall = Blocks.STONE_BRICKS;
        Block trim = Blocks.CHISELED_STONE_BRICKS;
        int wallHeight = 6;
        int towerHeight = 10;
        int y = centre.getY();

        levelArea(world, centre, halfWidth + 2, Blocks.GRASS_BLOCK);

        for (int dx = -halfWidth; dx <= halfWidth; dx++) {
            for (int dz = -halfWidth; dz <= halfWidth; dz++) {
                boolean edge = Math.abs(dx) == halfWidth || Math.abs(dz) == halfWidth;
                if (!edge) {
                    // courtyard
                    world.setBlockState(new BlockPos(centre.getX() + dx, y - 1, centre.getZ() + dz),
                            Blocks.STONE_BRICKS.getDefaultState());
                    continue;
                }
                boolean gate = dz == halfWidth && Math.abs(dx) <= 1;
                int height = gate ? 0 : wallHeight;
                for (int dy = 0; dy < height; dy++) {
                    // crenellate the top course
                    if (dy == wallHeight - 1 && ((dx + dz) & 1) == 0) {
                        continue;
                    }
                    world.setBlockState(new BlockPos(centre.getX() + dx, y + dy, centre.getZ() + dz),
                            wall.getDefaultState());
                }
            }
        }

        int[][] corners = {{-halfWidth, -halfWidth}, {-halfWidth, halfWidth},
                           {halfWidth, -halfWidth}, {halfWidth, halfWidth}};
        for (int[] c : corners) {
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dy = 0; dy < towerHeight; dy++) {
                        Block b = (dy == towerHeight - 1) ? trim : wall;
                        if (dy == towerHeight - 1 && ((dx + dz) & 1) != 0) {
                            continue;
                        }
                        world.setBlockState(new BlockPos(
                                centre.getX() + c[0] + dx, y + dy, centre.getZ() + c[1] + dz),
                                b.getDefaultState());
                    }
                }
            }
        }

        // keep: a small tower in the courtyard
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                if (!edge) {
                    continue;
                }
                for (int dy = 0; dy < 8; dy++) {
                    world.setBlockState(new BlockPos(centre.getX() + dx, y + dy, centre.getZ() + dz),
                            wall.getDefaultState());
                }
            }
        }
        world.setBlockState(centre.up(8), Blocks.LANTERN.getDefaultState());
    }

    /** A small house: walls, a door gap, a pitched roof. */
    public static void buildHouse(ServerWorld world, BlockPos corner, int size, Block wall, Block roof) {
        int y = corner.getY();
        levelArea(world, corner.add(size / 2, 0, size / 2), size / 2 + 2, Blocks.GRASS_BLOCK);

        for (int dx = 0; dx <= size; dx++) {
            for (int dz = 0; dz <= size; dz++) {
                boolean edge = dx == 0 || dz == 0 || dx == size || dz == size;
                for (int dy = 0; dy < 4; dy++) {
                    BlockPos p = new BlockPos(corner.getX() + dx, y + dy, corner.getZ() + dz);
                    if (!edge) {
                        world.setBlockState(p, Blocks.AIR.getDefaultState());
                        continue;
                    }
                    boolean door = dz == 0 && dx == size / 2 && dy < 2;
                    boolean window = dy == 2 && (dx == size / 2 || dz == size / 2);
                    if (door) {
                        world.setBlockState(p, Blocks.AIR.getDefaultState());
                    } else if (window) {
                        world.setBlockState(p, Blocks.GLASS_PANE.getDefaultState());
                    } else {
                        world.setBlockState(p, wall.getDefaultState());
                    }
                }
            }
        }
        for (int layer = 0; layer <= size / 2; layer++) {
            for (int dx = layer; dx <= size - layer; dx++) {
                for (int dz = layer; dz <= size - layer; dz++) {
                    boolean edge = dx == layer || dz == layer || dx == size - layer || dz == size - layer;
                    if (edge) {
                        world.setBlockState(new BlockPos(
                                corner.getX() + dx, y + 4 + layer, corner.getZ() + dz),
                                roof.getDefaultState());
                    }
                }
            }
        }
    }

    /** A lit well, as a village centrepiece. */
    public static void buildWell(ServerWorld world, BlockPos centre) {
        int y = centre.getY();
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockPos p = new BlockPos(centre.getX() + dx, y, centre.getZ() + dz);
                boolean edge = dx != 0 || dz != 0;
                world.setBlockState(p, edge
                        ? Blocks.COBBLESTONE.getDefaultState()
                        : Blocks.WATER.getDefaultState());
            }
        }
        world.setBlockState(centre.up(1), Blocks.LANTERN.getDefaultState());
    }
}
