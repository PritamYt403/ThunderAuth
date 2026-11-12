
package com.pritamx.thunderauth;

import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
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

import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class AuthListener implements Listener {

    private final ThunderAuth plugin;
    private final AuthManager authManager;
    private final BossBarManager bossBarManager;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final Map<UUID, BukkitTask> authTasks =
            new ConcurrentHashMap<>();

    private final Map<UUID, Location> previousLocations =
            new ConcurrentHashMap<>();

    private final Set<UUID> internalTeleports =
            ConcurrentHashMap.newKeySet();

    public AuthListener(
            ThunderAuth plugin,
            AuthManager authManager
    ) {
        this.plugin = plugin;
        this.authManager = authManager;
        this.bossBarManager = new BossBarManager(plugin);
    }

    // --------------------------------------------------
    // JOIN AND AUTHENTICATION
    // --------------------------------------------------

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        previousLocations.put(
                player.getUniqueId(),
                player.getLocation().clone()
        );

        if (authManager.restoreSession(player)) {
            previousLocations.remove(player.getUniqueId());

            plugin.getLogger().info(
                    "Restored authentication session for "
                            + player.getName()
            );
            return;
        }

        authManager.isRegistered(player).whenComplete(
                (registered, error) -> runOnMain(() -> {

                    if (!player.isOnline()) {
                        previousLocations.remove(player.getUniqueId());
                        return;
                    }

                    if (error != null) {
                        plugin.getLogger().severe(
                                "Could not check account status for "
                                        + player.getName() + ": "
                                        + error.getMessage()
                        );

                        send(player, "general.database-error");

                        player.kick(
                                miniMessage.deserialize(
                                        "<red>Authentication database is temporarily unavailable. Please reconnect later."
                                )
                        );
                        return;
                    }

                    boolean login = Boolean.TRUE.equals(registered);

                    teleportToAuthSpawn(player, login);

                    if (login) {
                        showLoginPrompt(player);
                    } else {
                        showRegisterPrompt(player);
                    }

                    startAuthTimer(player, login);
                })
        );
    }

    private void teleportToAuthSpawn(
            Player player,
            boolean login
    ) {
        String path = login
                ? "spawns.login"
                : "spawns.register";

        if (!plugin.getConfig().getBoolean(
                path + ".enabled", false
        )) {
            return;
        }

        String worldName = plugin.getConfig().getString(
                path + ".world"
        );

        if (worldName == null || worldName.isBlank()) {
            plugin.getLogger().warning(
                    "Authentication spawn world is missing: " + path
            );
            return;
        }

        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            plugin.getLogger().warning(
                    "Authentication spawn world is not loaded: "
                            + worldName
            );
            return;
        }

        Location target = new Location(
                world,
                plugin.getConfig().getDouble(path + ".x"),
                plugin.getConfig().getDouble(path + ".y"),
                plugin.getConfig().getDouble(path + ".z"),
                (float) plugin.getConfig().getDouble(path + ".yaw"),
                (float) plugin.getConfig().getDouble(path + ".pitch")
        );

        if (!target.isWorldLoaded()) {
            plugin.getLogger().warning(
                    "Authentication spawn location is invalid: " + path
            );
            return;
        }

        UUID uuid = player.getUniqueId();

        internalTeleports.add(uuid);

        try {
            boolean success = player.teleport(
                    target,
                    PlayerTeleportEvent.TeleportCause.PLUGIN
            );

            if (!success) {
                plugin.getLogger().warning(
                        "Could not teleport " + player.getName()
                                + " to authentication spawn."
                );
            }
        } finally {
            internalTeleports.remove(uuid);
        }
    }

    /**
     * Returns the player's original location captured on join.
     * This is not the authentication spawn location.
     */
    public Location getOriginalJoinLocation(Player player) {
        if (player == null) {
            return null;
        }

        Location location = previousLocations.get(
                player.getUniqueId()
        );

        return location == null ? null : location.clone();
    }

    /**
     * Backward-compatible completion method.
     * Treats the authentication as registration.
     */
    public void completeAuthentication(Player player) {
        completeAuthentication(player, false);
    }

    /**
     * Called after successful authentication.
     *
     * @param player the authenticated player
     * @param login true for login, false for registration
     */
    public void completeAuthentication(
            Player player,
            boolean login
    ) {
        if (player == null || !player.isOnline()) {
            return;
        }

        UUID uuid = player.getUniqueId();

        cancelAuthTimer(uuid);
        bossBarManager.remove(player);

        Location previous = previousLocations.remove(uuid);

        boolean returnToFirstRegistration =
                login && plugin.getConfig().getBoolean(
                        "spawns.return-to-first-register-location",
                        false
                );

        if (returnToFirstRegistration) {
            authManager.getAccount(player).whenComplete(
                    (account, error) -> runOnMain(() -> {

                        if (!player.isOnline()) {
                            return;
                        }

                        if (error != null) {
                            plugin.getLogger().warning(
                                    "Could not retrieve first registration "
                                            + "location for "
                                            + player.getName() + ": "
                                            + error.getMessage()
                            );

                            returnToPreviousLocation(player, previous);
                            return;
                        }

                        if (account != null) {
                            String worldName = account.firstWorld();

                            if (worldName != null && !worldName.isBlank()) {
                                World world = Bukkit.getWorld(worldName);

                                if (world != null) {
                                    Location firstLocation = new Location(
                                            world,
                                            account.firstX(),
                                            account.firstY(),
                                            account.firstZ(),
                                            account.firstYaw(),
                                            account.firstPitch()
                                    );

                                    if (firstLocation.isWorldLoaded()
                                            && teleportInternally(
                                                    player,
                                                    firstLocation
                                            )) {
                                        return;
                                    }
                                }
                            }
                        }

                        plugin.getLogger().warning(
                                "First registration location is unavailable "
                                        + "for " + player.getName()
                                        + ". Falling back to previous location."
                        );

                        returnToPreviousLocation(player, previous);
                    })
            );

            return;
        }

        returnToPreviousLocation(player, previous);
    }

    private void returnToPreviousLocation(
            Player player,
            Location previous
    ) {
        if (player == null
                || !player.isOnline()
                || !plugin.getConfig().getBoolean(
                        "spawns.return-to-previous-location",
                        true
                )
                || previous == null
                || previous.getWorld() == null
                || !previous.isWorldLoaded()) {
            return;
        }

        if (!teleportInternally(player, previous)) {
            plugin.getLogger().warning(
                    "Could not return " + player.getName()
                            + " to their previous location."
            );
        }
    }

    private boolean teleportInternally(
            Player player,
            Location target
    ) {
        if (player == null
                || !player.isOnline()
                || target == null
                || target.getWorld() == null
                || !target.isWorldLoaded()) {
            return false;
        }

        UUID uuid = player.getUniqueId();

        internalTeleports.add(uuid);

        try {
            boolean success = player.teleport(
                    target,
                    PlayerTeleportEvent.TeleportCause.PLUGIN
            );

            if (!success) {
                plugin.getLogger().warning(
                        "Could not teleport " + player.getName()
                                + " to the requested authentication location."
                );
            }

            return success;
        } finally {
            internalTeleports.remove(uuid);
        }
    }

    // --------------------------------------------------
    // TITLE AND PROMPTS
    // --------------------------------------------------

    private void showLoginPrompt(Player player) {
        String title = plugin.getConfig().getString(
                "presentation.titles.login.title",
                "<gold>FIREMC"
        );

        String subtitle = plugin.getConfig().getString(
                "presentation.titles.login.subtitle",
                "<white>Please login."
        );

        showTitle(player, title, subtitle);

        send(player, "authentication.login.required");
    }

    private void showRegisterPrompt(Player player) {
        String title = plugin.getConfig().getString(
                "presentation.titles.register.title",
                "<gold>FIREMC"
        );

        String subtitle = plugin.getConfig().getString(
                "presentation.titles.register.subtitle",
                "<white>Please register."
        );

        showTitle(player, title, subtitle);

        send(player, "authentication.register.usage");
    }

    private void showTitle(
            Player player,
            String title,
            String subtitle
    ) {
        if (!plugin.getConfig().getBoolean(
                "presentation.enabled", true
        )) {
            return;
        }

        if (!plugin.getConfig().getBoolean(
                "presentation.titles.enabled", true
        )) {
            return;
        }

        int fadeIn = Math.max(
                0,
                plugin.getConfig().getInt(
                        "presentation.titles.fade.fade-in", 10
                )
        );

        int stay = Math.max(
                0,
                plugin.getConfig().getInt(
                        "presentation.titles.fade.stay", 60
                )
        );

        int fadeOut = Math.max(
                0,
                plugin.getConfig().getInt(
                        "presentation.titles.fade.fade-out", 20
                )
        );

        Title.Times times = Title.Times.times(
                Duration.ofMillis(fadeIn * 50L),
                Duration.ofMillis(stay * 50L),
                Duration.ofMillis(fadeOut * 50L)
        );

        player.showTitle(
                Title.title(
                        miniMessage.deserialize(title),
                        miniMessage.deserialize(subtitle),
                        times
                )
        );
    }

    // --------------------------------------------------
    // AUTHENTICATION TIMER
    // --------------------------------------------------

    private void startAuthTimer(
            Player player,
            boolean login
    ) {
        UUID uuid = player.getUniqueId();

        cancelAuthTimer(uuid);
        bossBarManager.remove(player);

        String section = login
                ? "settings.login"
                : "settings.register";

        int timeout = Math.max(
                0,
                plugin.getConfig().getInt(
                        section + ".timeout-seconds", 60
                )
        );

        if (timeout <= 0) {
            return;
        }

        int reminderInterval = Math.max(
                1,
                plugin.getConfig().getInt(
                        "presentation.reminders.interval-seconds", 10
                )
        );

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
                                miniMessage.deserialize(
                                        prefix + message
                                )
                        );
                        return;
                    }

                    bossBarManager.update(
                            player,
                            login,
                            remaining[0],
                            timeout
                    );

                    showActionBar(player, login, remaining[0]);

                    showReminder(
                            player,
                            login,
                            remaining[0],
                            reminderInterval
                    );

                    remaining[0]--;
                },
                0L,
                20L
        );

        authTasks.put(uuid, task);
    }

    private void showActionBar(
            Player player,
            boolean login,
            int remaining
    ) {
        if (!plugin.getConfig().getBoolean(
                "presentation.actionbar.enabled", true
        )) {
            return;
        }

        String path = login
                ? "presentation.actionbar.login"
                : "presentation.actionbar.register";

        String message = plugin.getConfig().getString(path, "");

        if (message == null || message.isEmpty()) {
            return;
        }

        message = message.replace(
                "{time}",
                String.valueOf(remaining)
        );

        player.sendActionBar(
                miniMessage.deserialize(message)
        );
    }

    private void showReminder(
            Player player,
            boolean login,
            int remaining,
            int interval
    ) {
        if (!plugin.getConfig().getBoolean(
                "presentation.reminders.enabled", true
        )) {
            return;
        }

        if (remaining % interval != 0) {
            return;
        }

        String path = login
                ? "presentation.reminders.login"
                : "presentation.reminders.register";

        String message = plugin.getConfig().getString(path, "");

        if (message == null || message.isEmpty()) {
            return;
        }

        player.sendMessage(
                miniMessage.deserialize(message)
        );
    }

    // --------------------------------------------------
    // SCHEDULER AND MESSAGES
    // --------------------------------------------------

    private void runOnMain(Runnable action) {
        if (!plugin.isEnabled()) {
            return;
        }

        try {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (plugin.isEnabled()) {
                    action.run();
                }
            });
        } catch (IllegalStateException exception) {
            plugin.getLogger().warning(
                    "Could not schedule authentication task: "
                            + exception.getMessage()
            );
        }
    }

    private void cancelAuthTimer(UUID uuid) {
        BukkitTask task = authTasks.remove(uuid);

        if (task != null) {
            task.cancel();
        }
    }

    private void send(Player player, String path) {
        String prefix = plugin.getMessages().getString(
                "prefix", ""
        );

        String message = plugin.getMessages().getString(
                path,
                "<red>Missing message: " + path
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
                "settings.security." + setting,
                true
        );
    }

    // --------------------------------------------------
    // MOVEMENT AND TELEPORT SECURITY
    // --------------------------------------------------

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
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

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onTeleport(PlayerTeleportEvent event) {
        if (internalTeleports.contains(
                event.getPlayer().getUniqueId()
        )) {
            return;
        }

        if (securityEnabled("prevent-teleport-before-auth")
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // --------------------------------------------------
    // CHAT AND COMMAND SECURITY
    // --------------------------------------------------

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onChat(AsyncPlayerChatEvent event) {
        if (securityEnabled("block-chat")
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onCommand(PlayerCommandPreprocessEvent event) {
        if (!securityEnabled("block-commands")
                || !isBlocked(event.getPlayer())) {
            return;
        }

        String command = event.getMessage()
                .toLowerCase(Locale.ROOT)
                .split("\\s+", 2)[0];

        int separator = command.indexOf(':');

        if (separator >= 0) {
            command = command.substring(separator + 1);
        }

        if (command.equals("/login")
                || command.equals("/log")
                || command.equals("/register")
                || command.equals("/reg")) {
            return;
        }

        event.setCancelled(true);

        send(
                event.getPlayer(),
                "security.command-blocked"
        );
    }

    // --------------------------------------------------
    // BLOCK SECURITY
    // --------------------------------------------------

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onBlockBreak(BlockBreakEvent event) {
        if (securityEnabled("block-block-break")
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onBlockDamage(BlockDamageEvent event) {
        if (securityEnabled("block-block-break")
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onBlockPlace(BlockPlaceEvent event) {
        if (securityEnabled("block-block-place")
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    // --------------------------------------------------
    // INTERACTION AND INVENTORY SECURITY
    // --------------------------------------------------

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onInteract(PlayerInteractEvent event) {
        if (securityEnabled("block-interactions")
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        if (securityEnabled("block-interactions")
                && isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player
                && securityEnabled("block-inventory")
                && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && securityEnabled("block-inventory")
                && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && securityEnabled("block-inventory")
                && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    // --------------------------------------------------
    // DAMAGE SECURITY
    // --------------------------------------------------

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onDamage(EntityDamageEvent event) {
        if (securityEnabled("block-damage")
                && event.getEntity() instanceof Player player
                && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (securityEnabled("block-damage")
                && event.getDamager() instanceof Player player
                && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    // --------------------------------------------------
    // ITEM AND PLAYER ACTION SECURITY
    // --------------------------------------------------

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onDrop(PlayerDropItemEvent event) {
        if (isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player
                && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onConsume(PlayerItemConsumeEvent event) {
        if (isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onSneak(PlayerToggleSneakEvent event) {
        if (isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onSprint(PlayerToggleSprintEvent event) {
        if (isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onFish(PlayerFishEvent event) {
        if (isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onShear(PlayerShearEntityEvent event) {
        if (isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onBedEnter(PlayerBedEnterEvent event) {
        if (isBlocked(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(
            priority = EventPriority.HIGHEST,
            ignoreCancelled = true
    )
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player
                && isBlocked(player)) {
            event.setCancelled(true);
        }
    }

    // --------------------------------------------------
    // QUIT AND SHUTDOWN
    // --------------------------------------------------

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        UUID uuid = player.getUniqueId();

        cancelAuthTimer(uuid);
        bossBarManager.remove(player);

        previousLocations.remove(uuid);
        internalTeleports.remove(uuid);

        authManager.removeAuthenticated(player);
    }

    public void shutdown() {
        for (BukkitTask task : authTasks.values()) {
            task.cancel();
        }

        authTasks.clear();
        previousLocations.clear();
        internalTeleports.clear();

        bossBarManager.removeAll();
    }
}
