
package com.pritamx.thunderauth;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.scheduler.BukkitTask;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class AuthManager {

    private final ThunderAuth plugin;
    private final AccountStore accounts;

    private final Map<UUID, Boolean> authenticated = new HashMap<>();
    private final Map<UUID, Integer> failedAttempts = new HashMap<>();
    private final Map<UUID, Long> sessionExpiry = new HashMap<>();
    private final Map<UUID, BukkitTask> timeoutTasks = new HashMap<>();
    private final Map<UUID, BukkitTask> reminderTasks = new HashMap<>();
    private final Map<UUID, Location> returnLocations = new HashMap<>();

    public AuthManager(ThunderAuth plugin, AccountStore accounts) {
        this.plugin = plugin;
        this.accounts = accounts;
    }

    public boolean isAuthenticated(UUID uuid) {
        return uuid != null && authenticated.getOrDefault(uuid, false);
    }

    public boolean isAuthenticated(Player player) {
        return player != null && isAuthenticated(player.getUniqueId());
    }

    public boolean hasAccount(UUID uuid) {
        return accounts.hasAccount(uuid);
    }

    public boolean isSessionValid(UUID uuid) {
        if (uuid == null || !plugin.getConfig().getBoolean(
                "settings.session.remember-after-quit", true)) {
            if (uuid != null) {
                sessionExpiry.remove(uuid);
            }
            return false;
        }

        Long expiry = sessionExpiry.get(uuid);

        if (expiry == null) {
            return false;
        }

        if (expiry <= System.currentTimeMillis()) {
            sessionExpiry.remove(uuid);
            return false;
        }

        return true;
    }

    private String getIdentity(Player player) {
        FloodgateHook hook = plugin.getFloodgateHook();
        return hook == null ? null : hook.getIdentity(player);
    }

    public void saveReturnLocation(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        Location location = player.getLocation();

        if (location.getWorld() != null) {
            returnLocations.put(
                    player.getUniqueId(), location.clone());
        }
    }

    public boolean restoreReturnLocation(Player player) {
        if (player == null || !player.isOnline()) {
            return false;
        }

        UUID uuid = player.getUniqueId();
        Location location = returnLocations.get(uuid);

        if (location == null || location.getWorld() == null) {
            return false;
        }

        if (!player.teleport(location)) {
            return false;
        }

        returnLocations.remove(uuid);
        return true;
    }

    public void clearReturnLocation(UUID uuid) {
        if (uuid != null) {
            returnLocations.remove(uuid);
        }
    }

    public boolean register(Player player, String identity, String password) {
        if (player == null || !player.isOnline()
                || identity == null || identity.isBlank()
                || accounts.hasAccount(player.getUniqueId())) {
            return false;
        }

        UUID uuid = player.getUniqueId();

        if (plugin.getConfig().getBoolean(
                "settings.security.one-account-per-identity", true)
                && accounts.hasIdentity(identity)) {
            return false;
        }

        String currentIdentity = getIdentity(player);

        if (currentIdentity == null || !currentIdentity.equals(identity)) {
            return false;
        }

        int min = plugin.getConfig().getInt(
                "settings.password.min-length", 6);
        int max = plugin.getConfig().getInt(
                "settings.password.max-length", 64);

        if (password == null || password.length() < min
                || password.length() > max) {
            return false;
        }

        try {
            if (!accounts.register(uuid, identity, password)) {
                return false;
            }

            failedAttempts.remove(uuid);
            authenticate(player);
            return true;
        } catch (IOException exception) {
            plugin.getLogger().severe(
                    "Could not save account for " + uuid + ": "
                            + exception.getMessage());
            return false;
        }
    }

    public boolean login(Player player, String password, String identity) {
        if (player == null || !player.isOnline() || password == null) {
            return false;
        }

        UUID uuid = player.getUniqueId();
        String storedIdentity = accounts.getIdentity(uuid);
        String currentIdentity = getIdentity(player);

        boolean valid = identity != null && !identity.isBlank()
                && currentIdentity != null
                && currentIdentity.equals(identity)
                && storedIdentity != null
                && storedIdentity.equals(identity)
                && accounts.hasAccount(uuid)
                && accounts.verifyPassword(uuid, password);

        if (!valid) {
            int attempts = failedAttempts.merge(uuid, 1, Integer::sum);
            int maxAttempts = Math.max(1, plugin.getConfig().getInt(
                    "settings.login.max-attempts", 5));

            if (attempts >= maxAttempts && player.isOnline()) {
                player.kickPlayer(plugin.messageWithoutPrefix(
                        "authentication.too-many-attempts"));
            }

            return false;
        }

        failedAttempts.remove(uuid);
        authenticate(player);
        return true;
    }

    public boolean changePassword(
            Player player, String oldPassword, String newPassword) {
        if (player == null || !player.isOnline()
                || !isAuthenticated(player)
                || oldPassword == null || newPassword == null
                || !accounts.verifyPassword(
                        player.getUniqueId(), oldPassword)) {
            return false;
        }

        int min = plugin.getConfig().getInt(
                "settings.password.min-length", 6);
        int max = plugin.getConfig().getInt(
                "settings.password.max-length", 64);

        if (newPassword.length() < min || newPassword.length() > max) {
            return false;
        }

        try {
            return accounts.changePassword(
                    player.getUniqueId(), newPassword);
        } catch (IOException exception) {
            plugin.getLogger().severe(
                    "Could not change password for "
                            + player.getUniqueId() + ": "
                            + exception.getMessage());
            return false;
        }
    }

    public void beginAuthentication(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        UUID uuid = player.getUniqueId();

        if (isSessionValid(uuid) && accounts.hasAccount(uuid)) {
            String currentIdentity = getIdentity(player);
            String storedIdentity = accounts.getIdentity(uuid);

            if (currentIdentity != null
                    && currentIdentity.equals(storedIdentity)) {
                failedAttempts.remove(uuid);
                authenticate(player);
                return;
            }

            sessionExpiry.remove(uuid);
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

                        if (online != null && online.isOnline()) {
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

                    if (online == null || !online.isOnline()
                            || isAuthenticated(uuid)) {
                        cancelReminder(uuid);
                        return;
                    }

                    String key = accounts.hasAccount(uuid)
                            ? "authentication.reminder-login"
                            : "authentication.reminder-register";

                    online.sendMessage(plugin.message(key));
                }, reminder * 20L, reminder * 20L));
    }

    public void authenticate(Player player) {
        if (player == null || !player.isOnline()) {
            return;
        }

        UUID uuid = player.getUniqueId();
        authenticated.put(uuid, true);

        long minutes = Math.max(0L, plugin.getConfig().getLong(
                "settings.session.duration-minutes", 30));

        if (minutes == 0L) {
            sessionExpiry.remove(uuid);
        } else {
            sessionExpiry.put(uuid,
                    System.currentTimeMillis() + minutes * 60_000L);
        }

        cancelTasks(uuid);
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        player.setWalkSpeed(0.2F);
        player.setFlySpeed(0.1F);

        if (plugin.getBossBarManager() != null) {
            plugin.getBossBarManager().stop(player);
        }
    }

    public void handleQuit(Player player) {
        if (player == null) {
            return;
        }

        UUID uuid = player.getUniqueId();

        if (isAuthenticated(uuid)
                && plugin.getConfig().getBoolean(
                "settings.session.remember-after-quit", true)) {
            long minutes = Math.max(0L, plugin.getConfig().getLong(
                    "settings.session.duration-minutes", 30));

            if (minutes > 0L) {
                sessionExpiry.put(uuid,
                        System.currentTimeMillis() + minutes * 60_000L);
            } else {
                sessionExpiry.remove(uuid);
            }
        } else {
            sessionExpiry.remove(uuid);
        }

        cleanup(uuid);
    }

    public void cleanup(UUID uuid) {
        if (uuid == null) {
            return;
        }

        authenticated.remove(uuid);
        failedAttempts.remove(uuid);
        returnLocations.remove(uuid);
        cancelTasks(uuid);
    }

    public void cleanupAll() {
        Set<UUID> uuids = new HashSet<>();

        uuids.addAll(authenticated.keySet());
        uuids.addAll(failedAttempts.keySet());
        uuids.addAll(sessionExpiry.keySet());
        uuids.addAll(timeoutTasks.keySet());
        uuids.addAll(reminderTasks.keySet());
        uuids.addAll(returnLocations.keySet());

        for (UUID uuid : uuids) {
            cleanup(uuid);
        }

        sessionExpiry.clear();
        returnLocations.clear();
        authenticated.clear();
        failedAttempts.clear();
        timeoutTasks.clear();
        reminderTasks.clear();
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
