#!/usr/bin/env python3
"""Build the Create workshop, in one reproducible pass.

    python build_factory.py                 # build at the default site
    python build_factory.py --x 8 --y 69 --z 26

Everything here was established by experiment against the running server rather than from memory,
and two findings shaped the whole layout:

  * A millstone only accepts rotation from BELOW or from a vertical cogwheel BESIDE it. Not from
    above, and not from a horizontal shaft. Driving it from the side is what frees the blocks above
    and below for item logistics.
  * Vanilla hoppers work in both directions - they insert into a millstone (from above or the side)
    and pull from its output inventory (from below). That removes any need for belts, which cannot
    be placed with /setblock at all: a belt forms by interaction between two shafts, so a placed
    belt block is simply inert.

Hence every line is a vertical column: input chest, hopper, millstone, hopper, chest, with one motor
and a stack of cogwheels alongside driving all of that column's millstones.
"""
import argparse
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))
from rcon import Rcon


def fill(x1, y1, z1, x2, y2, z2, block, mode=""):
    return f"fill {x1} {y1} {z1} {x2} {y2} {z2} {block}{(' ' + mode) if mode else ''}"


def set_(x, y, z, block):
    return f"setblock {x} {y} {z} {block}"


def chest_of(x, y, z, item, stacks=12):
    items = ",".join(
        f'{{Slot:{i}b,id:"{item}",count:64}}' for i in range(stacks))
    return f"data merge block {x} {y} {z} {{Items:[{items}]}}"


def mill_column(x, y, z, cx, stages, feed, label):
    """One production column.

    y is the floor. The column runs upward: output chest at y+1, then hopper/millstone pairs, and
    the input chest on top. cx is the x of the drive stack beside it.
    """
    cmds = []
    height = y + 1
    cmds.append(set_(x, height, z, "minecraft:chest"))              # output
    # Drive stack: one motor at the bottom, cogwheels level with each millstone, shaft between.
    cmds.append(set_(cx, height, z, "create:creative_motor[facing=up]{ScrollValue:128}"))
    level = height + 1
    for stage in range(stages):
        cmds.append(set_(x, level, z, "minecraft:hopper[facing=down]"))
        cmds.append(set_(cx, level, z, "create:shaft[axis=y]"))
        level += 1
        cmds.append(set_(x, level, z, "create:millstone"))
        cmds.append(set_(cx, level, z, "create:cogwheel[axis=y]"))
        level += 1
    cmds.append(set_(x, level, z, "minecraft:hopper[facing=down]"))
    cmds.append(set_(cx, level, z, "create:andesite_casing"))
    level += 1
    cmds.append(set_(x, level, z, "minecraft:chest"))               # input
    cmds.append(chest_of(x, level, z, feed))
    cmds.append(set_(cx, level, z, "minecraft:lantern"))
    # A sign on the output chest, so the plant explains itself in-world.
    cmds.append(set_(x, height, z + 1,
                     'minecraft:oak_wall_sign[facing=south]'
                     '{front_text:{messages:[\'{"text":"%s"}\',\'{"text":"OUTPUT"}\',\'{"text":""}\',\'{"text":""}\']}}'
                     % label))
    return cmds


