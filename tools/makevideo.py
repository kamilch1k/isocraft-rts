"""Staple the frames from the in-game recorder into an mp4.

    python makevideo.py battle-square --fps 10

Frames come from the mod's `record <name> <frames> <every>` control command and land in the
instance's screenshots/ directory as <name>-0000.png upward.

ponytail: OpenCV rather than ffmpeg, because OpenCV is already installed here and ffmpeg is not.
No frame interpolation, no audio, no scaling - the frames are already the right size.
"""
import argparse
import pathlib
import sys

import cv2


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("name", help="frame prefix, e.g. battle-square")
    ap.add_argument("--shots", default=r"C:\cc\isocraft-real\screenshots")
    ap.add_argument("--out", default=r"C:\cc\isocraft-rts\media")
    # 20 is the ceiling, not a preference: frames are captured once per game tick and Minecraft
    # ticks twenty times a second. Encoding faster than that only duplicates frames.
    ap.add_argument("--fps", type=int, default=20)
    ap.add_argument("--keep-frames", action="store_true")
    # Windows display scaling means a "1920x1080" window actually has a 2400x1350 framebuffer, so
    # frames arrive above 1080p. Downscaling to exactly 1080p also cleans up the aliasing.
    ap.add_argument("--scale-width", type=int, default=1920)
    args = ap.parse_args()

    shots = pathlib.Path(args.shots)
    frames = sorted(shots.glob(f"{args.name}-*.png"))
    if not frames:
        sys.exit(f"no frames matching {args.name}-*.png in {shots}")

    first = cv2.imread(str(frames[0]))
    if first is None:
        sys.exit(f"could not read {frames[0]}")
    height, width = first.shape[:2]
    if args.scale_width and width > args.scale_width:
        height = round(height * args.scale_width / width)
        height -= height % 2                  # encoders want even dimensions
        width = args.scale_width

    out_dir = pathlib.Path(args.out)
    out_dir.mkdir(parents=True, exist_ok=True)
    out_path = out_dir / f"{args.name}.mp4"

    # H.264 would compress better, but this OpenCV build has no working H.264 encoder (avc1 opens
    # and then writes a header-only file), so mp4v it is - which is why resolution carries the
    # quality here.
    writer = cv2.VideoWriter(str(out_path), cv2.VideoWriter_fourcc(*"mp4v"),
                             args.fps, (width, height))
    if not writer.isOpened():
        sys.exit(f"could not open a writer for {out_path}")
    written = 0
    for frame_path in frames:
        image = cv2.imread(str(frame_path))
        if image is None:
            continue
        if image.shape[:2] != (height, width):
            image = cv2.resize(image, (width, height))
        writer.write(image)
        written += 1
    writer.release()

    if not args.keep_frames:
        for frame_path in frames:
            frame_path.unlink()

    size_mb = out_path.stat().st_size / (1024 * 1024)
    print(f"{out_path}  ({written} frames, {width}x{height}, {args.fps} fps, {size_mb:.1f} MB)")


if __name__ == "__main__":
    main()
