# NucleoCraft: Detonation

A NeoForge mod for **Minecraft 1.21.1** that creates yield-scaled nuclear detonations with animated terrain excavation, persistent fallout atmosphere, a long-lived mushroom cloud, distance-delayed blast audio, cinematic shockwaves, and optional Distant Horizons integration.

> **Development status:** active development / source-overlay build  
> **Mod version:** `0.1.0`  
> **Current source revision:** v23  
> **Mod ID:** `nucleocraft_detonation`

## Target environment

| Requirement      |                                                            Version |
|------------------|-------------------------------------------------------------------:|
| Minecraft        |                                                           `1.21.1` |
| NeoForge         |                          `21.1.242` or newer compatible 21.1 build |
| Java             |                                                               `21` |
| Distant Horizons | Optional; advanced integration requires a build exposing DH API v3 |

The mod must be installed on both the logical server and connecting clients. Distant Horizons is optional and client-side only.

---

## Features

### Yield-scaled detonations

Detonations accept yields from **10 kilotonnes to 25 megatonnes**. Crater dimensions use cube-root scaling, so increasingly large yields produce progressively larger craters without scaling linearly into unusable sizes.

The project treats one Minecraft TNT block as one tonne of TNT equivalent for its internal design scale.

### Animated crater generation

The crater is not created in a single frozen operation. It is excavated incrementally through expanding horizontal waves:

- Surface excavation begins first.
- Deeper layers begin at short intervals.
- Every active layer expands outwards from the detonation centre.
- Block drops are suppressed during excavation.
- Bedrock is protected.
- The crater receives a raised rim made from suitable nearby terrain material.
- Fractured ground extends beyond the primary bowl.
- Deterministic terrain noise creates uneven walls, terraces, ledges, cuts and occasional deeper gouges.
- Crater generation is processed across server ticks rather than attempting every block operation at once.

Current processing limit: **50,000 crater operations per server tick**.

### Full-screen nuclear flash

Players within range receive a distance-scaled flash which renders over both the normal HUD and open GUI screens.

- Full white hold: **80 ticks / 4 seconds**
- Fade duration: **160 ticks / 8 seconds**
- Flash range scales with crater size and is clamped between 1,500 and 24,000 blocks.

### Distance-delayed blast audio

The blast sound is delayed according to the listener's distance from the detonation.

- Simulated speed of sound: **343 blocks per second**
- Sound range scales with crater size and is clamped between 2,500 and 36,000 blocks.
- The bundled sound is streamed from:
  `assets/nucleocraft_detonation/sounds/nuclear_blast.ogg`

The visual flash therefore arrives before the sound for distant players.

### Shockwaves

The detonation renders three custom geometry shockwaves:

- A vertical ground-level shockwave wall
- A lower atmospheric ring
- A second atmospheric ring above the first

The ground wave expands at the same rate as the lower sky wave but remains radially ahead of it. The upper atmospheric wave begins slightly later.

Current shared expansion duration: **460 ticks / 23 seconds**.

### Particle mushroom cloud

The cloud uses a custom recolourable particle based on Minecraft's vanilla poof sprite sequence.

- The original fireball becomes the cloud head; a separate cap is not spawned.
- The head starts around the crater radius.
- Cap widening begins at approximately **15% of the ascent**.
- The cap continues expanding as it rises.
- Once normally formed, it enters a second, much slower width-only expansion phase.
- The stem starts at its full intended width and becomes thinner towards the top.
- A curved middle profile gives the stem a more natural concave silhouette while retaining its existing bottom and top widths.
- A small smoke collar forms at the bottom of the stem.
- A smoke ring follows the actual crater edge.
- Once the head reaches full height, a separated grey ring fades in beneath the cap at half the cap radius.
- The fireball changes from white-hot and yellow through orange and brown into warm grey, dark grey and near-black smoke.
- The complete cloud remains active for approximately **20 minutes**.

The mature cloud uses a reduced particle refresh rate and longer-lived particles to avoid sustaining the initial formation density for the entire lifetime.

### Persistent fallout environment

Each detonation creates a saved fallout zone with a muted green-grey atmosphere.

Current fallout colour: `#5A6C56`

The effect includes:

- Fog colour and distance changes
- Sky tint
- Cloud tint
- Water tint
- Airborne dust particles

Fallout radius is based on crater radius, using a 12× multiplier and a clamp of **800 to 8,000 blocks**.

