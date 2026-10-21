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

    public FloodgateHook(ThunderAuth plugin) {
        this.plugin = plugin;
        refresh();
    }

    public void refresh() {
        boolean enabled = plugin.getConfig().getBoolean(
                "integrations.floodgate.enabled", true
        ) && plugin.getConfig().getBoolean(
                "settings.security.bedrock-floodgate-support", true
        );

        Plugin floodgate = Bukkit.getPluginManager().getPlugin("floodgate");

        available = enabled
                && floodgate != null
                && floodgate.isEnabled();

        if (available) {
            plugin.getLogger().info(
                    "Floodgate detected. Bedrock identity support enabled."
            );
        } else if (enabled) {
            plugin.getLogger().info(
                    "Floodgate not detected. Java UUID identity will be used."
            );
        }
    }

    public boolean isAvailable() {
        return available;
    }

    /**
     * Returns a stable identity key for a player.
     * Floodgate XUID is used for Bedrock players when available.
     * Java players use their Java UUID.
     */
    public String getIdentity(Player player) {
        if (player == null) {
            return null;
        }

        if (available && isFloodgatePlayer(player.getUniqueId())) {
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

        return "JAVA:" + player.getUniqueId();
    }

    public boolean isFloodgatePlayer(UUID uuid) {
        if (!available || uuid == null) {
            return false;
        }

        try {
            Class<?> apiClass = Class.forName(
                    "org.geysermc.floodgate.api.FloodgateApi"
            );

            Object api = apiClass.getMethod("getInstance").invoke(null);

            Method method = apiClass.getMethod(
                    "isFloodgatePlayer", UUID.class
            );

            Object result = method.invoke(api, uuid);

            return result instanceof Boolean value && value;
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning(
                    "Could not check Floodgate player identity: "
                            + exception.getMessage()
            );
            return false;
        }
    }

    public String getXuid(UUID uuid) {
        if (!available || uuid == null || !isFloodgatePlayer(uuid)) {
            return null;
        }

        try {
            Class<?> apiClass = Class.forName(
                    "org.geysermc.floodgate.api.FloodgateApi"
            );

            Object api = apiClass.getMethod("getInstance").invoke(null);

            Object player = apiClass.getMethod(
                    "getPlayer", UUID.class
            ).invoke(api, uuid);

            if (player instanceof Optional<?> optional) {
                player = optional.orElse(null);
            }

            if (player == null) {
                return null;
            }

            Object xuid = player.getClass().getMethod("getXuid").invoke(player);

            return xuid == null ? null : xuid.toString();
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning(
                    "Could not read Floodgate XUID: "
                            + exception.getMessage()
            );
            return null;
        }
    }
}
