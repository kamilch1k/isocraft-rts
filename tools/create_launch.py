#!/usr/bin/env python3
"""Launch the NeoForge/Create instance and connect straight to the workshop server.

  python create_launch.py                      # join 127.0.0.1:25565
  python create_launch.py --menu               # stop at the main menu

Two things here are worked around rather than chosen:

portablemc's CLI cannot do this. Its -s/--server flags emit Minecraft's pre-1.20 --server/--port
arguments, which modern versions ignore outright - the client loads and sits on the main menu having
never attempted a connection. The modern equivalent is --quickPlayMultiplayer, and since the CLI
silently drops arguments it does not recognise, it has to be appended through the Python API.

The version is a locally installed id rather than portablemc's "neoforge:1.21.1" resolver, because
that resolver crashes on this version (KeyError: 'ROOT') while running the installer's processors.
NeoForge's own installer writes the version into versions/ correctly, and an already-installed id
needs no post-processing.
"""
import argparse
from pathlib import Path

from portablemc.standard import Context, Version


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--dir", default=r"C:\cc\create-real", help="instance dir (mods/saves/versions)")
    ap.add_argument("--version", default="neoforge-21.1.244", help="installed version id")
    ap.add_argument("--server", default="127.0.0.1:25565", help="host:port to join")
    ap.add_argument("--menu", action="store_true", help="don't auto-join, stop at the menu")
    ap.add_argument("--size", default="819x461", help="window size, WxH")
    ap.add_argument("--user", default="Falkner")
    args = ap.parse_args()

    instance = Path(args.dir)
    width, height = args.size.split("x")

    # Both dirs point at the instance: the NeoForge installer put versions and libraries here, and
    # mods/ and saves/ live here too, so the whole thing is self-contained.
    ctx = Context(instance, instance)
    version = Version(args.version, context=ctx)
    # ponytail: offline auth by design - no token, so no credentials to hold. The server runs with
    # online-mode=false for the same reason.
    version.set_auth_offline(args.user, None)

    env = version.install()
    env.game_args += ["--width", width, "--height", height]
    if not args.menu:
        env.game_args += ["--quickPlayMultiplayer", args.server]
        print(f"joining {args.server}")

    print(f"{args.version} @ {instance} at {width}x{height}")
    env.run()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