The current fallout duration is permanent. It remains saved after the mushroom cloud has expired.

### World persistence

The following state is stored using Minecraft's `SavedData` system:

- Dimension
- Detonation centre
- Fallout radius, duration, age and strength
- Mushroom-cloud visual scale, age and strength

Persistent effects are synchronised when a player joins or changes dimension.

Important behaviour:

- Cloud age advances using server game ticks.
- Real-world time while the server is offline does not count.
- Rejoining restores the cloud at its saved stage.
- Restoring saved effects does not replay the flash, blast sound or shockwaves.
- Normal crater block changes are saved by Minecraft as world terrain.
- An active countdown or partially running crater task is not currently serialised. Stopping the server during generation preserves already-changed blocks but does not resume the unfinished task.

---

## Distant Horizons compatibility

Distant Horizons support is optional and loaded through reflection, allowing NucleoCraft: Detonation to run normally when DH is absent.

### Fallout fog bridge

While the player is inside fallout, DH far-terrain fog follows the current NucleoCraft fallout intensity. Outside fallout, the temporary DH configuration overrides are cleared so the user's normal settings are restored.

### Crater LOD refresh

After crater generation finishes:

- Affected chunks are queued for DH LOD regeneration.
- Chunk updates are submitted in controlled batches.
- The DH render cache is refreshed after the queue completes.
- Persisted detonations request another refresh when their dimension is synchronised.

Chunks that are not currently available are retried for a limited number of ticks.

### Distant mushroom-cloud model

At long range, the detailed particle cloud is replaced by a simplified DH generic-renderer model containing:

- Filled curved stem slices
- A filled oval cap
- The separated underside grey ring
- The crater-edge smoke ring

The v23 distant model uses **filled, overlapping voxel slices** rather than sparse outlines, preventing massive gaps in the cap and stem.

The distant representation:

- Uses the same saved cloud centre, age and scale as the close particle model.
- Updates more frequently while forming and less frequently once mature.
- Is disabled near the player so it does not overlap the detailed particle cloud.
- Remains an approximation rather than a one-to-one copy of every poof particle.

### DH version note

The mod metadata marks Distant Horizons `2.2.0+` as optional, but the current reflection bridge requires a DH build exposing the newer **API v3** classes for all three integration paths. Unsupported versions are ignored safely rather than preventing NucleoCraft from starting.

---

## Command usage

The current detonation system is accessed through a development command:

```mcfunction
/nuke detonate <yield>
```

The command requires permission level `2` and must be executed by a player.

Look directly at the block which should become the centre of the crater. The target block must be within **512 blocks**.

### Accepted yield formats

```text
10kt
37.5kt
1mt
2.3mt
25mt
```

The parser accepts the units `t`, `kt` and `mt`, but the final value must still fall between **10kt and 25mt**.

Example:

```mcfunction
/nuke detonate 1mt
```

After execution:

1. A five-second countdown appears.
2. Immediate visual effects begin when the countdown reaches zero.
3. The crater is generated incrementally.
4. A completion message appears when terrain processing finishes.

---

## Approximate crater sizes

These values use the current cube-root scaling and are rounded to blocks.

| Yield | Radius | Depth | Rim width | Rim height |
|------:|-------:|------:|----------:|-----------:|
|  10kt |     32 |    11 |         6 |          3 |
| 100kt |     69 |    23 |         6 |          3 |
|   1mt |    149 |    51 |         7 |          3 |
|  10mt |    320 |   109 |        10 |          3 |
|  25mt |    434 |   148 |        10 |          3 |

The visible disturbed area is larger than the listed crater radius because edge noise, fractures and the raised rim extend beyond the primary bowl.

---

## Multiplayer behaviour

- The command and crater generation run on the logical server.
- Detonation effects are broadcast to players in the affected dimension.
- Every client calculates its own flash strength and sound delay from distance.
- Persistent visual entries are synchronised per dimension.
- Distant Horizons integration is client-side and does not affect server startup when DH is absent.

---

## Performance notes

Large yields are intentionally expensive.


## Licence

The current mod metadata declares:

```text
All Rights Reserved
```

Copyright © TheCaptain. No permission is granted to redistribute, modify or publish the project unless the author provides it separately.

---

## Author

**TheCaptain**

NucleoCraft: Detonation is an independent Minecraft mod and is not affiliated with Mojang Studios, Microsoft, NeoForged or the Distant Horizons team.
