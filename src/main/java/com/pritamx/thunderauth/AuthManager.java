
package com.pritamx.thunderauth;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class AuthManager {

    private final ThunderAuth plugin;
    private final AccountStore accounts;

    private final Map<UUID, Boolean> authenticated = new HashMap<>();
    private final Map<UUID, Integer> failedAttempts = new HashMap<>();
    private final Map<UUID, Long> sessionExpiry = new HashMap<>();
    private final Map<UUID, BukkitTask> timeoutTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> reminderTasks = new HashMap<>();

    public AuthManager(ThunderAuth plugin, AccountStore accounts) {
        this.plugin = plugin;
        this.accounts = accounts;
    }

    public boolean isAuthenticated(UUID uuid) {
        return authenticated.getOrDefault(uuid, false);
    }

    public boolean isAuthenticated(Player player) {
        return isAuthenticated(player.getUniqueId());
    }

    public boolean hasAccount(UUID uuid) {
        return accounts.hasAccount(uuid);
    }

    public boolean isSessionValid(UUID uuid) {
        if (!plugin.getConfig().getBoolean(
                "settings.session.remember-after-quit", true)) {
            return false;
        }

        Long expiry = sessionExpiry.get(uuid);
        return expiry != null && expiry > System.currentTimeMillis();
    }

    public boolean register(Player player, String identity, String password) {
        UUID uuid = player.getUniqueId();

        boolean oneAccountPerIdentity = plugin.getConfig().getBoolean(
                "settings.security.one-account-per-identity", true);

        if (identity == null || identity.isBlank()
                || accounts.hasAccount(uuid)
                || (oneAccountPerIdentity && accounts.hasIdentity(identity))) {
            return false;
        }

        int min = plugin.getConfig().getInt("settings.password.min-length", 6);
        int max = plugin.getConfig().getInt("settings.password.max-length", 64);

        if (password == null || password.length() < min
                || password.length() > max) {
            return false;
        }

        try {
            if (!accounts.register(uuid, identity, password)) {
                return false;
            }

            authenticate(player);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe(
                    "Could not save account for " + uuid + ": "
                            + exception.getMessage());
            return false;
        }
    }

    /**
     * Verifies both the password and the identity bound to the account.
     */
    public boolean login(Player player, String password, String identity) {
        UUID uuid = player.getUniqueId();
        String storedIdentity = accounts.getIdentity(uuid);

        if (identity == null || identity.isBlank()
                || storedIdentity == null
                || !storedIdentity.equals(identity)
                || !accounts.hasAccount(uuid)
                || !accounts.verifyPassword(uuid, password)) {

            int attempts = failedAttempts.merge(uuid, 1, Integer::sum);
            int maxAttempts = Math.max(1, plugin.getConfig().getInt(
                    "settings.login.max-attempts", 5));

            if (attempts >= maxAttempts && player.isOnline()) {
                player.kickPlayer(plugin.messageWithoutPrefix(
                        "authentication.too-many-attempts"));
            }

            return false;
        }

        authenticate(player);
        failedAttempts.remove(uuid);
        return true;
    }

    public boolean changePassword(
            Player player, String oldPassword, String newPassword) {
        UUID uuid = player.getUniqueId();

        if (!isAuthenticated(uuid)
                || !accounts.verifyPassword(uuid, oldPassword)) {
            return false;
        }

        int min = plugin.getConfig().getInt("settings.password.min-length", 6);
        int max = plugin.getConfig().getInt("settings.password.max-length", 64);

        if (newPassword == null || newPassword.length() < min
                || newPassword.length() > max) {
            return false;
        }

        try {
            return accounts.changePassword(uuid, newPassword);
        } catch (IOException exception) {
            plugin.getLogger().severe(
                    "Could not change password for " + uuid + ": "
                            + exception.getMessage());
            return false;
        }
    }

    public void beginAuthentication(Player player) {
        UUID uuid = player.getUniqueId();

        if (isSessionValid(uuid) && accounts.hasAccount(uuid)) {
            authenticate(player);
            return;
        }

        authenticated.put(uuid, false);
        failedAttempts.put(uuid, 0);
        cancelTasks(uuid);

        long timeout = Math.max(1L, plugin.getConfig().getLong(
                "settings.login.timeout-seconds", 60));

        timeoutTasks.put(uuid, Bukkit.getScheduler().runTaskLater(
                plugin, () -> {
                    if (!isAuthenticated(uuid)) {
                        Player online = Bukkit.getPlayer(uuid);
                        if (online != null) {
                            online.kickPlayer(plugin.messageWithoutPrefix(
                                    "authentication.timeout"));
                        }
                        cleanup(uuid);
                    }
                }, timeout * 20L));

        long reminder = Math.max(1L, plugin.getConfig().getLong(
                "settings.login.reminder-interval-seconds", 5));

        reminderTasks.put(uuid, Bukkit.getScheduler().runTaskTimer(
                plugin, () -> {
                    Player online = Bukkit.getPlayer(uuid);

                    if (online == null || isAuthenticated(uuid)) {
                        cancelReminder(uuid);
                        return;
                    }

                    String key = accounts.hasAccount(uuid)
                            ? "authentication.reminder-login"
                            : "authentication.reminder-register";

                    online.sendMessage(plugin.message(key));
                }, 0L, reminder * 20L));
    }

    public void authenticate(Player player) {
        UUID uuid = player.getUniqueId();

        authenticated.put(uuid, true);

        long minutes = Math.max(0L, plugin.getConfig().getLong(
                "settings.session.duration-minutes", 30));

        sessionExpiry.put(uuid,
                System.currentTimeMillis() + minutes * 60_000L);

        cancelTasks(uuid);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.setWalkSpeed(0.2F);
        player.setFlySpeed(0.1F);

        if (plugin.getBossBarManager() != null) {
            plugin.getBossBarManager().stop(player);
        }
    }

    public void handleQuit(Player player) {
        UUID uuid = player.getUniqueId();

        if (isAuthenticated(uuid)
                && plugin.getConfig().getBoolean(
                "settings.session.remember-after-quit", true)) {
            long minutes = Math.max(0L, plugin.getConfig().getLong(
                    "settings.session.duration-minutes", 30));

            sessionExpiry.put(uuid,
                    System.currentTimeMillis() + minutes * 60_000L);
        } else {
            sessionExpiry.remove(uuid);
        }

        cleanup(uuid);
    }

    public void cleanup(UUID uuid) {
        authenticated.remove(uuid);
        failedAttempts.remove(uuid);
        cancelTasks(uuid);
    }

    private void cancelTasks(UUID uuid) {
        BukkitTask timeout = timeoutTasks.remove(uuid);
        if (timeout != null) {
            timeout.cancel();
        }
        cancelReminder(uuid);
    }

    private void cancelReminder(UUID uuid) {
        BukkitTask reminder = reminderTasks.remove(uuid);
        if (reminder != null) {
            reminder.cancel();
        }
    }
}
