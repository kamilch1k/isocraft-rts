package dev.isorts;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.isorts.faction.Faction;
import dev.isorts.faction.FactionManager;
import dev.isorts.unit.IsoUnits;
import dev.isorts.unit.Skirmish;
import dev.isorts.unit.UnitAI;
import dev.isorts.unit.Units;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;

import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class IsoRts implements ModInitializer {

    public static final String MOD_ID = "isorts";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /** The self-playing simulation: two armies that raise units and fight. */
    public static final FactionManager FACTIONS = new FactionManager();

    /** How far a move order may be from the ordering player - stops a spoofed packet from anywhere. */
    private static final double MAX_ORDER_RANGE = 256.0;
    private static final double MOVE_SPEED = 1.0;

    @Override
    public void onInitialize() {
        IsoUnits.register();
        PayloadTypeRegistry.playC2S().register(MoveOrderPayload.ID, MoveOrderPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(AttackOrderPayload.ID, AttackOrderPayload.CODEC);
        PayloadTypeRegistry.playC2S().register(TeleportPayload.ID, TeleportPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(MoveOrderPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> handleMoveOrder(player, payload));
        });
        ServerPlayNetworking.registerGlobalReceiver(AttackOrderPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> handleAttackOrder(player, payload));
        });
        ServerPlayNetworking.registerGlobalReceiver(TeleportPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> {
                BlockPos to = payload.target();
                ServerWorld world = player.getEntityWorld();
                int y = Math.max(to.getY(), world.getTopY(
                        Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, to.getX(), to.getZ()));
                player.teleport(world, to.getX() + 0.5, y, to.getZ() + 0.5,
                        Set.of(), player.getYaw(), player.getPitch(), false);
                LOG.info("[ctl] teleported player to {} {} {}", to.getX(), y, to.getZ());
            });
        });

        // Order matters: the scenario decides whether this world may be terraformed at all, and on
        // our own preset it teleports the player onto the island it just raised.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> {
                    ServerPlayerEntity player = handler.getPlayer();
                    if (player != null) {
                        FACTIONS.ensureScenario(player.getEntityWorld(), player);
                        giveStartingGear(player);
                    }
                }));

        // The simulation runs whether or not anyone is looking at it.
        ServerTickEvents.END_WORLD_TICK.register(world -> {
            if (FACTIONS.isBuilt() && world.getRegistryKey() == World.OVERWORLD) {
                FACTIONS.tick(world);
                Skirmish.tick(world);
                UnitAI.tick(world);
            }
        });

        ServerLifecycleEvents.SERVER_STOPPED.register(server -> {
            FACTIONS.reset();
            Skirmish.reset();
            UnitAI.reset();
        });

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("rts")
                        // ponytail: no permission gate. 1.21.11 replaced permission levels with a
                        // PermissionPredicate system, and this instance is offline-auth singleplayer
                        // so no second player can ever connect. Gate it if this ever goes multiplayer.
                        .then(CommandManager.literal("wave")
                                .executes(ctx -> wave(ctx.getSource(), 4))
                                .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 40))
                                        .executes(ctx -> wave(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count")))))
                        .then(CommandManager.literal("status")
                                .executes(ctx -> status(ctx.getSource())))
                        .then(CommandManager.literal("clear")
                                .executes(ctx -> clearUnits(ctx.getSource())))));

        LOG.info("Isocraft RTS ready");
    }

    /** True if this entity can be selected and ordered around. */
    public static boolean isUnit(Entity entity) {
        return Units.isUnit(entity);
    }

    /** Issue a move order. Returns false if no path could be found. */
    public static boolean orderMoveTo(MobEntity unit, BlockPos target) {
        UnitAI.markOrdered(unit);
        unit.setTarget(null);
        return unit.getNavigation().startMovingTo(
                target.getX() + 0.5, target.getY(), target.getZ() + 0.5, MOVE_SPEED);
    }

    /**
     * Kit the player out on first join.
     * <p>
     * ponytail: idempotent by checking for the sword rather than storing a "kitted" flag - one
     * inventory scan beats persistent state we would have to migrate.
     */
    private static void giveStartingGear(ServerPlayerEntity player) {
        // Keyed on the armour, not the sword: the kit changed after players already had the old
        // one, and a guard on the sword meant the new armour was never handed out.
        if (player.getEquippedStack(EquipmentSlot.CHEST).isOf(Items.CHAINMAIL_CHESTPLATE)) {
            return;
        }
        // Placed in named slots rather than inserted: on a map that hands the player a full
        // creative inventory, "insert somewhere" buries the sword in the backpack, and auto-attack
        // then swings a fistful of building blocks.
        player.getInventory().setStack(0, new ItemStack(Items.DIAMOND_SWORD));
        player.getInventory().setStack(1, new ItemStack(Items.BOW));
        player.getInventory().setStack(2, new ItemStack(Items.ARROW, 64));
        player.getInventory().setStack(3, new ItemStack(Items.COOKED_BEEF, 32));
        player.getInventory().setStack(4, new ItemStack(Items.GOLDEN_APPLE, 8));
        player.getInventory().setSelectedSlot(0);

        player.equipStack(EquipmentSlot.OFFHAND, new ItemStack(Items.SHIELD));
        player.equipStack(EquipmentSlot.HEAD, new ItemStack(Items.IRON_HELMET));
        player.equipStack(EquipmentSlot.CHEST, new ItemStack(Items.CHAINMAIL_CHESTPLATE));
        player.equipStack(EquipmentSlot.LEGS, new ItemStack(Items.CHAINMAIL_LEGGINGS));
        player.equipStack(EquipmentSlot.FEET, new ItemStack(Items.CHAINMAIL_BOOTS));

        LOG.info("gave battle gear to {} (sword in hand, shield in offhand)",
                player.getName().getString());
    }

    /** Validate on the server - never trust a client-supplied target. */
    private static void handleMoveOrder(ServerPlayerEntity player, MoveOrderPayload payload) {
        MobEntity unit = resolveUnit(player, payload.entityId());
        if (unit == null) {
            return;
        }
        BlockPos target = payload.target();
        if (!player.getEntityWorld().isInBuildLimit(target)
                || player.squaredDistanceTo(Vec3d.ofCenter(target)) > MAX_ORDER_RANGE * MAX_ORDER_RANGE) {
            return;
        }
        orderMoveTo(unit, target);
    }

    private static void handleAttackOrder(ServerPlayerEntity player, AttackOrderPayload payload) {
        MobEntity unit = resolveUnit(player, payload.unitId());
        Entity target = player.getEntityWorld().getEntityById(payload.targetId());
        if (unit == null || !(target instanceof LivingEntity living) || target == player) {
            return;
        }
        UnitAI.markOrdered(unit);
        unit.setTarget(living);
        unit.getNavigation().startMovingTo(target, MOVE_SPEED);
    }

    /** A unit the given player is allowed to command right now, or null. */
    private static MobEntity resolveUnit(ServerPlayerEntity player, int entityId) {
        Entity entity = player.getEntityWorld().getEntityById(entityId);
        if (!(entity instanceof MobEntity unit) || !isUnit(entity)) {
            return null;
        }
        return unit.squaredDistanceTo(player) > MAX_ORDER_RANGE * MAX_ORDER_RANGE ? null : unit;
    }

    /** Force both sides to raise n units immediately - for testing a battle without waiting. */
    private static int wave(ServerCommandSource source, int count) {
        ServerWorld world = source.getWorld();
        int spawned = 0;
        for (Faction faction : FACTIONS.factions()) {
            for (int i = 0; i < count; i++) {
                if (faction.trySpawnUnit(world) != null) {
                    spawned++;
                }
            }
        }
        final int n = spawned;
        source.sendFeedback(() -> Text.literal("raised " + n + " unit(s)"), false);
        return spawned;
    }

    /**
     * Remove every unit in the world, so the next wave musters fresh around the player.
     * <p>
     * Units are persistent by design, which means a battle left behind in a forest keeps pulling
     * reinforcements to it. This is how a fight gets staged somewhere specific.
     */
    private static int clearUnits(ServerCommandSource source) {
        ServerWorld world = source.getWorld();
        int removed = 0;
        for (MobEntity unit : world.getEntitiesByType(
                TypeFilter.instanceOf(MobEntity.class), Units::isUnit)) {
            unit.discard();
            removed++;
        }
        UnitAI.reset();
        final int n = removed;
        source.sendFeedback(() -> Text.literal("cleared " + n + " unit(s)"), false);
        LOG.info("[ctl] cleared {} unit(s)", n);
        return removed;
    }

    private static int status(ServerCommandSource source) {
        for (Faction faction : FACTIONS.factions()) {
            String line = String.format("%s: home %s, muster %s, %d building(s)",
                    faction.name(), faction.home().toShortString(),
                    faction.muster().toShortString(), faction.buildings());
            source.sendFeedback(() -> Text.literal(line), false);
            LOG.info("[status] {}", line);
        }
        return 1;
    }
}
