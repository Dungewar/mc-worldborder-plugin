# mc-worldborder-plugin

A basic Paper plugin that continuously randomizes the world border size.

## Features
- Picks the next border size using:
  - `500 * log((1 - x) / 2) / log(0.5)` where `x = Math.random()`.
- Picks a random transition duration from configurable **minutes** (`timing.min-minutes` to `timing.max-minutes`), including sub-minute values.
- Once a transition completes, it immediately schedules the next one.
- Teleports players back inside the border to the highest block at a clamped in-bounds location.
- Sends a `POST` request to `https://dungewar.com/api/mc-world-border` on each border change.
- Broadcasts each update in chat, sends a title to all online players, and plays a loud alert sound.
- Includes `/wbdebug` to toggle debug mode (immediate start + random 20-60 second cycles until toggled off).

## Configuration
Default config is at `src/main/resources/config.yml` and includes:
- API URL and secret.
- Target world name.
- Border size clamping min/max.
- Minute-based timing range (`timing.min-minutes`, `timing.max-minutes`) with sub-minute support.
- Periodic out-of-bounds teleport check interval.

## Commands
- `/wbdebug` (permission: `mcworldborder.debug`): toggles debug mode on/off.

## Build
```bash
mvn package
```

The plugin JAR will be generated in `target/`.
