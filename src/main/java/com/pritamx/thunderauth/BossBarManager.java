
package com.pritamx.thunderauth;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BossBarManager {

    private final ThunderAuth plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final Map<UUID, BossBar> bars = new ConcurrentHashMap<>();

    public BossBarManager(ThunderAuth plugin) {
        this.plugin = plugin;
    }

    public void show(Player player, boolean login, int totalSeconds) {
        if (!plugin.getConfig().getBoolean("presentation.enabled", true)
                || !plugin.getConfig().getBoolean(
                "presentation.bossbar.enabled", true)) {
            return;
        }

        remove(player);

        String path = login
                ? "presentation.bossbar.login.title"
                : "presentation.bossbar.register.title";

        String title = plugin.getConfig().getString(
                path, "<gold>FIREMC"
        );

        BossBar.Color color = getColor(
                plugin.getConfig().getString(
                        "presentation.bossbar.color", "YELLOW"
                )
        );

        BossBar.Overlay overlay = getOverlay(
                plugin.getConfig().getString(
                        "presentation.bossbar.style", "SOLID"
                )
        );

        BossBar bar = BossBar.bossBar(
                miniMessage.deserialize(
                        title.replace("{time}", String.valueOf(totalSeconds))
                ),
                1.0f,
                color,
                overlay
        );

        bars.put(player.getUniqueId(), bar);
        player.showBossBar(bar);
    }

    public void update(
            Player player,
            boolean login,
            int remaining,
            int total
    ) {
        BossBar bar = bars.get(player.getUniqueId());

        if (bar == null || !player.isOnline()) {
            return;
        }

        String path = login
                ? "presentation.bossbar.login.title"
                : "presentation.bossbar.register.title";

        String title = plugin.getConfig().getString(
                path, "<gold>FIREMC"
        );

        bar.name(miniMessage.deserialize(
                title.replace("{time}", String.valueOf(remaining))
        ));

        if (plugin.getConfig().getBoolean(
                "presentation.bossbar.progress.enabled", true)) {

            float progress = total <= 0
                    ? 0.0f
                    : Math.max(0.0f, Math.min(
                            1.0f, (float) remaining / total
                    ));

            bar.progress(progress);
        }
    }

    public void remove(Player player) {
        BossBar bar = bars.remove(player.getUniqueId());

        if (bar != null) {
            player.hideBossBar(bar);
        }
    }

    public void removeAll() {
        for (Map.Entry<UUID, BossBar> entry : bars.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());

            if (player != null && player.isOnline()) {
                player.hideBossBar(entry.getValue());
            }
        }

        bars.clear();
    }

    private BossBar.Color getColor(String value) {
        try {
            return BossBar.Color.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            return BossBar.Color.YELLOW;
        }
    }

    private BossBar.Overlay getOverlay(String value) {
        try {
            return BossBar.Overlay.valueOf(value.toUpperCase());
        } catch (IllegalArgumentException exception) {
            return BossBar.Overlay.PROGRESS;
        }
    }
}
