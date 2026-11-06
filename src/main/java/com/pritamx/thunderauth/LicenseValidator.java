package com.pritamx.thunderauth;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

public final class LicenseValidator {

    private static final String API_URL =
            "https://thunderlicense.blockmc.indevs.in/api/validate";

    private static final Pattern VALID_PATTERN =
            Pattern.compile("\"valid\"\\s*:\\s*true");

    private final HttpClient httpClient;

    public record ValidationResult(
            boolean valid,
            String response
    ) {}

    public LicenseValidator() {
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public CompletableFuture<ValidationResult> validate(
            String licenseKey,
            String serverId,
            String serverName
    ) {

        if (licenseKey == null || licenseKey.isBlank()
                || serverId == null || serverId.isBlank()) {

            return CompletableFuture.completedFuture(
                    new ValidationResult(false, "Missing license information")
            );
        }

        String json = "{"
                + "\"license_key\":\"" + escapeJson(licenseKey) + "\","
                + "\"plugin\":\"ThunderAuth\","
                + "\"server_id\":\"" + escapeJson(serverId) + "\","
                + "\"server_name\":\"" + escapeJson(
                        serverName == null ? "Unknown" : serverName
                ) + "\""
                + "}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json))
                .build();

        return httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofString()
        ).thenApply(response -> {

            String body = response.body();

            boolean valid = response.statusCode() == 200
                    && VALID_PATTERN.matcher(body).find();

            return new ValidationResult(valid, body);

        }).exceptionally(exception ->
                new ValidationResult(false, exception.getMessage())
        );
    }

    private String escapeJson(String value) {
        return value
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
