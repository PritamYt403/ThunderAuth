package com.pritamx.thunderauth;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

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
        if (!plugin.getDataFolder().exists()
                && !plugin.getDataFolder().mkdirs()) {
            throw new IllegalStateException(
                    "Could not create ThunderAuth data folder."
            );
        }

        data = YamlConfiguration.loadConfiguration(accountsFile);
    }

    public synchronized void save() throws IOException {
        data.save(accountsFile);
    }

    public synchronized boolean hasAccount(UUID uuid) {
        return data.contains("accounts." + uuid);
    }

    public synchronized boolean hasIdentity(String identity) {
        if (identity == null || identity.isBlank()) {
            return false;
        }

        ConfigurationSection accounts = data.getConfigurationSection("accounts");

        if (accounts == null) {
            return false;
        }

        for (String accountId : accounts.getKeys(false)) {
            String storedIdentity = data.getString(
                    "accounts." + accountId + ".identity"
            );

            if (identity.equals(storedIdentity)) {
                return true;
            }
        }

        return false;
    }

    public synchronized boolean register(
            UUID uuid,
            String identity,
            String password
    ) throws IOException {
        if (uuid == null || identity == null || identity.isBlank()
                || password == null || hasAccount(uuid)
                || hasIdentity(identity)) {
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

        try {
            save();
            return true;
        } catch (IOException exception) {
            data.set(path, null);
            throw exception;
        }
    }

    public synchronized boolean verifyPassword(
            UUID uuid,
            String password
    ) {
        if (uuid == null || password == null || !hasAccount(uuid)) {
            return false;
        }

        String path = "accounts." + uuid;
        String encodedSalt = data.getString(path + ".salt");
        String expectedHash = data.getString(path + ".hash");

        if (encodedSalt == null || expectedHash == null) {
            return false;
        }

        try {
            byte[] salt = Base64.getDecoder().decode(encodedSalt);
            byte[] expected = Base64.getDecoder().decode(expectedHash);
            byte[] actual = Base64.getDecoder().decode(
                    hashPassword(password, salt)
            );

            return MessageDigest.isEqual(expected, actual);
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    public synchronized boolean changePassword(
            UUID uuid,
            String newPassword
    ) throws IOException {
        if (uuid == null || newPassword == null || !hasAccount(uuid)) {
            return false;
        }

        String path = "accounts." + uuid;
        byte[] salt = new byte[SALT_LENGTH];
        secureRandom.nextBytes(salt);

        data.set(path + ".salt",
                Base64.getEncoder().encodeToString(salt));
        data.set(path + ".hash", hashPassword(newPassword, salt));

        save();
        return true;
    }

    public synchronized String getIdentity(UUID uuid) {
        if (uuid == null) {
            return null;
        }

        return data.getString("accounts." + uuid + ".identity");
    }

    private String hashPassword(String password, byte[] salt) {
        PBEKeySpec spec = new PBEKeySpec(
                password.toCharArray(),
                salt,
                HASH_ITERATIONS,
                HASH_BITS
        );

        try {
            SecretKeyFactory factory =
                    SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");

            return Base64.getEncoder().encodeToString(
                    factory.generateSecret(spec).getEncoded()
            );
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Secure password hashing is unavailable.",
                    exception
            );
        } finally {
            spec.clearPassword();
        }
    }
      }
