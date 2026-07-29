# Known issues

Deferred deliberately to get the world and faction simulation built first. Each entry records
what was observed and what is actually suspected, so nobody re-derives it later.

## 1. First-person to isometric transition stutters

**Status:** unresolved, cause not confirmed.

Reported repeatedly across three different `transitionHalfLife` values (0.22, 0.06, 0.12), which
strongly suggests the value is not the variable. Two attempted fixes so far:

- Tuned `camera.transitionHalfLife` / `smoothingHalfLife` in `isocraft.json` - no effect.
- Changed how Isocraft's toggle is synthesised: previously the key was marked held for 2 ticks
  *and* the press counter bumped. If Isocraft polls `isPressed()` rather than draining
  `wasPressed()`, that hold fires the toggle 2-3 times and the view flips on/off/on mid
  transition. Now only the counter is bumped. Effect unconfirmed.

**Next step:** if it still stutters, the transition is inside Isocraft's own camera code and no
config value here will fix it. Determine whether it is a frame-time hitch (a freeze partway
through - likely shader or chunk work) or smooth-but-juddery motion (an interpolation bug). Those
have different causes and only the former is worth chasing on our side.

## 2. RTS camera feel unverified

**Status:** rewritten, not yet confirmed by eye.

Three rounds of rework. Confirmed-real defects found and fixed:

- `applyCameraTransform()` set `lastX/Y/Z` to the *new* position each tick, destroying frame
  interpolation - the camera moved in 20 visible steps per second regardless of framerate.
- The focus point tracked terrain height every tick, so panning over a hill lifted the camera.
  Height is now fixed on entry and changed only by the wheel.
- Controls were wrong: middle-drag panned and the angle was locked. Now right-drag/WASD pan,
  middle-drag looks, wheel zooms.

Still unverified: whether the free cursor actually appears, and whether pan speed, zoom range and
look sensitivity feel right. All three are single-constant changes in `RtsCameraMode`.

## 3. Selection is a single unit, no box select

`CursorPick` raycasts through the cursor, but only one unit can be selected at a time. Drag-box
selection over multiple units is not implemented.

## 4. Harmless log warning

`Error while parsing the block ID map entry for "block.10104"` appears on every boot. Predates the
faction work and has no observed effect.
