# HC_SpawnScaling

Scales beacon NPC spawn limits based on online player count so that each player experiences the same mob density as they would in single-player. Injects per-player scaling curves into all beacon spawns at server startup, with separate density multipliers for hostile and passive mobs. Also extends the idle despawn timer for hostile NPCs to prevent mid-combat despawns.

## Features

- Linear per-player scaling of beacon spawn limits up to a configurable maximum player count
- Separate density multipliers for hostile beacons (combat mobs) and passive beacons (fish, animals)
- Hostile mob density multiplier increases total alive mobs per beacon at all player counts
- Passive mob density multiplier reduces ambient mob scaling to preserve hostile spawn budget
- Extended idle despawn time for hostile NPCs (default 120s vs engine default 10s) to prevent mid-combat despawns
- All settings configurable via HC_Core settings API without code changes
- Skips beacons that already have custom scaling curves defined in their assets

## Dependencies

- **HC_Core** (required) -- settings API for runtime configuration

## Building

```bash
./gradlew build
```
