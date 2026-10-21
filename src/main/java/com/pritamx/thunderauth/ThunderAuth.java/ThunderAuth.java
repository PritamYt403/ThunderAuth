package com.pritamx.thunderauth;

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

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadMessages();

        getLogger().info("==================================");
        getLogger().info(" ThunderAuth Enabled Successfully");
        getLogger().info(" Created by Pritam");
        getLogger().info("==================================");
    }

    @Override
    public void onDisable() {
        getLogger().info("ThunderAuth has been disabled.");
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
        String text = messages.getString(path, "&cMissing message: " + path);

        return color(prefix + text);
    }

    public String messageWithoutPrefix(String path) {
        String text = messages.getString(path, "&cMissing message: " + path);

        return color(text);
    }

    public String color(String text) {
        return ChatColor.translateAlternateColorCodes('&', text);
    }

    public void reloadThunderAuth() {
        reloadConfig();

        try {
            messages.load(messagesFile);
        } catch (IOException | org.bukkit.configuration.InvalidConfigurationException exception) {
            getLogger().log(
                    Level.SEVERE,
                    "Could not reload messages.yml",
                    exception
            );
        }
    }
                 }
