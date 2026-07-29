package dev.isorts.client;

import dev.isorts.IsoRts;
import dev.isorts.MoveOrderPayload;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

public class IsoRtsClient implements ClientModInitializer {

    /**
     * The three control states, cycled by one key.
     * <ol>
     *   <li>{@link #FIRST_PERSON} - normal Minecraft, controlling the character</li>
     *   <li>{@link #ISO_PLAYER} - Isocraft's isometric view, still controlling the character</li>
     *   <li>{@link #RTS} - detached RTS camera, not controlling the character</li>
     * </ol>
     * Isocraft's view is not toggled but ENFORCED: every tick its enabled flag is set to what the
     * current state demands, via {@link IsocraftBridge}. Blind toggling could drift out of parity
     * (a stray press of its own binding, a toggle mid-transition), after which its player-relative
     * camera fought the RTS camera - the "moves in weird directions" bug.
     */
    private enum State { FIRST_PERSON, ISO_PLAYER, RTS }

    private static final double DRAG_DEADZONE = 1.5;
    private static final int ISO_AUTO_DELAY_TICKS = 40;

    /** Reach for cursor picking - generous, since the RTS camera sits well back. */
    private static final double PICK_DISTANCE = 256.0;

    private State state = State.FIRST_PERSON;
    private Entity selected;

    private final RtsCameraMode rtsCamera = new RtsCameraMode();
    private final ControlFile controlFile = new ControlFile(this);
    private KeyBinding cycleKey;

    private int isoToggleCountdown = -1;

    private boolean middleWasDown;
    private double lastMouseX;
    private boolean leftWasDown;
    private boolean rtsRightWasDown;

