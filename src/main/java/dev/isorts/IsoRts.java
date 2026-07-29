package dev.isorts;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityType;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.passive.IronGolemEntity;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class IsoRts implements ModInitializer {

    public static final String MOD_ID = "isorts";
    public static final Logger LOG = LoggerFactory.getLogger(MOD_ID);

    /**
     * Units are vanilla iron golems.
     * ponytail: a custom EntityType needs a renderer, model, texture and model-layer registration
     * before it draws at all - and with none of that, Iris's shadow pass NPEs on a null renderer.
     * Golems already render, already pathfind, are passive, and use goal-based AI (so an explicit
     * navigation target sticks, unlike villagers' brain-driven AI which overrides it every tick).
     * Swap in a custom entity when units must LOOK different from golems - that's the only reason.
     */
    public static final EntityType<IronGolemEntity> UNIT_TYPE = EntityType.IRON_GOLEM;

    /** How far a move order may be from the ordering player - stops a spoofed packet moving units across the world. */
    private static final double MAX_ORDER_RANGE = 128.0;
    private static final double MOVE_SPEED = 1.0;

    /** Starting squad, spawned once per area on join. */
    private static final int SEED_UNIT_COUNT = 6;
    private static final double SEED_CHECK_RADIUS = 64.0;

    @Override
    public void onInitialize() {
        PayloadTypeRegistry.playC2S().register(MoveOrderPayload.ID, MoveOrderPayload.CODEC);

        ServerPlayNetworking.registerGlobalReceiver(MoveOrderPayload.ID, (payload, context) -> {
            ServerPlayerEntity player = context.player();
            context.server().execute(() -> handleMoveOrder(player, payload));
        });

        // AFK seed: a fresh world should already have units in it, so there's something to watch.
        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) ->
                server.execute(() -> seedUnits(handler.getPlayer())));

        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) ->
                dispatcher.register(CommandManager.literal("rts")
                        // ponytail: no permission gate. 1.21.11 replaced permission levels with a
                        // PermissionPredicate system, and this instance is offline-auth singleplayer
                        // so no second player can ever connect. Gate it if this ever goes multiplayer.
                        .then(CommandManager.literal("spawn")
                                .executes(ctx -> spawnUnits(ctx.getSource(), 1))
                                .then(CommandManager.argument("count", IntegerArgumentType.integer(1, 50))
                                        .executes(ctx -> spawnUnits(ctx.getSource(),
                                                IntegerArgumentType.getInteger(ctx, "count")))))));

        LOG.info("Isocraft RTS ready");
    }

    /** True if this entity can be selected and ordered around. */
    public static boolean isUnit(Entity entity) {
        return entity instanceof IronGolemEntity;
    }

    /** Issue a move order. Returns false if no path could be found. */
    public static boolean orderMoveTo(MobEntity unit, BlockPos target) {
        return unit.getNavigation().startMovingTo(
                target.getX() + 0.5, target.getY(), target.getZ() + 0.5, MOVE_SPEED);
    }

    /**
     * Spawn the starting squad if the player's area has none.
     * ponytail: idempotent by counting nearby units rather than storing a "seeded" flag in world
     * data - one query beats a persistence format we'd have to migrate later.
     */
    private static void seedUnits(ServerPlayerEntity player) {
        if (player == null) {
            return;
        }
        ServerWorld world = player.getEntityWorld();
        Box area = player.getBoundingBox().expand(SEED_CHECK_RADIUS);
        List<IronGolemEntity> existing = world.getEntitiesByClass(IronGolemEntity.class, area, e -> true);
        if (!existing.isEmpty()) {
            LOG.info("{} unit(s) already present, not seeding", existing.size());
            return;
        }
        BlockPos base = player.getBlockPos();
        int spawned = 0;
        for (int i = 0; i < SEED_UNIT_COUNT; i++) {
            IronGolemEntity unit = UNIT_TYPE.create(world, SpawnReason.EVENT);
            if (unit == null) {
                continue;
            }
            int dx = (i % 3) - 1;
            int dz = (i / 3) - 1;
            BlockPos at = world.getTopPosition(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES,
                    base.add(dx * 3, 0, dz * 3));
            unit.refreshPositionAndAngles(at.getX() + 0.5, at.getY(), at.getZ() + 0.5, 0.0f, 0.0f);
            unit.setPersistent();
            if (world.spawnEntity(unit)) {
                spawned++;
            }
        }
        LOG.info("seeded {} unit(s) near {}", spawned, base.toShortString());
    }

    /** Validate on the server - never trust a client-supplied target. */
    private static void handleMoveOrder(ServerPlayerEntity player, MoveOrderPayload payload) {
        Entity entity = player.getEntityWorld().getEntityById(payload.entityId());
        if (!(entity instanceof MobEntity unit) || !isUnit(entity)) {
            return;
        }
        BlockPos target = payload.target();
        if (!player.getEntityWorld().isInBuildLimit(target)) {
            return;
        }
        if (player.squaredDistanceTo(Vec3d.ofCenter(target)) > MAX_ORDER_RANGE * MAX_ORDER_RANGE) {
            return;
        }
        if (unit.squaredDistanceTo(player) > MAX_ORDER_RANGE * MAX_ORDER_RANGE) {
            return;
        }
        orderMoveTo(unit, target);
    }

    private static int spawnUnits(ServerCommandSource source, int count) {
        Vec3d pos = source.getPosition();
        ServerWorld world = source.getWorld();
        for (int i = 0; i < count; i++) {
            IronGolemEntity unit = UNIT_TYPE.create(world, SpawnReason.COMMAND);
            if (unit == null) {
                continue;
            }
            unit.refreshPositionAndAngles(pos.x + (i % 5) - 2, pos.y, pos.z + (i / 5.0), 0.0f, 0.0f);
            unit.setPersistent();
            world.spawnEntity(unit);
        }
        final int n = count;
        source.sendFeedback(() -> Text.literal("spawned " + n + " unit(s)"), false);
        return count;
    }
}
