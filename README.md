# NucleoCraft: Detonation — first crater milestone

Target: Minecraft 1.21.1 with NeoForge.

Assumed identifiers:

- Mod ID: `nucleocraft_detonation`
- Java package: `io.github.loganmartingreen.nucleocraftdetonation`

## Add the files

Copy the `src/main/java` folder into the matching location in your NeoForge project.

Make sure your project metadata uses:

```properties
mod_id=nucleocraft_detonation
mod_name=NucleoCraft: Detonation
```

The main class is:

```text
io.github.loganmartingreen.nucleocraftdetonation.NucleoCraftDetonation
```

## Test command

Look at a surface block, then run one of:

```text
/nuke detonate 10kt
/nuke detonate 100kt
/nuke detonate 1mt
/nuke detonate 2.3mt
/nuke detonate 25mt
```

The accepted range is continuous from `10kt` through `25mt`. Inputs outside that range are rejected.

The command sends `5`, `4`, `3`, `2`, `1` to the command user's chat and then begins the crater.

## Current behaviour

- Crater dimensions depend only on yield.
- Yield radius scaling uses a cube-root relationship.
- The crater is shallow and wide.
- The outline is mostly circular with controlled low-frequency noise.
- The centre is carved by direct block removal with no drops.
- The outer ring is raised using material sampled from the local ground.
- Work is divided across server ticks to avoid one enormous synchronous edit.
- This first pass gradually loads chunks as it reaches them; persistent pending effects for unloaded chunks come later.

## Current tuning points

In `CraterDimensions.java`:

```java
private static final double REFERENCE_RADIUS_BLOCKS = 24.0D;
```

This makes a `10kt` crater 24 blocks in radius. Increase or decrease it after the first visual test.

The current dimensions are approximately:

| Yield | Radius | Depth | Rim width | Rim height |
|---:|---:|---:|---:|---:|
| 10 kt | 24 | 7 | 4 | 2 |
| 100 kt | 52 | 15 | 7 | 4 |
| 1 Mt | 111 | 31 | 16 | 8 |
| 25 Mt | 326 | 91 | 46 | 23 |

## Deliberately not included yet

- flash
- fireball
- mushroom cloud
- shockwave
- scorched terrain
- water vaporisation
- structural damage logic
- player/entity damage
- radiation
