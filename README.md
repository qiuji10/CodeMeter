# CodeMeter

Android-only quota tracker for **Claude Code** and **OpenAI Codex** subscriptions.

There is no companion app and no custom server. The phone authenticates with each provider, queries subscription usage directly, stores OAuth tokens encrypted by Android Keystore, and records quota snapshots in a local SQLite database.

## Included

- Jetpack Compose dashboard.
- **Multiple profiles/accounts** for both Claude Code and Codex.
- Add Profile -> choose Claude Code or Codex -> connect that account.
- Profile rename, disconnect, refresh, and removal from each card's options menu.
- Persistent animated drag-and-drop profile ordering from **Settings -> Profiles**, with a lifted drag preview, drop-slot ghost, and animated neighbouring cards.
- Existing v0.1 single-account credentials/history migrate automatically into normal profiles.
- Claude Code OAuth + PKCE login with copy/paste authorization code.
- Codex/ChatGPT device-code login.
- Claude 5-hour/session, weekly, dynamic model-scoped limits, and extra-usage parsing.
- Codex session, weekly, model-specific limits, flex credits, and read-only reset-credit count.
- Provider-aware 429 handling with persisted `Retry-After` cooldowns and last-good stale-data fallback.
- Data freshness state (`Updated ... ago`, provider/rate-limit status, retry countdown).
- OAuth refresh-token handling for every profile.
- AES-256-GCM token encryption using Android Keystore.
- Local 24-hour session-usage history per profile on a fixed 0–100% scale with reset detection; history is retained for 31 days.
- Automatic refresh on every cold start and warm foreground resume, plus manual refresh, configurable foreground auto-refresh, and optional WorkManager background fetch.
- Two local notification types: nearly-exhausted quota alerts and quota-reset alerts.
- No analytics, ads, account system, telemetry, server, or desktop dependency.

## Settings

The Settings page includes:

- **Usage used / Usage left** display mode. This changes both the displayed percentage and progress-bar meaning.
- **Auto refresh** master toggle for foreground refreshes.
- Auto-refresh interval: **5 / 10 / 15 / 30 / 60 minutes** (default 5 minutes).
- **Background fetch** toggle; Android enforces a 15-minute minimum for periodic background work.
- **Nearly exhausted** notification toggle with one configurable usage threshold (default 90% used for new installs).
- **Limit reset** notification toggle when a quota window ends and becomes available again.
- Link to Android's system notification settings.
- **Compact view** for tighter profile cards and shorter history charts.
- **Reorder profiles** with long-press drag and drop.
- Reset time shown as **remaining time** or an **exact local date/time**.
- Toggle for the 24-hour history section.

Notifications are fully local; no remote push server exists. Nearly-exhausted alerts are evaluated after usage refreshes and use quota **consumed**, even if the dashboard shows usage left. When a provider exposes a reset timestamp, CodeMeter schedules a one-time local WorkManager notification for that reset; Android may delay it slightly under battery optimization/doze.

## Provider colors

- Claude Code: orange progress/history bars.
- Codex: blue progress/history bars.

## Important limitation

This is a personal/experimental client. The Claude subscription usage endpoint, Codex WHAM usage endpoint, and the first-party CLI OAuth client identifiers used by this project are not public third-party API contracts. Anthropic or OpenAI can change or disable them without notice. If that happens, update the constants/parsers under `provider/claude/` or `provider/codex/`.

Do not ship this broadly or publish it to Google Play without reviewing the current provider terms and replacing these flows with officially supported third-party integrations if the providers make those available.

## Requirements

- Current Android Studio stable.
- Android SDK Platform 37.
- Android SDK Build Tools compatible with API 37.
- JDK 17+.
- Android 8.0 / API 26 or later device.

The project uses Android Gradle Plugin 9.2.0, Gradle 9.4.1 and Compose BOM 2026.08.00.

The Android application ID is `com.qiuji.codemeter`. Because this differs from earlier builds that used `com.hongyu.aiusage`, Android treats this as a separate app; existing profiles/history from the old package are not automatically transferred.

## Open and run

