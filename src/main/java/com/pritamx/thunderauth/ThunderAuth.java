package com.pritamx.thunderauth;

import org.bukkit.plugin.java.JavaPlugin;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;

public final class ThunderAuth extends JavaPlugin {

    private AccountStore accountStore;
    private AuthManager authManager;

    @Override
    public void onEnable() {

        saveDefaultConfig();

        getLogger().info("Starting ThunderAuth...");

        try {
            String databaseName = getConfig().getString(
                    "storage.sqlite.file",
                    "accounts.db"
            );

            accountStore = new AccountStore(
                    getDataFolder().toPath(),
                    databaseName
            );

            authManager = new AuthManager(
                    this,
                    accountStore
            );

        } catch (SQLException | IOException exception) {

            getLogger().severe(
                    "Could not initialize the account database!"
            );

            exception.printStackTrace();

            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        getLogger().info("--------------------------------");
        getLogger().info(" ThunderAuth initialized!");
        getLogger().info(" Database: SQLite");
        getLogger().info(" Version: " + getDescription().getVersion());
        getLogger().info("--------------------------------");
    }

    @Override
    public void onDisable() {

        if (accountStore == null) {
            return;
        }

        try {
            boolean backupEnabled = getConfig().getBoolean(
                    "storage.sqlite.backup-on-shutdown",
                    true
            );

            if (backupEnabled) {

                Path backupFolder = getDataFolder()
                        .toPath()
                        .resolve("backups");

                accountStore.closeAndBackup(backupFolder);

            } else {
                accountStore.close();
            }

            getLogger().info("Account database closed successfully.");

        } catch (SQLException | IOException exception) {

            getLogger().severe("Database shutdown error!");
            exception.printStackTrace();
        }
    }

    public AccountStore getAccountStore() {
        return accountStore;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }
}
