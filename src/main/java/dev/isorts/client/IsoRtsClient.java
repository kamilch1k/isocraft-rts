package dev.isorts.client;

import dev.isorts.AttackOrderPayload;
import dev.isorts.IsoRts;
import dev.isorts.MoveOrderPayload;
import dev.isorts.TeleportPayload;
import dev.isorts.client.render.SoldierRenderer;
import dev.isorts.unit.IsoUnits;
import dev.isorts.unit.Units;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.rendering.v1.EntityRendererRegistry;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.TypeFilter;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

/**
 * The isometric view is the game's main view, and RTS command is layered on top of it.
 * <p>
 * <b>Why a modifier key.</b> Isocraft is an action-RPG mod: in its isometric view the left button
 * already swings at whatever the cursor is over, and the right button uses items. Taking those
 * buttons for selection would break its combat, and sharing them would make both unpredictable.
 * Holding {@code LEFT ALT} switches the mouse into command mode instead, which collides with
 * nothing in vanilla or Isocraft.
 * <ul>
 *   <li><b>V</b> - isometric (default) or first person; both control the character</li>
 *   <li><b>Alt + left-click</b> - select a unit; <b>Alt + left-drag</b> - rubber-band select</li>
 *   <li><b>Alt + right-click</b> - move order, or attack order on an enemy</li>
 *   <li><b>G</b> - select every friendly unit nearby, <b>X</b> - clear the selection</li>
 *   <li><b>F6</b> - detached free camera (kept, but off the main path)</li>
 * </ul>
 */
public class IsoRtsClient implements ClientModInitializer {

    /** Two views for the character; the free camera is a separate toggle, not part of the cycle. */
    private enum View { ISOMETRIC, FIRST_PERSON }

    private static final int ISO_AUTO_DELAY_TICKS = 40;
    private static final double DRAG_DEADZONE = 1.5;

    /** Reach for cursor picking - generous, since the isometric camera sits well back. */
    private static final double PICK_DISTANCE = 256.0;
    /** A drag shorter than this is a click, not a rubber band. */
    private static final double BOX_MIN_PIXELS = 8.0;
    /** How far around the player to look for units when selecting. */
    private static final double SELECT_RADIUS = 128.0;
    /** What counts as "the same fight" when looking for where the action is. */
    private static final double CLUSTER_RADIUS = 14.0;

    private View view = View.ISOMETRIC;
    private final List<MobEntity> selected = new ArrayList<>();

    private final RtsCameraMode freeCamera = new RtsCameraMode();
    private final ControlFile controlFile = new ControlFile(this);
    private final Recorder recorder = new Recorder();

    private KeyBinding cycleKey;
    private KeyBinding freeCamKey;
    private KeyBinding selectAllKey;
    private KeyBinding clearKey;

    private int isoAutoDelay = -1;

    private boolean leftWasDown;
    private boolean rightWasDown;
    private boolean middleWasDown;
    private double lastMouseX;
    private double dragStartX;
    private double dragStartY;
    private boolean loggedCursorSource;
    /** A camera pose queued by the control file, applied once the camera is running. */
    private double[] pendingPose;

