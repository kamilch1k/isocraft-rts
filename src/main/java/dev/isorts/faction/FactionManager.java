package dev.isorts.faction;

import dev.isorts.IsoRts;
import dev.isorts.world.IslandMap;
import dev.isorts.world.Structures;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.world.Difficulty;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.WorldProperties;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Lays out the scenario once per world, then ticks the factions so the game plays itself.
 * <p>
 * Two kinds of world are supported and they are told apart automatically:
 * <ul>
 *   <li><b>Our ocean preset</b> - islands are raised, a castle and a village are built.</li>
 *   <li><b>An imported map</b> - nothing is ever built or terraformed. A downloaded map is
 *       somebody's hand-built work; carving islands into it or dropping village houses on it would
 *       destroy the very thing it was downloaded for. The two armies simply muster on it.</li>
 * </ul>
 * ponytail: state lives in memory and is rebuilt from the world on reload. That beats defining a
 * persistent save format for two integers.
 */
public final class FactionManager {

    private static final int CASTLE_HALF_WIDTH = 9;
    /**
     * How far either side of spawn the two armies camp on an imported map.
     * <p>
     * Kept short deliberately: at 70 the camps were 140 blocks apart, which put one army outside
     * the client's entity tracking range - so half the battle was invisible and unselectable from
     * where the player stands. At 40 the whole engagement fits on one screen.
     */
    private static final int CAMP_OFFSET = 40;
    private static final int CAMP_SEARCH = 24;

    private final List<Faction> factions = new ArrayList<>();
    private boolean built;

    public boolean isBuilt() {
        return built;
    }

    public List<Faction> factions() {
        return factions;
    }

    public void ensureScenario(ServerWorld world, ServerPlayerEntity player) {
        if (built) {
            return;
        }
        ensureFightingIsPossible(world);
        BlockPos origin = new BlockPos(0, IslandMap.SEA_LEVEL, 0);

        if (IslandMap.isOurs(world)) {
            IsoRts.LOG.info("island map already present, re-attaching");
            attachToIslands(world, IslandMap.centres(origin), false, player);
        } else if (isOceanPreset(world)) {
            IsoRts.LOG.info("ocean preset detected, raising island map...");
            attachToIslands(world, IslandMap.build(world, origin), true, player);
        } else {
            IsoRts.LOG.info("imported map detected - no terraforming, mustering armies only");
            musterOnImportedMap(world, player);
        }
        built = true;
    }

    /**
     * Peaceful difficulty deletes every hostile mob the instant it spawns.
     * <p>
     * The Hohenzollern map ships on Peaceful, which made the raiders unexplainably vanish: a
     * vindicator was raised every ten seconds and was gone before the next tick, so their army
     * count sat at 1 forever while the golems piled up. There is no way to opt an entity out of
     * that without a mixin, so the world's difficulty is raised instead - Normal, not Hard, because
     * only Hard lets vindicators break doors, and this map's doors are not ours to break.
     */
    private static void ensureFightingIsPossible(ServerWorld world) {
        if (world.getDifficulty() != Difficulty.PEACEFUL || world.getServer() == null) {
            return;
        }
        world.getServer().setDifficulty(Difficulty.NORMAL, true);
        IsoRts.LOG.info("difficulty was PEACEFUL (which deletes hostile units) - raised to NORMAL");
    }

    /** The untouched signature of worldgen.ps1 -Ocean: stone sea floor, water above it. */
    private static boolean isOceanPreset(ServerWorld world) {
        return world.getBlockState(new BlockPos(0, IslandMap.SEA_LEVEL - 1, 0)).isOf(Blocks.WATER)
                && world.getBlockState(new BlockPos(0, 50, 0)).isOf(Blocks.STONE);
    }

    private void attachToIslands(ServerWorld world, List<BlockPos> islands, boolean fresh,
                                 ServerPlayerEntity player) {
        BlockPos castleSite = surfacePos(world, islands.get(0).getX(), islands.get(0).getZ());
        BlockPos villageSite = surfacePos(world, islands.get(1).getX(), islands.get(1).getZ());

        Faction kingdom = Faction.kingdom(castleSite);
        Faction raiders = Faction.raiders(villageSite);

        if (fresh) {
            IsoRts.LOG.info("building bases: castle at {}, village at {}",
                    castleSite.toShortString(), villageSite.toShortString());
            Structures.buildCastle(world, castleSite, CASTLE_HALF_WIDTH);
            Structures.buildWell(world, villageSite);
            kingdom.buildNow(world, 2);
            raiders.buildNow(world, 3);

            BlockPos centre = surfacePos(world, islands.get(2).getX(), islands.get(2).getZ());
            world.setSpawnPoint(WorldProperties.SpawnPoint.create(
                    World.OVERWORLD, centre.up(), 0.0f, 0.0f));
            movePlayer(world, player, centre);
            IsoRts.LOG.info("world spawn set to centre island {}", centre.toShortString());
        } else {
            kingdom.restore(world);
            raiders.restore(world);
        }

        register(kingdom, raiders);
    }

    /**
     * On a hand-built map, find two flat clearings either side of the map's own spawn and camp the
     * armies there. Nothing is placed, nothing is levelled.
     */
    private void musterOnImportedMap(ServerWorld world, ServerPlayerEntity player) {
        BlockPos spawn = player != null ? player.getBlockPos() : world.getSpawnPoint().getPos();

        BlockPos west = Structures.findFlatSite(world, spawn.getX() - CAMP_OFFSET, spawn.getZ(), CAMP_SEARCH);
        BlockPos east = Structures.findFlatSite(world, spawn.getX() + CAMP_OFFSET, spawn.getZ(), CAMP_SEARCH);
        IsoRts.LOG.info("camps: kingdom at {}, raiders at {} (spawn {})",
                west.toShortString(), east.toShortString(), spawn.toShortString());

        register(Faction.kingdom(west).noBuilding(), Faction.raiders(east).noBuilding());
    }

    private void register(Faction a, Faction b) {
        factions.clear();
        factions.add(a);
        factions.add(b);
    }

    private static void movePlayer(ServerWorld world, ServerPlayerEntity player, BlockPos to) {
        if (player != null) {
            player.teleport(world, to.getX() + 0.5, to.getY(), to.getZ() + 0.5,
                    Set.of(), 0.0f, 0.0f, false);
        }
    }

    public void tick(ServerWorld world) {
        if (!built) {
            return;
        }
        for (Faction faction : factions) {
            faction.tick(world);
        }
    }

    public void reset() {
        factions.clear();
        built = false;
    }

    private static BlockPos surfacePos(ServerWorld world, int x, int z) {
        return new BlockPos(x, Structures.terrainY(world, x, z), z);
    }
}
