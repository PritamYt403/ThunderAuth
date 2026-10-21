
package com.pritamx.thunderauth;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.logging.Level;

public final class ThunderAuth extends JavaPlugin {

    private File messagesFile;
    private FileConfiguration messages;

    private AccountStore accountStore;
    private AuthManager authManager;
    private AuthCommands authCommands;
    private AuthListener authListener;
    private BossBarManager bossBarManager;
    private FloodgateHook floodgateHook;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        if (!loadMessages()) {
            getServer().getPluginManager().disablePlugin(this);
            return;
        }

        try {
            accountStore = new AccountStore(this);
            authManager = new AuthManager(this, accountStore);
            bossBarManager = new BossBarManager(this, authManager);
            floodgateHook = new FloodgateHook(this);

            authCommands = new AuthCommands(
                    this, accountStore, authManager);
            authListener = new AuthListener(this, authManager);

            if (!registerCommands()) {
                getServer().getPluginManager().disablePlugin(this);
                return;
            }

            Bukkit.getPluginManager().registerEvents(authListener, this);

            getLogger().info("==================================");
            getLogger().info(" ThunderAuth Enabled Successfully");
            getLogger().info(" Created by Pritam");
            getLogger().info("==================================");

        } catch (Exception exception) {
            getLogger().log(
                    Level.SEVERE,
                    "ThunderAuth failed to initialize.",
                    exception);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (bossBarManager != null) {
            bossBarManager.stopAll();
        }

        getLogger().info("ThunderAuth has been disabled.");
    }

    private boolean registerCommands() {
        String[] commands = {
                "register",
                "login",
                "changepassword",
                "thunderauth",
                "setlogspawn",
                "setregspawn"
        };

        boolean success = true;

        for (String name : commands) {
            org.bukkit.command.PluginCommand command = getCommand(name);

            if (command == null) {
                getLogger().severe(
                        "Command '" + name
                                + "' is missing from plugin.yml!");
                success = false;
                continue;
            }

            command.setExecutor(authCommands);
        }

        return success;
    }

    private boolean loadMessages() {
        if (!getDataFolder().exists()
                && !getDataFolder().mkdirs()) {
            getLogger().severe(
                    "Could not create ThunderAuth data folder.");
            return false;
        }

        messagesFile = new File(getDataFolder(), "messages.yml");

        if (!messagesFile.exists()) {
            try {
                saveResource("messages.yml", false);
            } catch (IllegalArgumentException exception) {
                getLogger().log(
                        Level.SEVERE,
                        "Default messages.yml is missing from the plugin JAR.",
                        exception);
                return false;
            }
        }

        messages = YamlConfiguration.loadConfiguration(messagesFile);
        return true;
    }

    public FileConfiguration getMessages() {
        return messages;
    }

    public String message(String path) {
        String prefix = messages == null
                ? ""
                : messages.getString("prefix", "");

        return color(prefix + getMessageText(path));
    }

    public String messageWithoutPrefix(String path) {
        return color(getMessageText(path));
    }

    private String getMessageText(String path) {
        if (messages == null) {
            return "&cMessages are not loaded.";
        }

        return messages.getString(
                path, "&cMissing message: " + path);
    }

    public String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public void reloadThunderAuth() {
        reloadConfig();

        if (messagesFile == null || !messagesFile.isFile()) {
            getLogger().warning(
                    "messages.yml is missing; cannot reload messages.");
            return;
        }

        messages = YamlConfiguration.loadConfiguration(messagesFile);

        if (floodgateHook != null) {
            floodgateHook.refresh();
        }

        getLogger().info("Configuration and messages reloaded.");
    }

    public AccountStore getAccountStore() {
        return accountStore;
    }

    public AuthManager getAuthManager() {
        return authManager;
    }

    public BossBarManager getBossBarManager() {
        return bossBarManager;
    }

    public FloodgateHook getFloodgateHook() {
        return floodgateHook;
    }
}