    @Override
    public void onInitializeClient() {
        // Without a registered renderer a custom entity does not merely fail to draw - Iris's
        // shadow pass asks for the renderer, gets null, and crashes the game.
        EntityRendererRegistry.register(IsoUnits.SOLDIER, SoldierRenderer::new);

        // 1.21.11: KeyBinding takes a KeyBinding.Category record, not a translation-key String.
        KeyBinding.Category category = KeyBinding.Category.create(Identifier.of(IsoRts.MOD_ID, "isorts"));
        cycleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.isorts.cycle_view", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V, category));
        freeCamKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.isorts.free_camera", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_F6, category));
        selectAllKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.isorts.select_all", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_G, category));
        clearKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.isorts.clear_selection", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_X, category));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            view = View.ISOMETRIC;
            clearSelection();
            isoAutoDelay = ISO_AUTO_DELAY_TICKS;
            freeCamera.hookScroll(client);
        });

        // A stale camera entity across a disconnect would leave the player unable to move.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            freeCamera.disable(client);
            clearSelection();
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        IsoRts.LOG.info("Isocraft RTS client ready");
    }

    RtsCameraMode rtsCamera() {
        return freeCamera;
    }

    private void onTick(MinecraftClient client) {
        controlFile.tick(client);
        recorder.tick(client);
        if (client.player == null || client.world == null) {
            return;
        }

        while (cycleKey.wasPressed()) {
            view = view == View.ISOMETRIC ? View.FIRST_PERSON : View.ISOMETRIC;
            say(client, view == View.ISOMETRIC ? "isometric" : "first person");
        }
        while (freeCamKey.wasPressed()) {
            if (freeCamera.isActive()) {
                freeCamera.disable(client);
            } else {
                freeCamera.enable(client);
            }
        }
        while (selectAllKey.wasPressed()) {
            selectAllNearby(client);
        }
        while (clearKey.wasPressed()) {
            clearSelection();
            say(client, "selection cleared");
        }

        if (isoAutoDelay > 0 && --isoAutoDelay == 0) {
            isoAutoDelay = -1;
            IsoRts.LOG.info("isometric view is the default view");
        }

        // Enforce rather than toggle: Isocraft's isometric camera is on exactly when we want it,
        // and never while the detached free camera owns the view.
        boolean wantIso = view == View.ISOMETRIC && !freeCamera.isActive() && isoAutoDelay < 0;
        if (IsocraftBridge.available() && IsocraftBridge.isIso() != wantIso) {
            IsocraftBridge.setIso(wantIso);
        }

        if (pendingPose != null && freeCamera.isActive()) {
            freeCamera.setPose(pendingPose[0], pendingPose[1], pendingPose[2],
                    (float) pendingPose[3], (float) pendingPose[4]);
            pendingPose = null;
        }

        freeCamera.tick(client);
        if (!freeCamera.isActive()) {
            AutoAttack.tick(client);                 // hold attack, fight what is next to you
            if (view == View.ISOMETRIC) {
                handleMiddleDragRotate(client);
            }
        }
        pruneSelection();
        handleCommands(client);
    }

    // ---------------------------------------------------------------- selection and orders

    /**
     * Command mode: the free camera always has it (the pointer is free there), otherwise it is
     * held on LEFT ALT so Isocraft keeps its own click behaviour.
     */
    private boolean commandMode(MinecraftClient client) {
        if (freeCamera.isActive()) {
            return true;
        }
        long window = client.getWindow().getHandle();
        return GLFW.glfwGetKey(window, GLFW.GLFW_KEY_LEFT_ALT) == GLFW.GLFW_PRESS
                || GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_ALT) == GLFW.GLFW_PRESS;
    }

    /**
     * The cursor to pick through, in window pixels.
     * <p>
     * In the isometric view the pointer is Isocraft's own virtual cursor, not the OS pointer, so
     * ask it first and fall back to the real mouse. Logged once, because if the two ever disagree
     * that is the first thing worth knowing.
     */
    private double[] cursor(MinecraftClient client) {
        double[] iso = IsocraftBridge.cursor();
        double mouseX = client.mouse.getX();
        double mouseY = client.mouse.getY();
        if (!loggedCursorSource) {
            loggedCursorSource = true;
            IsoRts.LOG.info("cursor source: isocraft={} mouse={},{}",
                    iso == null ? "none" : (iso[0] + "," + iso[1]), mouseX, mouseY);
        }
        return iso != null && !freeCamera.isActive() ? iso : new double[] { mouseX, mouseY };
    }

    /** The FOV actually being rendered with - Isocraft's own in the isometric view. */
    private double fov(MinecraftClient client) {
        if (!freeCamera.isActive() && IsocraftBridge.isIso()) {
            double iso = IsocraftBridge.isoFov();
            if (iso > 1.0) {
                return iso;
            }
        }
        return client.options.getFov().getValue();
    }

    private void handleCommands(MinecraftClient client) {
        long window = client.getWindow().getHandle();
        boolean armed = commandMode(client);
        boolean left = armed && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean right = armed && GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;
        double[] c = cursor(client);

        if (left && !leftWasDown) {
            dragStartX = c[0];
            dragStartY = c[1];
        } else if (!left && leftWasDown) {
            double dx = Math.abs(c[0] - dragStartX);
            double dy = Math.abs(c[1] - dragStartY);
            // Rubber-band select needs world-to-screen projection, which is only trustworthy under
            // our own camera: Isocraft leases and expands the render projection, so no single FOV
            // reproduces it. In the isometric view a drag therefore selects like a click, and G
            // covers "take the whole army".
            if ((dx > BOX_MIN_PIXELS || dy > BOX_MIN_PIXELS) && freeCamera.isActive()) {
                boxSelect(client, dragStartX, dragStartY, c[0], c[1]);
            } else {
                clickSelect(client, c);
            }
        }
        leftWasDown = left;

        // Order on release, and never while the free camera is being right-dragged to pan.
        if (!right && rightWasDown && !(freeCamera.isActive() && freeCamera.consumedRightDrag())) {
            issueOrder(client, c);
        }
        rightWasDown = right;
    }

    /**
     * What the cursor is pointing at.
     * <p>
     * In the isometric view this is Isocraft's own cursor target: it replaces the vanilla crosshair
     * pick with a raycast through its cursor for its aiming, so {@code crosshairTarget} is already
     * exactly what is under the pointer - no unprojection of ours required, and no dependence on a
     * projection we cannot reproduce. Under our own free camera we do the raycast ourselves.
     */
    private HitResult target(MinecraftClient client, double[] c) {
        if (freeCamera.isActive()) {
            return CursorPick.pick(client, c[0], c[1], fov(client), PICK_DISTANCE);
        }
        return client.crosshairTarget;
    }

    private void clickSelect(MinecraftClient client, double[] c) {
        HitResult hit = target(client, c);
        if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
            Entity entity = ((EntityHitResult) hit).getEntity();
            if (entity instanceof MobEntity unit && Units.isUnit(unit)) {
                clearSelection();
                selected.add(unit);
                unit.setGlowing(true);
                say(client, "selected " + Units.factionOf(unit) + " unit");
                return;
            }
        }
        clearSelection();
        say(client, "nothing selected");
    }

    /** Rubber band: project every nearby unit to the screen and keep the ones inside the box. */
    private void boxSelect(MinecraftClient client, double x1, double y1, double x2, double y2) {
        double minX = Math.min(x1, x2);
        double maxX = Math.max(x1, x2);
        double minY = Math.min(y1, y2);
        double maxY = Math.max(y1, y2);

        clearSelection();
        double fov = fov(client);
        for (MobEntity unit : nearbyUnits(client)) {
            double[] p = CursorPick.project(client, unit.getEntityPos().add(0.0, 1.0, 0.0), fov);
            if (p == null || p[0] < minX || p[0] > maxX || p[1] < minY || p[1] > maxY) {
                continue;
            }
            selected.add(unit);
            unit.setGlowing(true);
        }
        say(client, "selected " + selected.size() + " unit(s)");
    }

    private void selectAllNearby(MinecraftClient client) {
        clearSelection();
        for (MobEntity unit : nearbyUnits(client)) {
            if (Units.KINGDOM.equals(Units.factionOf(unit))) {
                selected.add(unit);
                unit.setGlowing(true);
            }
        }
        say(client, "selected " + selected.size() + " " + Units.KINGDOM + " unit(s)");
    }

    private List<MobEntity> nearbyUnits(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            return List.of();
        }
        Box area = client.player.getBoundingBox().expand(SELECT_RADIUS);
        return client.world.getEntitiesByType(
                TypeFilter.instanceOf(MobEntity.class), area, Units::isUnit);
    }

    private void issueOrder(MinecraftClient client, double[] c) {
        if (selected.isEmpty()) {
            return;
        }
        HitResult hit = target(client, c);
        if (hit == null) {
            return;
        }

        if (hit.getType() == HitResult.Type.ENTITY) {
            Entity target = ((EntityHitResult) hit).getEntity();
            int ordered = 0;
            for (MobEntity unit : selected) {
                if (Units.areEnemies(unit, target)) {
                    ClientPlayNetworking.send(new AttackOrderPayload(unit.getId(), target.getId()));
                    ordered++;
                }
            }
            if (ordered > 0) {
                say(client, ordered + " unit(s) attacking");
                return;
            }
        }

        BlockPos centre = null;
        if (hit.getType() == HitResult.Type.BLOCK) {
            centre = ((BlockHitResult) hit).getBlockPos().up();
        } else {
            // Nothing under the cursor: fall back to Isocraft's aim point, so an order onto open
            // ground still lands somewhere sensible.
            Vec3d aim = IsocraftBridge.cursorAimPoint(client.player);
            if (aim != null) {
                centre = BlockPos.ofFloored(aim);
            }
        }
        if (centre != null) {
            // Spread the squad around the target so they do not pile onto one block.
            int i = 0;
            for (MobEntity unit : selected) {
                BlockPos target = centre.add(spread(i), 0, spread(i + 1));
                ClientPlayNetworking.send(new MoveOrderPayload(unit.getId(), target));
                i += 2;
            }
            say(client, selected.size() + " unit(s) moving to " + centre.toShortString());
        }
    }

    /** A small square spiral of offsets, so a squad spreads instead of stacking. */
    private static int spread(int i) {
        return ((i / 2) % 3) - 1;
    }

    private void clearSelection() {
        for (MobEntity unit : selected) {
            unit.setGlowing(false);
        }
        selected.clear();
    }

    private void pruneSelection() {
        selected.removeIf(unit -> {
            if (unit.isRemoved() || !unit.isAlive()) {
                return true;
            }
            unit.setGlowing(true);          // the server resyncs entity flags; keep the outline on
            return false;
        });
    }

    // ---------------------------------------------------------------- view plumbing

    /** Middle-drag rotates Isocraft's camera, through the same input its own rotate keys feed. */
    private void handleMiddleDragRotate(MinecraftClient client) {
        long window = client.getWindow().getHandle();
        boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
        double mouseX = client.mouse.getX();

        if (!down) {
            if (middleWasDown) {
                IsocraftBridge.setRotationInput(0);
            }
            middleWasDown = false;
            return;
        }
        if (!middleWasDown) {
            middleWasDown = true;
            lastMouseX = mouseX;
            return;
        }

        double dx = mouseX - lastMouseX;
        lastMouseX = mouseX;
        IsocraftBridge.setRotationInput(dx > DRAG_DEADZONE ? 1 : dx < -DRAG_DEADZONE ? -1 : 0);
    }

    /** Jump straight to a named view - used by the terminal control file. */
    void setState(String name) {
        MinecraftClient client = MinecraftClient.getInstance();
        switch (name) {
            case "fp" -> {
                if (freeCamera.isActive()) {
                    freeCamera.disable(client);
                }
                view = View.FIRST_PERSON;
            }
            case "iso" -> {
                if (freeCamera.isActive()) {
                    freeCamera.disable(client);
                }
                view = View.ISOMETRIC;
            }
            case "rts" -> {
                if (!freeCamera.isActive()) {
                    freeCamera.enable(client);
                }
            }
            default -> {
                return;
            }
        }
        isoAutoDelay = -1;
        IsoRts.LOG.info("[ctl] view -> {}{}", view, freeCamera.isActive() ? " + free camera" : "");
    }

    /** Terminal diagnostics: what the selection machinery currently sees. */
    void probe() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player == null) {
            return;
        }
        double[] iso = IsocraftBridge.cursor();
        IsoRts.LOG.info("[probe] iso={} isoFov={} vanillaFov={} mouse={},{} locked={}",
                IsocraftBridge.isIso(), IsocraftBridge.isoFov(),
                client.options.getFov().getValue(), client.mouse.getX(), client.mouse.getY(),
                client.mouse.isCursorLocked());
        IsoRts.LOG.info("[probe] isocraftCursor={}",
                iso == null ? "none" : iso[0] + "," + iso[1]);

        Box area = client.player.getBoundingBox().expand(SELECT_RADIUS);
        int mobs = client.world == null ? 0
                : client.world.getEntitiesByType(TypeFilter.instanceOf(MobEntity.class), area, e -> true).size();
        var cam = client.gameRenderer.getCamera();
        IsoRts.LOG.info("[probe] player at {} | camera at {} yaw {} pitch {} | {} mob(s) within {}",
                client.player.getBlockPos().toShortString(),
                cam.getBlockPos().toShortString(), (int) cam.getYaw(), (int) cam.getPitch(),
                mobs, (int) SELECT_RADIUS);

        // The two things selection actually depends on: what Isocraft says the cursor is over, and
        // what it says the cursor is aiming at in the world.
        HitResult cross = client.crosshairTarget;
        String crossDesc = cross == null ? "null" : switch (cross.getType()) {
            case ENTITY -> "ENTITY " + ((EntityHitResult) cross).getEntity().getType()
                    + " name=" + ((EntityHitResult) cross).getEntity().getCustomName();
            case BLOCK -> "BLOCK " + ((BlockHitResult) cross).getBlockPos().toShortString();
            case MISS -> "MISS";
        };
        IsoRts.LOG.info("[probe] crosshairTarget: {}", crossDesc);
        IsoRts.LOG.info("[probe] cursorAimPoint: {}", IsocraftBridge.cursorAimPoint(client.player));

        int i = 0;
        for (MobEntity unit : nearbyUnits(client)) {
            Vec3d p = unit.getEntityPos();
            IsoRts.LOG.info("[probe] {} at {} {} {} dist {} health {}", Units.factionOf(unit),
                    (int) p.x, (int) p.y, (int) p.z,
                    (int) Math.sqrt(unit.squaredDistanceTo(client.player)), (int) unit.getHealth());
            if (++i >= 8) {
                break;
            }
        }
        IsoRts.LOG.info("[probe] {} selected", selected.size());
    }

    /**
     * Point the free camera at wherever the fighting is.
     * <p>
     * A battle moves, so a camera posed at fixed coordinates is looking at an empty field by the
     * time the screenshot lands. This aims at the centre of mass of the units instead.
     */
    void watchBattle() {
        MinecraftClient client = MinecraftClient.getInstance();
        List<MobEntity> units = nearbyUnits(client);
        if (units.isEmpty()) {
            IsoRts.LOG.info("[ctl] watch: no units nearby");
            return;
        }

        // The densest knot of units, not their average position: two armies spread across a map
        // average out to an empty field between them, which is exactly what earlier shots framed.
        MobEntity busiest = null;
        int most = -1;
        for (MobEntity unit : units) {
            int neighbours = 0;
            for (MobEntity other : units) {
                if (unit.squaredDistanceTo(other) < CLUSTER_RADIUS * CLUSTER_RADIUS) {
                    neighbours++;
                }
            }
            if (neighbours > most) {
                most = neighbours;
                busiest = unit;
            }
        }

        double x = busiest.getEntityPos().x;
        double y = busiest.getEntityPos().y;
        double z = busiest.getEntityPos().z;
        IsoRts.LOG.info("[ctl] watch: densest knot has {} unit(s)", most);

        if (!freeCamera.isActive()) {
            freeCamera.enable(client);
        }
        // Applied on the NEXT tick, not here: enabling the camera seeds its target from the player,
        // and a pose set in the same call was being overwritten by that - the camera ended up
        // hanging over the player instead of the battle.
        pendingPose = new double[] { x, y + 22.0, z + 26.0, 180.0, 45.0 };
        IsoRts.LOG.info("[ctl] watch: {} unit(s), centre {} {} {}",
                units.size(), (int) x, (int) y, (int) z);
    }

    void record(String name, int frames, int everyNTicks) {
        recorder.start(name, frames, everyNTicks);
    }

    void teleport(BlockPos target) {
        ClientPlayNetworking.send(new TeleportPayload(target));
    }

    /** Terminal test hook: select and order without a mouse. */
    void selectAllFromTerminal() {
        selectAllNearby(MinecraftClient.getInstance());
    }

    void orderTo(BlockPos target) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (selected.isEmpty()) {
            say(client, "nothing selected");
            return;
        }
        int i = 0;
        for (MobEntity unit : selected) {
            ClientPlayNetworking.send(new MoveOrderPayload(
                    unit.getId(), target.add(spread(i), 0, spread(i + 1))));
            i += 2;
        }
        IsoRts.LOG.info("[ctl] ordered {} unit(s) to {}", selected.size(), target.toShortString());
    }

    private static void say(MinecraftClient client, String msg) {
        if (client.player != null) {
            client.player.sendMessage(
                    Text.literal("[rts] ").formatted(Formatting.GRAY).append(Text.literal(msg)), true);
        }
    }
}
