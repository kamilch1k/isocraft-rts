# Isocraft RTS

An RTS layer for Minecraft, built on top of the [Isocraft](https://modrinth.com/mod/isocraft)
isometric-view mod. Fabric, Minecraft 1.21.11.

The goal: an isometric, largely AFK strategy game that plays itself in the background — factions
that build, spawn units and expand — rendered through a real shader stack, in a small borderless
window you can leave running in the corner of a screen.

Everything here is driven from a terminal: no launcher GUI, no world-creation screen, no clicking.

## Status

Working:

- Selectable units with move orders (client picks, server validates and paths)
- `/rts spawn [n]` and an auto-seeded starting squad on world join
- Middle-mouse-drag camera rotation
- Isometric view auto-enabled on join
- One-command launch: borderless, sized, positioned, on a chosen virtual desktop, straight into a world
- Headless world generation via the vanilla server jar

Not built yet — see [Roadmap](#roadmap).

## The three control states

The design target, in the author's words:

1. **First person**, controlling the player character
2. **Isometric top-down**, controlling the player character
3. **RTS camera** — detached free-flying view, *not* controlling the player: select units, issue
   commands, watch the simulation

States 1 and 2 exist today (Isocraft's `V` toggle). State 3 is the next major piece, and it is the
frame the self-playing simulation is meant to be watched in.

## Layout

| path | what |
| --- | --- |
| `src/main/java/dev/isorts/IsoRts.java` | entity handling, `/rts` command, server-side order validation, unit seeding |
| `src/main/java/dev/isorts/MoveOrderPayload.java` | client→server move-order packet |
| `src/main/java/dev/isorts/client/IsoRtsClient.java` | selection, orders, middle-drag rotation, auto-iso |
| `tools/play.ps1` | launch: borderless, positioned, virtual desktop, into a world |
| `tools/launch.py` | portablemc wrapper — needed because its CLI drops `--quickPlaySingleplayer` |
| `tools/worldgen.ps1` | headless world generation with the vanilla server jar |
| `build.ps1` | build wrapper (see [Toolchain](#toolchain-notes)) |

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
| `V` | toggle isometric view |
| `Z` / `C` | rotate camera |
| middle-drag | rotate camera |
| `PgUp` / `PgDn` | pitch |
| `=` / `-`, scroll | zoom |
| left-click a unit | select |
| right-click ground | move order |
| `N` | Isocraft settings |

## Toolchain notes

Hard-won, all of it non-obvious:

- **Gradle 9.5+ is required.** Loom 1.17.17 fails variant matching on Gradle 8.x.
- **JDK 21**, not 25 — and note that a `org.gradle.java.home` in `~/.gradle/gradle.properties`
  *outranks* project settings and `JAVA_HOME`. `build.ps1` overrides it with `-D` rather than
  editing a global file that may belong to another project.
- **Sodium is pinned.** Sodium 0.8.13 declares it breaks Iris ≤1.10.7, and 1.10.7 is the newest
  Iris for 1.21.11. Use Sodium **0.8.12**; updating it stops the game booting.
- **1.21.11 renamed** `getWorld()` → `getEntityWorld()`, and replaced permission levels with a
  `PermissionPredicate` system.
- **Orthographic projection breaks Iris shaderpacks.** Shaderpacks derive shadows, fog and
  screen-space effects from the *perspective* projection matrix. Isocraft works because it
  positions the camera rather than replacing that matrix.
- **A custom `EntityType` with no registered renderer crashes Iris**, not vanilla: the shadow pass
  calls `getRenderer()`, gets null, and NPEs. Units are vanilla iron golems until there is a
  reason for them to look different.
- **World generation**: don't try to deliver a `stop` command to the server. A PowerShell pipe
  prepends a UTF-8 BOM (`<BOM>stop` → unknown command) and redirected stdin errors on EOF.
  `worldgen.ps1` waits for `Done` then kills the process, which is safe only because
  `sync-chunk-writes=true` flushes chunks as they generate.

## Roadmap

- [ ] **State 3** — detached RTS camera, player input suppressed, cursor unprojection and
      drag-box selection (replacing the current crosshair pick)
- [ ] **Self-playing factions** — periodic unit spawning, settlement building, expansion
- [ ] **Dedicated map** — island archipelago with mountains, forests, cherry groves and villages
- [ ] Custom unit entity (needs renderer + model + texture + model-layer registration)

## License

MIT
