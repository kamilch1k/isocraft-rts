# Isocraft RTS

An RTS layer for Minecraft, built on top of the [Isocraft](https://modrinth.com/mod/isocraft)
isometric-view mod. Fabric, Minecraft 1.21.11.

The goal: an isometric, largely AFK strategy game that plays itself in the background - factions
that build, spawn units and expand - rendered through a real shader stack, in a small borderless
window you can leave running in the corner of a screen.

Everything here is driven from a terminal: no launcher GUI, no world-creation screen, no clicking.

## Status

Working:

- Three control states cycled with one key
- A real RTS camera: focus-point panning, wheel zoom, stepped rotation
- Selectable units with move orders (client picks, server validates and paths)
- `/rts spawn [n]` and an auto-seeded starting squad on world join
- Isometric view auto-enabled on join
- One-command launch: borderless, sized, positioned, on a chosen virtual desktop, straight into a world
- Headless world generation via the vanilla server jar

Not built yet - see [Roadmap](#roadmap).

## The three control states

1. **First person**, controlling the player character
2. **Isometric top-down**, controlling the player character
3. **RTS camera** - detached, *not* controlling the player: pan, zoom, select units, issue commands

All three cycle with a single key, **`V`**. Isocraft's own toggle is moved to a parked key (`F24`)
which our code drives by synthesising its key state, so the two never double-fire. It has to stay
*bound* to something for that to work, which is why it is parked rather than unbound.

State 3 is a camera that orbits a focus point on the ground at a fixed isometric pitch - it is
deliberately not a freecam. The angle never follows the mouse, which leaves the pointer free for
selection. Entering state 3 turns Isocraft's isometric view off first: that view positions the
camera relative to the player and would otherwise fight the detached one.

None of this uses mixins:

- The camera is an invisible armour stand passed to `MinecraftClient#setCameraEntity`.
- Player movement is suppressed by swapping `ClientPlayerEntity#input` for a plain `Input`, whose
  `tick()` never reads the keyboard. Zeroing the field does not work - `KeyboardInput.tick()`
  rewrites it from live key state every tick.
- Wheel zoom chains GLFW's scroll callback and forwards to the previous one when inactive, so the
  hotbar keeps working normally.

Entering and leaving are guarded, and disconnect force-exits, so a failure can never strand the
player unable to move.

## Layout

| path | what |
| --- | --- |
| `src/main/java/dev/isorts/IsoRts.java` | units, `/rts` command, server-side order validation, unit seeding |
| `src/main/java/dev/isorts/MoveOrderPayload.java` | client to server move-order packet |
| `src/main/java/dev/isorts/client/IsoRtsClient.java` | view-state cycling, selection, orders |
| `src/main/java/dev/isorts/client/RtsCameraMode.java` | the RTS camera: pan, zoom, rotate |
| `tools/play.ps1` | launch: borderless, positioned, virtual desktop, into a world |
| `tools/launch.py` | portablemc wrapper - needed because its CLI drops `--quickPlaySingleplayer` |
| `tools/worldgen.ps1` | headless world generation with the vanilla server jar |
| `build.ps1` | build wrapper (see [Toolchain notes](#toolchain-notes)) |

## Build

```powershell
powershell -File build.ps1
```

Output lands in `build/libs/`. Copy the non-`sources` jar into your instance's `mods/`.

## Run

```powershell
powershell -File tools\play.ps1 -World "IsoWorld"
```

Flags: `-Instance`, `-World`, `-DesktopIndex`, `-Width`/`-Height`, `-Bordered`.

Generate a world without touching a GUI:

```powershell
powershell -File tools\worldgen.ps1 -WorldName "IsoWorld"
```

## Controls

| key | action |
| --- | --- |
| `V` | cycle view: first person, isometric, RTS camera |
| `WASD` | pan (RTS camera) / move character (states 1-2) |
| middle-drag | pan the map (RTS camera) / rotate camera (isometric) |
| wheel | zoom (RTS camera) |
| `Z` / `C` | rotate camera |
| `PgUp` / `PgDn` | pitch (isometric) |
| left-click a unit | select |
| right-click ground | move order |
| `N` | Isocraft settings |

## Toolchain notes

Hard-won, all of it non-obvious:

- **Gradle 9.5+ is required.** Loom 1.17.17 fails variant matching on Gradle 8.x.
- **JDK 21**, not 25 - and note that `org.gradle.java.home` in `~/.gradle/gradle.properties`
  *outranks* project settings and `JAVA_HOME`. `build.ps1` overrides it with `-D` rather than
  editing a global file that may belong to another project.
- **Sodium is pinned.** Sodium 0.8.13 declares it breaks Iris <=1.10.7, and 1.10.7 is the newest
  Iris for 1.21.11. Use Sodium **0.8.12**; updating it stops the game booting.
- **1.21.11 renames**: `getWorld()` to `getEntityWorld()`, `prevYaw`/`prevPitch` to
  `lastYaw`/`lastPitch`, permission levels replaced by a `PermissionPredicate` system, and
  `KeyBinding` now takes a `KeyBinding.Category` record instead of a translation-key String.
- **Orthographic projection breaks Iris shaderpacks.** Shaderpacks derive shadows, fog and
  screen-space effects from the *perspective* projection matrix. Isocraft works because it
  positions the camera rather than replacing that matrix.
- **A custom `EntityType` with no registered renderer crashes Iris**, not vanilla: the shadow pass
  calls `getRenderer()`, gets null, and NPEs. Units are vanilla iron golems until there is a
  reason for them to look different.
- **World generation**: don't try to deliver a `stop` command to the server. A PowerShell pipe
  prepends a UTF-8 BOM (`<BOM>stop` becomes an unknown command) and redirected stdin errors on
  EOF. `worldgen.ps1` waits for `Done` then kills the process, which is safe only because
  `sync-chunk-writes=true` flushes chunks as they generate.
- **Editing files from Windows PowerShell**: `Get-Content -Raw` reads a BOM-less UTF-8 file as
  ANSI and corrupts every non-ASCII character on write. Edit text files with a real editor, not a
  PowerShell regex pass.

## Roadmap

- [x] **State 3** - detached RTS camera with pan, zoom and rotation
- [ ] Cursor unprojection and drag-box selection (replacing the current crosshair pick)
- [ ] **Self-playing factions** - periodic unit spawning, settlement building, expansion
- [ ] **Dedicated map** - island archipelago with mountains, forests, cherry groves and villages
- [ ] Custom unit entity (needs renderer + model + texture + model-layer registration)

## License

MIT
