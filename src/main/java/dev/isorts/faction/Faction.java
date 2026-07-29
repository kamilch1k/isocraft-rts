package dev.isorts.faction;

import dev.isorts.IsoRts;
import dev.isorts.world.Structures;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.List;

/**
 * A self-playing faction: it grows a settlement and raises units without any input.
 * <p>
 * ponytail: no resources, no economy, no combat. Growth is on timers, and the unit cap rises with
 * the number of buildings so expansion is visible rather than simulated. Add an economy when
 * something has to compete for it - right now nothing does.
 */
public final class Faction {

    /** Ticks between construction of one new building. */
    private static final int BUILD_INTERVAL = 1200;      // 60s
    /** Ticks between unit spawn attempts. */
    private static final int SPAWN_INTERVAL = 400;       // 20s
    private static final int MAX_BUILDINGS = 12;
    private static final int UNITS_PER_BUILDING = 1;
    private static final int BASE_UNIT_CAP = 3;
    private static final double UNIT_SEARCH_RADIUS = 80.0;

    /** Vanilla village house templates, so buildings look like real village houses. */
    private static final String[] PLAINS_HOUSES = {
            "village/plains/houses/plains_small_house_1",
            "village/plains/houses/plains_small_house_2",
            "village/plains/houses/plains_small_house_3",
            "village/plains/houses/plains_small_house_4",
            "village/plains/houses/plains_medium_house_1",
            "village/plains/houses/plains_big_house_1",
            "village/plains/houses/plains_butcher_shop_1",
            "village/plains/houses/plains_library_1",
    };

    private static final String[] TAIGA_HOUSES = {
            "village/taiga/houses/taiga_small_house_1",
            "village/taiga/houses/taiga_small_house_2",
            "village/taiga/houses/taiga_medium_house_1",
            "village/taiga/houses/taiga_big_house_1",
            "village/taiga/houses/taiga_armorer_house_1",
            "village/taiga/houses/taiga_tool_smith_1",
    };

    private final String name;
    private final BlockPos home;
    private final EntityType<? extends MobEntity> unitType;
    private final String[] houseTemplates;

    private int buildings;
    private int buildTimer;
    private int spawnTimer;

    public Faction(String name, BlockPos home, EntityType<? extends MobEntity> unitType,
                   String[] houseTemplates) {
        this.name = name;
        this.home = home;
        this.unitType = unitType;
        this.houseTemplates = houseTemplates;
        // Stagger the two factions so they do not act on the same tick.
        this.buildTimer = BUILD_INTERVAL / 2;
        this.spawnTimer = SPAWN_INTERVAL / 2;
    }

    public String name() {
        return name;
    }

    public BlockPos home() {
        return home;
    }

    public int buildings() {
        return buildings;
    }

    public void tick(ServerWorld world) {
        if (--spawnTimer <= 0) {
            spawnTimer = SPAWN_INTERVAL;
            trySpawnUnit(world);
        }
        if (--buildTimer <= 0) {
            buildTimer = BUILD_INTERVAL;
            tryBuild(world);
        }
    }

    private void trySpawnUnit(ServerWorld world) {
        int cap = BASE_UNIT_CAP + buildings * UNITS_PER_BUILDING;
        Box area = new Box(home).expand(UNIT_SEARCH_RADIUS);
        List<? extends MobEntity> present = world.getEntitiesByType(unitType, area, e -> true);
        if (present.size() >= cap) {
            return;
        }
        MobEntity unit = unitType.create(world, SpawnReason.EVENT);
        if (unit == null) {
            return;
        }
        int y = Structures.groundY(world, home.getX(), home.getZ());
        unit.refreshPositionAndAngles(home.getX() + 0.5, y, home.getZ() + 0.5, 0.0f, 0.0f);
        unit.setPersistent();
        if (world.spawnEntity(unit)) {
            IsoRts.LOG.info("[{}] raised a unit ({}/{})", name, present.size() + 1, cap);
        }
    }

    /** Add one building, spiralling outward from home so the settlement visibly grows. */
    private void tryBuild(ServerWorld world) {
        if (buildings >= MAX_BUILDINGS) {
            return;
        }
        int ring = 1 + buildings / 4;
        int slot = buildings % 4;
        int spacing = 11;
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
        if (y <= world.getBottomY() + 2) {
            return;
        }

        String template = houseTemplates[buildings % houseTemplates.length];
        BlockRotation rotation = BlockRotation.values()[buildings % BlockRotation.values().length];
        boolean placed = Structures.placeVillageHouse(
                world, new BlockPos(x, y, z), template, rotation, world.getRandom());
        if (!placed) {
            IsoRts.LOG.warn("[{}] template missing: {}", name, template);
            return;
        }
        buildings++;
        IsoRts.LOG.info("[{}] built {} at {} {} {} ({} total)", name, template, x, y, z, buildings);
    }

    /** Restore counters when a world is re-entered, by counting what is already standing. */
    public void restoreFrom(int existingBuildings) {
        this.buildings = Math.min(existingBuildings, MAX_BUILDINGS);
    }

    public static Faction castle(BlockPos home) {
        return new Faction("Castle", home, EntityType.IRON_GOLEM, TAIGA_HOUSES);
    }

    public static Faction village(BlockPos home) {
        return new Faction("Village", home, EntityType.VILLAGER, PLAINS_HOUSES);
    }
}
