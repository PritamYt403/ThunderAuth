
package com.pritamx.thunderauth;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.security.GeneralSecurityException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthManager {

    private final ThunderAuth plugin;
    private final AccountStore accountStore;

    private final Set<UUID> authenticatedPlayers =
            ConcurrentHashMap.newKeySet();

    private final Map<UUID, Integer> loginAttempts =
            new ConcurrentHashMap<>();

    public AuthManager(
            ThunderAuth plugin,
            AccountStore accountStore
    ) {
        this.plugin = plugin;
        this.accountStore = accountStore;
    }

    public boolean isAuthenticated(Player player) {
        return authenticatedPlayers.contains(player.getUniqueId());
    }

    public void setAuthenticated(Player player) {
        authenticatedPlayers.add(player.getUniqueId());
    }

    public void removeAuthenticated(Player player) {
        authenticatedPlayers.remove(player.getUniqueId());
    }

    public String getIdentity(Player player) {
        FloodgateHook hook = plugin.getFloodgateHook();

        if (hook != null && hook.isEnabled()) {
            Optional<String> xuid = hook.getXuid(player);

            if (xuid.isPresent()) {
                return "BEDROCK:" + xuid.get();
            }
        }

        // Keep the original Java UUID format for existing accounts.
        return player.getUniqueId().toString();
    }

    public int recordFailedLogin(Player player, int maxAttempts) {
        int attempts = loginAttempts.merge(
                player.getUniqueId(),
                1,
                Integer::sum
        );

        return Math.max(0, maxAttempts - attempts);
    }

    public void resetLoginAttempts(Player player) {
        loginAttempts.remove(player.getUniqueId());
    }

    public void clearLoginAttempts(Player player) {
        loginAttempts.remove(player.getUniqueId());
    }

    public CompletableFuture<Boolean> isRegistered(Player player) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        String identity = getIdentity(player);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                boolean registered = accountStore.isRegistered(identity);
                future.complete(registered);

            } catch (SQLException exception) {
                future.completeExceptionally(exception);
            }
        });

        return future;
    }

    public CompletableFuture<Boolean> login(
            Player player,
            char[] password
    ) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        String identity = getIdentity(player);
        char[] passwordCopy = password.clone();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                boolean valid = accountStore.verifyPassword(
                        identity,
                        passwordCopy
                );

                if (valid) {
                    accountStore.updateLastLogin(identity);
                }

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (valid && player.isOnline()) {
                        setAuthenticated(player);
                        resetLoginAttempts(player);
                    }

                    future.complete(valid);
                });

            } catch (SQLException | GeneralSecurityException exception) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        future.completeExceptionally(exception)
                );

            } finally {
                Arrays.fill(passwordCopy, '\0');
            }
        });

        return future;
    }

    public CompletableFuture<Boolean> register(
            Player player,
            char[] password
    ) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        String identity = getIdentity(player);
        String username = player.getName();

        Location location = player.getLocation().clone();

        String world = location.getWorld() == null
                ? "world"
                : location.getWorld().getName();

        double x = location.getX();
        double y = location.getY();
        double z = location.getZ();

        float yaw = location.getYaw();
        float pitch = location.getPitch();

        char[] passwordCopy = password.clone();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
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

                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (registered && player.isOnline()) {
                        setAuthenticated(player);
                    }

                    future.complete(registered);
                });

            } catch (SQLException | GeneralSecurityException exception) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        future.completeExceptionally(exception)
                );

            } finally {
                Arrays.fill(passwordCopy, '\0');
            }
        });

        return future;
    }

    public CompletableFuture<Boolean> changePassword(
            Player player,
            char[] oldPassword,
            char[] newPassword
    ) {
        CompletableFuture<Boolean> future = new CompletableFuture<>();

        String identity = getIdentity(player);

        char[] oldCopy = oldPassword.clone();
        char[] newCopy = newPassword.clone();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                boolean changed = accountStore.changePassword(
                        identity,
                        oldCopy,
                        newCopy
                );

                Bukkit.getScheduler().runTask(plugin, () ->
                        future.complete(changed)
                );

            } catch (SQLException | GeneralSecurityException exception) {
                Bukkit.getScheduler().runTask(plugin, () ->
                        future.completeExceptionally(exception)
                );

            } finally {
                Arrays.fill(oldCopy, '\0');
                Arrays.fill(newCopy, '\0');
            }
        });

        return future;
    }

    public AccountStore getAccountStore() {
        return accountStore;
    }

    public void clearAuthenticatedPlayers() {
        authenticatedPlayers.clear();
        loginAttempts.clear();
    }
}
