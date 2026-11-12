
package com.pritamx.thunderauth;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthCommands implements CommandExecutor {

    private final ThunderAuth plugin;
    private final AuthManager authManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final Set<UUID> operationsInProgress =
            ConcurrentHashMap.newKeySet();

    public AuthCommands(
            ThunderAuth plugin,
            AuthManager authManager
    ) {
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
        String name = command.getName().toLowerCase(Locale.ROOT);

        switch (name) {
            case "login", "log" -> handleLogin(sender, args);
            case "register" -> handleRegister(sender, args);
            case "changepass" -> handleChangePassword(sender, args);
            case "thunderauth" -> handleAdmin(sender, args);
            case "setlogspawn" -> handleSetSpawn(sender, true);
            case "setregspawn" -> handleSetSpawn(sender, false);
            default -> send(sender, "general.unknown-command");
        }

        return true;
    }

    private boolean beginOperation(Player player) {
        if (player == null) {
            return false;
        }

        if (!operationsInProgress.add(player.getUniqueId())) {
            send(player, "authentication.operation-in-progress");
            return false;
        }

        return true;
    }

    private void endOperation(UUID playerId) {
        operationsInProgress.remove(playerId);
    }

    // --------------------------------------------------
    // LOGIN
    // --------------------------------------------------

    private void handleLogin(
            CommandSender sender,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            send(sender, "general.players-only");
            return;
        }

        if (authManager.isAuthenticated(player)) {
            send(player, "authentication.login.already-logged-in");
            return;
        }

        if (!plugin.getConfig().getBoolean(
                "settings.login.enabled", true
        )) {
            send(
                    player,
                    "authentication.login.account-not-registered"
            );
            return;
        }

        if (args.length != 1) {
            send(player, "authentication.login.usage");
            return;
        }

        if (!beginOperation(player)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        char[] password = args[0].toCharArray();

        authManager.login(player, password).whenComplete(
                (success, error) -> runOnMain(() -> {

                    Arrays.fill(password, '\0');

                    if (!player.isOnline()) {
                        endOperation(playerId);
                        return;
                    }

                    if (error != null) {
                        endOperation(playerId);

                        plugin.getLogger().severe(
                                "Login database operation failed for "
                                        + player.getName() + ": "
                                        + error.getMessage()
                        );

                        send(player, "general.database-error");
                        return;
                    }

                    if (Boolean.TRUE.equals(success)
                            && authManager.isAuthenticated(player)) {

                        endOperation(playerId);

                        AuthListener listener =
                                plugin.getAuthListener();

                        if (listener != null) {
                            listener.completeAuthentication(player);
                        }

                        send(
                                player,
                                "authentication.login.success"
                        );

                        broadcastAnnouncement("login", player);
                        return;
                    }

                    int maxAttempts = Math.max(
                            1,
                            plugin.getConfig().getInt(
                                    "settings.login.max-attempts",
                                    5
                            )
                    );

                    int remaining = authManager.recordFailedLogin(
                            player,
                            maxAttempts
                    );

                    endOperation(playerId);

                    if (remaining <= 0) {
                        kick(
                                player,
                                "authentication.login.max-attempts"
                        );
                    } else {
                        send(
                                player,
                                "authentication.login.incorrect-password",
                                "{attempts}",
                                String.valueOf(remaining)
                        );
                    }
                })
        );
    }

    // --------------------------------------------------
    // REGISTER
    // --------------------------------------------------

    private void handleRegister(
            CommandSender sender,
            String[] args
    ) {
        if (!(sender instanceof Player player)) {
            send(sender, "general.players-only");
            return;
        }

        if (authManager.isAuthenticated(player)) {
            send(
                    player,
                    "authentication.register.already-registered"
            );
            return;
        }

        if (!plugin.getConfig().getBoolean(
                "settings.register.enabled", true
        )) {
            send(player, "authentication.register.usage");
            return;
        }

        if (args.length != 2) {
            send(player, "authentication.register.usage");
            return;
        }

        if (!args[0].equals(args[1])) {
            send(
                    player,
                    "authentication.register.password-mismatch"
            );
            return;
        }

        int min = plugin.getConfig().getInt(
                "settings.password.min-length",
                6
        );

        int max = plugin.getConfig().getInt(
                "settings.password.max-length",
                32
        );

        if (min < 1 || max < min) {
            plugin.getLogger().severe(
                    "Invalid password length configuration."
            );

            send(player, "general.database-error");
            return;
        }

        if (args[0].length() < min) {
            send(
                    player,
                    "authentication.register.password-too-short",
                    "{min}",
                    String.valueOf(min)
            );
            return;
        }

        if (args[0].length() > max) {
            send(
                    player,
                    "authentication.register.password-too-long",
                    "{max}",
                    String.valueOf(max)
            );
            return;
        }

        if (!beginOperation(player)) {
            return;
        }

        UUID playerId = player.getUniqueId();
        char[] password = args[0].toCharArray();

        authManager.isRegistered(player).whenComplete(
                (registered, checkError) -> runOnMain(() -> {

                    if (!player.isOnline()) {
                        Arrays.fill(password, '\0');
                        endOperation(playerId);
                        return;
                    }

                    if (checkError != null) {
                        Arrays.fill(password, '\0');
                        endOperation(playerId);

                        plugin.getLogger().severe(
                                "Registration account check failed for "
                                        + player.getName() + ": "
                                        + checkError.getMessage()
                        );

                        send(player, "general.database-error");
                        return;
                    }

                    if (Boolean.TRUE.equals(registered)) {
                        Arrays.fill(password, '\0');
                        endOperation(playerId);

                        send(
                                player,
                                "authentication.register.already-registered"
                        );
                        return;
                    }

                    authManager.register(
                            player,
                            password
                    ).whenComplete((success, error) ->
                            runOnMain(() -> {

                                Arrays.fill(password, '\0');

                                if (!player.isOnline()) {
                                    endOperation(playerId);
                                    return;
                                }

                                if (error != null) {
                                    endOperation(playerId);

                                    plugin.getLogger().severe(
                                            "Registration failed for "
                                                    + player.getName() + ": "
                                                    + error.getMessage()
                                    );

                                    send(
                                            player,
                                            "general.database-error"
                                    );
                                    return;
                                }

                                if (Boolean.TRUE.equals(success)
                                        && authManager.isAuthenticated(player)) {

                                    endOperation(playerId);

                                    AuthListener listener =
                                            plugin.getAuthListener();

                                    if (listener != null) {
                                        listener.completeAuthentication(
                                                player
                                        );
                                    }

                                    send(
                                            player,
                                            "authentication.register.success"
                                    );

                                    broadcastAnnouncement(
                                            "register",
                                            player
                                    );
                                    return;
                                }

                                endOperation(playerId);

                                send(
                                        player,
                                        "authentication.register.already-registered"
                                );
                            })
                    );
                })
        );
    }

    // --------------------------------------------------
    // ANNOUNCEMENTS
    // --------------------------------------------------

    private void broadcastAnnouncement(
            String type,
            Player player
    ) {
        if (type == null || player == null) {
            return;
        }

        String enabledPath = "announcements." + type + ".enabled";

        if (!plugin.getConfig().getBoolean(
                enabledPath, true
        )) {
            return;
        }

        String messagePath = "announcements." + type;

        String message = plugin.getMessages().getString(
                messagePath,
                ""
        );

        if (message == null || message.isBlank()) {
            return;
        }

        message = message.replace(
                "{player}",
                player.getName()
        );

        Bukkit.broadcast(
                miniMessage.deserialize(message)
        );
    }

    // --------------------------------------------------
    // CHANGE PASSWORD
    // --------------------------------------------------

    private void handleChangePassword(
            CommandSender sender,
            String[] args
    ) {
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

        int min = plugin.getConfig().getInt(
                "settings.password.min-length",
                6
        );

        int max = plugin.getConfig().getInt(
                "settings.password.max-length",
                32
        );

        if (min < 1 || max < min) {
            send(player, "general.database-error");
            return;
        }

        if (args[1].length() < min) {
            send(
                    player,
                    "authentication.register.password-too-short",
                    "{min}",
                    String.valueOf(min)
            );
            return;
        }

        if (args[1].length() > max) {
            send(
                    player,
                    "authentication.register.password-too-long",
                    "{max}",
                    String.valueOf(max)
            );
            return;
        }

        if (!beginOperation(player)) {
            return;
        }

        UUID playerId = player.getUniqueId();

        char[] oldPassword = args[0].toCharArray();
        char[] newPassword = args[1].toCharArray();

        authManager.changePassword(
                player,
                oldPassword,
                newPassword
        ).whenComplete((changed, error) -> runOnMain(() -> {

            Arrays.fill(oldPassword, '\0');
            Arrays.fill(newPassword, '\0');

            endOperation(playerId);

            if (!player.isOnline()) {
                return;
            }

            if (error != null) {
                plugin.getLogger().severe(
                        "Password change failed for "
                                + player.getName() + ": "
                                + error.getMessage()
                );

                send(player, "general.database-error");

            } else if (Boolean.TRUE.equals(changed)) {
                send(
                        player,
                        "authentication.password.success"
                );

            } else {
                send(
                        player,
                        "authentication.password.incorrect-old-password"
                );
            }
        }));
    }

    // --------------------------------------------------
    // SPAWN COMMANDS
    // --------------------------------------------------

    private void handleSetSpawn(
            CommandSender sender,
            boolean login
    ) {
        if (!(sender instanceof Player player)) {
            send(sender, "general.players-only");
            return;
        }

        if (!player.hasPermission("thunderauth.admin")) {
            send(player, "general.no-permission");
            return;
        }

        Location location = player.getLocation();

        if (location.getWorld() == null) {
            send(player, "general.database-error");
            return;
        }

        String path = login
                ? "spawns.login"
                : "spawns.register";

        plugin.getConfig().set(path + ".enabled", true);

        plugin.getConfig().set(
                path + ".world",
                location.getWorld().getName()
        );

        plugin.getConfig().set(path + ".x", location.getX());
        plugin.getConfig().set(path + ".y", location.getY());
        plugin.getConfig().set(path + ".z", location.getZ());
        plugin.getConfig().set(path + ".yaw", location.getYaw());
        plugin.getConfig().set(path + ".pitch", location.getPitch());

        plugin.saveConfig();

        String message = login
                ? "<green>Login spawn saved successfully!"
                : "<green>Registration spawn saved successfully!";

        sendRaw(player, message);
    }

    // --------------------------------------------------
    // ADMIN COMMAND
    // --------------------------------------------------

    private void handleAdmin(
            CommandSender sender,
            String[] args
    ) {
        if (!sender.hasPermission("thunderauth.admin")) {
            send(sender, "general.no-permission");
            return;
        }

        if (args.length == 1
                && args[0].equalsIgnoreCase("reload")) {

            plugin.reloadConfig();
            plugin.reloadMessages();

            send(sender, "general.reloaded");
            return;
        }

        send(sender, "general.unknown-command");
    }

    // --------------------------------------------------
    // UTILITIES
    // --------------------------------------------------

    private void runOnMain(Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }

        try {
            Bukkit.getScheduler().runTask(
                    plugin,
                    () -> {
                        if (plugin.isEnabled()) {
                            action.run();
                        }
                    }
            );
        } catch (IllegalStateException exception) {
            plugin.getLogger().warning(
                    "Could not schedule authentication callback: "
                            + exception.getMessage()
            );
        }
    }

    private void kick(Player player, String path) {
        String prefix = plugin.getMessages().getString(
                "prefix",
                ""
        );

        String message = plugin.getMessages().getString(
                path,
                "<red>Maximum login attempts reached."
        );

        player.kick(
                miniMessage.deserialize(prefix + message)
        );
    }

    private void sendRaw(
            CommandSender sender,
            String message
    ) {
        String prefix = plugin.getMessages().getString(
                "prefix",
                ""
        );

        sender.sendMessage(
                miniMessage.deserialize(prefix + message)
        );
    }

    private void send(
            CommandSender sender,
            String path
    ) {
        send(sender, path, null, null);
    }

    private void send(
            CommandSender sender,
            String path,
            String placeholder,
            String value
    ) {
        String prefix = plugin.getMessages().getString(
                "prefix",
                ""
        );

        String message = plugin.getMessages().getString(
                path,
                "<red>Missing message: " + path
        );

        if (placeholder != null && value != null) {
            message = message.replace(placeholder, value);
        }

        sender.sendMessage(
                miniMessage.deserialize(prefix + message)
        );
    }
}
