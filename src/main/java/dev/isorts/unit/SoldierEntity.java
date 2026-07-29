package dev.isorts.unit;

import net.minecraft.entity.EntityType;
import net.minecraft.entity.ai.goal.LookAroundGoal;
import net.minecraft.entity.ai.goal.MeleeAttackGoal;
import net.minecraft.entity.ai.goal.RevengeGoal;
import net.minecraft.entity.ai.goal.WanderAroundFarGoal;
import net.minecraft.entity.attribute.DefaultAttributeContainer;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.mob.PathAwareEntity;
import net.minecraft.world.World;

/**
 * A human soldier: the player's own troops, wearing one of Minecraft's stock player skins.
 * <p>
 * ponytail: a plain {@link PathAwareEntity} with three vanilla goals. It is NOT a hostile mob
 * subclass, which matters twice over - hostiles are deleted outright on Peaceful difficulty, and
 * they come with target goals that hunt players.
 * <p>
 * The skin is derived from the entity's UUID rather than stored in tracked data. The UUID is
 * already synchronised to the client and already persisted, so a whole data-tracker field, its
 * registration, and NBT read/write for it all disappear - and every soldier still keeps its own
 * face across saves.
 */
public class SoldierEntity extends PathAwareEntity {

    /** Minecraft's own default player skins, minus Steve. */
    public static final String[] SKINS = {
            "ari", "efe", "kai", "makena", "noor", "sunny", "zuri", "alex",
    };

    public SoldierEntity(EntityType<? extends PathAwareEntity> type, World world) {
        super(type, world);
    }

    public static DefaultAttributeContainer.Builder createSoldierAttributes() {
        return MobEntity.createMobAttributes()
                .add(EntityAttributes.MAX_HEALTH, 40.0)
                .add(EntityAttributes.MOVEMENT_SPEED, 0.32)
                .add(EntityAttributes.ATTACK_DAMAGE, 6.0)
                .add(EntityAttributes.ARMOR, 4.0)
                .add(EntityAttributes.FOLLOW_RANGE, 40.0);
    }

    @Override
    protected void initGoals() {
        goalSelector.add(1, new MeleeAttackGoal(this, 1.15, true));
        goalSelector.add(6, new WanderAroundFarGoal(this, 0.8));
        goalSelector.add(8, new LookAroundGoal(this));
        // Hit back at whatever hits us; the skirmish director assigns the rest of the targets.
        targetSelector.add(1, new RevengeGoal(this));
    }

    /** Which of {@link #SKINS} this soldier wears - stable for the life of the entity. */
    public int skinIndex() {
        return Math.floorMod(getUuid().hashCode(), SKINS.length);
    }
}
