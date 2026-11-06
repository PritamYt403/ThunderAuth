
package com.pritamx.thunderauth;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

public final class FloodgateHook {

    private final ThunderAuth plugin;
    private Object floodgateApi;
    private Method getPlayerMethod;
    private Method getXuidMethod;
    private boolean enabled;

    public FloodgateHook(ThunderAuth plugin) {
        this.plugin = plugin;
    }

    public void setup() {
        enabled = false;
        floodgateApi = null;

        if (!plugin.getConfig().getBoolean(
                "integrations.floodgate.enabled", true)) {
            plugin.getLogger().info("Floodgate integration is disabled.");
            return;
        }

        Plugin floodgate = Bukkit.getPluginManager()
                .getPlugin("floodgate");

        if (floodgate == null || !floodgate.isEnabled()) {
            plugin.getLogger().info(
                    "Floodgate not found. Using Java UUID identity."
            );
            return;
        }

        try {
            Class<?> apiClass = Class.forName(
                    "org.geysermc.floodgate.api.FloodgateApi"
            );

            Object api = apiClass.getMethod("getInstance").invoke(null);

            Method playerMethod = apiClass.getMethod(
                    "getPlayer", UUID.class
            );

            Class<?> floodgatePlayerClass = Class.forName(
                    "org.geysermc.floodgate.api.player.FloodgatePlayer"
            );

            Method xuidMethod = floodgatePlayerClass.getMethod("getXuid");

            floodgateApi = api;
            getPlayerMethod = playerMethod;
            getXuidMethod = xuidMethod;
            enabled = true;

            plugin.getLogger().info(
                    "Floodgate integration enabled successfully."
            );

        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning(
                    "Could not hook Floodgate: " + exception.getMessage()
            );
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public Optional<String> getXuid(Player player) {
        if (!enabled || floodgateApi == null) {
            return Optional.empty();
        }

        try {
            Object result = getPlayerMethod.invoke(
                    floodgateApi,
                    player.getUniqueId()
            );

            if (!(result instanceof Optional<?> optional)
                    || optional.isEmpty()) {
                return Optional.empty();
            }

            Object floodgatePlayer = optional.get();
            Object xuid = getXuidMethod.invoke(floodgatePlayer);

            if (xuid == null) {
                return Optional.empty();
            }

            String value = xuid.toString().trim();

            return value.isEmpty()
                    ? Optional.empty()
                    : Optional.of(value);

        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning(
                    "Could not read Floodgate XUID for "
                            + player.getName() + ": "
                            + exception.getMessage()
            );

            return Optional.empty();
        }
    }

    public String getIdentity(Player player) {
        return getXuid(player)
                .map(xuid -> "BEDROCK:" + xuid)
                .orElse("JAVA:" + player.getUniqueId());
    }
}
