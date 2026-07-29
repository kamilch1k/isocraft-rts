package dev.isorts.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Raycasts from the camera through the mouse cursor, rather than through screen centre.
 * <p>
 * Vanilla only ever picks down the crosshair, which is meaningless once the pointer is free - an
 * RTS needs to hit whatever is under the cursor.
 */
public final class CursorPick {

    private CursorPick() {
    }

    /** Block or entity under the cursor, or null. Entities win ties when nearer. */
    public static HitResult pick(MinecraftClient client, double maxDistance) {
        Entity camera = client.getCameraEntity();
        if (camera == null || client.world == null) {
            return null;
        }

        Vec3d origin = camera.getEntityPos();
        Vec3d direction = cursorRay(client, camera);
        Vec3d end = origin.add(direction.multiply(maxDistance));

        HitResult block = client.world.raycast(new RaycastContext(
                origin, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, camera));

        double limit = block != null && block.getType() != HitResult.Type.MISS
                ? block.getPos().squaredDistanceTo(origin)
                : maxDistance * maxDistance;

        Box search = new Box(origin, end).expand(1.0);
        EntityHitResult entity = ProjectileUtil.raycast(
                camera, origin, end, search, e -> !e.isSpectator() && e.canHit(), limit);

        return entity != null ? entity : block;
    }

    /** Unproject the cursor into a world-space direction. */
    private static Vec3d cursorRay(MinecraftClient client, Entity camera) {
        int width = Math.max(1, client.getWindow().getWidth());
        int height = Math.max(1, client.getWindow().getHeight());

        // Normalised device coords: -1..1, y up.
        double ndcX = (2.0 * client.mouse.getX() / width) - 1.0;
        double ndcY = 1.0 - (2.0 * client.mouse.getY() / height);

        double fovDegrees = client.options.getFov().getValue();
        double tanHalfFov = Math.tan(fovDegrees * MathHelper.RADIANS_PER_DEGREE / 2.0);
        double aspect = (double) width / height;

        float yaw = camera.getYaw();
        float pitch = camera.getPitch();
        Vec3d forward = Vec3d.fromPolar(pitch, yaw);
        Vec3d right = Vec3d.fromPolar(0.0f, yaw + 90.0f);
        Vec3d up = right.crossProduct(forward);

        return forward
                .add(right.multiply(ndcX * aspect * tanHalfFov))
                .add(up.multiply(ndcY * tanHalfFov))
                .normalize();
    }
}
