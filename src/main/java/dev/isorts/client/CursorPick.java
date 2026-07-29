package dev.isorts.client;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.Camera;
import net.minecraft.entity.Entity;
import net.minecraft.entity.projectile.ProjectileUtil;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;

/**
 * Turns a screen position into a world ray, and a world position back into a screen position.
 * <p>
 * Vanilla only ever picks down the crosshair, which is meaningless once the pointer is free - an
 * RTS needs to hit whatever is under the cursor, and needs to know where each unit appears on
 * screen to rubber-band select them.
 * <p>
 * Both directions work from {@code gameRenderer.getCamera()} - the REAL render camera - not from
 * the camera entity. In the isometric view Isocraft leaves the camera entity (the player) where it
 * is and moves the render camera tens of blocks away at its own pitch, so a ray built from the
 * player's eyes points somewhere entirely different from what is on screen. That, plus reading the
 * vanilla FOV (70) while Isocraft renders at 18, is why clicking a unit in the isometric view never
 * hit anything: wrong origin, wrong angle, wrong scale.
 */
public final class CursorPick {

    private CursorPick() {
    }

    /** Block or entity at this screen position. Entities win when nearer than the block. */
    public static HitResult pick(MinecraftClient client, double screenX, double screenY,
                                 double fovDegrees, double maxDistance) {
        Camera camera = client.gameRenderer.getCamera();
        Entity owner = client.getCameraEntity();
        if (client.world == null || camera == null || !camera.isReady() || owner == null) {
            return null;
        }

        Vec3d origin = camera.getCameraPos();
        Vec3d direction = ray(client, camera, screenX, screenY, fovDegrees);
        Vec3d end = origin.add(direction.multiply(maxDistance));

        HitResult block = client.world.raycast(new RaycastContext(
                origin, end, RaycastContext.ShapeType.OUTLINE, RaycastContext.FluidHandling.NONE, owner));

        double limit = block != null && block.getType() != HitResult.Type.MISS
                ? block.getPos().squaredDistanceTo(origin)
                : maxDistance * maxDistance;

        Box search = new Box(origin, end).expand(1.0);
        EntityHitResult entity = ProjectileUtil.raycast(
                owner, origin, end, search, e -> !e.isSpectator() && e.canHit(), limit);

        return entity != null ? entity : block;
    }

    /** Unproject a screen position into a world-space direction. */
    private static Vec3d ray(MinecraftClient client, Camera camera,
                             double screenX, double screenY, double fovDegrees) {
        int width = Math.max(1, client.getWindow().getWidth());
        int height = Math.max(1, client.getWindow().getHeight());

        // Normalised device coords: -1..1, y up.
        double ndcX = (2.0 * screenX / width) - 1.0;
        double ndcY = 1.0 - (2.0 * screenY / height);

        double tanHalfFov = Math.tan(fovDegrees * MathHelper.RADIANS_PER_DEGREE / 2.0);
        double aspect = (double) width / height;

        Vec3d forward = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        Vec3d right = Vec3d.fromPolar(0.0f, camera.getYaw() + 90.0f);
        Vec3d up = right.crossProduct(forward);

        return forward
                .add(right.multiply(ndcX * aspect * tanHalfFov))
                .add(up.multiply(ndcY * tanHalfFov))
                .normalize();
    }

    /**
     * Where a world position lands on screen, in window pixels, or null if it is behind the camera.
     * The inverse of {@link #ray} - used to test which units fall inside a selection rectangle.
     */
    public static double[] project(MinecraftClient client, Vec3d world, double fovDegrees) {
        Camera camera = client.gameRenderer.getCamera();
        if (camera == null || !camera.isReady()) {
            return null;
        }
        int width = Math.max(1, client.getWindow().getWidth());
        int height = Math.max(1, client.getWindow().getHeight());

        Vec3d forward = Vec3d.fromPolar(camera.getPitch(), camera.getYaw());
        Vec3d right = Vec3d.fromPolar(0.0f, camera.getYaw() + 90.0f);
        Vec3d up = right.crossProduct(forward);

        Vec3d v = world.subtract(camera.getCameraPos());
        double depth = v.dotProduct(forward);
        if (depth <= 0.01) {
            return null;                        // behind the camera
        }

        double tanHalfFov = Math.tan(fovDegrees * MathHelper.RADIANS_PER_DEGREE / 2.0);
        double aspect = (double) width / height;

        double ndcX = (v.dotProduct(right) / depth) / (aspect * tanHalfFov);
        double ndcY = (v.dotProduct(up) / depth) / tanHalfFov;

        return new double[] { (ndcX + 1.0) * 0.5 * width, (1.0 - ndcY) * 0.5 * height };
    }
}
