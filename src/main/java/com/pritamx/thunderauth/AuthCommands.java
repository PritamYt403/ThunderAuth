
package com.pritamx.thunderauth;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class AuthCommands implements CommandExecutor {

    private final ThunderAuth plugin;
    private final AccountStore accounts;
    private final AuthManager auth;

    public AuthCommands(
            ThunderAuth plugin,
            AccountStore accounts,
            AuthManager auth
    ) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.auth = auth;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        switch (command.getName().toLowerCase()) {
            case "register":
                return handleRegister(sender, args);
            case "login":
                return handleLogin(sender, args);
            case "changepassword":
                return handlePasswordChange(sender, args);
            case "thunderauth":
                return handleAdmin(sender, args);
            case "setlogspawn":
                return handleSetSpawn(sender, true);
            case "setregspawn":
                return handleSetSpawn(sender, false);
            default:
                return false;
        }
    }

    private String getIdentity(Player player) {
        FloodgateHook hook = plugin.getFloodgateHook();

        if (hook == null) {
            return null;
        }

        return hook.getIdentity(player);
    }

    private boolean handleRegister(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("commands.player-only"));
            return true;
        }

        if (args.length != 2) {
            player.sendMessage(plugin.messageWithoutPrefix(
                    "authentication.register-required"));
            return true;
        }

        if (auth.isAuthenticated(player)) {
            player.sendMessage(plugin.message(
                    "authentication.register-success"));
            return true;
        }

        if (auth.hasAccount(player.getUniqueId())) {
            player.sendMessage(plugin.message(
                    "authentication.already-registered"));
            return true;
        }

        if (!MessageDigest.isEqual(
                args[0].getBytes(StandardCharsets.UTF_8),
                args[1].getBytes(StandardCharsets.UTF_8))) {
            player.sendMessage(plugin.message(
                    "authentication.password-mismatch"));
            return true;
        }

        int min = plugin.getConfig().getInt(
                "settings.password.min-length", 6);
        int max = plugin.getConfig().getInt(
                "settings.password.max-length", 64);

        if (args[0].length() < min) {
            player.sendMessage(plugin.message(
                    "authentication.password-too-short"));
            return true;
        }

        if (args[0].length() > max) {
            player.sendMessage(plugin.message(
                    "authentication.password-too-long"));
            return true;
        }

        String identity = getIdentity(player);

        if (identity == null || identity.isBlank()) {
            player.sendMessage(plugin.message(
                    "authentication.identity-unavailable"));
            return true;
        }

        if (auth.register(player, identity, args[0])) {
            player.sendMessage(plugin.message(
                    "authentication.register-success"));

            if (plugin.getConfig().getBoolean(
                    "settings.announcements.welcome-on-register", true)) {
                player.sendMessage(plugin.messageWithoutPrefix(
                        "announcements.welcome-register"));
            }

            Bukkit.broadcastMessage(plugin.messageWithoutPrefix(
                    "announcements.registered")
                    .replace("%player%", player.getName()));

            teleportToConfiguredSpawn(player, true);
        } else {
            player.sendMessage(plugin.message(
                    "authentication.already-registered"));
        }

        return true;
    }

    private boolean handleLogin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("commands.player-only"));
            return true;
        }

        if (args.length != 1) {
            player.sendMessage(plugin.messageWithoutPrefix(
                    "authentication.login-required"));
            return true;
        }

        if (auth.isAuthenticated(player)) {
            player.sendMessage(plugin.message(
                    "authentication.login-success"));
            return true;
        }

        if (!auth.hasAccount(player.getUniqueId())) {
            player.sendMessage(plugin.message(
                    "authentication.not-registered"));
            return true;
        }

        String identity = getIdentity(player);

        if (identity == null || identity.isBlank()) {
            player.sendMessage(plugin.message(
                    "authentication.identity-unavailable"));
            return true;
        }

        String storedIdentity = accounts.getIdentity(player.getUniqueId());

        if (storedIdentity == null || !storedIdentity.equals(identity)) {
            player.sendMessage(plugin.message(
                    "authentication.identity-mismatch"));
            return true;
        }

        if (auth.login(player, args[0], identity)) {
            player.sendMessage(plugin.message(
                    "authentication.login-success"));

            if (plugin.getConfig().getBoolean(
                    "settings.announcements.welcome-on-login", true)) {
                player.sendMessage(plugin.messageWithoutPrefix(
                        "announcements.welcome-login"));
            }

            Bukkit.broadcastMessage(plugin.messageWithoutPrefix(
                    "announcements.logged-in")
                    .replace("%player%", player.getName()));

            teleportToConfiguredSpawn(player, false);
        } else if (player.isOnline()) {
            player.sendMessage(plugin.message(
                    "authentication.wrong-password"));
        }

        return true;
    }

    private boolean handleSetSpawn(
            CommandSender sender,
            boolean loginSpawn
    ) {
        if (!sender.hasPermission("thunderauth.admin")) {
            sender.sendMessage(plugin.message("commands.no-permission"));
            return true;
        }

        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("commands.player-only"));
            return true;
        }

        Location location = player.getLocation();
        World world = location.getWorld();

        if (world == null) {
            player.sendMessage(plugin.color(
                    "&cCould not determine your current world."));
            return true;
        }

        String base = loginSpawn
                ? "settings.spawn.normal-"
                : "settings.spawn.first-registration-";

        plugin.getConfig().set(base + "world", world.getName());
        plugin.getConfig().set(base + "x", location.getX());
        plugin.getConfig().set(base + "y", location.getY());
        plugin.getConfig().set(base + "z", location.getZ());
        plugin.getConfig().set(base + "yaw", location.getYaw());
        plugin.getConfig().set(base + "pitch", location.getPitch());

        if (!loginSpawn) {
            plugin.getConfig().set(
                    "settings.spawn.first-registration-enabled", true);
        }

        plugin.saveConfig();

        player.sendMessage(plugin.color(
                loginSpawn
                        ? "&aLogin spawn location saved!"
                        : "&aRegistration spawn location saved!"));

        return true;
    }

    private void teleportToConfiguredSpawn(
            Player player,
            boolean firstRegistration
    ) {
        if (firstRegistration && !plugin.getConfig().getBoolean(
                "settings.spawn.first-registration-enabled", true)) {
            return;
        }

        String base = firstRegistration
                ? "settings.spawn.first-registration-"
                : "settings.spawn.normal-";

        String worldName = plugin.getConfig().getString(
                base + "world", "world");

        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            plugin.getLogger().warning(
                    "Spawn world '" + worldName + "' was not found.");
            return;
        }

        double x = plugin.getConfig().getDouble(base + "x", 0.5);
        double y = plugin.getConfig().getDouble(base + "y", 65.0);
        double z = plugin.getConfig().getDouble(base + "z", 0.5);
        float yaw = (float) plugin.getConfig().getDouble(base + "yaw", 0.0);
        float pitch = (float) plugin.getConfig().getDouble(base + "pitch", 0.0);

        player.teleport(new Location(world, x, y, z, yaw, pitch));
    }

    private boolean handlePasswordChange(
            CommandSender sender,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(plugin.message("commands.player-only"));
            return true;
        }

        if (args.length != 2) {
            player.sendMessage(plugin.messageWithoutPrefix(
                    "password.usage"));
            return true;
        }

        if (!auth.isAuthenticated(player)) {
            player.sendMessage(plugin.message(
                    "authentication.login-required"));
            return true;
        }

        int min = plugin.getConfig().getInt(
                "settings.password.min-length", 6);
        int max = plugin.getConfig().getInt(
                "settings.password.max-length", 64);

        if (args[1].length() < min) {
            player.sendMessage(plugin.message(
                    "authentication.password-too-short"));
            return true;
        }

        if (args[1].length() > max) {
            player.sendMessage(plugin.message(
                    "authentication.password-too-long"));
            return true;
        }

        if (auth.changePassword(player, args[0], args[1])) {
            player.sendMessage(plugin.message("password.changed"));
        } else {
            player.sendMessage(plugin.message(
                    "password.old-password-wrong"));
        }

        return true;
    }

    private boolean handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("thunderauth.admin")) {
            sender.sendMessage(plugin.message("commands.no-permission"));
            return true;
        }

        if (args.length != 1
                || !args[0].equalsIgnoreCase("reload")) {
            sender.sendMessage(plugin.messageWithoutPrefix(
                    "commands.reload-usage"));
            return true;
        }

        plugin.reloadThunderAuth();
        sender.sendMessage(plugin.message("commands.reload-success"));
        return true;
    }
}
