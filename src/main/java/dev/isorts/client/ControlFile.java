package dev.isorts.client;

import dev.isorts.IsoRts;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;
import net.minecraft.util.math.BlockPos;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Terminal remote control, so the game can be driven and - crucially - SEEN without any
 * computer-use tooling. Drop lines into {@code <gameDir>/isorts-ctl.txt}; they are executed on
 * the client thread and the file is deleted.
 * <pre>
 *   snap                       screenshot -> screenshots/isorts-snap.png (overwrites)
 *   state fp|iso|rts           jump straight to a control state
 *   cam x y z yaw pitch        place the RTS camera exactly (requires rts state)
 *   respawn                    click through the death screen
 *   probe                      log cursor, FOV and where each unit projects on screen
 *   selectall                  select every friendly unit nearby
 *   order x y z                order the selection to a block
 *   watch                      aim the free camera at the thickest of the fighting
 *   record name frames every   capture numbered PNGs -> tools/makevideo.py
 *   tp x y z                   move the player (the armies muster around them)
 * </pre>
 * ponytail: a polled file, not a socket or RCON. One writer, one reader, five-tick latency is
 * fine for taking screenshots; a protocol server would be pure ceremony.
 */
final class ControlFile {

    private static final String NAME = "isorts-ctl.txt";
    private static final int POLL_TICKS = 5;

    private final IsoRtsClient owner;
    private int cooldown;

    ControlFile(IsoRtsClient owner) {
        this.owner = owner;
    }

    void tick(MinecraftClient client) {
        if (++cooldown < POLL_TICKS) {
            return;
        }
        cooldown = 0;

        Path ctl = client.runDirectory.toPath().resolve(NAME);
        if (!Files.exists(ctl)) {
            return;
        }
        List<String> lines;
        try {
            lines = Files.readAllLines(ctl);
            Files.delete(ctl);
        } catch (IOException e) {
            IsoRts.LOG.warn("could not read control file", e);
            return;
        }
        for (String line : lines) {
            try {
                execute(client, line.trim());
            } catch (Exception e) {
                IsoRts.LOG.warn("control command failed: {}", line, e);
            }
        }
    }

    private void execute(MinecraftClient client, String line) {
        if (line.isEmpty() || line.startsWith("#")) {
            return;
        }
        String[] a = line.split("\\s+");
        switch (a[0]) {
            case "snap" -> {
                ScreenshotRecorder.saveScreenshot(client.runDirectory, "isorts-snap.png",
                        client.getFramebuffer(), 1,
                        text -> IsoRts.LOG.info("[ctl] {}", text.getString()));
                IsoRts.LOG.info("[ctl] snap requested");
            }
            case "state" -> owner.setState(a[1]);
            case "probe" -> owner.probe();
            case "watch" -> owner.watchBattle();
            case "record" -> owner.record(a[1], Integer.parseInt(a[2]), Integer.parseInt(a[3]));
            case "tp" -> owner.teleport(new BlockPos(
                    Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])));
            // Anything the /rts command can already do, without inventing a packet for each.
            case "cmd" -> {
                if (client.player != null) {
                    // sendChatCommand wants the command WITHOUT a leading slash; passing one
                    // produces "//rts clear", which silently does nothing.
                    String command = line.substring(4).trim();
                    client.player.networkHandler.sendChatCommand(
                            command.startsWith("/") ? command.substring(1) : command);
                }
            }
            case "selectall" -> owner.selectAllFromTerminal();
            case "order" -> owner.orderTo(new BlockPos(
                    Integer.parseInt(a[1]), Integer.parseInt(a[2]), Integer.parseInt(a[3])));
            case "respawn" -> {
                if (client.player != null && client.player.isDead()) {
                    client.player.requestRespawn();
                }
            }
            case "cam" -> owner.rtsCamera().setPose(
                    Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]),
                    Float.parseFloat(a[4]), Float.parseFloat(a[5]));
            default -> IsoRts.LOG.warn("[ctl] unknown command: {}", line);
        }
    }
}
