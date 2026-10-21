package com.pritamx.thunderauth;

import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerCommandPreprocessEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerPickupItemEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class AuthListener implements Listener {

    private final ThunderAuth plugin;
    private final AuthManager auth;
    private final Map<UUID, GameMode> previousModes = new HashMap<>();

    public AuthListener(ThunderAuth plugin, AuthManager auth) {
        this.plugin = plugin;
        this.auth = auth;
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        Player player = event.getPlayer();

        Bukkit.getScheduler().runTask(plugin, () -> {
            if (!player.isOnline()) {
                return;
            }

            auth.beginAuthentication(player);

            if (!auth.isAuthenticated(player)) {
                applyRestrictions(player);

                String path = auth.hasAccount(player.getUniqueId())
                        ? "authentication.login-required"
                        : "authentication.register-required";

                player.sendMessage(plugin.message(path));
            }
        });
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();

        removeRestrictions(player);
        auth.handleQuit(player);
    }

    private boolean restricted(Player player) {
        return !auth.isAuthenticated(player);
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
                    Integer.MAX_VALUE,
                    0,
                    false,
                    false,
                    false
            ));
        }
    }

    private void removeRestrictions(Player player) {
        player.removePotionEffect(PotionEffectType.BLINDNESS);
        previousModes.remove(player.getUniqueId());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onMove(PlayerMoveEvent event) {
        if (!restricted(event.getPlayer())
                || !plugin.getConfig().getBoolean(
                "settings.login.block-movement", true)) {
            return;
        }

        Location from = event.getFrom();
        Location to = event.getTo();

        if (to != null && (from.getBlockX() != to.getBlockX()
                || from.getBlockY() != to.getBlockY()
                || from.getBlockZ() != to.getBlockZ())) {
            event.setTo(from);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onChat(AsyncPlayerChatEvent event) {
        if (restricted(event.getPlayer())
                && plugin.getConfig().getBoolean(
                "settings.login.block-chat", true)) {
            event.setCancelled(true);
            event.getPlayer().sendMessage(plugin.message(
                    "authentication.login-required"));
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onCommand(PlayerCommandPreprocessEvent event) {
        Player player = event.getPlayer();

        if (!restricted(player)
                || !plugin.getConfig().getBoolean(
                "settings.login.block-commands", true)) {
            return;
        }

        String commandLine = event.getMessage().toLowerCase().trim();
        String root = commandLine.split("\\s+", 2)[0];

        if (root.equals("/login")
                || root.equals("/register")
                || root.equals("/l")
                || root.equals("/reg")) {
            return;
        }

        event.setCancelled(true);
        player.sendMessage(plugin.message(
                "authentication.login-required"));
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        if (restricted(event.getPlayer())
                && plugin.getConfig().getBoolean(
                "settings.login.block-mining", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (restricted(event.getPlayer())
                && plugin.getConfig().getBoolean(
                "settings.login.block-block-placement", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (restricted(event.getPlayer())
                && plugin.getConfig().getBoolean(
                "settings.login.block-interactions", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && restricted(player)
                && plugin.getConfig().getBoolean(
                "settings.login.hide-inventory", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (event.getWhoClicked() instanceof Player player
                && restricted(player)
                && plugin.getConfig().getBoolean(
                "settings.login.hide-inventory", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (event.getPlayer() instanceof Player player
                && restricted(player)
                && plugin.getConfig().getBoolean(
                "settings.login.hide-inventory", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (restricted(event.getPlayer())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (restricted(event.getPlayer())
                && plugin.getConfig().getBoolean(
                "settings.login.block-item-use", true)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDamage(EntityDamageEvent event) {
        if (event.getEntity() instanceof Player player
                && restricted(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onFoodChange(FoodLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player
                && restricted(player)) {
            event.setCancelled(true);
        }
    }
}
