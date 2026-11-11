
package com.pritamx.thunderauth;

import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class BossBarManager {

    private static final String DEFAULT_TITLE =
            "<gold>FIREMC <white>» {time}s";

    private static final BossBar.Color DEFAULT_COLOR =
            BossBar.Color.YELLOW;

    private static final BossBar.Overlay DEFAULT_OVERLAY =
            BossBar.Overlay.PROGRESS;

    private final ThunderAuth plugin;
    private final MiniMessage miniMessage =
            MiniMessage.miniMessage();

    private final Map<UUID, BossBar> activeBars =
            new ConcurrentHashMap<>();

    public BossBarManager(ThunderAuth plugin) {
        this.plugin = plugin;
    }

    public void show(Player player, boolean login, int totalSeconds) {
        if (!isPlayerOnline(player)) {
            return;
        }

        if (!isBossBarEnabled()) {
            remove(player);
            return;
        }

        // Remove any previous bar before creating a new one.
        remove(player);

        String title = getAuthenticationTitle(login);
        String formattedTitle = formatTitle(title, totalSeconds);

        BossBar bar = BossBar.bossBar(
                miniMessage.deserialize(formattedTitle),
                getInitialProgress(totalSeconds),
                getConfiguredColor(),
                getConfiguredOverlay()
        );

        activeBars.put(player.getUniqueId(), bar);
        player.showBossBar(bar);
    }

    public void update(
            Player player,
            boolean login,
            int remainingSeconds,
            int totalSeconds
    ) {
        if (!isPlayerOnline(player)) {
            return;
        }

        BossBar bar = activeBars.get(player.getUniqueId());

        if (bar == null) {
            return;
        }

        String title = getAuthenticationTitle(login);

        bar.name(
                miniMessage.deserialize(
                        formatTitle(title, remainingSeconds)
                )
        );

        if (plugin.getConfig().getBoolean(
                "presentation.bossbar.progress.enabled", true
        )) {
            bar.progress(
                    calculateProgress(remainingSeconds, totalSeconds)
            );
        }
    }

    public void remove(Player player) {
        if (player == null) {
            return;
        }

        BossBar bar = activeBars.remove(player.getUniqueId());

        if (bar != null && player.isOnline()) {
            player.hideBossBar(bar);
        }
    }

    public void removeAll() {
        for (Map.Entry<UUID, BossBar> entry : activeBars.entrySet()) {
            Player player = plugin.getServer().getPlayer(entry.getKey());

            if (isPlayerOnline(player)) {
                player.hideBossBar(entry.getValue());
            }
        }

        activeBars.clear();
    }

    private boolean isPlayerOnline(Player player) {
        return player != null && player.isOnline();
    }

    private boolean isBossBarEnabled() {
        return plugin.getConfig().getBoolean(
                "presentation.enabled", true
        ) && plugin.getConfig().getBoolean(
                "presentation.bossbar.enabled", true
        );
    }

    private String getAuthenticationTitle(boolean login) {
        String path = login
                ? "presentation.bossbar.login.title"
                : "presentation.bossbar.register.title";

        return getConfiguredTitle(path);
    }

    private String getConfiguredTitle(String path) {
        String title = plugin.getConfig().getString(
                path,
                DEFAULT_TITLE
        );

        return title == null || title.isBlank()
                ? DEFAULT_TITLE
                : title;
    }

    private String formatTitle(String title, int seconds) {
        return title.replace(
                "{time}",
                String.valueOf(Math.max(0, seconds))
        );
    }

    private float getInitialProgress(int totalSeconds) {
        return totalSeconds <= 0 ? 0.0f : 1.0f;
    }

    private float calculateProgress(
            int remainingSeconds,
            int totalSeconds
    ) {
        if (totalSeconds <= 0) {
            return 0.0f;
        }

        float progress =
                (float) remainingSeconds / totalSeconds;

        return Math.max(0.0f, Math.min(1.0f, progress));
    }

    private BossBar.Color getConfiguredColor() {
        String value = plugin.getConfig().getString(
                "presentation.bossbar.color",
                DEFAULT_COLOR.name()
        );

        return parseColor(value);
    }

    private BossBar.Color parseColor(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_COLOR;
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

            return DEFAULT_COLOR;
        }
    }

    private BossBar.Overlay getConfiguredOverlay() {
        String value = plugin.getConfig().getString(
                "presentation.bossbar.style",
                DEFAULT_OVERLAY.name()
        );

        return parseOverlay(value);
    }

    private BossBar.Overlay parseOverlay(String value) {
        if (value == null || value.isBlank()) {
            return DEFAULT_OVERLAY;
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

            return DEFAULT_OVERLAY;
        }
    }
}
