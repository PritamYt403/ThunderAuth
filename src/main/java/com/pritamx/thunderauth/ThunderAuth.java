package com.pritamx.thunderauth;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
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
        loadMessages();

        accountStore = new AccountStore(this);
        authManager = new AuthManager(this, accountStore);
        authCommands = new AuthCommands(this, accountStore, authManager);
        authListener = new AuthListener(this, authManager);
        bossBarManager = new BossBarManager(this, authManager);
        floodgateHook = new FloodgateHook(this);

        registerCommands();
        Bukkit.getPluginManager().registerEvents(authListener, this);

        getLogger().info("==================================");
        getLogger().info(" ThunderAuth Enabled Successfully");
        getLogger().info(" Created by Pritam");
        getLogger().info("==================================");
    }

    @Override
    public void onDisable() {
        if (bossBarManager != null) {
            bossBarManager.stopAll();
        }

        getLogger().info("ThunderAuth has been disabled.");
    }

    private void registerCommands() {
        String[] commands = {
                "register",
                "login",
                "changepassword",
                "thunderauth"
        };

        for (String name : commands) {
            var command = getCommand(name);

            if (command == null) {
                getLogger().severe(
                        "Command '" + name + "' is missing from plugin.yml!"
                );
                continue;
            }

            command.setExecutor(authCommands);
        }
    }

    private void loadMessages() {
        if (!getDataFolder().exists()
                && !getDataFolder().mkdirs()) {
            getLogger().severe(
                    "Could not create ThunderAuth data folder."
            );
        }

        messagesFile = new File(getDataFolder(), "messages.yml");

        if (!messagesFile.exists()) {
            saveResource("messages.yml", false);
        }

        messages = YamlConfiguration.loadConfiguration(messagesFile);
    }

    public FileConfiguration getMessages() {
        return messages;
    }

    public String message(String path) {
        String prefix = messages.getString("prefix", "");
        String text = messages.getString(
                path, "&cMissing message: " + path
        );

        return color(prefix + text);
    }

    public String messageWithoutPrefix(String path) {
        String text = messages.getString(
                path, "&cMissing message: " + path
        );

        return color(text);
    }

    public String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public void reloadThunderAuth() {
        reloadConfig();

        try {
            messages = YamlConfiguration.loadConfiguration(messagesFile);
            floodgateHook.refresh();
            getLogger().info("Configuration and messages reloaded.");
        } catch (Exception exception) {
            getLogger().log(
                    Level.SEVERE,
                    "Could not reload ThunderAuth configuration.",
                    exception
            );
        }
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
