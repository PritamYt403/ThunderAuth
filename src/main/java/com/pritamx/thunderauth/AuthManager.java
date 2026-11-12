
package com.pritamx.thunderauth;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthManager {

    private final ThunderAuth plugin;
    private final AccountStore accountStore;

    private final Set<UUID> authenticatedPlayers =
            ConcurrentHashMap.newKeySet();

    private final Map<UUID, Integer> loginAttempts =
            new ConcurrentHashMap<>();

    // Temporary in-memory sessions. Never persisted to disk.
    private final Map<String, Long> rememberedSessions =
            new ConcurrentHashMap<>();

    public AuthManager(
            ThunderAuth plugin,
            AccountStore accountStore
    ) {
        this.plugin = plugin;
        this.accountStore = accountStore;
    }

    public boolean isAuthenticated(Player player) {
        return player != null
                && authenticatedPlayers.contains(player.getUniqueId());
    }

    public void setAuthenticated(Player player) {
        if (player == null) {
            return;
        }

        authenticatedPlayers.add(player.getUniqueId());
        rememberedSessions.remove(getIdentity(player));
        resetLoginAttempts(player);
    }

    public void removeAuthenticated(Player player) {
        if (player == null) {
            return;
        }

        boolean wasAuthenticated =
                authenticatedPlayers.remove(player.getUniqueId());

        if (!wasAuthenticated) {
            return;
        }

        // Remembered sessions are disabled in Offline Mode.
        if (!Bukkit.getOnlineMode()) {
            return;
        }

        boolean remember = plugin.getConfig().getBoolean(
                "settings.session.remember-after-quit", false
        );

        int timeout = plugin.getConfig().getInt(
                "settings.session.timeout-seconds", 0
        );

        if (!remember || timeout <= 0) {
            return;
        }

        long expiry = System.currentTimeMillis()
                + (timeout * 1000L);

        rememberedSessions.put(getIdentity(player), expiry);
    }

    public boolean restoreSession(Player player) {
        if (player == null || isAuthenticated(player)) {
            return player != null && isAuthenticated(player);
        }

        if (!Bukkit.getOnlineMode()) {
            return false;
        }

        boolean remember = plugin.getConfig().getBoolean(
                "settings.session.remember-after-quit", false
        );

        int timeout = plugin.getConfig().getInt(
                "settings.session.timeout-seconds", 0
        );

        if (!remember || timeout <= 0) {
            return false;
        }

        String identity = getIdentity(player);
        Long expiry = rememberedSessions.get(identity);

        if (expiry == null) {
            return false;
        }

        if (System.currentTimeMillis() >= expiry) {
            rememberedSessions.remove(identity, expiry);
            return false;
        }

        authenticatedPlayers.add(player.getUniqueId());
        rememberedSessions.remove(identity);

        return true;
    }

    public String getIdentity(Player player) {
        if (player == null) {
            throw new IllegalArgumentException(
                    "Player cannot be null."
            );
        }

        FloodgateHook hook = plugin.getFloodgateHook();

        if (hook != null && hook.isEnabled()) {
            Optional<String> xuid = hook.getXuid(player);

            if (xuid.isPresent()) {
                return "BEDROCK:" + xuid.get();
            }
        }

        return player.getUniqueId().toString();
    }

    public int recordFailedLogin(Player player, int maxAttempts) {
        if (player == null) {
            return 0;
        }

        int limit = Math.max(1, maxAttempts);

        int attempts = loginAttempts.merge(
                player.getUniqueId(),
                1,
                (oldValue, increment) ->
                        oldValue >= Integer.MAX_VALUE
                                ? Integer.MAX_VALUE
                                : oldValue + increment
        );

        return Math.max(0, limit - attempts);
    }

    public void resetLoginAttempts(Player player) {
        if (player != null) {
            loginAttempts.remove(player.getUniqueId());
        }
    }

    public void clearLoginAttempts(Player player) {
        resetLoginAttempts(player);
    }

    public CompletableFuture<Boolean> isRegistered(Player player) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        if (player == null) {
            future.complete(false);
            return future;
        }

        final String identity;

        try {
            identity = getIdentity(player);
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
            return future;
        }

        try {
            Bukkit.getScheduler().runTaskAsynchronously(
                    plugin,
                    () -> {
                        try {
                            future.complete(
                                    accountStore.isRegistered(identity)
                            );
                        } catch (SQLException | RuntimeException exception) {
                            future.completeExceptionally(exception);
                        }
                    }
            );
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }

        return future;
    }

    public CompletableFuture<Boolean> login(
            Player player,
            char[] password
    ) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        if (player == null || password == null) {
            future.complete(false);
            return future;
        }

        final String identity;
        final char[] passwordCopy = password.clone();

        try {
            identity = getIdentity(player);
        } catch (RuntimeException exception) {
            Arrays.fill(passwordCopy, '\0');
            future.completeExceptionally(exception);
            return future;
        }

        try {
            Bukkit.getScheduler().runTaskAsynchronously(
                    plugin,
                    () -> {
                        try {
                            boolean valid = accountStore.verifyPassword(
                                    identity,
                                    passwordCopy
                            );

                            if (valid) {
                                accountStore.updateLastLogin(identity);
                            }

                            runOnMain(future, () -> {
                                if (valid && player.isOnline()) {
                                    setAuthenticated(player);
                                }

                                future.complete(valid);
                            });

                        } catch (SQLException
                                 | GeneralSecurityException
                                 | RuntimeException exception) {
                            future.completeExceptionally(exception);
                        } finally {
                            Arrays.fill(passwordCopy, '\0');
                        }
                    }
            );
        } catch (RuntimeException exception) {
            Arrays.fill(passwordCopy, '\0');
            future.completeExceptionally(exception);
        }

        return future;
    }

    public CompletableFuture<Boolean> register(
            Player player,
            char[] password
    ) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        if (player == null || password == null) {
            future.complete(false);
            return future;
        }

        final String identity;
        final String username;
        final String world;
        final double x;
        final double y;
        final double z;
        final float yaw;
        final float pitch;

        final char[] passwordCopy = password.clone();

        try {
            identity = getIdentity(player);
            username = player.getName();

            /*
             * Prefer the original location captured when the player joined.
             * This avoids saving the registration spawn as their first
             * location when authentication spawn teleportation is enabled.
             */
            Location location = null;

            AuthListener listener = plugin.getAuthListener();

            if (listener != null) {
                location = listener.getOriginalJoinLocation(player);
            }

            // Safe fallback if the original location is unavailable.
            if (location == null) {
                location = player.getLocation().clone();
            } else {
                location = location.clone();
            }

            if (location.getWorld() == null) {
                throw new IllegalArgumentException(
                        "Player location has no world."
                );
            }

            world = location.getWorld().getName();
            x = location.getX();
            y = location.getY();
            z = location.getZ();
            yaw = location.getYaw();
            pitch = location.getPitch();

        } catch (RuntimeException exception) {
            Arrays.fill(passwordCopy, '\0');
            future.completeExceptionally(exception);
            return future;
        }

        try {
            Bukkit.getScheduler().runTaskAsynchronously(
                    plugin,
                    () -> {
                        try {
                            boolean registered = accountStore.register(
                                    identity,
                                    username,
                                    passwordCopy,
                                    world,
                                    x,
                                    y,
                                    z,
                                    yaw,
                                    pitch
                            );

                            runOnMain(future, () -> {
                                if (registered && player.isOnline()) {
                                    setAuthenticated(player);
                                }

                                future.complete(registered);
                            });

                        } catch (SQLException
                                 | GeneralSecurityException
                                 | RuntimeException exception) {
                            future.completeExceptionally(exception);
                        } finally {
                            Arrays.fill(passwordCopy, '\0');
                        }
                    }
            );
        } catch (RuntimeException exception) {
            Arrays.fill(passwordCopy, '\0');
            future.completeExceptionally(exception);
        }

        return future;
    }

    public CompletableFuture<Boolean> changePassword(
            Player player,
            char[] oldPassword,
            char[] newPassword
    ) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        if (player == null || oldPassword == null || newPassword == null) {
            future.complete(false);
            return future;
        }

        final String identity;
        final char[] oldCopy = oldPassword.clone();
        final char[] newCopy = newPassword.clone();

        try {
            identity = getIdentity(player);
        } catch (RuntimeException exception) {
            Arrays.fill(oldCopy, '\0');
            Arrays.fill(newCopy, '\0');
            future.completeExceptionally(exception);
            return future;
        }

        try {
            Bukkit.getScheduler().runTaskAsynchronously(
                    plugin,
                    () -> {
                        try {
                            boolean changed = accountStore.changePassword(
                                    identity,
                                    oldCopy,
                                    newCopy
                            );

                            runOnMain(future, () ->
                                    future.complete(changed)
                            );

                        } catch (SQLException
                                 | GeneralSecurityException
                                 | RuntimeException exception) {
                            future.completeExceptionally(exception);
                        } finally {
                            Arrays.fill(oldCopy, '\0');
                            Arrays.fill(newCopy, '\0');
                        }
                    }
            );
        } catch (RuntimeException exception) {
            Arrays.fill(oldCopy, '\0');
            Arrays.fill(newCopy, '\0');
            future.completeExceptionally(exception);
        }

        return future;
    }

    private void runOnMain(
            CompletableFuture<?> future,
            Runnable action
    ) {
        try {
            Bukkit.getScheduler().runTask(plugin, action);
        } catch (RuntimeException exception) {
            future.completeExceptionally(exception);
        }
    }

    public AccountStore getAccountStore() {
        return accountStore;
    }

    public void clearAuthenticatedPlayers() {
        authenticatedPlayers.clear();
        loginAttempts.clear();
        rememberedSessions.clear();
    }
}
