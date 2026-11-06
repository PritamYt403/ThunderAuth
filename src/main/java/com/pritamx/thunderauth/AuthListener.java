
package com.pritamx.thunderauth;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.scheduler.BukkitTask;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthListener implements Listener {

    private final ThunderAuth plugin;
    private final AuthManager authManager;
    private final BossBarManager bossBarManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final Map<UUID, BukkitTask> authTasks =
            new ConcurrentHashMap<>();

    public AuthListener(ThunderAuth plugin, AuthManager authManager) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.bossBarManager = new BossBarManager(plugin);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();
        authManager.removeAuthenticated(player);

        authManager.isRegistered(player).whenComplete((registered, error) ->
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (!player.isOnline() || !plugin.isEnabled()) {
                        return;
                    }

                    if (error != null) {
                        send(player, "general.database-error");
                        plugin.getLogger().severe(
                                "Could not check account status for "
                                        + player.getName()
                        );
                        return;
                    }

                    if (Boolean.TRUE.equals(registered)) {
                        showLoginPrompt(player);
                        startAuthTimer(player, true);
                    } else {
                        showRegisterPrompt(player);
                        startAuthTimer(player, false);
                    }
                })
        );
    }

    private void showLoginPrompt(Player player) {
        String title = plugin.getConfig().getString(
                "presentation.titles.login.title", "<gold>FIREMC"
        );
        String subtitle = plugin.getConfig().getString(
                "presentation.titles.login.subtitle", "<white>Please login."
        );

        showTitle(player, title, subtitle);
        send(player, "authentication.login.required");
    }

    private void showRegisterPrompt(Player player) {
        String title = plugin.getConfig().getString(
                "presentation.titles.register.title", "<gold>FIREMC"
        );
        String subtitle = plugin.getConfig().getString(
                "presentation.titles.register.subtitle", "<white>Please register."
        );

        showTitle(player, title, subtitle);
        send(player, "authentication.register.usage");
    }

    private void showTitle(Player player, String title, String subtitle) {
        if (!plugin.getConfig().getBoolean("presentation.enabled", true)
                || !plugin.getConfig().getBoolean(
                        "presentation.titles.enabled", true)) {
            return;
        }

        player.showTitle(
                net.kyori.adventure.title.Title.title(
                        miniMessage.deserialize(title),
                        miniMessage.deserialize(subtitle)
                )
        );
    }

    private void startAuthTimer(Player player, boolean login) {
        UUID uuid = player.getUniqueId();

        cancelAuthTimer(uuid);
        bossBarManager.remove(player);

        String section = login ? "settings.login" : "settings.register";

        int timeout = Math.max(0,
                plugin.getConfig().getInt(section + ".timeout-seconds", 60));

        if (timeout <= 0) {
            return;
        }

        int reminderInterval = Math.max(1,
                plugin.getConfig().getInt(
                        "presentation.reminders.interval-seconds", 10
                ));

        int[] remaining = {timeout};

        bossBarManager.show(player, login, timeout);

        BukkitTask task = Bukkit.getScheduler().runTaskTimer(
                plugin,
                () -> {
                    if (!player.isOnline()
                            || authManager.isAuthenticated(player)) {
                        cancelAuthTimer(uuid);
                        bossBarManager.remove(player);
                        return;
                    }

                    if (remaining[0] <= 0) {
                        cancelAuthTimer(uuid);
                        bossBarManager.remove(player);

                        String path = login
                                ? "authentication.login.timeout"
                                : "authentication.register.timeout";

                        String prefix = plugin.getMessages()
                                .getString("prefix", "");

                        String message = plugin.getMessages()
                                .getString(path, "<red>Authentication timed out.");

                        player.kick(miniMessage.deserialize(prefix + message));
                        return;
                    }

                    bossBarManager.update(
                            player,
                            login,
                            remaining[0],
                            timeout
                    );

                    if (plugin.getConfig().getBoolean(
                            "presentation.actionbar.enabled", true)) {

                        String actionPath = login
                                ? "presentation.actionbar.login"
                                : "presentation.actionbar.register";

                        String actionMessage = plugin.getConfig()
                                .getString(actionPath, "");

                        actionMessage = actionMessage.replace(
                                "{time}", String.valueOf(remaining[0])
                        );

                        player.sendActionBar(
                                miniMessage.deserialize(actionMessage)
                        );
                    }

                    if (plugin.getConfig().getBoolean(
                            "presentation.reminders.enabled", true)
                            && remaining[0] % reminderInterval == 0) {

                        String reminderPath = login
                                ? "presentation.reminders.login"
                                : "presentation.reminders.register";

                        String reminder = plugin.getConfig()
                                .getString(reminderPath, "");

                        if (!reminder.isEmpty()) {
                            player.sendMessage(
                                    miniMessage.deserialize(reminder)
                            );
                        }
                    }

                    remaining[0]--;
                },
                0L,
                20L
        );

        authTasks.put(uuid, task);
    }

    private void cancelAuthTimer(UUID uuid) {
        BukkitTask task = authTasks.remove(uuid);

        if (task != null) {
            task.cancel();
        }
    }

    private void send(Player player, String path) {
        String prefix = plugin.getMessages().getString("prefix", "");
        String message = plugin.getMessages().getString(
                path, "<red>Missing message: " + path
        );

        player.sendMessage(
                miniMessage.deserialize(prefix + message)
        );
    }

    private boolean isBlocked(Player player) {
        return !authManager.isAuthenticated(player);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!plugin.getConfig().getBoolean(
                "settings.security.block-movement", true)
                || !isBlocked(event.getPlayer())
                || event.getTo() == null) {
            return;
        }

        if (event.getFrom().getBlockX() != event.getTo().getBlockX()
                || event.getFrom().getBlockY() != event.getTo().getBlockY()
                || event.getFrom().getBlockZ() != event.getTo().getBlockZ()) {
            event.setTo(event.getFrom());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (plugin.getConfig().getBoolean(
                "settings.security.block-chat", true)
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!plugin.getConfig().getBoolean(
                "settings.security.block-commands", true)
                || !isBlocked(event.getPlayer())) {
            return;
        }

        String command = event.getMessage()
                .toLowerCase(Locale.ROOT)
                .split("\\s+", 2)[0];

        if (command.equals("/login")
                || command.equals("/register")
                || command.equals("/reg")
                || command.equals("/log")) {
            return;
        }

        event.setCancelled(true);
        send(event.getPlayer(), "security.command-blocked");
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (plugin.getConfig().getBoolean(
                "settings.security.block-block-break", true)
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (plugin.getConfig().getBoolean(
                "settings.security.block-block-place", true)
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (plugin.getConfig().getBoolean(
                "settings.security.block-interactions", true)
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (plugin.getConfig().getBoolean(
                "settings.security.block-interactions", true)
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player
                && plugin.getConfig().getBoolean(
                        "settings.security.block-inventory", true)
                && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && plugin.getConfig().getBoolean(
                        "settings.security.block-inventory", true)
                && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && plugin.getConfig().getBoolean(
                        "settings.security.block-inventory", true)
                && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (plugin.getConfig().getBoolean(
                "settings.security.block-damage", true)
                && event.getEntity() instanceof Player player
                && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        cancelAuthTimer(player.getUniqueId());
        bossBarManager.remove(player);
        authManager.removeAuthenticated(player);
    }
}
