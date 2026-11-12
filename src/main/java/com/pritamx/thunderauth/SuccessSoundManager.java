
package com.pritamx.thunderauth;

import org.bukkit.Sound;
import org.bukkit.entity.Player;

import java.util.Locale;

public final class SuccessSoundManager {

    private final ThunderAuth plugin;

    public SuccessSoundManager(ThunderAuth plugin) {
        this.plugin = plugin;
    }

    public void play(Player player, String type) {
        if (player == null || !player.isOnline()
                || type == null || type.isBlank()) {
            return;
        }

        if (!plugin.getConfig().getBoolean(
                "effects.sounds.enabled", true
        )) {
            return;
        }

        String path = "effects.sounds." + type;

        String soundName = plugin.getConfig().getString(
                path + ".sound",
                ""
        );

        if (soundName == null || soundName.isBlank()) {
            return;
        }

        float volume = (float) plugin.getConfig().getDouble(
                path + ".volume", 1.0
        );

        float pitch = (float) plugin.getConfig().getDouble(
                path + ".pitch", 1.0
        );

        volume = Math.max(0.0f, Math.min(volume, 10.0f));
        pitch = Math.max(0.5f, Math.min(pitch, 2.0f));

        try {
            Sound sound = Sound.valueOf(
                    soundName.trim().toUpperCase(Locale.ROOT)
            );

            player.playSound(
                    player.getLocation(),
                    sound,
                    volume,
                    pitch
            );

        } catch (IllegalArgumentException exception) {
            plugin.getLogger().warning(
                    "Invalid authentication sound in config: "
                            + soundName
            );
        }
    }
}
