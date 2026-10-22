package com.pritamx.thunderauth;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.UUID;

public final class AccountStore {
    private static final int SALT_LENGTH = 16;
    private static final int HASH_ITERATIONS = 210_000;
    private static final int HASH_BITS = 256;

    private final ThunderAuth plugin;
    private final File accountsFile;
    private final SecureRandom secureRandom = new SecureRandom();
    private YamlConfiguration data;

    public AccountStore(ThunderAuth plugin) {
        this.plugin = plugin;
        this.accountsFile = new File(plugin.getDataFolder(), "accounts.yml");
        load();
    }

    public synchronized void load() {
        if (!plugin.getDataFolder().exists() && !plugin.getDataFolder().mkdirs()) {
            throw new IllegalStateException("Could not create ThunderAuth data folder.");
        }
        data = YamlConfiguration.loadConfiguration(accountsFile);
    }

    public synchronized void save() throws IOException {
        data.save(accountsFile);
    }

    public synchronized boolean hasAccount(UUID uuid) {
        return uuid != null && data.contains("accounts." + uuid);
    }

    public synchronized boolean hasIdentity(String identity) {
        if (identity == null || identity.isBlank()) return false;
        ConfigurationSection section = data.getConfigurationSection("accounts");
        if (section == null) return false;
        for (String accountId : section.getKeys(false)) {
            if (identity.equals(data.getString("accounts." + accountId + ".identity"))) return true;
        }
        return false;
    }

    public synchronized boolean hasRegistrationIp(String ipAddress) {
        String ipHash = hashIp(ipAddress);
        if (ipHash == null) return false;
        ConfigurationSection section = data.getConfigurationSection("accounts");
        if (section == null) return false;
        for (String accountId : section.getKeys(false)) {
            if (ipHash.equals(data.getString("accounts." + accountId + ".registration-ip-hash"))) return true;
        }
        return false;
    }

    public synchronized boolean register(UUID uuid, String identity, String password, String ipAddress)
            throws IOException {
        boolean uniqueIdentityRequired = plugin.getConfig().getBoolean(
                "settings.security.one-account-per-identity", true);
        boolean uniqueIpRequired = plugin.getConfig().getBoolean(
                "settings.security.one-account-per-ip", true);

        String ipHash = hashIp(ipAddress);
        if (uuid == null || identity == null || identity.isBlank() || password == null
                || hasAccount(uuid)
                || (uniqueIdentityRequired && hasIdentity(identity))
                || (uniqueIpRequired && (ipHash == null || hasRegistrationIp(ipAddress)))) {
            return false;
        }

        String path = "accounts." + uuid;
        byte[] salt = new byte[SALT_LENGTH];
        secureRandom.nextBytes(salt);
        String hash = hashPassword(password, salt);

        data.set(path + ".identity", identity);
        data.set(path + ".salt", Base64.getEncoder().encodeToString(salt));
        data.set(path + ".hash", hash);
        data.set(path + ".created-at", System.currentTimeMillis());
        if (ipHash != null) data.set(path + ".registration-ip-hash", ipHash);

        try {
            save();
            return true;
        } catch (IOException exception) {
            data.set(path, null);
            throw exception;
        }
    }

    /** Backwards-compatible overload for integrations that do not supply an IP. */
    public synchronized boolean register(UUID uuid, String identity, String password) throws IOException {
        return register(uuid, identity, password, null);
    }

    public synchronized boolean verifyPassword(UUID uuid, String password) {
        if (uuid == null || password == null || !hasAccount(uuid)) return false;
        String path = "accounts." + uuid;
        String encodedSalt = data.getString(path + ".salt");
        String expectedHash = data.getString(path + ".hash");
        if (encodedSalt == null || expectedHash == null) return false;
        try {
            byte[] salt = Base64.getDecoder().decode(encodedSalt);
            byte[] expected = Base64.getDecoder().decode(expectedHash);
            byte[] actual = Base64.getDecoder().decode(hashPassword(password, salt));
            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public synchronized boolean changePassword(UUID uuid, String newPassword) throws IOException {
        if (uuid == null || newPassword == null || !hasAccount(uuid)) return false;
        String path = "accounts." + uuid;
        String oldSalt = data.getString(path + ".salt");
        String oldHash = data.getString(path + ".hash");

        byte[] salt = new byte[SALT_LENGTH];
        secureRandom.nextBytes(salt);
        String newHash = hashPassword(newPassword, salt);
        data.set(path + ".salt", Base64.getEncoder().encodeToString(salt));
        data.set(path + ".hash", newHash);
        try {
            save();
            return true;
        } catch (IOException exception) {
            data.set(path + ".salt", oldSalt);
            data.set(path + ".hash", oldHash);
            throw exception;
        }
    }

    public synchronized String getIdentity(UUID uuid) {
        return uuid == null ? null : data.getString("accounts." + uuid + ".identity");
    }

    private String hashIp(String ipAddress) {
        if (ipAddress == null || ipAddress.isBlank()) return null;
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(ipAddress.trim().getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 is unavailable.", exception);
        }
    }

    private String hashPassword(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, HASH_ITERATIONS, HASH_BITS);
        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return Base64.getEncoder().encodeToString(factory.generateSecret(spec).getEncoded());
        } catch (Exception exception) {
            throw new IllegalStateException("Secure password hashing is unavailable.", exception);
        } finally {
            spec.clearPassword();
        }
    }
}
