package com.pritamx.thunderauth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Base64;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class AccountStore {

    private static final int HASH_ITERATIONS = 210_000;
    private static final int SALT_LENGTH = 16;
    private static final int HASH_LENGTH = 32;

    private final Path databaseFile;
    private final Connection connection;
    private final SecureRandom secureRandom = new SecureRandom();

    public record Account(
            String identity,
            String username,
            long createdAt,
            long lastLogin,
            String firstWorld,
            double firstX,
            double firstY,
            double firstZ,
            float firstYaw,
            float firstPitch
    ) {}

    public AccountStore(Path dataFolder, String databaseName)
            throws SQLException, IOException {

        Files.createDirectories(dataFolder);

        this.databaseFile = dataFolder.resolve(databaseName);

        this.connection = DriverManager.getConnection(
                "jdbc:sqlite:" + databaseFile.toAbsolutePath()
        );

        initializeDatabase();
    }

    private void initializeDatabase() throws SQLException {

        try (Statement statement = connection.createStatement()) {

            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=FULL");
            statement.execute("PRAGMA foreign_keys=ON");
            statement.execute("PRAGMA busy_timeout=5000");

            statement.execute("""
                CREATE TABLE IF NOT EXISTS accounts (
                    identity TEXT PRIMARY KEY,
                    username TEXT NOT NULL,
                    password_salt TEXT NOT NULL,
                    password_hash TEXT NOT NULL,
                    created_at INTEGER NOT NULL,
                    last_login INTEGER NOT NULL DEFAULT 0,
                    first_world TEXT,
                    first_x REAL NOT NULL DEFAULT 0,
                    first_y REAL NOT NULL DEFAULT 0,
                    first_z REAL NOT NULL DEFAULT 0,
                    first_yaw REAL NOT NULL DEFAULT 0,
                    first_pitch REAL NOT NULL DEFAULT 0
                )
            """);
        }
    }

    public synchronized boolean isRegistered(String identity)
            throws SQLException {

        String sql = "SELECT 1 FROM accounts WHERE identity = ?";

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, identity);

            try (ResultSet result = statement.executeQuery()) {
                return result.next();
            }
        }
    }

    public synchronized boolean register(
            String identity,
            String username,
            char[] password,
            String world,
            double x,
            double y,
            double z,
            float yaw,
            float pitch
    ) throws SQLException, GeneralSecurityException {

        byte[] salt = new byte[SALT_LENGTH];
        secureRandom.nextBytes(salt);

        byte[] hash = hashPassword(password, salt);

        String sql = """
            INSERT INTO accounts (
                identity, username, password_salt, password_hash,
                created_at, first_world, first_x, first_y, first_z,
                first_yaw, first_pitch
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, identity);
            statement.setString(2, username);
            statement.setString(3, Base64.getEncoder().encodeToString(salt));
            statement.setString(4, Base64.getEncoder().encodeToString(hash));
            statement.setLong(5, System.currentTimeMillis());
            statement.setString(6, world);
            statement.setDouble(7, x);
            statement.setDouble(8, y);
            statement.setDouble(9, z);
            statement.setFloat(10, yaw);
            statement.setFloat(11, pitch);

            return statement.executeUpdate() == 1;
        }
    }

    public synchronized boolean verifyPassword(
            String identity,
            char[] password
    ) throws SQLException, GeneralSecurityException {

        String sql = """
            SELECT password_salt, password_hash
            FROM accounts
            WHERE identity = ?
        """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, identity);

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    return false;
                }

                byte[] salt = Base64.getDecoder().decode(
                        result.getString("password_salt")
                );

                byte[] storedHash = Base64.getDecoder().decode(
                        result.getString("password_hash")
                );

                byte[] enteredHash = hashPassword(password, salt);

                return MessageDigest.isEqual(storedHash, enteredHash);
            }
        }
    }

    public synchronized boolean changePassword(
            String identity,
            char[] oldPassword,
            char[] newPassword
    ) throws SQLException, GeneralSecurityException {

        if (!verifyPassword(identity, oldPassword)) {
            return false;
        }

        byte[] salt = new byte[SALT_LENGTH];
        secureRandom.nextBytes(salt);

        byte[] hash = hashPassword(newPassword, salt);

        String sql = """
            UPDATE accounts
            SET password_salt = ?, password_hash = ?
            WHERE identity = ?
        """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, Base64.getEncoder().encodeToString(salt));
            statement.setString(2, Base64.getEncoder().encodeToString(hash));
            statement.setString(3, identity);

            return statement.executeUpdate() == 1;
        }
    }

    public synchronized void updateLastLogin(String identity)
            throws SQLException {

        String sql = """
            UPDATE accounts
            SET last_login = ?
            WHERE identity = ?
        """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setLong(1, System.currentTimeMillis());
            statement.setString(2, identity);
            statement.executeUpdate();
        }
    }

    public synchronized Account getAccount(String identity)
            throws SQLException {

        String sql = """
            SELECT identity, username, created_at, last_login,
                   first_world, first_x, first_y, first_z,
                   first_yaw, first_pitch
            FROM accounts
            WHERE identity = ?
        """;

        try (PreparedStatement statement = connection.prepareStatement(sql)) {

            statement.setString(1, identity);

            try (ResultSet result = statement.executeQuery()) {

                if (!result.next()) {
                    return null;
                }

                return new Account(
                        result.getString("identity"),
                        result.getString("username"),
                        result.getLong("created_at"),
                        result.getLong("last_login"),
                        result.getString("first_world"),
                        result.getDouble("first_x"),
                        result.getDouble("first_y"),
                        result.getDouble("first_z"),
                        result.getFloat("first_yaw"),
                        result.getFloat("first_pitch")
                );
            }
        }
    }

    private byte[] hashPassword(char[] password, byte[] salt)
            throws GeneralSecurityException {

        PBEKeySpec spec = new PBEKeySpec(
                password,
                salt,
                HASH_ITERATIONS,
                HASH_LENGTH * 8
        );

        try {
            SecretKeyFactory factory = SecretKeyFactory.getInstance(
                    "PBKDF2WithHmacSHA256"
            );

            return factory.generateSecret(spec).getEncoded();

        } finally {
            spec.clearPassword();
        }
    }

    public synchronized void closeAndBackup(Path backupFolder)
            throws SQLException, IOException {

        if (connection.isClosed()) {
            return;
        }

        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA wal_checkpoint(TRUNCATE)");
        }

        connection.close();

        Files.createDirectories(backupFolder);

        String backupName = "accounts-" +
                System.currentTimeMillis() + ".db";

        Files.copy(
                databaseFile,
                backupFolder.resolve(backupName)
        );
    }

    public synchronized void close() throws SQLException {

        if (!connection.isClosed()) {
            connection.close();
        }
    }
}
