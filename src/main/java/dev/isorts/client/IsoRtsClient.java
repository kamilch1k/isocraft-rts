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
import org.lwjgl.glfw.GLFW;
import net.minecraft.entity.Entity;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import org.lwjgl.glfw.GLFW;

public class IsoRtsClient implements ClientModInitializer {

    /** Isocraft's rotate keys, as bound in options.txt. We drive these rather than its internals. */
    private static final String ROTATE_CCW_KEY = "key.keyboard.z";
    private static final String ROTATE_CW_KEY = "key.keyboard.c";

    /** Isocraft's "toggle isometric camera" key, as bound in options.txt. */
    private static final String ISO_TOGGLE_KEY = "key.keyboard.v";

    /** Horizontal drag (in raw mouse units) before a rotate key is synthesised. */
    private static final double DRAG_DEADZONE = 1.5;

    /** Ticks to wait after joining before flipping to isometric - the world needs to be in. */
    private static final int ISO_AUTO_DELAY_TICKS = 40;

    private Entity selected;

    private final RtsCameraMode rtsCamera = new RtsCameraMode();
    private KeyBinding rtsCameraKey;

    private int isoToggleCountdown = -1;
    private int isoToggleRelease = -1;
    private InputUtil.Key isoKey;

    private boolean middleWasDown;
    private double lastMouseX;
    private InputUtil.Key ccwKey;
    private InputUtil.Key cwKey;
    private boolean ccwHeld;
    private boolean cwHeld;

    @Override
    public void onInitializeClient() {
        ccwKey = InputUtil.fromTranslationKey(ROTATE_CCW_KEY);
        cwKey = InputUtil.fromTranslationKey(ROTATE_CW_KEY);
        isoKey = InputUtil.fromTranslationKey(ISO_TOGGLE_KEY);

        // 1.21.11: the category is a KeyBinding.Category record, not a translation-key String.
        rtsCameraKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.isorts.rts_camera", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_M,
                KeyBinding.Category.create(Identifier.of(IsoRts.MOD_ID, "isorts"))));

        // Isocraft has no "start in isometric" setting, so tap its toggle shortly after joining.
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            isoToggleCountdown = ISO_AUTO_DELAY_TICKS;
            selected = null;
        });

        // A stale camera entity across a disconnect would leave the player unable to move.
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            if (rtsCamera.isActive()) {
                rtsCamera.toggle(client);
            }
        });

        ClientTickEvents.END_CLIENT_TICK.register(this::onTick);
        IsoRts.LOG.info("Isocraft RTS client ready");
    }

    private void onTick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            releaseRotate();
            return;
        }
        while (rtsCameraKey.wasPressed()) {
            rtsCamera.toggle(client);
        }
        rtsCamera.tick(client);

        handleIsoAutoToggle();
        handleMiddleDragRotate(client);
        handleSelectionAndOrders(client);
    }

    /**
     * Synthesise a single tap of Isocraft's toggle key so the game starts in isometric view.
     * ponytail: a tap, not a state query - we can't read Isocraft's internal enabled flag without
     * coupling to it. If it ever ships a "default on" setting, delete this.
     */
    private void handleIsoAutoToggle() {
        if (isoToggleCountdown > 0) {
            isoToggleCountdown--;
            if (isoToggleCountdown == 0) {
                KeyBinding.setKeyPressed(isoKey, true);
                KeyBinding.onKeyPressed(isoKey);
                isoToggleRelease = 2;
                isoToggleCountdown = -1;
                IsoRts.LOG.info("auto-enabled isometric view");
            }
        }
        if (isoToggleRelease > 0) {
            isoToggleRelease--;
            if (isoToggleRelease == 0) {
                KeyBinding.setKeyPressed(isoKey, false);
                isoToggleRelease = -1;
            }
        }
    }

    /**
     * Middle-drag camera rotation.
     * <p>
     * Isocraft binds rotation to hold-keys because in isometric mode the cursor is already
     * consumed by aiming and click-to-move. Rather than mixin into its camera, we synthesise
     * the key state its existing binding reads - so it keeps working if that mod updates.
     * ponytail: no acceleration curve, no smoothing. Add if it feels wrong in the hand.
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

        if (!middleWasDown) {          // drag just started - anchor, don't rotate yet
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
     * Left-click a unit to select it, right-click ground to order it there.
     * ponytail: uses the vanilla crosshair pick, which follows Isocraft's aim. Proper
     * cursor unprojection (and box-select) is the upgrade once the loop is proven.
     */
    private void handleSelectionAndOrders(MinecraftClient client) {
        HitResult hit = client.crosshairTarget;
        if (hit == null) {
            return;
        }

        if (client.options.attackKey.wasPressed()) {
            if (hit.getType() == HitResult.Type.ENTITY) {
                Entity entity = ((EntityHitResult) hit).getEntity();
                if (IsoRts.isUnit(entity)) {
                    selected = entity;
                    say(client, "selected unit " + entity.getId());
                }
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

    private static void say(MinecraftClient client, String msg) {
        if (client.player != null) {
            client.player.sendMessage(Text.literal("[rts] " + msg), true);
        }
    }
}
