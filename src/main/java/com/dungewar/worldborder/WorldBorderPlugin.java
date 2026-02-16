package com.dungewar.worldborder;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.WorldBorder;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
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
    private static final double MIN_MINUTES = 1.0;
    private static final double MAX_MINUTES = 720.0;
    private static final long DEBUG_MIN_SECONDS = 20L;
    private static final long DEBUG_MAX_SECONDS = 60L;

    private BukkitTask currentCycleTask;
    private BukkitTask teleportCheckTask;
    private boolean debugMode;
    private double lastTargetBorderSize;
    private long lastDurationTicks;
    private long cycleEndsAtMillis;
    // private long teleportIntervalTicks;

    private HttpClient httpClient;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        // teleportIntervalTicks = resolveTeleportIntervalTicks();
        World world = resolveWorld();
        if (world != null) {
            disableBorderDamage(world.getWorldBorder());
        }

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
            currentCycleTask = Bukkit.getScheduler().runTaskLater(this, this::runBorderCycle, getRetryDelayTicks());
            return;
        }

        WorldBorder border = world.getWorldBorder();
        disableBorderDamage(border);
        double oldSize = border.getSize();
        double targetSize = generateRandomBorderSize();
        long durationSeconds = nextDurationSeconds();
        long durationTicks = Math.max(1L, durationSeconds * 20L);

        border.changeSize(targetSize, durationTicks);
        enforcePlayersInBounds(world, border);

        lastTargetBorderSize = targetSize;
        lastDurationTicks = durationTicks;
        cycleEndsAtMillis = System.currentTimeMillis() + (durationTicks * 50L);

        notifyPlayers(oldSize, targetSize, durationSeconds);
        sendBorderUpdate(oldSize, targetSize, durationSeconds);

        String debugSuffix = debugMode ? " [DEBUG]" : "";
        getLogger().info(String.format(
                Locale.US,
                "World border update: %.2f -> %.2f over %s.%s",
                oldSize,
                targetSize,
                formatDuration(durationSeconds),
                debugSuffix
        ));

        currentCycleTask = Bukkit.getScheduler().runTaskLater(this, this::runBorderCycle, durationTicks);
    }

    private void startTeleportChecks() {
        teleportCheckTask = Bukkit.getScheduler().runTaskTimer(this, () -> {
            World world = resolveWorld();
            if (world == null) {
                // TODO: Give warning abt this
                return;
            }
            WorldBorder border = world.getWorldBorder();
            disableBorderDamage(border);
            enforcePlayersInBounds(world, border);
        }, 1L, 20L);
    }

    // private long resolveTeleportIntervalTicks() {
    //     if (getConfig().isSet("teleport.check-interval-ticks")) {
    //         return Math.max(1L, getConfig().getLong("teleport.check-interval-ticks", 20L));
    //     }
    //     double seconds = getConfig().getDouble("teleport.check-interval-seconds", 1.0);
    //     return Math.max(1L, Math.round(seconds * 20.0));
    // }

    private World resolveWorld() {
        String worldName = getConfig().getString("world.name", "world");
        World world = Bukkit.getWorld(worldName);
        if (world == null && !Bukkit.getWorlds().isEmpty()) {
            world = Bukkit.getWorlds().getFirst();
        }
        return world;
    }

    private void disableBorderDamage(WorldBorder border) {
        if (border.getDamageAmount() != 0.0) {
            border.setDamageAmount(0.0);
        }
    }

    private double generateRandomBorderSize() {
        double x = Math.random();
        double scale = debugMode ? 50.0 : 500.0;
        double raw = scale * (Math.log((1.0 - x) / 2.0) / Math.log(0.5));

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
            player.teleportAsync(safe).thenAccept(success -> {
                if (!success) {
                    return;
                }
                Bukkit.getScheduler().runTask(this, () -> notifyBoundaryCorrection(player));
            });
        }
    }

    private void notifyBoundaryCorrection(Player player) {
        if (!player.isOnline()) {
            return;
        }
        player.sendMessage(ChatColor.RED + "You were outside the world border and have been moved back inside.");
        player.sendTitle(
                ChatColor.RED + "Border Warning",
                ChatColor.YELLOW + "You were moved back inside",
                5,
                40,
                10
        );
        player.playSound(player.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 2.5f, 1.0f);
        player.spawnParticle(Particle.PORTAL, player.getLocation().add(0.0, 1.0, 0.0), 60, 0.6, 0.8, 0.6, 0.05);
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

    private long getRetryDelayTicks() {
        if (debugMode) {
            return nextDebugDurationSeconds() * 20L;
        }
        return 20L * 60L;
    }

    private long nextDurationSeconds() {
        if (debugMode) {
            return nextDebugDurationSeconds();
        }

        double minMinutes = getConfig().getDouble("timing.min-minutes", MIN_MINUTES);
        double maxMinutes = getConfig().getDouble("timing.max-minutes", MAX_MINUTES);

        minMinutes = Math.max(1.0 / 60.0, minMinutes);
        maxMinutes = Math.max(1.0 / 60.0, maxMinutes);
        if (maxMinutes < minMinutes) {
            double temp = minMinutes;
            minMinutes = maxMinutes;
            maxMinutes = temp;
        }

        if (Math.abs(maxMinutes - minMinutes) < 1e-9) {
            return Math.max(1L, Math.round(minMinutes * 60.0));
        }

        double minutes = ThreadLocalRandom.current().nextDouble(minMinutes, maxMinutes);
        return Math.max(1L, Math.round(minutes * 60.0));
    }

    private long nextDebugDurationSeconds() {
        return ThreadLocalRandom.current().nextLong(DEBUG_MIN_SECONDS, DEBUG_MAX_SECONDS + 1);
    }

    private void notifyPlayers(double oldSize, double newSize, long durationSeconds) {
        String durationLabel = formatDuration(durationSeconds);
        String chatMessage = String.format(
                Locale.US,
                "%sWorld border: %.0f -> %.0f over %s%s",
                ChatColor.GOLD,
                oldSize,
                newSize,
                durationLabel,
                debugMode ? " [DEBUG]" : ""
        );
        Bukkit.broadcastMessage(chatMessage);

        String title = ChatColor.RED + "World Border Updating";
        String subtitle = String.format(
                Locale.US,
                "%sSize %.0f in %s",
                ChatColor.YELLOW,
                newSize,
                durationLabel
        );

        for (Player player : Bukkit.getOnlinePlayers()) {
            player.sendTitle(title, subtitle, 10, 100, 20);
            player.playSound(player.getLocation(), Sound.ENTITY_ENDER_DRAGON_GROWL, 10.0f, 0.75f);
        }
    }

    private String formatDuration(long totalSeconds) {
        if (totalSeconds < 60L) {
            return totalSeconds + "s";
        }
        long minutes = totalSeconds / 60L;
        long seconds = totalSeconds % 60L;
        if (seconds == 0L) {
            return minutes + "m";
        }
        return minutes + "m " + seconds + "s";
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String commandName = command.getName().toLowerCase(Locale.ROOT);
        if (commandName.equals("wbstatus")) {
            sendStatus(sender);
            return true;
        }
        if (!commandName.equals("wbdebug") || !sender.isOp()) {
            return false;
        }

        debugMode = !debugMode;
        if (currentCycleTask != null) {
            currentCycleTask.cancel();
            currentCycleTask = null;
        }

        if (debugMode) {
            sender.sendMessage(ChatColor.GREEN + "World border debug mode enabled. Immediate start with 20-60s random cycles.");
        } else {
            sender.sendMessage(ChatColor.YELLOW + "World border debug mode disabled. Returning to configured minute-based cycles.");
        }

        Bukkit.getScheduler().runTask(this, this::runBorderCycle);
        return true;
    }

    private void sendStatus(CommandSender sender) {
        World world = resolveWorld();
        if (world == null) {
            sender.sendMessage(ChatColor.RED + "World not found.");
            return;
        }

        WorldBorder border = world.getWorldBorder();
        long millisLeft = Math.max(0L, cycleEndsAtMillis - System.currentTimeMillis());
        long ticksLeft = millisLeft / 50L;
        long secondsLeft = ticksLeft / 20L;

        sender.sendMessage(ChatColor.GOLD + "WorldBorder Status");
        sender.sendMessage(ChatColor.YELLOW + "Current Size: " + ChatColor.WHITE + String.format(Locale.US, "%.2f", border.getSize()));
        sender.sendMessage(ChatColor.YELLOW + "Target Size: " + ChatColor.WHITE + String.format(Locale.US, "%.2f", lastTargetBorderSize));
        sender.sendMessage(ChatColor.YELLOW + "Cycle Duration: " + ChatColor.WHITE + lastDurationTicks + " ticks (" + formatDuration(lastDurationTicks / 20L) + ")");
        sender.sendMessage(ChatColor.YELLOW + "Time Remaining: " + ChatColor.WHITE + ticksLeft + " ticks (" + formatDuration(secondsLeft) + ")");
        sender.sendMessage(ChatColor.YELLOW + "Debug Mode: " + ChatColor.WHITE + (debugMode ? "ON" : "OFF"));
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
