
package com.pritamx.thunderauth;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;

public final class AuthCommands implements CommandExecutor {

    private final ThunderAuth plugin;
    private final AuthManager authManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    public AuthCommands(ThunderAuth plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
    }

    @Override
    public boolean onCommand(
            CommandSender sender,
            Command command,
            String label,
            String[] args
    ) {
        String name = command.getName().toLowerCase();

        switch (name) {
            case "login", "log" -> handleLogin(sender, args);
            case "register", "reg" -> handleRegister(sender, args);
            case "changepass" -> handleChangePassword(sender, args);
            case "thunderauth" -> handleAdmin(sender, args);
            default -> send(sender, "general.unknown-command");
        }

        return true;
    }

    private void handleLogin(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "general.players-only");
            return;
        }

        if (authManager.isAuthenticated(player)) {
            send(player, "authentication.login.already-logged-in");
            return;
        }

        if (!plugin.getConfig().getBoolean("settings.login.enabled", true)) {
            send(player, "authentication.login.account-not-registered");
            return;
        }

        if (args.length != 1) {
            send(player, "authentication.login.usage");
            return;
        }

        char[] password = args[0].toCharArray();

        authManager.login(player, password).whenComplete((success, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    Arrays.fill(password, '\0');

                    if (!player.isOnline()) {
                        return;
                    }

                    if (error != null) {
                        plugin.getLogger().severe(
                                "Login database operation failed for "
                                        + player.getName()
                        );
                        send(player, "general.database-error");
                        return;
                    }

                    if (Boolean.TRUE.equals(success)) {
                        send(player, "authentication.login.success");
                    } else {
                        send(player, "authentication.login.incorrect-password");
                    }
                })
        );
    }

    private void handleRegister(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "general.players-only");
            return;
        }

        if (authManager.isAuthenticated(player)) {
            send(player, "authentication.register.already-registered");
            return;
        }

        if (!plugin.getConfig().getBoolean("settings.register.enabled", true)) {
            send(player, "authentication.register.usage");
            return;
        }

        if (args.length != 2) {
            send(player, "authentication.register.usage");
            return;
        }

        if (!args[0].equals(args[1])) {
            send(player, "authentication.register.password-mismatch");
            return;
        }

        int min = plugin.getConfig().getInt("settings.password.min-length", 6);
        int max = plugin.getConfig().getInt("settings.password.max-length", 32);

        if (args[0].length() < min) {
            send(player, "authentication.register.password-too-short",
                    "{min}", String.valueOf(min));
            return;
        }

        if (args[0].length() > max) {
            send(player, "authentication.register.password-too-long",
                    "{max}", String.valueOf(max));
            return;
        }

        char[] password = args[0].toCharArray();

        authManager.isRegistered(player).whenComplete((registered, checkError) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline()) {
                        Arrays.fill(password, '\0');
                        return;
                    }

                    if (checkError != null) {
                        Arrays.fill(password, '\0');
                        send(player, "general.database-error");
                        return;
                    }

                    if (Boolean.TRUE.equals(registered)) {
                        Arrays.fill(password, '\0');
                        send(player, "authentication.register.already-registered");
                        return;
                    }

                    authManager.register(player, password).whenComplete(
                            (success, error) ->
                                    Bukkit.getScheduler().runTask(plugin, () -> {
                                        Arrays.fill(password, '\0');

                                        if (!player.isOnline()) {
                                            return;
                                        }

                                        if (error != null) {
                                            plugin.getLogger().severe(
                                                    "Registration failed for "
                                                            + player.getName()
                                            );
                                            send(player, "general.database-error");
                                            return;
                                        }

                                        if (Boolean.TRUE.equals(success)) {
                                            send(player, "authentication.register.success");
                                        } else {
                                            send(player, "authentication.register.already-registered");
                                        }
                                    })
                    );
                })
        );
    }

    private void handleChangePassword(CommandSender sender, String[] args) {
        if (!(sender instanceof Player player)) {
            send(sender, "general.players-only");
            return;
        }

        if (!authManager.isAuthenticated(player)) {
            send(player, "security.command-blocked");
            return;
        }

        if (args.length != 2) {
            send(player, "authentication.password.usage");
            return;
        }

        int min = plugin.getConfig().getInt("settings.password.min-length", 6);
        int max = plugin.getConfig().getInt("settings.password.max-length", 32);

        if (args[1].length() < min || args[1].length() > max) {
            send(player, "authentication.register.password-too-short",
                    "{min}", String.valueOf(min));
            return;
        }

        char[] oldPassword = args[0].toCharArray();
        char[] newPassword = args[1].toCharArray();

        authManager.changePassword(player, oldPassword, newPassword)
                .whenComplete((changed, error) ->
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Arrays.fill(oldPassword, '\0');
                            Arrays.fill(newPassword, '\0');

                            if (!player.isOnline()) {
                                return;
                            }

                            if (error != null) {
                                send(player, "general.database-error");
                            } else if (Boolean.TRUE.equals(changed)) {
                                send(player, "authentication.password.success");
                            } else {
                                send(player, "authentication.password.incorrect-old-password");
                            }
                        })
                );
    }

    private void handleAdmin(CommandSender sender, String[] args) {
        if (!sender.hasPermission("thunderauth.admin")) {
            send(sender, "general.no-permission");
            return;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            plugin.reloadConfig();
            plugin.reloadMessages();
            send(sender, "general.reloaded");
            return;
        }

        send(sender, "general.unknown-command");
    }

    private void send(CommandSender sender, String path) {
        send(sender, path, null, null);
    }

    private void send(
            CommandSender sender,
            String path,
            String placeholder,
            String value
    ) {
        String prefix = plugin.getMessages().getString("prefix", "");
        String message = plugin.getMessages().getString(
                path, "<red>Missing message: " + path
        );

        if (placeholder != null && value != null) {
            message = message.replace(placeholder, value);
        }

        sender.sendMessage(
                miniMessage.deserialize(prefix + message)
        );
    }
}
