
package com.pritamx.thunderauth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.*;
import java.util.Base64;

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

        if (dataFolder == null) {
            throw new IllegalArgumentException("Data folder cannot be null.");
        }

        if (databaseName == null
                || !databaseName.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,63}")) {
            throw new IllegalArgumentException(
                    "Invalid SQLite database filename."
            );
        }

        Path folder = dataFolder.toAbsolutePath().normalize();
        Files.createDirectories(folder);

        if (!Files.isDirectory(folder)) {
            throw new IOException("Plugin data folder is not a directory.");
        }

        Path resolvedFile = folder.resolve(databaseName).normalize();

        if (!resolvedFile.getParent().equals(folder)) {
            throw new IOException("Database path is outside plugin folder.");
        }

        if (Files.isSymbolicLink(resolvedFile)) {
            throw new IOException("Database symbolic links are not allowed.");
        }

        if (Files.exists(resolvedFile)
                && !Files.isRegularFile(resolvedFile)) {
            throw new IOException("Database path is not a regular file.");
        }

        Connection opened = DriverManager.getConnection(
                "jdbc:sqlite:" + resolvedFile
        );

        try {
            initializeDatabase(opened);
        } catch (SQLException | RuntimeException exception) {
            try {
                opened.close();
            } catch (SQLException closeError) {
                exception.addSuppressed(closeError);
            }
            throw exception;
        }

        this.databaseFile = resolvedFile;
        this.connection = opened;
    }

    private void initializeDatabase(Connection db) throws SQLException {

        try (Statement statement = db.createStatement()) {
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

        validateIdentity(identity);

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

        validateIdentity(identity);

        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("Username cannot be empty.");
        }

        if (password == null || password.length == 0) {
            throw new IllegalArgumentException("Password cannot be empty.");
        }

        if (!Double.isFinite(x) || !Double.isFinite(y)
                || !Double.isFinite(z)
                || !Float.isFinite(yaw) || !Float.isFinite(pitch)) {
            throw new IllegalArgumentException("Invalid player location.");
        }

        byte[] salt = new byte[SALT_LENGTH];
        byte[] hash = null;

        try {
            secureRandom.nextBytes(salt);
            hash = hashPassword(password, salt);

            String sql = """
                INSERT OR IGNORE INTO accounts (
                    identity, username, password_salt, password_hash,
                    created_at, first_world, first_x, first_y, first_z,
                    first_yaw, first_pitch
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {

                statement.setString(1, identity);
                statement.setString(2, username);
                statement.setString(3,
                        Base64.getEncoder().encodeToString(salt));
                statement.setString(4,
                        Base64.getEncoder().encodeToString(hash));
                statement.setLong(5, System.currentTimeMillis());
                statement.setString(6, world);
                statement.setDouble(7, x);
                statement.setDouble(8, y);
                statement.setDouble(9, z);
                statement.setFloat(10, yaw);
                statement.setFloat(11, pitch);

                return statement.executeUpdate() == 1;
            }
        } finally {
            java.util.Arrays.fill(salt, (byte) 0);

            if (hash != null) {
                java.util.Arrays.fill(hash, (byte) 0);
            }
        }
    }

    public synchronized boolean verifyPassword(
            String identity,
            char[] password
    ) throws SQLException, GeneralSecurityException {

        validateIdentity(identity);

        if (password == null) {
            return false;
        }

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

                byte[] salt;
                byte[] storedHash;

                try {
                    salt = Base64.getDecoder().decode(
                            result.getString("password_salt")
                    );

                    storedHash = Base64.getDecoder().decode(
                            result.getString("password_hash")
                    );
                } catch (IllegalArgumentException exception) {
                    throw new SQLException(
                            "Stored password data is corrupted.",
                            exception
                    );
                }

                if (salt.length != SALT_LENGTH
                        || storedHash.length != HASH_LENGTH) {
                    throw new SQLException(
                            "Stored password data has an invalid length."
                    );
                }

                byte[] enteredHash = null;

                try {
                    enteredHash = hashPassword(password, salt);

                    return MessageDigest.isEqual(
                            storedHash,
                            enteredHash
                    );
                } finally {
                    java.util.Arrays.fill(salt, (byte) 0);
                    java.util.Arrays.fill(storedHash, (byte) 0);

                    if (enteredHash != null) {
                        java.util.Arrays.fill(enteredHash, (byte) 0);
                    }
                }
            }
        }
    }

    public synchronized boolean changePassword(
            String identity,
            char[] oldPassword,
            char[] newPassword
    ) throws SQLException, GeneralSecurityException {

        validateIdentity(identity);

        if (!verifyPassword(identity, oldPassword)) {
            return false;
        }

        if (newPassword == null || newPassword.length == 0) {
            throw new IllegalArgumentException("New password cannot be empty.");
        }

        byte[] salt = new byte[SALT_LENGTH];
        byte[] hash = null;

        try {
            secureRandom.nextBytes(salt);
            hash = hashPassword(newPassword, salt);

            String sql = """
                UPDATE accounts
                SET password_salt = ?, password_hash = ?
                WHERE identity = ?
            """;

            try (PreparedStatement statement =
                         connection.prepareStatement(sql)) {

                statement.setString(1,
                        Base64.getEncoder().encodeToString(salt));
                statement.setString(2,
                        Base64.getEncoder().encodeToString(hash));
                statement.setString(3, identity);

                return statement.executeUpdate() == 1;
            }
        } finally {
            java.util.Arrays.fill(salt, (byte) 0);

            if (hash != null) {
                java.util.Arrays.fill(hash, (byte) 0);
            }
        }
    }

    public synchronized void updateLastLogin(String identity)
            throws SQLException {

        validateIdentity(identity);

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

        validateIdentity(identity);

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

    private void validateIdentity(String identity) {
        if (identity == null || identity.isBlank()
                || identity.length() > 200) {
            throw new IllegalArgumentException("Invalid player identity.");
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
