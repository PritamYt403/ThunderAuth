
package com.pritamx.thunderauth;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerBedEnterEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerFishEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.event.player.PlayerToggleSprintEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
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

        authManager.isRegistered(player).whenComplete((registered, error) -> {
            if (!plugin.isEnabled()) {
                return;
            }

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
            });
        });
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
                "presentation.titles.register.subtitle",
                "<white>Please register."
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
                                .getString(
                                        path,
                                        "<red>Authentication timed out."
                                );

                        player.kick(
                                miniMessage.deserialize(prefix + message)
                        );
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

    private boolean securityEnabled(String setting) {
        return plugin.getConfig().getBoolean(
                "settings.security." + setting, true
        );
    }

    // Movement lock: blocks exact position changes, but allows camera rotation.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!securityEnabled("block-movement")
                || !isBlocked(event.getPlayer())
                || event.getTo() == null) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        boolean positionChanged =
                Double.compare(from.getX(), to.getX()) != 0
                || Double.compare(from.getY(), to.getY()) != 0
                || Double.compare(from.getZ(), to.getZ()) != 0;

        if (positionChanged) {
            Location locked = from.clone();
            locked.setYaw(to.getYaw());
            locked.setPitch(to.getPitch());
            event.setTo(locked);
        }
    }

    // Explicitly blocks teleportation and portals before authentication.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (plugin.getConfig().getBoolean(
                "settings.security.prevent-teleport-before-auth", true)
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (securityEnabled("block-chat") && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!securityEnabled("block-commands")
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
        if (securityEnabled("block-block-break")
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockDamage(BlockDamageEvent event) {
        if (securityEnabled("block-block-break")
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (securityEnabled("block-block-place")
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (securityEnabled("block-interactions")
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (securityEnabled("block-interactions")
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player
                && securityEnabled("block-inventory")
                && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && securityEnabled("block-inventory")
                && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && securityEnabled("block-inventory")
                && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (securityEnabled("block-damage")
                && event.getEntity() instanceof Player player
                && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    // Prevent an unauthenticated player from attacking entities.
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (event.getDamager() instanceof Player player
                && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSneak(PlayerToggleSneakEvent event) {
        if (isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSprint(PlayerToggleSprintEvent event) {
        if (isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFish(PlayerFishEvent event) {
        if (isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onShear(PlayerShearEntityEvent event) {
        if (isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    public void shutdown() {
        for (BukkitTask task : authTasks.values()) {
            task.cancel();
        }

        authTasks.clear();
        bossBarManager.removeAll();
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        cancelAuthTimer(player.getUniqueId());
        bossBarManager.remove(player);
        authManager.removeAuthenticated(player);
    }
}
