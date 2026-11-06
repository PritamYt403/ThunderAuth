package com.pritamx.thunderauth;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class ServerIdentity {

    private ServerIdentity() {
    }

    public static String loadOrCreate(Path dataFolder)
            throws IOException {

        Files.createDirectories(dataFolder);

        Path idFile = dataFolder.resolve("server-id.txt");

        if (Files.exists(idFile)) {
            String existingId = Files.readString(idFile).trim();

            if (!existingId.isBlank()) {
                return existingId;
            }
        }

        String serverId = UUID.randomUUID().toString();

        Files.writeString(idFile, serverId);

        return serverId;
    }
}
