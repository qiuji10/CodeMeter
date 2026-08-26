# Security

This project handles bearer and refresh tokens capable of authenticating to provider services.

- Every profile stores its OAuth token bundle under a separate encrypted record.
- Never print or upload OAuth tokens, authorization codes, device codes, or JWTs.
- Never commit runtime credentials. The project contains only public client identifiers used by the first-party CLI flows.
- Keep Android backups disabled unless credential storage is redesigned.
- Do not replace HTTPS endpoints with cleartext transports.
- Treat changes to OAuth endpoints, scopes, redirect URIs, or client identifiers as security-sensitive.
- Profile names/types and display settings are stored in ordinary app-private preferences; OAuth token bundles remain encrypted with Android Keystore.
- Disconnect removes only that profile's encrypted OAuth token bundle. Remove Profile additionally deletes its local quota history and notification state.
- Treat the Gradle wrapper as executable supply-chain material. The bootstrap scripts verify the published Gradle 9.4.1 wrapper SHA-256, and `gradle-wrapper.properties` verifies the Gradle distribution SHA-256.
- For distribution to other people, review provider authorization policies and current terms before release.

If the device is rooted or the Android OS is compromised, Android Keystore and application sandbox protections may no longer be sufficient.