1. Extract/open this folder in Android Studio.
2. Allow Gradle sync to download dependencies.
3. If SDK 37 is missing, install it from **Tools -> SDK Manager**.
4. Select an Android device and run `app`.
5. Notification permission is requested from Settings when needed.

The included `gradlew`/`gradlew.bat` bootstrap the official Gradle 9.4.1 wrapper JAR on first use and verify its published SHA-256 before executing it. The Gradle distribution itself is also pinned with its published SHA-256 in `gradle-wrapper.properties`.

CLI build after your Android SDK is configured:

```bash
./gradlew assembleDebug
```

Windows PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## GitHub Actions signed release

`.github/workflows/android.yml` always runs integrity checks, unit tests, and Debug/Release builds. When release-signing secrets are configured, CI also signs the release APK with your permanent CodeMeter key and verifies the certificate with `apksigner`.

Create these **Repository secrets** under **GitHub -> Settings -> Secrets and variables -> Actions**:

```text
CODEMETER_KEYSTORE_BASE64
CODEMETER_KEYSTORE_PASSWORD
CODEMETER_KEY_ALIAS
CODEMETER_KEY_PASSWORD
```

`CODEMETER_KEYSTORE_BASE64` must contain the Base64 representation of the same `.jks` / `.keystore` that signed your existing `com.qiuji.codemeter` release. Using a different key makes Android reject an in-place update with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Never commit the keystore itself.

PowerShell example for creating the Base64 secret value:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("C:\path\to\codemeter-release.jks"))
```

With all four secrets present, the workflow artifact includes:

```text
CodeMeter-v0.4.2.apk                  # signed release; distribute/install this
CodeMeter-v0.4.2-debug.apk            # CI/debug only
CodeMeter-v0.4.2-release-unsigned.apk # verification only; Android cannot install it
CodeMeter-android-v0.4.2-source.zip
SHA256SUMS.txt
```

Normal branch and pull-request CI still works without signing secrets and simply omits the signed APK. A `v*` tag is stricter: the job fails if signing is not configured, preventing an unsigned APK from being mistaken for a release.

## Adding profiles

1. Tap **+** in the home toolbar.
2. Choose **Claude Code** or **Codex**.
3. Tap **Add & connect**.
4. Complete that provider's login flow.
5. The new profile appears as its own card on the dashboard.

Adding the same provider again creates `Claude Code 2`, `Codex 2`, etc. Rename a profile from its three-dot card menu to make accounts easier to identify.

### Claude Code login

1. Add a Claude Code profile or tap Connect on an existing disconnected Claude profile.
2. Tap **Open Claude authorization**.
3. Sign in/approve in the browser using the account intended for that profile.
4. Copy the authorization code shown by Claude.
5. Return to CodeMeter, paste it, and tap **Connect**.

Current implementation points:

- Authorization: `https://claude.com/cai/oauth/authorize`
- Token: `https://platform.claude.com/v1/oauth/token`
- Usage: `https://api.anthropic.com/api/oauth/usage`
- Redirect used by Claude Code: `https://platform.claude.com/oauth/code/callback`

### Codex login

1. Add a Codex profile or tap Connect on an existing disconnected Codex profile.
2. Copy the device code and tap **Open browser**.
3. Sign in to ChatGPT using the account intended for that profile and authorize the code.
4. Return to the app and tap **Finish**.

Current implementation points:

- Device code: `https://auth.openai.com/api/accounts/deviceauth/usercode`
- Device polling: `https://auth.openai.com/api/accounts/deviceauth/token`
- Verification page: `https://auth.openai.com/codex/device`
- OAuth token: `https://auth.openai.com/oauth/token`
- Usage: `https://chatgpt.com/backend-api/wham/usage`
- Reset credits: `https://chatgpt.com/backend-api/wham/rate-limit-reset-credits`

## Start session window

When a connected profile has a fresh successful usage snapshot and its normal 5-hour **Session** window has not started yet, the profile card's three-dot menu shows **Start session window**.

