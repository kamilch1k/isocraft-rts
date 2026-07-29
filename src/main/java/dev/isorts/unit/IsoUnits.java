package dev.isorts.unit;

import dev.isorts.IsoRts;
import net.fabricmc.fabric.api.object.builder.v1.entity.FabricDefaultAttributeRegistry;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnGroup;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;

/** Entity type registration. Must run on both sides, hence the common initializer. */
public final class IsoUnits {

    public static final Identifier SOLDIER_ID = Identifier.of(IsoRts.MOD_ID, "soldier");

    public static final EntityType<SoldierEntity> SOLDIER = Registry.register(
            Registries.ENTITY_TYPE, SOLDIER_ID,
            EntityType.Builder.create(SoldierEntity::new, SpawnGroup.CREATURE)
                    .dimensions(0.6f, 1.8f)
                    .eyeHeight(1.62f)
                    // Tracking range in chunks: the whole skirmish must stay visible from where the
                    // player stands, or half the battle silently stops existing on the client.
                    .maxTrackingRange(12)
                    .build(RegistryKey.of(RegistryKeys.ENTITY_TYPE, SOLDIER_ID)));

    private IsoUnits() {
    }

    public static void register() {
        FabricDefaultAttributeRegistry.register(SOLDIER, SoldierEntity.createSoldierAttributes());
        IsoRts.LOG.info("registered unit types");
    }
}