def build(x0, y0, z0):
    """x0,z0 is the south-west corner of the shell; y0 is the floor."""
    x1, z1 = x0 + 20, z0 + 12
    cmds = []

    # --- site ---------------------------------------------------------------
    cmds.append(fill(x0 - 3, y0 - 4, z0 - 3, x1 + 3, y0 - 1, z1 + 3, "minecraft:stone"))
    cmds.append(fill(x0 - 3, y0, z0 - 3, x1 + 3, y0, z1 + 3, "minecraft:polished_andesite"))
    cmds.append(fill(x0 - 3, y0 + 1, z0 - 3, x1 + 3, y0 + 16, z1 + 3, "minecraft:air"))

    # --- shell --------------------------------------------------------------
    cmds.append(fill(x0, y0 + 1, z0, x1, y0 + 10, z1, "minecraft:bricks", "outline"))
    # Iron pillars on the corners and midpoints - the industrial look comes from the frame.
    for px in (x0, x0 + 10, x1):
        for pz in (z0, z1):
            cmds.append(fill(px, y0 + 1, pz, px, y0 + 10, pz, "create:industrial_iron_block"))
    for pz in (z0 + 6,):
        for px in (x0, x1):
            cmds.append(fill(px, y0 + 1, pz, px, y0 + 10, pz, "create:industrial_iron_block"))
    # Window bands on both long walls.
    for zz in (z0, z1):
        cmds.append(fill(x0 + 2, y0 + 4, zz, x0 + 8, y0 + 6, zz, "minecraft:glass_pane"))
        cmds.append(fill(x0 + 12, y0 + 4, zz, x1 - 2, y0 + 6, zz, "minecraft:glass_pane"))
    # Roof: copper, with an iron rim.
    cmds.append(fill(x0 - 1, y0 + 11, z0 - 1, x1 + 1, y0 + 11, z1 + 1, "minecraft:copper_block"))
    cmds.append(fill(x0 - 1, y0 + 11, z0 - 1, x1 + 1, y0 + 11, z1 + 1,
                     "create:industrial_iron_block", "outline"))
    # Doorway in the south wall.
    cmds.append(fill(x0 + 9, y0 + 1, z1, x0 + 10, y0 + 3, z1, "minecraft:air"))

    # --- chimney ------------------------------------------------------------
    stack_x, stack_z = x1 - 3, z0 + 3
    cmds.append(fill(stack_x, y0 + 11, stack_z, stack_x + 1, y0 + 18, stack_z + 1, "minecraft:bricks"))
    cmds.append(fill(stack_x, y0 + 19, stack_z, stack_x + 1, y0 + 19, stack_z + 1,
                     "create:industrial_iron_block"))
    cmds.append(fill(stack_x, y0 + 12, stack_z, stack_x + 1, y0 + 18, stack_z + 1, "minecraft:air"))
    cmds.append(fill(stack_x, y0 + 12, stack_z, stack_x + 1, y0 + 12, stack_z + 1,
                     "minecraft:campfire[lit=true]"))

    # --- production ---------------------------------------------------------
    # Two-stage crushing line: cobblestone -> gravel -> flint.
    cmds += mill_column(x0 + 4, y0, z0 + 6, x0 + 5, 2, "minecraft:cobblestone", "FLINT LINE")
    # Single-stage mills.
    cmds += mill_column(x0 + 9, y0, z0 + 6, x0 + 10, 1, "minecraft:wheat", "FLOUR MILL")
    cmds += mill_column(x0 + 14, y0, z0 + 6, x0 + 15, 1, "minecraft:gravel", "FLINT MILL")

    # --- storage row and lighting ------------------------------------------
    for i in range(6):
        cmds.append(set_(x0 + 2 + i * 3, y0 + 1, z1 - 2, "minecraft:barrel[facing=up]"))
    for lx in (x0 + 4, x0 + 10, x0 + 16):
        cmds.append(set_(lx, y0 + 10, z0 + 2, "minecraft:lantern[hanging=true]"))
        cmds.append(set_(lx, y0 + 10, z1 - 2, "minecraft:lantern[hanging=true]"))

    # --- water wheels outside ----------------------------------------------
    # A real channel with flowing water. The wheels are wired into nothing - the motors inside do the
    # work - but a mill with no wheel looks wrong, and FlowScore in their NBT says whether they turn.
    wx = x0 - 3
    cmds.append(fill(wx - 4, y0 - 1, z0, wx, y0 - 1, z1, "minecraft:stone_bricks"))
    cmds.append(fill(wx - 3, y0, z0, wx - 1, y0, z1, "minecraft:air"))
    cmds.append(fill(wx - 3, y0, z0, wx - 3, y0, z1, "minecraft:water"))
    for i, wz in enumerate((z0 + 3, z0 + 6, z0 + 9)):
        cmds.append(set_(wx - 1, y0, wz, f"create:water_wheel[axis=z]"))

    cmds.append(f"setworldspawn {x0 + 10} {y0 + 1} {z1 + 6} 180")
    return cmds


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--x", type=int, default=8)
    ap.add_argument("--y", type=int, default=69, help="floor level")
    ap.add_argument("--z", type=int, default=26)
    ap.add_argument("--host", default="127.0.0.1")
    ap.add_argument("--port", type=int, default=25575)
    ap.add_argument("--password", default="isorts")
    args = ap.parse_args()

    cmds = build(args.x, args.y, args.z)
    rcon = Rcon(args.host, args.port, args.password)
    failures = 0
    try:
        for command in cmds:
            reply = rcon.command(command)
            if reply and ("Could not" in reply or "Unknown" in reply or "Incorrect" in reply
                          or "Invalid" in reply or "Expected" in reply):
                failures += 1
                print(f"FAILED: {command[:110]}\n   -> {reply[:200]}")
    finally:
        rcon.close()
    print(f"sent {len(cmds)} commands, {failures} failed")


if __name__ == "__main__":
    main()
