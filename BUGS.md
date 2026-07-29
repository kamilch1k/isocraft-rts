# Known issues

Deferred deliberately to get the world and faction simulation built first. Each entry records
what was observed and what is actually suspected, so nobody re-derives it later.

## 1. First-person to isometric transition stutters

**Status:** likely fixed, awaiting confirmation by eye.

Reported repeatedly across three different `transitionHalfLife` values (0.22, 0.06, 0.12), which
strongly suggested the value was never the variable. The real suspect was found by reading
Isocraft's bytecode: our mod used to *toggle* Isocraft blindly by synthesising presses of its
keybinding. If its state and ours ever fell out of parity (a stray press of its real binding, a
toggle landing mid-transition), every toggle after that did the opposite of what we meant - the
view flipped on/off/on and read as a stutter. `IsocraftBridge` now calls its
`CameraController.setEnabled()` directly by reflection and *enforces* the state every tick, so
parity cannot drift. If V still stutters, the remaining motion is inside Isocraft's own
transition code.

## 2. RTS camera feel

**Status:** transform verified correct by screenshot; interactive feel still needs a human hand.

Confirmed-real defects found and fixed across the rework:

- `applyCameraTransform()` set `lastX/Y/Z` to the *new* position each tick, destroying frame
  interpolation - the camera moved in 20 visible steps per second regardless of framerate.
- The camera armour stand used a client-assigned entity id, which collides with ids the server
  hands to freshly spawned units - when the server's entity arrived, it replaced the camera and
  the view teleported to wherever that unit was. Id parked at `Integer.MAX_VALUE - 4242`.
- Entry height was 110 blocks: the whole map dissolved into Complementary's atmospheric fog and
  looked like a broken void. Now 40, verified crisp by screenshot.
- Ground basis was inverted (right vector was actually left); wheel moved along Y instead of
  dollying along the view axis; middle-drag pitch never registered because `MinecraftClient#mouse`
  is unreliable while the pointer is grabbed (now raw `glfwGetCursorPos`).

Camera poses set through the control file land exactly where commanded (verified in the
on-screen readout and screenshots). WASD/drag/wheel go through the same transform; what remains
unverifiable from the terminal is how the sensitivities feel.

## 3. Selection is a single unit, no box select

`CursorPick` raycasts through the cursor, but only one unit can be selected at a time. Drag-box
selection over multiple units is not implemented.

## 4. Harmless log warning

`Error while parsing the block ID map entry for "block.10104"` appears on every boot. Predates the
faction work and has no observed effect.

## Fixed and verified (kept for the record)

- **Player and starting units buried under the map** - the scenario raised islands on top of the
  flat-preset spawn *after* units were seeded around the player. Order flipped: scenario first
  (which teleports the player onto the new centre island), then seeding. Verified by log and
  screenshot: player and 6 units on the surface at 0/67/0.
- **Buildings stacked on top of each other** - `groundY` used the WORLD_SURFACE heightmap, which
  includes existing houses and trees, so each new house was placed on the roof of the last one;
  ring spacing of 11 also jammed houses into the castle wall. `terrainY` scans down past anything
  that is not terrain; slots are 8 per ring at 18/32 blocks with an occupancy check. Verified by
  screenshot of both settlements.
- **Islands perfectly round** - radius now modulated by two sine waves around the perimeter plus
  low rolling surface bumps, deterministic per island. Verified by screenshot.
- **Player slain by own iron golem while AFK** - vanilla golems that are not "player-created"
  retaliate; every golem we spawn is now flagged `setPlayerCreated(true)`.
- **Game paused whenever the window lost focus** (which is always, on Desktop 2) - the whole
  self-playing simulation was frozen while unwatched. `pauseOnLostFocus:false` in options.txt.
