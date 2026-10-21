
package com.pritamx.thunderauth;

import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class BossBarManager {

    private final ThunderAuth plugin;
    private final AuthManager auth;

    private final Map<UUID, BossBar> bars = new HashMap<>();
    private final Map<UUID, BukkitTask> tasks = new HashMap<>();
    private final Map<UUID, Integer> remainingSeconds = new HashMap<>();

    public BossBarManager(ThunderAuth plugin, AuthManager auth) {
        this.plugin = plugin;
        this.auth = auth;
    }

    public void start(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        stop(uuid);

        if (!plugin.getConfig().getBoolean(
                "settings.bossbar.enabled", true)) {
            return;
        }

        int timeout = Math.max(1, plugin.getConfig().getInt(
                "settings.login.timeout-seconds", 60));

        BarColor color = parseColor(plugin.getConfig().getString(
                "settings.bossbar.color", "RED"));

        BarStyle style = parseStyle(plugin.getConfig().getString(
                "settings.bossbar.style", "SOLID"));

        BossBar bar = Bukkit.createBossBar(
                getText(player, timeout), color, style);

        bar.setProgress(1.0);
        bar.addPlayer(player);

        bars.put(uuid, bar);
        remainingSeconds.put(uuid, timeout);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> {
                    Player online = Bukkit.getPlayer(uuid);

                    if (online == null
                            || !online.isOnline()
                            || auth.isAuthenticated(uuid)) {
                        stop(uuid);
                        return;
                    }

                    int remaining = remainingSeconds.getOrDefault(
                            uuid, 0) - 1;

                    if (remaining <= 0) {
                        stop(uuid);
                        return;
                    }

                    remainingSeconds.put(uuid, remaining);
                    bar.setTitle(getText(online, remaining));

                    if (plugin.getConfig().getBoolean(
                            "settings.bossbar.countdown", true)) {
                        bar.setProgress(Math.max(
                                0.0,
                                Math.min(1.0,
                                        (double) remaining / timeout)));
                    }
                },
                20L,
                20L
        );

        tasks.put(uuid, task);
    }

    private String getText(Player player, int seconds) {
        String path = auth.hasAccount(player.getUniqueId())
                ? "bossbar.login"
                : "bossbar.register";

        return plugin.messageWithoutPrefix(path)
                .replace("%time%", String.valueOf(seconds));
    }

    public void stop(Player player) {
        if (player != null) {
            stop(player.getUniqueId());
        }
    }

    public void stop(UUID uuid) {
        if (uuid == null) {
            return;
        }

        BukkitTask task = tasks.remove(uuid);

        if (task != null) {
            task.cancel();
        }

        BossBar bar = bars.remove(uuid);

        if (bar != null) {
            bar.removeAll();
        }

        remainingSeconds.remove(uuid);
    }

    public void stopAll() {
        for (UUID uuid : bars.keySet().toArray(new UUID[0])) {
            stop(uuid);
        }

        for (UUID uuid : tasks.keySet().toArray(new UUID[0])) {
            stop(uuid);
        }
    }

    private BarColor parseColor(String value) {
        try {
            return BarColor.valueOf(
                    value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return BarColor.RED;
        }
    }

    private BarStyle parseStyle(String value) {
        try {
            return BarStyle.valueOf(
                    value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException | NullPointerException exception) {
            return BarStyle.SOLID;
        }
    }
}
