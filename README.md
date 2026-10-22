# ThunderAuth

Professional Minecraft authentication plugin by Pritam.

## Features
- Register, login, and change password
- PBKDF2-HMAC-SHA256 password hashes with per-account random salts
- UUID identity binding and optional Floodgate XUID identity support
- Optional one-account-per-IP registration rule (IP values are stored as SHA-256 hashes)
- Rejects a password exactly matching the player's Minecraft username (case-insensitive)
- Login timeout, failed-attempt kick, reminders, titles, and BossBar timer
- Unauthenticated-player restrictions and configurable login/registration spawns
- Configurable messages in `messages.yml`

## Build
Requires Java 21 and Maven. Run `mvn --batch-mode clean package`. The JAR is created at `target/ThunderAuth.jar`.

## Notes
- Floodgate is an optional `softdepend`; Java-only mode remains available when Floodgate is absent.
- With `settings.security.one-account-per-ip: true`, players sharing a public IP (for example, household Wi-Fi or carrier-grade mobile NAT) share the same registration limit.
- Existing accounts created before the IP feature have no recorded registration IP, so the IP rule applies to registrations made after this version is installed.
- If the server is behind a proxy, ensure the proxy forwarding configuration is secure so the server receives the real client address.
