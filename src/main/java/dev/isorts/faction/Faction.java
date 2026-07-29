package dev.isorts.faction;

import dev.isorts.IsoRts;
import dev.isorts.unit.Units;
import dev.isorts.world.Structures;
import net.minecraft.block.BlockState;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.BlockRotation;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;

import java.util.List;

/**
 * A self-playing faction: it raises units without any input, and grows a settlement if it is
 * allowed to build.
 * <p>
 * ponytail: no resources, no economy. Growth is on timers and the unit cap rises with the number
 * of buildings, so expansion is visible rather than simulated. Add an economy when something has
 * to compete for it - right now nothing does.
 */
public final class Faction {

    /** Ticks between construction of one new building. */
    private static final int BUILD_INTERVAL = 1200;      // 60s
    /** Ticks between unit spawn attempts. */
    private static final int SPAWN_INTERVAL = 200;       // 10s - battles need bodies
    private static final int MAX_BUILDINGS = 12;
    private static final int UNITS_PER_BUILDING = 1;
    private static final int BASE_UNIT_CAP = 8;
    /** Count our own units anywhere in the theatre: they march off to fight and must still count. */
    private static final double UNIT_SEARCH_RADIUS = 400.0;

    /**
     * Building sites: eight compass directions per ring, ring two staggered between ring one's
     * spokes. The first ring must clear the castle wall plus half the widest template
     * (9 + 6 + a path), which is why the old spacing of 11 jammed houses into the curtain wall.
     */
    private static final int FIRST_RING = 18;
    private static final int RING_STEP = 14;
    private static final int SLOTS_PER_RING = 8;

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
    /** Where fresh units appear - outside the walls, not inside the keep. */
    private final BlockPos muster;
    private final EntityType<? extends MobEntity> unitType;
    private final String[] houseTemplates;
    /** False on a hand-built map: dropping village houses on someone's castle map is vandalism. */
    private boolean mayBuild = true;

    private int buildings;
    private int buildTimer;
    private int spawnTimer;

    public Faction(String name, BlockPos home, BlockPos muster,
                   EntityType<? extends MobEntity> unitType, String[] houseTemplates) {
        this.name = name;
        this.home = home;
        this.muster = muster;
        this.unitType = unitType;
        this.houseTemplates = houseTemplates;
        // Stagger the two factions so they do not act on the same tick.
        this.buildTimer = BUILD_INTERVAL / 2;
        this.spawnTimer = 40;                   // first wave promptly, so there is a battle to see
    }

    public String name() {
        return name;
    }

    public BlockPos home() {
        return home;
    }

    public BlockPos muster() {
        return muster;
    }

    public int buildings() {
        return buildings;
    }

    public Faction noBuilding() {
        mayBuild = false;
        return this;
    }

    /**
     * Factions only build now. Raising units moved to {@link dev.isorts.unit.Skirmish}, which
     * spawns both sides around the player so the fight is where the player is looking - camps at
     * fixed map coordinates put half the battle out of sight and out of the client's tracking range.
     */
    public void tick(ServerWorld world) {
        if (mayBuild && --buildTimer <= 0) {
            buildTimer = BUILD_INTERVAL;
            tryBuild(world);
        }
    }

    /** @return the unit, or null if the cap is reached or spawning failed */
    public MobEntity trySpawnUnit(ServerWorld world) {
        int cap = BASE_UNIT_CAP + buildings * UNITS_PER_BUILDING;
        Box area = new Box(home).expand(UNIT_SEARCH_RADIUS);
        List<? extends MobEntity> present =
                world.getEntitiesByType(unitType, area, e -> name.equals(dev.isorts.unit.Units.factionOf(e)));
        if (present.size() >= cap) {
            return null;
        }
        MobEntity unit = unitType.create(world, SpawnReason.EVENT);
        if (unit == null) {
            return null;
        }
        Units.enlist(unit, name);
        // Scatter around the muster point so units don't spawn inside each other.
        int x = muster.getX() + world.getRandom().nextInt(7) - 3;
        int z = muster.getZ() + world.getRandom().nextInt(7) - 3;
        int y = Structures.terrainY(world, x, z);
        unit.refreshPositionAndAngles(x + 0.5, y, z + 0.5, 0.0f, 0.0f);
        if (!world.spawnEntity(unit)) {
            return null;
        }
        IsoRts.LOG.info("[{}] raised a unit ({}/{}) at {} {} {}", name, present.size() + 1, cap, x, y, z);
        return unit;
    }

    /** Centre of building slot n, ring by ring outward from home. */
    private BlockPos slotPos(int index) {
        int ring = index / SLOTS_PER_RING;
        int slot = index % SLOTS_PER_RING;
        // Offset ring two's spokes by half a step so it does not shadow ring one.
        double angle = slot * (Math.PI * 2 / SLOTS_PER_RING) + ring * (Math.PI / SLOTS_PER_RING);
        int dist = FIRST_RING + ring * RING_STEP;
        return home.add((int) Math.round(Math.sin(angle) * dist), 0,
                (int) Math.round(Math.cos(angle) * dist));
    }

    /** True if something man-made already stands at ground level here (trees don't count). */
    private static boolean occupied(ServerWorld world, int x, int y, int z) {
        BlockState at = world.getBlockState(new BlockPos(x, y, z));
        return !at.isAir() && !at.isIn(BlockTags.LOGS) && !at.isIn(BlockTags.LEAVES)
                && !at.isIn(BlockTags.FLOWERS) && !at.isIn(BlockTags.REPLACEABLE);
    }

    /** Add one building in the next free slot, spiralling outward so the settlement grows. */
    private void tryBuild(ServerWorld world) {
        if (buildings >= MAX_BUILDINGS) {
            return;
        }
        BlockPos site = slotPos(buildings);
        int x = site.getX();
        int z = site.getZ();
        int y = Structures.terrainY(world, x, z);

        // Underwater or occupied: burn the slot and move on rather than stacking or stalling.
        if (y <= world.getSeaLevel() || occupied(world, x, y, z)) {
            buildings++;
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

    /** Build n houses immediately - the starting settlement on a fresh map. */
    public void buildNow(ServerWorld world, int n) {
        for (int i = 0; i < n; i++) {
            tryBuild(world);
        }
    }

    /** Re-enter an existing world: count what is standing in our slots instead of persisting it. */
    public void restore(ServerWorld world) {
        int found = 0;
        for (int i = 0; i < MAX_BUILDINGS; i++) {
            BlockPos s = slotPos(i);
            int y = Structures.terrainY(world, s.getX(), s.getZ());
            if (occupied(world, s.getX(), y, s.getZ())) {
                found++;
            }
        }
        buildings = Math.min(found, MAX_BUILDINGS);
    }

    /** The defenders: iron golems, which never target players once flagged player-created. */
    public static Faction kingdom(BlockPos home) {
        return new Faction(Units.KINGDOM, home, home.add(0, 0, 13),
                EntityType.IRON_GOLEM, TAIGA_HOUSES);
    }

    /**
     * The attackers: vindicators. Armed, human-shaped, and they already know how to fight - the
     * only reason they are safe to use is that {@code UnitAI} strips any player they target.
     */
    public static Faction raiders(BlockPos home) {
        return new Faction(Units.RAIDERS, home, home.add(0, 0, -13),
                EntityType.VINDICATOR, PLAINS_HOUSES);
    }
}
