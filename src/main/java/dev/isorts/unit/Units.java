package dev.isorts.unit;

import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Which army a mob belongs to, and how one is turned into a soldier.
 * <p>
 * ponytail: a unit's side is its custom name. That gives identity that survives a world reload
 * for free, is visible on screen without writing a HUD, and costs nothing to query - versus a
 * persistent component or a scoreboard team, both of which would need registering, saving and
 * migrating for what is one string per mob.
 */
public final class Units {

    public static final String KINGDOM = "Kingdom";
    public static final String RAIDERS = "Raiders";

    private Units() {
    }

    /** Turn a freshly created mob into a persistent soldier of one side. */
    public static void enlist(MobEntity unit, String faction) {
        unit.setCustomName(Text.literal(faction)
                .formatted(KINGDOM.equals(faction) ? Formatting.AQUA : Formatting.RED));
        unit.setCustomNameVisible(true);
        unit.setPersistent();
        unit.setCanPickUpLoot(false);
        if (unit instanceof IronGolemEntity golem) {
            // A golem that is not "player created" attacks players on sight - one killed the
            // player while AFK. This flag is vanilla's own opt-out.
            golem.setPlayerCreated(true);
        }
    }

    /** The army this entity fights for, or null if it is not a unit. */
    public static String factionOf(Entity entity) {
        if (!(entity instanceof MobEntity) || entity.getCustomName() == null) {
            return null;
        }
        String name = entity.getCustomName().getString();
        return KINGDOM.equals(name) || RAIDERS.equals(name) ? name : null;
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