This is an explicit, consequential action: CodeMeter asks for confirmation, then sends one deliberately tiny real inference request (`Reply with hi`) using that profile's existing OAuth login. That small request consumes real quota and starts the provider's normal session timer early. CodeMeter never performs this action automatically, on app startup, during background refresh, or from WorkManager.

After the request succeeds, CodeMeter waits briefly and makes at most one usage refresh to confirm the new reset timestamp. If the provider's usage backend has not propagated the new window yet, the action still reports success and the next normal refresh will pick it up.

Implementation points:

- Claude: `POST https://api.anthropic.com/v1/messages` using the existing `user:inference` OAuth scope, a one-token response, and the Claude Code subscription request marker/header shape.
- Codex: `POST https://chatgpt.com/backend-api/codex/responses` using the existing ChatGPT bearer token + account id, `store=false`, and a minimal streamed Responses request.
- Both inference routes are private/undocumented subscription-client behavior and can change independently of the usage endpoints.

## Security model

- Every profile has its own encrypted OAuth token record.
- OAuth access/refresh/ID tokens are encrypted with an AES-256 key generated inside Android Keystore.
- Android backup is disabled for the app.
- Cleartext HTTP is disabled.
- Tokens are never intentionally logged.
- Disconnect deletes that profile's stored OAuth tokens but preserves its local history/profile card.
- Remove Profile deletes the profile, OAuth tokens, notification state, and local usage history.
- Usage history contains only profile/provider/window metadata, percentages, reset timestamps, and capture timestamps.
- The app never sends data to a developer-owned server because there is no server component.

A rooted/compromised Android device can still undermine application-level protections.

## Refresh and background behavior

Every time CodeMeter enters the foreground—both a cold process launch and a warm resume—it immediately refreshes every connected profile. This startup/resume refresh is independent of the periodic **Auto refresh** toggle.

Android WorkManager's periodic minimum is 15 minutes. Foreground auto-refresh can use the selected 5/10-minute interval; background fetch uses `max(selected interval, 15 minutes)` and refreshes every connected profile when a network is available.

Provider cooldowns are authoritative. If Claude or Codex returns HTTP 429, CodeMeter honors `Retry-After` (or a five-minute fallback), persists that cooldown across process restarts, and suppresses further manual/foreground/background provider calls until it expires. Last-good quota data remains visible and is marked stale. Transient network/5xx failures also preserve last-good data. Stale data is never inserted into history and never triggers quota notifications.

Because Android can defer WorkManager under battery optimization/doze, a background fetch is approximate. Opening/foregrounding CodeMeter always requests an immediate refresh unless the provider is currently in a mandatory cooldown.

## Project structure

```text
app/src/main/java/com/qiuji/codemeter/
├── CodeMeterApplication.kt
├── AppGraph.kt
├── MainActivity.kt
├── data/
│   ├── ProfileStore.kt
│   ├── SettingsStore.kt
│   └── UsageRepository.kt
├── db/UsageHistoryDb.kt
├── model/Models.kt
├── network/Http.kt
├── notification/UsageNotifier.kt
├── provider/
│   ├── claude/
│   │   ├── ClaudeAuth.kt
│   │   ├── ClaudeSessionClient.kt
│   │   └── ClaudeUsageClient.kt
│   └── codex/
│       ├── CodexAuth.kt
│       ├── CodexSessionClient.kt
│       └── CodexUsageClient.kt
├── security/SecureStore.kt
├── ui/
│   ├── AppScreen.kt
│   ├── AppViewModel.kt
│   └── Theme.kt
├── util/
│   ├── Jwt.kt
│   ├── Pkce.kt
│   └── TimeFormat.kt
└── worker/
    ├── UsageRefreshWorker.kt
    └── ResetNotificationWorker.kt
```

## When provider APIs change

Start with `docs/PROVIDER_NOTES.md`. Network endpoints and client ids are centralized in the two auth/client classes. JSON parsing intentionally accepts multiple known field aliases so small response-shape changes do not immediately break the dashboard.

## Not included

The Android-only app cannot reproduce CLI-local statistics such as Claude's `~/.claude/projects/` logs or Codex's `~/.codex/sessions/` logs. It tracks subscription quota directly and builds its own history from snapshots taken by the phone.
