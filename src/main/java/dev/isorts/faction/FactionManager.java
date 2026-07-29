package dev.isorts.faction;

import dev.isorts.IsoRts;
import dev.isorts.world.IslandMap;
import dev.isorts.world.Structures;
import net.minecraft.block.Blocks;
import net.minecraft.server.network.ServerPlayerEntity;
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
 * ponytail: state lives in memory and is rebuilt from what is standing in the world on reload -
 * counting the buildings in the factions' own slots. That beats defining a persistent save
 * format for two integers.
 */
public final class FactionManager {

    private static final int CASTLE_HALF_WIDTH = 9;

    private final List<Faction> factions = new ArrayList<>();
    private boolean built;

    public boolean isBuilt() {
        return built;
    }

    public List<Faction> factions() {
        return factions;
    }

    /**
     * Build the scenario if it is not already there; otherwise just re-attach to it.
     * <p>
     * The layout is anchored at world origin rather than wherever the player happens to spawn,
     * so the map is deterministic and re-entering a world always finds the same islands.
     * <p>
     * Runs BEFORE anything spawns units near the player: the islands are raised on top of
     * wherever the flat preset put the player, so the player (and anything already seeded around
     * them) would end up entombed. The player is teleported onto the new centre island instead.
     */
    public void ensureScenario(ServerWorld world, ServerPlayerEntity player) {
        if (built) {
            return;
        }

        BlockPos origin = new BlockPos(0, IslandMap.SEA_LEVEL, 0);

        // Land at the first island's centre means the map has already been raised.
        BlockPos probe = new BlockPos(-110, IslandMap.SEA_LEVEL + 3, -80);
        boolean alreadyBuilt = !world.getBlockState(probe).isAir()
                && !world.getBlockState(probe).isOf(Blocks.WATER);

        List<BlockPos> islands;
        if (!alreadyBuilt) {
            IsoRts.LOG.info("raising island map...");
            islands = IslandMap.build(world, origin);
        } else {
            IsoRts.LOG.info("island map already present, re-attaching");
            islands = IslandMap.centres(origin);
        }

        BlockPos castleSite = surfacePos(world, islands.get(0).getX(), islands.get(0).getZ());
        BlockPos villageSite = surfacePos(world, islands.get(1).getX(), islands.get(1).getZ());

        Faction castle = Faction.castle(castleSite);
        Faction village = Faction.village(villageSite);

        if (!alreadyBuilt) {
            IsoRts.LOG.info("building bases: castle at {}, village at {}",
                    castleSite.toShortString(), villageSite.toShortString());
            Structures.buildCastle(world, castleSite, CASTLE_HALF_WIDTH);
            Structures.buildWell(world, villageSite);
            // A starting settlement, through the same slot logic the factions grow with - so
            // nothing ever gets built where a seed house already stands.
            castle.buildNow(world, 2);
            village.buildNow(world, 3);

            // Spawn on the contested centre island, and move the player there NOW - the island
            // was just raised over wherever the flat preset dropped them.
            BlockPos centre = surfacePos(world, islands.get(2).getX(), islands.get(2).getZ());
            // 1.21.11: spawn is a WorldProperties.SpawnPoint record, not a BlockPos + angle.
            world.setSpawnPoint(WorldProperties.SpawnPoint.create(
                    World.OVERWORLD, centre.up(), 0.0f, 0.0f));
            if (player != null) {
                player.teleport(world, centre.getX() + 0.5, centre.getY(), centre.getZ() + 0.5,
                        Set.of(), 0.0f, 0.0f, false);
            }
            IsoRts.LOG.info("world spawn set to centre island {}", centre.toShortString());
        } else {
            castle.restore(world);
            village.restore(world);
        }

        factions.clear();
        factions.add(castle);
        factions.add(village);
        built = true;
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
