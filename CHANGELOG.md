# Changelog

## 0.4.0

- Updated Claude Code usage requests to the current OAuth usage request shape, including Claude Code headers and current OAuth scopes.
- Claude parser now accepts current `limits[]` / `weekly_scoped` model limits, nested `scope.model.display_name`, object-or-array quota buckets, legacy model keys, `percent_left`, and multiple reset timestamp aliases.
- Claude model-specific limits are discovered dynamically instead of hardcoding one model name.
- Claude HTTP 429 responses now honor `Retry-After`; the cooldown is persisted across process restarts and CodeMeter keeps the last successful bars visible as stale data instead of blanking the dashboard.
- Added stale/freshness status such as `Updated 8m ago · Rate limited · retry in 4m`.
- Transient network/5xx failures preserve the last successful snapshot without inserting fake history points or firing notifications.
- Updated Codex quota parsing to classify Session/Weekly by `limit_window_seconds`, including the case where a sole weekly window moves into `primary_window`.
- Codex can fall back to `x-codex-primary-used-percent` / `x-codex-secondary-used-percent` response headers.
- Added Codex legacy `percent_left` and reset-time aliases.
- Codex additional/model limits now support both session and weekly windows; Spark remains hidden for Plus profiles and is named from `limit_name`/`metered_feature` when eligible.
- Added read-only Codex rate-limit reset-credit count using the dedicated reset-credit endpoint with usage-body fallback.
- Added Codex flex-credit value display when a balance is returned.
- Every cold start and every warm foreground resume now immediately refreshes all connected profiles, independent of the periodic Auto refresh toggle.
- Provider-aware refresh prevents foreground/manual/background polling from bypassing an active provider cooldown.
- Version bumped to 0.4.0 (`versionCode` 14).


## 0.3.10

- Codex additional rate limits now use `limit_name` first, with `metered_feature` as a readable fallback.
- Spark-specific quota telemetry is hidden for ChatGPT Plus profiles because that bucket is not usable by the Plus plan.
- Other additional/model-specific Codex limits remain visible when the backend returns them.

## 0.3.8

- Simplified notifications to exactly two user-facing types: **Nearly exhausted** and **Limit reset**.
- Replaced warning/critical thresholds with one configurable nearly-exhausted threshold (default 90% used for new installs).
- Added independent toggles for nearly-exhausted and reset notifications.
- Reset alerts are scheduled locally from provider reset timestamps using one-time WorkManager jobs, so they do not depend on the next periodic usage fetch.
- Added a fallback reset detector for usage windows that do not expose a reset timestamp.
- Disconnecting/removing a profile now cancels its scheduled reset notifications.
- Existing notification preferences migrate automatically: the previous critical threshold becomes the new nearly-exhausted threshold when present.

## 0.3.7

- Fixed Compose compilation error in the simplified history card by importing `androidx.compose.ui.text.style.TextOverflow`.
- No behavior or data-format changes.

## 0.3.6

- Simplified 24-hour history cards to reduce visual noise.
- Replaced verbose graph explanations with a compact `Session · left/used` label.
- Removed Y-axis percentage labels, change summaries, direction legends, provider subtitles, and reset legend text.
- Kept a fixed 0-100% chart scale with subtle guide lines so graphs remain comparable.
- Kept only `24h ago` and `Now` time labels plus a compact reset count when applicable.
- Reduced graph height and visual weight while preserving provider color and latest-value indication.

## 0.3.5

- Fixed Compose compilation error caused by importing `matchParentSize` as a top-level layout extension.
- Reorder drag placeholder still uses `Modifier.matchParentSize()` correctly inside `BoxScope`.

# CodeMeter 0.3.4

- Reworked profile drag-and-drop interaction with a lifted card that follows the pointer.
- Added a visible translucent drop-slot ghost while dragging.
- Added smooth placement animation for neighbouring profiles as the dragged profile crosses them.
- Added animated lift/scale/shadow and provider-colored drag emphasis.
- Reordering now uses each measured card height rather than a fixed threshold, improving drag accuracy.
- Added a smooth settle animation when the profile is released.

# CodeMeter 0.3.3

- Reworked the 24-hour history section so it follows the selected Usage Used / Usage Left mode.
- Added an explicit history title and explanation of what line direction means.
- Added 0–100% Y-axis labels and a fixed 24-hour X-axis with 24h ago / 12h ago / Now markers.
- Added per-profile history cards with the current percentage, first-to-latest change summary, and provider color.
- Added latest-point markers.
- Added quota-reset detection with dashed reset markers and reset counts.
- History now uses a true 24-hour time scale instead of stretching the available samples across the whole graph.

# CodeMeter 0.3.2

- Replaced deprecated `Icons.Filled.ArrowBack` usage with `Icons.AutoMirrored.Filled.ArrowBack`.
- Added **Auto refresh** toggle for connected profiles while CodeMeter is in the foreground.
- Added configurable auto-refresh interval: 5, 10, 15, 30, or 60 minutes; default is 5 minutes.
- Added **Background fetch** toggle.
- Background WorkManager scheduling now follows the selected interval while respecting Android's 15-minute minimum periodic interval.
- Disabling Background fetch cancels the periodic worker.

# CodeMeter 0.3.1

- Changed Android namespace and application ID to `com.qiuji.codemeter`.
- Moved Kotlin source packages to `com.qiuji.codemeter`.
- Note: Android treats this package as separate from earlier `com.hongyu.aiusage` builds, so existing app data is not automatically migrated.

# Changelog

## 0.3.0

- Renamed the Android Studio project and visible app name to **CodeMeter** while keeping the existing application ID so installed v0.2 data upgrades in place.
- Replaced Material 3's segmented/stop-indicator progress bar with one continuous bar: 0% starts empty at the left and 100% fills to the right edge.
- Added a new adaptive launcher icon with a larger safe margin so launcher masks do not crop it.
- Added Android system-back handling from Settings back to the CodeMeter home screen.
- Added drag-and-drop profile reordering under Settings -> Profiles; profile order is persisted locally.
- Kept Claude orange and Codex blue provider accents.

## 0.2.0

- Added Settings page.
- Added Usage Used / Usage Left display mode.
- Added configurable local notification enable/disable, warning threshold, and critical threshold.
- Added Android system notification settings shortcut.
- Added Compact View.
- Added reset-time display mode: remaining time or exact local date/time.
- Added show/hide 24-hour history setting.
- Added multi-profile architecture for any number of Claude Code and Codex accounts.
- Added Add Profile flow with provider selection.
- Added automatic profile naming and rename action.
- Moved Disconnect into each profile card's three-dot options menu.
- Added Remove Profile with local credential/history cleanup.
- Changed Claude progress/history color to orange.
- Changed Codex progress/history color to blue.
- Added automatic migration from v0.1 single-provider credential/history storage.
- Moved notification permission request into Settings instead of prompting at app startup.
