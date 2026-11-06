
package com.pritamx.thunderauth;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

public final class ThunderAuth extends JavaPlugin {

    private AccountStore accountStore;
    private AuthManager authManager;
    private final AtomicBoolean shuttingDown = new AtomicBoolean(false);

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getLogger().info("Starting ThunderAuth...");
        getLogger().info("Checking license before opening account database...");

        LicenseManager licenseManager = new LicenseManager(this);

        licenseManager.validateLicense().whenComplete((valid, error) ->
                Bukkit.getScheduler().runTask(this, () -> {

                    if (shuttingDown.get() || !isEnabled()) {
                        return;
                    }

                    if (error != null) {
                        getLogger().log(Level.SEVERE,
                                "License validation could not complete.", error);
                        getServer().getPluginManager().disablePlugin(this);
                        return;
                    }

                    if (!Boolean.TRUE.equals(valid)) {
                        getLogger().severe(
                                "ThunderAuth disabled: a valid license is required."
                        );
                        getServer().getPluginManager().disablePlugin(this);
                        return;
                    }

                    initializeAccountDatabase();
                })
        );
    }

    private void initializeAccountDatabase() {
        try {
            String databaseName = getConfig().getString(
                    "storage.sqlite.file", "accounts.db"
            );

            if (databaseName == null || databaseName.isBlank()) {
                throw new IOException("Database filename is empty.");
            }

            accountStore = new AccountStore(
                    getDataFolder().toPath(), databaseName
            );

            authManager = new AuthManager(this, accountStore);

        } catch (SQLException | IOException exception) {
            getLogger().log(Level.SEVERE,
                    "Could not initialize the account database!", exception);
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("--------------------------------");
        getLogger().info(" ThunderAuth initialized!");
        getLogger().info(" License: Valid");
        getLogger().info(" Database: SQLite");
        getLogger().info(" Version: " + getDescription().getVersion());
        getLogger().info("--------------------------------");
    }

    @Override
    public void onDisable() {
        shuttingDown.set(true);

        if (accountStore == null) {
            return;
        }

        try {
            boolean backupEnabled = getConfig().getBoolean(
                    "storage.sqlite.backup-on-shutdown", true
            );

            if (backupEnabled) {
                Path backupFolder = getDataFolder().toPath().resolve("backups");
                accountStore.closeAndBackup(backupFolder);
            } else {
                accountStore.close();
            }

            getLogger().info("Account database closed successfully.");

        } catch (SQLException | IOException exception) {
            getLogger().log(Level.SEVERE, "Database shutdown error!", exception);
        } finally {
            accountStore = null;
            authManager = null;
        }
    }

    public AccountStore getAccountStore() {
        return accountStore;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }
}
