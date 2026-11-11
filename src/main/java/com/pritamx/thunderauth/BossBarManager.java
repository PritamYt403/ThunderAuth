
package com.pritamx.thunderauth;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BossBarManager {

    private final ThunderAuth plugin;
    private final MiniMessage miniMessage = MiniMessage.miniMessage();

    private final Map<UUID, BossBar> bars =
            new ConcurrentHashMap<>();

    public BossBarManager(ThunderAuth plugin) {
        this.plugin = plugin;
    }

    public void show(
            Player player,
            boolean login,
            int totalSeconds
    ) {
        if (player == null || !player.isOnline()) {
            return;
        }

        if (!plugin.getConfig().getBoolean(
                "presentation.enabled", true
        ) || !plugin.getConfig().getBoolean(
                "presentation.bossbar.enabled", true
        )) {
            remove(player);
            return;
        }

        remove(player);

        String path = login
                ? "presentation.bossbar.login.title"
                : "presentation.bossbar.register.title";

        String title = getTitle(path);

        BossBar.Color color = getColor(
                plugin.getConfig().getString(
                        "presentation.bossbar.color",
                        "YELLOW"
                )
        );

        BossBar.Overlay overlay = getOverlay(
                plugin.getConfig().getString(
                        "presentation.bossbar.style",
                        "PROGRESS"
                )
        );

        float progress = totalSeconds <= 0
                ? 0.0f
                : 1.0f;

        BossBar bar = BossBar.bossBar(
                miniMessage.deserialize(
                        title.replace(
                                "{time}",
                                String.valueOf(Math.max(0, totalSeconds))
                        )
                ),
                progress,
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
        if (player == null || !player.isOnline()) {
            return;
        }

        BossBar bar = bars.get(player.getUniqueId());

        if (bar == null) {
            return;
        }

        String path = login
                ? "presentation.bossbar.login.title"
                : "presentation.bossbar.register.title";

        String title = getTitle(path);

        bar.name(
                miniMessage.deserialize(
                        title.replace(
                                "{time}",
                                String.valueOf(Math.max(0, remaining))
                        )
                )
        );

        if (plugin.getConfig().getBoolean(
                "presentation.bossbar.progress.enabled", true
        )) {
            float progress = total <= 0
                    ? 0.0f
                    : Math.max(
                            0.0f,
                            Math.min(
                                    1.0f,
                                    (float) remaining / (float) total
                            )
                    );

            bar.progress(progress);
        }
    }

    public void remove(Player player) {
        if (player == null) {
            return;
        }

        BossBar bar = bars.remove(player.getUniqueId());

        if (bar != null && player.isOnline()) {
            player.hideBossBar(bar);
        }
    }

    public void removeAll() {
        for (Map.Entry<UUID, BossBar> entry : bars.entrySet()) {
            Player player = plugin.getServer().getPlayer(
                    entry.getKey()
            );

            if (player != null && player.isOnline()) {
                player.hideBossBar(entry.getValue());
            }
        }

        bars.clear();
    }

    private String getTitle(String path) {
        String title = plugin.getConfig().getString(
                path,
                "<gold>FIREMC <white>» {time}s"
        );

        if (title == null || title.isBlank()) {
            return "<gold>FIREMC <white>» {time}s";
        }

        return title;
    }

    private BossBar.Color getColor(String value) {
        if (value == null || value.isBlank()) {
            return BossBar.Color.YELLOW;
        }

        try {
            return BossBar.Color.valueOf(
                    value.trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning(
                    "Invalid BossBar color '" + value
                            + "'. Using YELLOW."
            );

            return BossBar.Color.YELLOW;
        }
    }

    private BossBar.Overlay getOverlay(String value) {
        if (value == null || value.isBlank()) {
            return BossBar.Overlay.PROGRESS;
        }

        try {
            return BossBar.Overlay.valueOf(
                    value.trim().toUpperCase(Locale.ROOT)
            );
        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning(
                    "Invalid BossBar style '" + value
                            + "'. Using PROGRESS."
            );

            return BossBar.Overlay.PROGRESS;
        }
    }
}