    @Override
    public void onInitializeClient() {
        // 1.21.11: KeyBinding takes a KeyBinding.Category record, not a translation-key String.
        cycleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.isorts.cycle_view", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_V,
                KeyBinding.Category.create(Identifier.of(IsoRts.MOD_ID, "isorts"))));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            state = State.FIRST_PERSON;
            selected = null;
            isoToggleCountdown = ISO_AUTO_DELAY_TICKS;   // land in ISO_PLAYER
            rtsCamera.hookScroll(client);
        });

        // A stale camera entity across a disconnect would leave the player unable to move.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            rtsCamera.disable(client);
            state = State.FIRST_PERSON;
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        IsoRts.LOG.info("Isocraft RTS client ready");
    }

    RtsCameraMode rtsCamera() {
        return rtsCamera;
    }

    private void onTick(MinecraftClient client) {
        controlFile.tick(client);
        if (client.player == null || client.world == null) {
            return;
        }

        while (cycleKey.wasPressed()) {
            cycle(client);
        }

        if (isoToggleCountdown > 0 && --isoToggleCountdown == 0) {
            isoToggleCountdown = -1;
            state = State.ISO_PLAYER;
            IsoRts.LOG.info("auto-enabled isometric view");
        }

        // Enforce, don't toggle: Isocraft's camera is on exactly when we are in ISO_PLAYER.
        boolean wantIso = state == State.ISO_PLAYER;
        if (IsocraftBridge.available() && IsocraftBridge.isIso() != wantIso) {
            IsocraftBridge.setIso(wantIso);
        }

        rtsCamera.tick(client);

        if (!rtsCamera.isActive()) {
            handleMiddleDragRotate(client);
        }
        handleSelectionAndOrders(client);
    }

    /** FIRST_PERSON -> ISO_PLAYER -> RTS -> FIRST_PERSON. */
    private void cycle(MinecraftClient client) {
        switch (state) {
            case FIRST_PERSON -> {
                state = State.ISO_PLAYER;
                say(client, "isometric - controlling character");
            }
            case ISO_PLAYER -> {
                // Isocraft's view positions the camera relative to the player and would fight our
                // detached camera; the per-tick enforcement turns it off before rendering.
                state = State.RTS;
                IsocraftBridge.setIso(false);
                rtsCamera.enable(client);
            }
            case RTS -> {
                rtsCamera.disable(client);
                state = State.FIRST_PERSON;
                say(client, "first person");
            }
        }
    }

    /** Jump straight to a named state - used by the terminal control file. */
    void setState(String name) {
        MinecraftClient client = MinecraftClient.getInstance();
        State target = switch (name) {
            case "fp" -> State.FIRST_PERSON;
            case "iso" -> State.ISO_PLAYER;
            case "rts" -> State.RTS;
            default -> null;
        };
        if (target == null || client.player == null) {
            return;
        }
        isoToggleCountdown = -1;
        for (int i = 0; i < 3 && state != target; i++) {
            cycle(client);
        }
        IsoRts.LOG.info("[ctl] state -> {}", state);
    }

    /**
     * Middle-drag rotates Isocraft's camera while controlling the character, through the same
     * continuous-rotation input its own keys feed. In RTS mode middle-drag looks around instead,
     * so this is skipped there.
     */
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

        if (dx > DRAG_DEADZONE) {
            IsocraftBridge.setRotationInput(1);
        } else if (dx < -DRAG_DEADZONE) {
            IsocraftBridge.setRotationInput(-1);
        } else {
            IsocraftBridge.setRotationInput(0);
        }
    }

    /**
     * ponytail: uses the vanilla crosshair pick, which follows whichever entity the camera is
     * attached to. Cursor unprojection and drag-box select are the upgrade.
     */
    private void handleSelectionAndOrders(MinecraftClient client) {
        if (rtsCamera.isActive()) {
            handleRtsSelection(client);
            return;
        }

        HitResult hit = client.crosshairTarget;
        if (hit == null) {
            return;
        }

        if (client.options.attackKey.wasPressed() && hit.getType() == HitResult.Type.ENTITY) {
            Entity entity = ((EntityHitResult) hit).getEntity();
            if (IsoRts.isUnit(entity)) {
                selected = entity;
                say(client, "selected unit " + entity.getId());
            }
        }

        if (client.options.useKey.wasPressed()) {
            if (selected == null || selected.isRemoved()) {
                return;
            }
            if (hit.getType() == HitResult.Type.BLOCK) {
                BlockPos target = ((BlockHitResult) hit).getBlockPos().up();
                ClientPlayNetworking.send(new MoveOrderPayload(selected.getId(), target));
                say(client, "move -> " + target.toShortString());
            }
        }
    }

    /**
     * RTS-mode picking: left-click selects whatever unit is under the cursor, right-click orders
     * it there. Reads raw button edges rather than key bindings, and picks through the cursor
     * rather than the crosshair - with the pointer free, screen centre means nothing.
     */
    private void handleRtsSelection(MinecraftClient client) {
        long window = client.getWindow().getHandle();
        boolean left = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_LEFT) == GLFW.GLFW_PRESS;
        boolean right = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_RIGHT) == GLFW.GLFW_PRESS;

        if (left && !leftWasDown) {
            HitResult hit = CursorPick.pick(client, PICK_DISTANCE);
            if (hit != null && hit.getType() == HitResult.Type.ENTITY) {
                Entity entity = ((EntityHitResult) hit).getEntity();
                if (IsoRts.isUnit(entity)) {
                    selected = entity;
                    say(client, "selected unit " + entity.getId());
                } else {
                    selected = null;
                }
            } else {
                selected = null;
                say(client, "nothing selected");
            }
        }
        leftWasDown = left;

        // Order on release, and only if the drag was short enough to be a click not a pan.
        if (!right && rtsRightWasDown && !rtsCamera.consumedRightDrag()) {
            if (selected != null && !selected.isRemoved()) {
                HitResult hit = CursorPick.pick(client, PICK_DISTANCE);
                if (hit != null && hit.getType() == HitResult.Type.BLOCK) {
                    BlockPos target = ((BlockHitResult) hit).getBlockPos().up();
                    ClientPlayNetworking.send(new MoveOrderPayload(selected.getId(), target));
                    say(client, "move -> " + target.toShortString());
                }
            }
        }
        rtsRightWasDown = right;
    }

    private static void say(MinecraftClient client, String msg) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[rts] " + msg), true);
        }
    }
}
