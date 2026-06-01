# SpawnPlugin

Paper plugin for managing the spawn area on a 1.21.4 SMP server. Handles spawn protection, block protection, mob filtering, and a bunch of other quality-of-life stuff.

## Features

- Set a server spawn with `/spawn set` — location is saved across restarts
- `/spawn` teleports you there, with a proximity countdown if enemies are nearby (closer = longer wait)
- Spawn protection blocks all damage, velocity, and block changes while active
- Mobs can't naturally spawn near spawn, creepers are capped to 1 per chunk
- `/random` sends you to a random location outside spawn (requires protection, 24h cooldown)
- Player-placed blocks in the outer zone (80–200 blocks) are tracked separately so explosions don't blow up terrain
- Handles world resets — players inside the reset area come back to spawn with protection, players outside come back to where they were

## Commands

| Command | Description | Permission |
|---|---|---|
| `/spawn` | Teleport to spawn | everyone |
| `/spawn set` | Set spawn to your location | OP |
| `/spawn cancel` | Clear the spawn point | OP |
| `/random` | Random teleport (needs spawn protection) | everyone |

## Protection zones

```
0 – 45 blocks    full protection (no pvp, no mobs, no building)
45 – 80 blocks   no building, pvp allowed
80 – 200 blocks  building allowed, but terrain is tracked vs. player-placed
```

## Disabled things

- Lingering potions (completely disabled)
- Oozing, Infested, Weaving, Wind Charged effects
- Sweep attacks (handled in CombatPlugin)
- Netherite knockback resistance
- Curse of Binding on all loot
- Rain / thunder (forced clear permanently)
- Player-player body collision

## Building

Java 21+ and Paper 1.21.4 required.

```bash
./mvnw clean package
```

Output: `target/SpawnPlugin-1.21.4.jar`

Drop it in your `plugins/` folder. No config file needed — everything saves automatically to `plugins/SpawnPlugin/`.

## Soft dependencies

- **AdminPlugin** — used to check staff rank for bone meal in spawn, and ghost/vanish mode for the /spawn countdown
- **ShopPlugin** — used to check combat tag so players can't /spawn or /random out of fights
- **RankTeamPlugin** — used to skip the /spawn countdown delay for teammates

The plugin loads fine without any of these, those checks just get skipped.
