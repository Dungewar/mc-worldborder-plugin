# mc-worldborder-plugin

A basic Paper plugin that continuously randomizes the world border size.

## Features
- Picks the next border size using:
  - `500 * log((1 - x) / 2) / log(0.5)` where `x = Math.random()`.
- Picks a random transition duration from **1 to 12 hours**.
- Once a transition completes, it immediately schedules the next one.
- Teleports players back inside the border to the highest block at a clamped in-bounds location.
- Sends a `POST` request to `https://dungewar.com/api/mc-world-border` on each border change.

## Configuration
Default config is at `src/main/resources/config.yml` and includes:
- API URL and secret.
- Target world name.
- Border size clamping min/max.
- Periodic out-of-bounds teleport check interval.

## Build
```bash
mvn package
```

The plugin JAR will be generated in `target/`.
