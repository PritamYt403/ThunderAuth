
package com.pritamx.thunderauth;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.Optional;
import java.util.UUID;

public final class FloodgateHook {

    private final ThunderAuth plugin;

    private boolean available;
    private boolean apiErrorLogged;

    public FloodgateHook(ThunderAuth plugin) {
        this.plugin = plugin;
        refresh();
    }

    public void refresh() {
        boolean enabled = plugin.getConfig().getBoolean(
                "integrations.floodgate.enabled", true)
                && plugin.getConfig().getBoolean(
                "settings.security.bedrock-floodgate-support", true);

        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");

        available = enabled
                && floodgate != null
                && floodgate.isEnabled();

        apiErrorLogged = false;

        if (available) {
            plugin.getLogger().info(
                    "Floodgate detected. Bedrock identity support enabled.");
        } else if (enabled) {
            plugin.getLogger().warning(
                    "Floodgate is unavailable. Bedrock identity verification "
                            + "cannot be performed.");
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public String getIdentity(Player player) {
        if (player == null) {
            return null;
        }

        if (available) {
            Boolean bedrock = checkFloodgatePlayer(player.getUniqueId());

            if (bedrock == null) {
                return null;
            }

            if (bedrock) {
                String xuid = getXuid(player.getUniqueId());

                if (xuid != null && !xuid.isBlank()) {
                    return "BEDROCK:" + xuid;
                }

                if (plugin.getConfig().getBoolean(
                        "settings.security.reject-unverified-bedrock-identity",
                        true)) {
                    return null;
                }
            }
        }

        return "JAVA:" + player.getUniqueId();
    }

    public boolean isFloodgatePlayer(UUID uuid) {
        return Boolean.TRUE.equals(checkFloodgatePlayer(uuid));
    }

    private Boolean checkFloodgatePlayer(UUID uuid) {
        if (!available || uuid == null) {
            return null;
        }

        try {
            Class<?> apiClass = Class.forName(
                    "org.geysermc.floodgate.api.FloodgateApi");

            Object api = apiClass.getMethod("getInstance").invoke(null);

            Object result = apiClass.getMethod(
                    "isFloodgatePlayer", UUID.class).invoke(api, uuid);

            return result instanceof Boolean value ? value : null;
        } catch (ReflectiveOperationException | LinkageError exception) {
            logApiError("Could not check Floodgate player identity", exception);
            return null;
        }
    }

    public String getXuid(UUID uuid) {
        if (!available || uuid == null) {
            return null;
        }

        Boolean bedrock = checkFloodgatePlayer(uuid);
        if (!Boolean.TRUE.equals(bedrock)) {
            return null;
        }

        try {
            Class<?> apiClass = Class.forName(
                    "org.geysermc.floodgate.api.FloodgateApi");

            Object api = apiClass.getMethod("getInstance").invoke(null);

            Object player = apiClass.getMethod(
                    "getPlayer", UUID.class).invoke(api, uuid);

            if (player instanceof Optional<?> optional) {
                player = optional.orElse(null);
            }

            if (player == null) {
                return null;
            }

            Object xuid = player.getClass()
                    .getMethod("getXuid")
                    .invoke(player);

            return xuid == null ? null : xuid.toString();
        } catch (ReflectiveOperationException | LinkageError exception) {
            logApiError("Could not read Floodgate XUID", exception);
            return null;
        }
    }

    private void logApiError(String message, Throwable exception) {
        if (apiErrorLogged) {
            return;
        }

        apiErrorLogged = true;
        plugin.getLogger().warning(
                message + ": " + exception.getClass().getSimpleName());
    }
}
