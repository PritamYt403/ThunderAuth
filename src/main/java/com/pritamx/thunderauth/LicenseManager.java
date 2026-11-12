
package com.pritamx.thunderauth;

import org.bukkit.configuration.file.FileConfiguration;

import java.util.concurrent.CompletableFuture;

public final class LicenseManager {

    private static final String FIXED_LICENSE_KEY =
            "PRITAMGAMING-THUNDERAUTH-001";

    private final ThunderAuth plugin;

    public LicenseManager(ThunderAuth plugin) {
        this.plugin = plugin;
    }

    public CompletableFuture<Boolean> validateLicense() {

        FileConfiguration config = plugin.getConfig();

        String configuredKey = config.getString(
                "license.key",
                ""
        );

        if (configuredKey == null || configuredKey.isBlank()) {
            plugin.getLogger().severe(
                    "ThunderAuth license key is missing!"
            );

            plugin.getLogger().severe(
                    "Set the license key in config.yml."
            );

            return CompletableFuture.completedFuture(false);
        }

        boolean valid = FIXED_LICENSE_KEY.equals(
                configuredKey.trim()
        );

        if (valid) {
            plugin.getLogger().info(
                    "================================"
            );
            plugin.getLogger().info(
                    " ThunderAuth License: VALID"
            );
            plugin.getLogger().info(
                    " License Owner: PritamGaming"
            );
            plugin.getLogger().info(
                    " License Type: Fixed Permanent"
            );
            plugin.getLogger().info(
                    " Server Restriction: None"
            );
            plugin.getLogger().info(
                    "================================"
            );

            return CompletableFuture.completedFuture(true);
        }

        plugin.getLogger().severe(
                "================================"
        );
        plugin.getLogger().severe(
                " ThunderAuth License: INVALID"
        );
        plugin.getLogger().severe(
                " Please check license.key in config.yml."
        );
        plugin.getLogger().severe(
                "================================"
        );

        return CompletableFuture.completedFuture(false);
    }
}
