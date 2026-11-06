package com.pritamx.thunderauth;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;

public final class LicenseManager {

    private final ThunderAuth plugin;
    private final LicenseValidator validator;

    public LicenseManager(ThunderAuth plugin) {
        this.plugin = plugin;
        this.validator = new LicenseValidator();
    }

    public CompletableFuture<Boolean> validateLicense() {

        FileConfiguration config = plugin.getConfig();

        String licenseKey = config.getString("license.key", "").trim();

        if (licenseKey.isBlank()) {
            plugin.getLogger().severe("No license key configured!");
            plugin.getLogger().severe(
                    "Set license.key in config.yml."
            );

            return CompletableFuture.completedFuture(false);
        }

        final String serverId;

        try {
            serverId = ServerIdentity.loadOrCreate(
                    plugin.getDataFolder().toPath()
            );
        } catch (IOException exception) {
            plugin.getLogger().log(
                    Level.SEVERE,
                    "Could not load or create server identity.",
                    exception
            );

            return CompletableFuture.completedFuture(false);
        }

        String serverName = Bukkit.getServer().getName();

        plugin.getLogger().info("Checking ThunderAuth license...");

        return validator.validate(
                licenseKey,
                serverId,
                serverName
        ).thenApply(result -> {

            if (result.valid()) {
                plugin.getLogger().info(
                        "ThunderAuth license successfully verified."
                );
                return true;
            }

            plugin.getLogger().severe(
                    "ThunderAuth license validation failed."
            );

            plugin.getLogger().severe(
                    "Reason: " + safeReason(result.response())
            );

            return false;
        });
    }

    private String safeReason(String response) {

        if (response == null || response.isBlank()) {
            return "License server did not provide a response.";
        }

        String clean = response
                .replaceAll("[\\r\\n\\t]", " ")
                .trim();

        return clean.length() > 250
                ? clean.substring(0, 250)
                : clean;
    }
}
