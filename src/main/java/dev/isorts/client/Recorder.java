package dev.isorts.client;

import dev.isorts.IsoRts;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotRecorder;

/**
 * Captures a run of numbered screenshots, which {@code tools/makevideo.py} turns into a video.
 * <p>
 * ponytail: frames, not video. Encoding in-process would mean shipping an encoder and bridging it
 * to the framebuffer; the game already knows how to write a PNG, and the machine already has
 * OpenCV to staple them together afterwards. The one real constraint is pace - a PNG encode per
 * frame is expensive, so every second tick (10 fps) is the honest ceiling here.
 */
final class Recorder {

    private int framesLeft;
    private int interval = 2;
    private int countdown;
    private int index;
    private String prefix = "rec";

    boolean isRecording() {
        return framesLeft > 0;
    }

    void start(String name, int frames, int everyNTicks) {
        prefix = name;
        framesLeft = frames;
        interval = Math.max(1, everyNTicks);
        countdown = 0;
        index = 0;
        IsoRts.LOG.info("[ctl] recording {} frame(s) every {} tick(s) as {}", frames, interval, name);
    }

    void tick(MinecraftClient client) {
        if (framesLeft <= 0) {
            return;
        }
        if (--countdown > 0) {
            return;
        }
        countdown = interval;
        framesLeft--;

        String file = String.format("%s-%04d.png", prefix, index++);
        ScreenshotRecorder.saveScreenshot(client.runDirectory, file, client.getFramebuffer(), 1,
                text -> { });
        if (framesLeft == 0) {
            IsoRts.LOG.info("[ctl] recording finished: {} frames as {}-*.png", index, prefix);
        }
    }
}
