package com.dungewar.worldborder;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

public class WorldBorderPlugin extends JavaPlugin {
    private static final int MIN_HOURS = 1;
    private static final int MAX_HOURS = 12;

    private BukkitTask currentCycleTask;
    private BukkitTask teleportCheckTask;

    private HttpClient httpClient;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        startTeleportChecks();
        runBorderCycle();

        getLogger().info("MCWorldBorder enabled.");
    }

    @Override
    public void onDisable() {
        if (currentCycleTask != null) {
            currentCycleTask.cancel();
        }
        if (teleportCheckTask != null) {
            teleportCheckTask.cancel();
        }
        getLogger().info("MCWorldBorder disabled.");
    }

    private void runBorderCycle() {
        World world = resolveWorld();
        if (world == null) {
            getLogger().severe("Configured world was not found. Retrying in 1 minute.");
            currentCycleTask = Bukkit.getScheduler().runTaskLater(this, this::runBorderCycle, 20L * 60);
            return;
        }

        WorldBorder border = world.getWorldBorder();
        double oldSize = border.getSize();
        double targetSize = generateRandomBorderSize();
        int durationHours = ThreadLocalRandom.current().nextInt(MIN_HOURS, MAX_HOURS + 1);
        long durationSeconds = durationHours * 3600L;

        border.setSize(targetSize, durationSeconds);
        enforcePlayersInBounds(world, border);

        sendBorderUpdate(oldSize, targetSize, durationSeconds);

        getLogger().info(String.format(
                Locale.US,
                "World border update: %.2f -> %.2f over %d hour(s).",
                oldSize,
                targetSize,
                durationHours
        ));

        currentCycleTask = Bukkit.getScheduler().runTaskLater(this, this::runBorderCycle, durationSeconds * 20L);
    }

    private void startTeleportChecks() {
        long intervalSeconds = Math.max(5L, getConfig().getLong("teleport.check-interval-seconds", 60L));
        long intervalTicks = intervalSeconds * 20L;

        teleportCheckTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            World world = resolveWorld();
            if (world == null) {
                return;
            }
            enforcePlayersInBounds(world, world.getWorldBorder());
        }, 20L, intervalTicks);
    }

    private World resolveWorld() {
        String worldName = getConfig().getString("world.name", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null && !Bukkit.getWorlds().isEmpty()) {
            world = Bukkit.getWorlds().getFirst();
        }
        return world;
    }

    private double generateRandomBorderSize() {
        double x = Math.random();
        double raw = 500.0 * (Math.log((1.0 - x) / 2.0) / Math.log(0.5));

        double minSize = getConfig().getDouble("bounds.min-border-size", 16.0);
        double maxSize = getConfig().getDouble("bounds.max-border-size", 59_999_968.0);

        if (Double.isNaN(raw) || Double.isInfinite(raw)) {
            return minSize;
        }
        return Math.max(minSize, Math.min(maxSize, raw));
    }

    private void enforcePlayersInBounds(World world, WorldBorder border) {
        for (Player player : world.getPlayers()) {
            Location current = player.getLocation();
            if (border.isInside(current)) {
                continue;
            }

            Location safe = findSafeLocationInsideBorder(world, border, current);
            player.teleportAsync(safe);
        }
    }

    private Location findSafeLocationInsideBorder(World world, WorldBorder border, Location current) {
        Location center = border.getCenter();
        double halfSize = border.getSize() / 2.0;

        double minX = center.getX() - halfSize + 1.0;
        double maxX = center.getX() + halfSize - 1.0;
        double minZ = center.getZ() - halfSize + 1.0;
        double maxZ = center.getZ() + halfSize - 1.0;

        double clampedX = Math.max(minX, Math.min(maxX, current.getX()));
        double clampedZ = Math.max(minZ, Math.min(maxZ, current.getZ()));

        int blockX = (int) Math.floor(clampedX);
        int blockZ = (int) Math.floor(clampedZ);
        int highestY = world.getHighestBlockYAt(blockX, blockZ);

        return new Location(
                world,
                blockX + 0.5,
                highestY + 1.0,
                blockZ + 0.5,
                current.getYaw(),
                current.getPitch()
        );
    }

    private void sendBorderUpdate(double oldSize, double newSize, long durationSeconds) {
        String url = getConfig().getString("api.url", "https://dungewar.com/api/mc-world-border");
        String secret = getConfig().getString("api.secret", "");

        String body = String.format(
                Locale.US,
                "{\"secret\":%s,\"old_size\":%.5f,\"new_size\":%.5f,\"duration\":%d}",
                jsonString(secret),
                oldSize,
                newSize,
                durationSeconds
        );

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .thenAccept(response -> getLogger().info("World border POST status: " + response.statusCode()))
                .exceptionally(ex -> {
                    getLogger().warning("Failed to send world border POST: " + ex.getMessage());
                    return null;
                });
    }

    private String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t") + "\"";
    }
}
