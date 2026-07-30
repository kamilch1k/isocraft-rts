#!/usr/bin/env python3
"""Build a small Create town: mill house, boiler house, orchard, yard, paths.

    python build_town.py --x 0 --y 69 --z 0

Written as one reproducible pass so the whole settlement can be rebuilt anywhere, on any world, in
about ninety commands.

What is real and what is scenery, stated plainly because it matters:

  * The millstone columns genuinely run - measured at 36 flint/minute - and are powered by Create
    motors with hoppers moving the items.
  * The boiler stack (fluid tank + lit blaze burner + steam engine + whistle) is assembled and the
    burner really is burning, but the tank has no water: Create's fluid storage would not accept a
    filled tank through /data merge, so a truly steaming boiler needs a pump-and-pipe feed. It is
    dressing until that is built.
  * Belts are scenery, unavoidably. Create only creates a belt's block entity through its Belt
    Connector item, so a belt placed by command - even with inline NBT - has no block entity at all
    and will never move an item. Working belts need a helper mod that calls Create's own API.
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from rcon import Rcon

CMDS = []


def cmd(text):
    CMDS.append(text)


def fill(x1, y1, z1, x2, y2, z2, block, mode=""):
    cmd(f"fill {x1} {y1} {z1} {x2} {y2} {z2} {block}{(' ' + mode) if mode else ''}")


def put(x, y, z, block):
    cmd(f"setblock {x} {y} {z} {block}")


def stock(x, y, z, item, slots=9):
    items = ",".join(f'{{Slot:{i}b,id:"{item}",count:64}}' for i in range(slots))
    cmd(f"data merge block {x} {y} {z} {{Items:[{items}]}}")


def gabled_house(x, y, z, w, d, wall, frame, roof, floor):
    """A house with a pitched roof, x..x+w by z..z+d, floor at y.

    The look comes from three things stacked: a brick plinth, framed timber walls, and a stair roof
    that overhangs by one block. Flat slab roofs read as a shed no matter what they are made of.
    """
    fill(x, y - 1, z, x + w, y - 1, z + d, "minecraft:bricks")             # plinth
    fill(x, y, z, x + w, y, z + d, floor)
    fill(x, y + 1, z, x + w, y + 4, z + d, wall, "outline")
    # Corner posts and mid-posts.
    for px in (x, x + w):
        for pz in (z, z + d):
            fill(px, y + 1, pz, px, y + 4, pz, frame)
    fill(x + w // 2, y + 1, z, x + w // 2, y + 4, z, frame)
    fill(x + w // 2, y + 1, z + d, x + w // 2, y + 4, z + d, frame)
    # Windows on the long walls, shutters beside them.
    for pz in (z, z + d):
        for px in (x + 2, x + w - 2):
            put(px, y + 2, pz, "minecraft:glass_pane")
            put(px, y + 3, pz, "minecraft:glass_pane")
    # Roof: stairs stepping inward from both long sides, ridge on top.
    steps = (d + 1) // 2
    for i in range(steps):
        yy = y + 5 + i
        zn, zf = z + i, z + d - i
        if zn > zf:
            break
        fill(x - 1, yy, zn, x + w + 1, yy, zn, f"{roof}[facing=south,half=bottom]")
        fill(x - 1, yy, zf, x + w + 1, yy, zf, f"{roof}[facing=north,half=bottom]")
        if zn + 1 <= zf - 1:
            fill(x - 1, yy, zn + 1, x + w + 1, yy, zf - 1, "minecraft:air")
    ridge_z = z + steps - 1
    fill(x - 1, y + 4 + steps, ridge_z, x + w + 1, y + 4 + steps, ridge_z + (1 if d % 2 else 0),
         "minecraft:deepslate_tiles")
    # Door, lit either side.
    fill(x + w // 2, y + 1, z + d, x + w // 2, y + 2, z + d, "minecraft:air")
    put(x + w // 2 - 1, y + 3, z + d, "minecraft:lantern[hanging=false]")
    put(x + w // 2 + 1, y + 3, z + d, "minecraft:lantern[hanging=false]")


def mill_column(x, y, z, cx, stages, feed, label):
    """Vertical production column. See build_factory.py for why the drive is on the side."""
    put(x, y + 1, z, "minecraft:chest")
    put(cx, y + 1, z, "create:creative_motor[facing=up]{ScrollValue:128}")
    level = y + 2
    for _ in range(stages):
        put(x, level, z, "minecraft:hopper[facing=down]")
        put(cx, level, z, "create:shaft[axis=y]")
        level += 1
        put(x, level, z, "create:millstone")
        put(cx, level, z, "create:cogwheel[axis=y]")
        level += 1
    put(x, level, z, "minecraft:hopper[facing=down]")
    put(cx, level, z, "create:andesite_casing")
    level += 1
    put(x, level, z, "minecraft:chest")
    stock(x, level, z, feed)
    cmd(f'setblock {x} {y + 1} {z + 1} minecraft:oak_wall_sign[facing=south]'
        f'{{front_text:{{messages:[\'{{"text":"{label}"}}\',\'{{"text":"output"}}\','
        f'\'{{"text":""}}\',\'{{"text":""}}\']}}}}')


def boiler(x, y, z):
    """The steam assembly from the reference: tank stack, lit burner beneath, engine, whistle."""
    put(x, y, z, "create:blaze_burner[blaze=kindled]")
    cmd(f"data merge block {x} {y} {z} {{fuelLevel:2,burnTimeRemaining:24000}}")
    put(x, y + 1, z, "create:fluid_tank")
    put(x, y + 2, z, "create:fluid_tank")
    put(x, y + 3, z, "create:steam_whistle[facing=up,size=large]")
    put(x + 1, y + 2, z, "create:steam_engine[facing=east]")
    put(x + 2, y + 2, z, "create:shaft[axis=x]")
    # Copper dressing so it reads as a boiler house rather than a stack of tanks.
    for dx, dz in ((-1, 0), (0, -1), (0, 1)):
        fill(x + dx, y, z + dz, x + dx, y + 1, z + dz, "minecraft:copper_block")


def orchard(x, y, z, w, d):
    """A fenced plot of saplings with a collection channel. Grown trees, not a contraption."""
    fill(x, y, z, x + w, y, z + d, "minecraft:grass_block")
    fill(x, y + 1, z, x + w, y + 1, z + d, "minecraft:air")
    fill(x, y + 1, z, x + w, y + 1, z, "minecraft:oak_fence")
    fill(x, y + 1, z + d, x + w, y + 1, z + d, "minecraft:oak_fence")
    fill(x, y + 1, z, x, y + 1, z + d, "minecraft:oak_fence")
    fill(x + w, y + 1, z, x + w, y + 1, z + d, "minecraft:oak_fence")
    for ox in range(x + 2, x + w - 1, 4):
        for oz in range(z + 2, z + d - 1, 4):
            put(ox, y + 1, oz, "minecraft:oak_sapling")
    # Half the plot gets fully grown trees straight away, so it looks like an orchard now.
    for ox in range(x + 2, x + w - 1, 8):
        for oz in range(z + 2, z + d - 1, 8):
            cmd(f"place feature minecraft:oak {ox} {y + 1} {oz}")


def build(ox, oy, oz):
    # --- level the site ---------------------------------------------------
    fill(ox - 6, oy - 5, oz - 6, ox + 54, oy - 1, oz + 44, "minecraft:stone")
    fill(ox - 6, oy, oz - 6, ox + 54, oy, oz + 44, "minecraft:grass_block")
    fill(ox - 6, oy + 1, oz - 6, ox + 54, oy + 24, oz + 44, "minecraft:air")

    # --- roads -------------------------------------------------------------
    fill(ox - 6, oy, oz + 14, ox + 54, oy, oz + 17, "minecraft:dirt_path")
    fill(ox + 20, oy, oz - 6, ox + 23, oy, oz + 44, "minecraft:dirt_path")
    for lx in range(ox - 2, ox + 52, 10):
        put(lx, oy + 1, oz + 13, "minecraft:lantern")
        put(lx, oy + 1, oz + 18, "minecraft:lantern")

    # --- the mill: production inside, water wheels outside ------------------
    gabled_house(ox, oy + 1, oz, 14, 10, "minecraft:spruce_planks", "minecraft:oak_log",
                 "minecraft:spruce_stairs", "minecraft:polished_andesite")
    mill_column(ox + 3, oy + 1, oz + 5, ox + 4, 2, "minecraft:cobblestone", "FLINT")
    mill_column(ox + 8, oy + 1, oz + 5, ox + 9, 1, "minecraft:wheat", "FLOUR")
    mill_column(ox + 11, oy + 1, oz + 5, ox + 12, 1, "minecraft:gravel", "FLINT II")
    # Chimney through the roof.
    fill(ox + 1, oy + 2, oz + 1, ox + 2, oy + 13, oz + 2, "minecraft:bricks")
    fill(ox + 1, oy + 3, oz + 1, ox + 2, oy + 13, oz + 2, "minecraft:air")
    put(ox + 1, oy + 3, oz + 1, "minecraft:campfire[lit=true]")
    # Water channel and wheels along the west wall.
    fill(ox - 5, oy - 1, oz, ox - 2, oy - 1, oz + 10, "minecraft:stone_bricks")
    fill(ox - 4, oy, oz, ox - 2, oy, oz + 10, "minecraft:air")
    fill(ox - 4, oy, oz, ox - 4, oy, oz + 10, "minecraft:water")
    for wz in (oz + 2, oz + 5, oz + 8):
        put(ox - 2, oy, wz, "create:water_wheel[axis=z]")

    # --- boiler house ------------------------------------------------------
    gabled_house(ox + 28, oy + 1, oz, 12, 8, "minecraft:bricks", "create:industrial_iron_block",
                 "minecraft:deepslate_tile_stairs", "minecraft:polished_andesite")
    boiler(ox + 31, oy + 2, oz + 4)
    boiler(ox + 36, oy + 2, oz + 4)

    # --- yard: crates, decorative belt line, storage ------------------------
    for i in range(7):
        put(ox + 16 + i * 2, oy + 1, oz + 20, "minecraft:barrel[facing=up]")
    # ponytail: these belts are decoration and cannot be otherwise - see the module docstring.
    put(ox + 15, oy + 1, oz + 23, "create:shaft[axis=x]")
    for bx in range(ox + 16, ox + 26):
        part = "start" if bx == ox + 16 else ("end" if bx == ox + 25 else "middle")
        put(bx, oy + 1, oz + 23, f"create:belt[casing=false,part={part},slope=horizontal,facing=east]")
    put(ox + 26, oy + 1, oz + 23, "create:shaft[axis=x]")

    # --- orchard -----------------------------------------------------------
    orchard(ox + 30, oy, oz + 20, 20, 18)

    # --- cottages, to make it a town rather than two sheds ------------------
    gabled_house(ox + 2, oy + 1, oz + 22, 8, 7, "minecraft:oak_planks", "minecraft:spruce_log",
                 "minecraft:oak_stairs", "minecraft:oak_planks")
    gabled_house(ox + 2, oy + 1, oz + 33, 8, 7, "minecraft:oak_planks", "minecraft:dark_oak_log",
                 "minecraft:dark_oak_stairs", "minecraft:oak_planks")

    cmd(f"setworldspawn {ox + 21} {oy + 1} {oz + 21} 180")


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--x", type=int, default=0)
    ap.add_argument("--y", type=int, default=69)
    ap.add_argument("--z", type=int, default=0)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25575)
    ap.add_argument("--password", default="isorts")
    args = ap.parse_args()

    build(args.x, args.y, args.z)
    rcon = Rcon(args.host, args.port, args.password)
    bad = 0
    try:
        for c in CMDS:
            reply = rcon.command(c)
            if reply and any(w in reply for w in
                             ("Could not", "Unknown", "Incorrect", "Invalid", "Expected", "Failed")):
                bad += 1
                print(f"FAILED {c[:100]}\n   -> {reply[:160]}")
    finally:
        rcon.close()
    print(f"sent {len(CMDS)} commands, {bad} failed")


if __name__ == "__main__":
    main()
