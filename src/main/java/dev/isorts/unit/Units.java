package dev.isorts.unit;

import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.entity.passive.WolfEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.random.Random;

/**
 * Which army a mob belongs to, and how one is turned into a soldier.
 * <p>
 * Side is decided by entity TYPE, not by name. It used to be the custom name, which was cheap but
 * spent the one field a player actually reads: every friendly was called "Kingdom" and every enemy
 * "Raiders". Type is just as free to query, just as reliable across a reload, and it leaves the
 * name for what names are for.
 */
public final class Units {

    public static final String KINGDOM = "Kingdom";
    public static final String RAIDERS = "Raiders";

    /** Types fighting for the player. */
    private static final EntityType<?>[] KINGDOM_TYPES = {
            IsoUnits.SOLDIER, EntityType.IRON_GOLEM, EntityType.WOLF,
    };

    /** Types fighting against the player. */
    private static final EntityType<?>[] RAIDER_TYPES = {
            EntityType.PILLAGER, EntityType.VINDICATOR, EntityType.EVOKER, EntityType.RAVAGER,
    };

    private static final String[] FRIENDLY_NAMES = {
            "Hans", "Otto", "Gustav", "Friedrich", "Wilhelm", "Konrad", "Dieter", "Anton",
            "Ernst", "Ludwig", "Bruno", "Kasimir", "Reinhold", "Sigmund", "Jorg", "Matthias",
    };

    private static final String[] RAIDER_NAMES = {
            "Grimjaw", "Skarn", "Vorn", "Blackrot", "Snaggle", "Rusk", "Malig", "Grish",
            "Kragg", "Drell", "Ozzur", "Vex", "Harrow", "Gnash", "Skulk", "Morg",
    };

    private Units() {
    }

    /** Turn a freshly created mob into a persistent soldier of one side, with its own name. */
    public static void enlist(MobEntity unit, String faction) {
        Random random = unit.getEntityWorld().getRandom();
        boolean friendly = KINGDOM.equals(faction);
        String[] pool = friendly ? FRIENDLY_NAMES : RAIDER_NAMES;

        unit.setCustomName(Text.literal(pool[random.nextInt(pool.length)])
                .formatted(friendly ? Formatting.AQUA : Formatting.RED));
        // ponytail: nameplates off. Four dozen floating labels in an isometric battle is a wall of
        // text; the name still shows when you look at a unit up close.
        unit.setCustomNameVisible(false);
        unit.setPersistent();
        unit.setCanPickUpLoot(false);

        if (unit instanceof IronGolemEntity golem) {
            // A golem that is not "player created" attacks players on sight - one killed the
            // player while AFK. This flag is vanilla's own opt-out.
            golem.setPlayerCreated(true);
        }
        if (unit instanceof WolfEntity wolf) {
            // Tamed: a wild wolf begs and flees instead of fighting, and is fair game for every
            // hostile on the map.
            wolf.setTamed(true, false);
            wolf.setHealth(wolf.getMaxHealth());
        }
    }

    /** The army this entity fights for, or null if it is not a unit. */
    public static String factionOf(Entity entity) {
        if (!(entity instanceof MobEntity)) {
            return null;
        }
        EntityType<?> type = entity.getType();
        for (EntityType<?> friendly : KINGDOM_TYPES) {
            if (type == friendly) {
                return KINGDOM;
            }
        }
        for (EntityType<?> hostile : RAIDER_TYPES) {
            if (type == hostile) {
                return RAIDERS;
            }
        }
        return null;
    }

    public static boolean isUnit(Entity entity) {
        return factionOf(entity) != null;
    }

    public static boolean areEnemies(Entity a, Entity b) {
        String fa = factionOf(a);
        String fb = factionOf(b);
        return fa != null && fb != null && !fa.equals(fb);
    }
}
