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
     */
    private enum State { FIRST_PERSON, ISO_PLAYER, RTS }

    /**
     * Isocraft's own toggle, moved off V to a key nobody presses so it cannot double-fire with
     * ours. We drive it by synthesising this key's state - it must stay BOUND to something for
     * that to work, which is why it is parked here rather than unbound.
     */
    private static final String ISO_TOGGLE_KEY = "key.keyboard.f8";

    /** Isocraft's rotate keys, driven by middle-drag while in ISO_PLAYER. */
    private static final String ROTATE_CCW_KEY = "key.keyboard.z";
    private static final String ROTATE_CW_KEY = "key.keyboard.c";

    private static final double DRAG_DEADZONE = 1.5;
    private static final int ISO_AUTO_DELAY_TICKS = 40;

    /** Reach for cursor picking - generous, since the RTS camera sits well back. */
    private static final double PICK_DISTANCE = 256.0;

    private State state = State.FIRST_PERSON;
    private Entity selected;

    private final RtsCameraMode rtsCamera = new RtsCameraMode();
    private KeyBinding cycleKey;

    private int isoToggleCountdown = -1;
    private int isoToggleRelease = -1;

    private InputUtil.Key isoKey;
    private InputUtil.Key ccwKey;
    private InputUtil.Key cwKey;
    private boolean ccwHeld;
    private boolean cwHeld;

    private boolean middleWasDown;
    private double lastMouseX;
    private boolean leftWasDown;
    private boolean rtsRightWasDown;

    @Override
    public void onInitializeClient() {
        isoKey = InputUtil.fromTranslationKey(ISO_TOGGLE_KEY);
        ccwKey = InputUtil.fromTranslationKey(ROTATE_CCW_KEY);
        cwKey = InputUtil.fromTranslationKey(ROTATE_CW_KEY);

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

    private void onTick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            releaseRotate();
            return;
        }

        while (cycleKey.wasPressed()) {
            cycle(client);
        }

        handleIsoAutoToggle(client);
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
                tapIsoToggle();                 // Isocraft iso on
                state = State.ISO_PLAYER;
                say(client, "isometric - controlling character");
            }
            case ISO_PLAYER -> {
                // Isocraft's iso view positions the camera relative to the player and would fight
                // our detached camera, so hand rendering over cleanly: its view off, ours on.
                tapIsoToggle();                 // Isocraft iso off
                rtsCamera.enable(client);
                state = State.RTS;
            }
            case RTS -> {
                rtsCamera.disable(client);
                state = State.FIRST_PERSON;
                say(client, "first person");
            }
        }
    }

    /**
     * Fire Isocraft's toggle exactly once.
     * <p>
     * Deliberately only bumps the press counter that {@code wasPressed()} drains - it does NOT
     * mark the key held. Holding it (as this did) leaves {@code isPressed()} true for several
     * ticks, and a poll-based handler then toggles the view on/off/on, which reads as a stutter
     * during the transition.
     */
    private void tapIsoToggle() {
        KeyBinding.onKeyPressed(isoKey);
    }

    private void handleIsoAutoToggle(MinecraftClient client) {
        if (isoToggleCountdown > 0) {
            isoToggleCountdown--;
            if (isoToggleCountdown == 0) {
                isoToggleCountdown = -1;
                tapIsoToggle();
                state = State.ISO_PLAYER;
                IsoRts.LOG.info("auto-enabled isometric view");
            }
        }
    }

    /**
     * Middle-drag rotates Isocraft's camera while controlling the character. In RTS mode
     * middle-drag pans instead, so this is skipped there.
     */
    private void handleMiddleDragRotate(MinecraftClient client) {
        long window = client.getWindow().getHandle();
        boolean down = GLFW.glfwGetMouseButton(window, GLFW.GLFW_MOUSE_BUTTON_MIDDLE) == GLFW.GLFW_PRESS;
        double mouseX = client.mouse.getX();

        if (!down) {
            if (middleWasDown) {
                releaseRotate();
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
            setRotate(false, true);
        } else if (dx < -DRAG_DEADZONE) {
            setRotate(true, false);
        } else {
            setRotate(false, false);
        }
    }

    private void setRotate(boolean ccw, boolean cw) {
        if (ccw != ccwHeld) {
            KeyBinding.setKeyPressed(ccwKey, ccw);
            ccwHeld = ccw;
        }
        if (cw != cwHeld) {
            KeyBinding.setKeyPressed(cwKey, cw);
            cwHeld = cw;
        }
    }

    private void releaseRotate() {
        setRotate(false, false);
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
