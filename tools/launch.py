#!/usr/bin/env python3
"""Launch a modded Minecraft instance, optionally straight into a world.

portablemc's CLI drops unknown args, so quickPlay has to go through its Python API.

  python launch.py                                  # 26.1.2 instance -> "New World"
  python launch.py --world "Some World"
  python launch.py --dir C:\\cc\\isocraft-real --mc 1.21.11    # no world -> main menu
"""
import argparse
import os
from pathlib import Path

from portablemc.fabric import FabricVersion
from portablemc.standard import Context

MAIN_DIR = Path(os.environ["APPDATA"]) / ".minecraft"   # shared vanilla libraries/assets


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=r"C:\cc\isocraft", help="instance work dir (mods/config/saves)")
    ap.add_argument("--mc", default="26.1.2", help="Minecraft version")
    ap.add_argument("--world", default="New World", help="world to boot into")
    # ponytail: a flag, not --world "", because PowerShell drops empty string args
    ap.add_argument("--menu", action="store_true", help="boot to the main menu instead of a world")
    ap.add_argument("--size", default="854x480", help="window size, WxH")
    # Offline mode picks one of Minecraft's 18 default skins by hashing the account UUID, which is
    # itself derived from this name - so the name IS the skin. "Falkner" lands on wide/kai, a
    # deliberate choice: the default for the old name was a Steve-alike.
    ap.add_argument("--user", default="Falkner")
    args = ap.parse_args()

    work_dir = Path(args.dir)
    width, height = args.size.split("x")

    ctx = Context(MAIN_DIR, work_dir)
    version = FabricVersion.with_fabric(args.mc, context=ctx)
    # ponytail: offline auth by design - no token, so no credentials to hold.
    # Cost is no multiplayer/Realms; singleplayer is unaffected.
    version.set_auth_offline(args.user, None)

    env = version.install()
    env.game_args += ["--width", width, "--height", height]

    if args.world and not args.menu:
        if not (work_dir / "saves" / args.world).is_dir():
            saves = work_dir / "saves"
            have = [p.name for p in saves.iterdir() if p.is_dir()] if saves.is_dir() else []
            print(f"no such world: {args.world!r}; have: {have} -- booting to the menu instead")
        else:
            env.game_args += ["--quickPlaySingleplayer", args.world]
            print(f"launching into {args.world!r}")

    print(f"{args.mc} @ {work_dir} at {width}x{height}")
    env.run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
