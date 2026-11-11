
package com.pritamx.thunderauth;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

public final class FloodgateHook {

    private final ThunderAuth plugin;

    private Object floodgateApi;
    private Method getPlayerMethod;
    private Method getXuidMethod;

    private boolean enabled;
    private boolean apiReady;

    public FloodgateHook(ThunderAuth plugin) {
        this.plugin = plugin;
    }

    public void setup() {
        enabled = false;
        apiReady = false;
        floodgateApi = null;
        getPlayerMethod = null;
        getXuidMethod = null;

        if (!plugin.getConfig().getBoolean(
                "integrations.floodgate.enabled", true)) {
            plugin.getLogger().info(
                    "Floodgate integration is disabled by configuration."
            );
            return;
        }

        Plugin floodgate = Bukkit.getPluginManager()
                .getPlugin("floodgate");

        if (floodgate == null || !floodgate.isEnabled()) {
            plugin.getLogger().info(
                    "Floodgate not found. Java UUID identity will be used."
            );
            return;
        }

        /*
         * Floodgate is installed. Keep the integration enabled even
         * if reflection setup fails, so identity lookup fails closed
         * instead of silently falling back to a Java UUID.
         */
        enabled = true;

        try {
            Class<?> apiClass = Class.forName(
                    "org.geysermc.floodgate.api.FloodgateApi"
            );

            Object api = apiClass.getMethod("getInstance").invoke(null);

            Method playerMethod = apiClass.getMethod(
                    "getPlayer",
                    UUID.class
            );

            Class<?> floodgatePlayerClass = Class.forName(
                    "org.geysermc.floodgate.api.player.FloodgatePlayer"
            );

            Method xuidMethod = floodgatePlayerClass.getMethod("getXuid");

            floodgateApi = api;
            getPlayerMethod = playerMethod;
            getXuidMethod = xuidMethod;
            apiReady = true;

            plugin.getLogger().info(
                    "Floodgate integration enabled successfully."
            );

        } catch (ReflectiveOperationException
                 | LinkageError exception) {

            plugin.getLogger().severe(
                    "Floodgate is installed but its API could not be "
                            + "initialized. Authentication identity lookup "
                            + "will fail closed. Reason: "
                            + exception.getMessage()
            );
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public boolean isApiReady() {
        return apiReady;
    }

    public Optional<String> getXuid(Player player) {
        if (player == null) {
            throw new IllegalArgumentException(
                    "Player cannot be null."
            );
        }

        if (!enabled) {
            return Optional.empty();
        }

        if (!apiReady || floodgateApi == null
                || getPlayerMethod == null || getXuidMethod == null) {
            throw new IllegalStateException(
                    "Floodgate is installed but its API is unavailable."
            );
        }

        try {
            Object result = getPlayerMethod.invoke(
                    floodgateApi,
                    player.getUniqueId()
            );

            if (!(result instanceof Optional<?> optional)) {
                throw new IllegalStateException(
                        "Floodgate returned an unexpected player result."
                );
            }

            /*
             * An empty result means this UUID is not registered
             * as a Floodgate player, so it can use Java identity.
             */
            if (optional.isEmpty()) {
                return Optional.empty();
            }

            Object floodgatePlayer = optional.get();

            if (floodgatePlayer == null) {
                throw new IllegalStateException(
                        "Floodgate returned a null player."
                );
            }

            Object xuidResult = getXuidMethod.invoke(floodgatePlayer);

            if (xuidResult == null) {
                throw new IllegalStateException(
                        "Floodgate returned a null XUID."
                );
            }

            String xuid = xuidResult.toString().trim();

            if (!xuid.matches("[0-9]{1,20}")) {
                throw new IllegalStateException(
                        "Floodgate returned an invalid XUID."
                );
            }

            return Optional.of(xuid);

        } catch (IllegalAccessException
                 | InvocationTargetException
                 | LinkageError exception) {

            throw new IllegalStateException(
                    "Could not safely resolve Floodgate identity for "
                            + player.getName(),
                    exception
            );
        }
    }

    public String getIdentity(Player player) {
        if (player == null) {
            throw new IllegalArgumentException(
                    "Player cannot be null."
            );
        }

        Optional<String> xuid = getXuid(player);

        return xuid
                .map(value -> "BEDROCK:" + value)
                .orElseGet(() ->
                        "JAVA:" + player.getUniqueId()
                );
    }
}
