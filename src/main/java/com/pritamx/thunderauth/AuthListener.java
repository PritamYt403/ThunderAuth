
package com.pritamx.thunderauth;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

public final class AuthListener implements Listener {

    private final ThunderAuth plugin;
    private final AuthManager auth;

    public AuthListener(ThunderAuth plugin, AuthManager auth) {
        this.plugin = plugin;
        this.auth = auth;
    }

    private boolean restricted(Player player) {
        return !auth.isAuthenticated(player);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }

            auth.saveReturnLocation(player);
            auth.beginAuthentication(player);

            if (!restricted(player)) {
                auth.clearReturnLocation(player.getUniqueId());
                return;
            }

            teleportToLoginSpawn(player);
            applyRestrictions(player);

            boolean registered = auth.hasAccount(player.getUniqueId());

            player.sendMessage(plugin.message(
                    registered
                            ? "authentication.login-required"
                            : "authentication.register-required"));

            if (plugin.getBossBarManager() != null
                    && plugin.getConfig().getBoolean(
                    "settings.bossbar.enabled", true)) {
                plugin.getBossBarManager().start(player);
            }

            showAuthTitle(player, registered);
        });
    }

    private void teleportToLoginSpawn(Player player) {
        String base = "settings.spawn.normal-";
        String worldName = plugin.getConfig().getString(
                base + "world", "world");

        World world = Bukkit.getWorld(worldName);

        if (world == null) {
            plugin.getLogger().warning(
                    "Login spawn world '" + worldName + "' was not found.");
            player.sendMessage(plugin.message("spawn.world-not-found"));
            return;
        }

        Location spawn = new Location(
                world,
                plugin.getConfig().getDouble(base + "x", 0.5),
                plugin.getConfig().getDouble(base + "y", 65.0),
                plugin.getConfig().getDouble(base + "z", 0.5),
                (float) plugin.getConfig().getDouble(base + "yaw", 0.0),
                (float) plugin.getConfig().getDouble(base + "pitch", 0.0)
        );

        if (!player.teleport(spawn)) {
            player.sendMessage(plugin.message("spawn.teleport-failed"));
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        player.removePotionEffect(PotionEffectType.BLINDNESS);
        auth.handleQuit(player);

        if (plugin.getBossBarManager() != null) {
            plugin.getBossBarManager().stop(player);
        }
    }

    private void applyRestrictions(Player player) {
        if (plugin.getConfig().getBoolean(
                "settings.login.hide-inventory", true)) {
            player.closeInventory();
        }

        if (plugin.getConfig().getBoolean(
                "settings.login.blindness", true)) {
            player.addPotionEffect(new PotionEffect(
                    PotionEffectType.BLINDNESS,
                    Integer.MAX_VALUE, 0, false, false, false));
        }
    }

    private void showAuthTitle(Player player, boolean registered) {
        if (!plugin.getConfig().getBoolean(
                "settings.titles.enabled", true)) {
            return;
        }

        String titlePath = registered
                ? "titles.login-title" : "titles.register-title";
        String subtitlePath = registered
                ? "titles.login-subtitle" : "titles.register-subtitle";

        player.sendTitle(
                plugin.messageWithoutPrefix(titlePath),
                plugin.messageWithoutPrefix(subtitlePath),
                Math.max(0, plugin.getConfig().getInt(
                        "settings.titles.fade-in", 5)),
                Math.max(0, plugin.getConfig().getInt(
                        "settings.titles.stay", 40)),
                Math.max(0, plugin.getConfig().getInt(
                        "settings.titles.fade-out", 10))
        );
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (event instanceof PlayerTeleportEvent) {
            return;
        }

        Player player = event.getPlayer();

        if (!restricted(player) || !plugin.getConfig().getBoolean(
                "settings.login.block-movement", true)) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        if (to != null && (
                from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ())) {
            event.setTo(from);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (restricted(event.getPlayer()) && plugin.getConfig().getBoolean(
                "settings.login.block-chat", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        if (!restricted(player) || !plugin.getConfig().getBoolean(
                "settings.login.block-commands", true)) {
            return;
        }

        String root = event.getMessage()
                .toLowerCase(java.util.Locale.ROOT)
                .trim().split("\\s+", 2)[0];

        if (root.equals("/login") || root.equals("/register")
                || root.equals("/l") || root.equals("/reg")) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(plugin.message(
                "authentication.login-required"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (restricted(event.getPlayer()) && plugin.getConfig().getBoolean(
                "settings.login.block-mining", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (restricted(event.getPlayer()) && plugin.getConfig().getBoolean(
                "settings.login.block-block-placement", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (restricted(event.getPlayer()) && plugin.getConfig().getBoolean(
                "settings.login.block-interactions", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && restricted(player) && plugin.getConfig().getBoolean(
                "settings.login.hide-inventory", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && restricted(player) && plugin.getConfig().getBoolean(
                "settings.login.hide-inventory", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player
                && restricted(player) && plugin.getConfig().getBoolean(
                "settings.login.hide-inventory", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (restricted(event.getPlayer()) && plugin.getConfig().getBoolean(
                "settings.login.block-item-drop",
                plugin.getConfig().getBoolean(
                        "settings.login.block-item-use", true))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (restricted(event.getPlayer()) && plugin.getConfig().getBoolean(
                "settings.login.block-hand-swap",
                plugin.getConfig().getBoolean(
                        "settings.login.block-item-use", true))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (restricted(event.getPlayer()) && plugin.getConfig().getBoolean(
                "settings.login.block-item-use", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        Entity entity = event.getEntity();

        if (entity instanceof Player player && restricted(player)
                && plugin.getConfig().getBoolean(
                "settings.login.block-item-pickup",
                plugin.getConfig().getBoolean(
                        "settings.login.block-item-use", true))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && restricted(player) && plugin.getConfig().getBoolean(
                "settings.login.block-damage", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player
                && restricted(player) && plugin.getConfig().getBoolean(
                "settings.login.block-hunger", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();

        if (restricted(player)) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (player.isOnline() && restricted(player)) {
                    applyRestrictions(player);
                }
            });
        }
    }
}
