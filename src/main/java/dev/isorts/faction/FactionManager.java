package dev.isorts.faction;

import dev.isorts.IsoRts;
import dev.isorts.world.Structures;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

/**
 * Lays out the scenario once per world, then ticks the factions so the game plays itself.
 * <p>
 * ponytail: state lives in memory and is rebuilt from what is standing in the world on reload -
 * a lantern at the castle keep is the "already built" marker. That beats defining a persistent
 * save format for two integers.
 */
public final class FactionManager {

    /** Distance either side of the player's spawn to place the two settlements. */
    private static final int SETTLEMENT_OFFSET = 60;
    private static final int CASTLE_HALF_WIDTH = 9;
    /** How far to hunt around the desired spot for level, low-lying ground. */
    private static final int SITE_SEARCH_RADIUS = 48;

    private final List<Faction> factions = new ArrayList<>();
    private boolean built;

    public boolean isBuilt() {
        return built;
    }

    public List<Faction> factions() {
        return factions;
    }

    /** Build the scenario if it is not already there; otherwise just re-attach to it. */
    public void ensureScenario(ServerWorld world, BlockPos near) {
        if (built) {
            return;
        }

        BlockPos castleSite = Structures.findFlatSite(world,
                near.getX() - SETTLEMENT_OFFSET, near.getZ() - SETTLEMENT_OFFSET, SITE_SEARCH_RADIUS);
        BlockPos villageSite = Structures.findFlatSite(world,
                near.getX() + SETTLEMENT_OFFSET, near.getZ() + SETTLEMENT_OFFSET, SITE_SEARCH_RADIUS);

        boolean alreadyBuilt = world.getBlockState(castleSite.up(8)).isOf(Blocks.LANTERN);
        if (!alreadyBuilt) {
            IsoRts.LOG.info("building scenario: castle at {}, village at {}",
                    castleSite.toShortString(), villageSite.toShortString());
            Structures.buildCastle(world, castleSite, CASTLE_HALF_WIDTH);
            Structures.buildWell(world, villageSite);
            // Seed the village with real vanilla houses so it reads as a village immediately.
            seedVillageHouse(world, villageSite.add(14, 0, -4), "village/plains/houses/plains_medium_house_1");
            seedVillageHouse(world, villageSite.add(-14, 0, 5), "village/plains/houses/plains_small_house_1");
            seedVillageHouse(world, villageSite.add(3, 0, 15), "village/plains/houses/plains_butcher_shop_1");
        } else {
            IsoRts.LOG.info("scenario already present, re-attaching");
        }

        Faction castle = Faction.castle(castleSite);
        Faction village = Faction.village(villageSite);
        if (alreadyBuilt) {
            castle.restoreFrom(countHouses(world, castleSite));
            village.restoreFrom(countHouses(world, villageSite));
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

    private static void seedVillageHouse(ServerWorld world, BlockPos at, String template) {
        BlockPos ground = surfacePos(world, at.getX(), at.getZ());
        Structures.placeVillageHouse(world, ground, template, BlockRotation.NONE, world.getRandom());
    }

    /** Count houses by probing the ring slots a faction builds into. */
    private static int countHouses(ServerWorld world, BlockPos home) {
        int found = 0;
        int spacing = 11;
        for (int i = 0; i < 12; i++) {
            int ring = 1 + i / 4;
            int slot = i % 4;
            int dx = switch (slot) {
                case 0 -> ring * spacing;
                case 1 -> -ring * spacing;
                default -> 0;
            };
            int dz = switch (slot) {
                case 2 -> ring * spacing;
                case 3 -> -ring * spacing;
                default -> 0;
            };
            int x = home.getX() + dx;
            int z = home.getZ() + dz;
            int y = Structures.groundY(world, x, z);
            if (!world.getBlockState(new BlockPos(x, y, z)).isAir()) {
                found++;
            }
        }
        return found;
    }

    private static BlockPos surfacePos(ServerWorld world, int x, int z) {
        return new BlockPos(x, Structures.groundY(world, x, z), z);
    }
}
