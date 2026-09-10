#!/usr/bin/env python3
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1]
required = [
    "settings.gradle.kts",
    "build.gradle.kts",
    "gradle/wrapper/gradle-wrapper.properties",
    "app/build.gradle.kts",
    "app/src/main/AndroidManifest.xml",
    "app/src/main/java/com/qiuji/codemeter/MainActivity.kt",
    "app/src/main/java/com/qiuji/codemeter/data/ProfileStore.kt",
    "app/src/main/java/com/qiuji/codemeter/data/SettingsStore.kt",
    "app/src/main/java/com/qiuji/codemeter/data/UsageRepository.kt",
    "app/src/main/java/com/qiuji/codemeter/network/Http.kt",
    "app/src/main/java/com/qiuji/codemeter/provider/claude/ClaudeAuth.kt",
    "app/src/main/java/com/qiuji/codemeter/provider/claude/ClaudeUsageClient.kt",
    "app/src/main/java/com/qiuji/codemeter/provider/claude/ClaudeSessionClient.kt",
    "app/src/main/java/com/qiuji/codemeter/provider/codex/CodexAuth.kt",
    "app/src/main/java/com/qiuji/codemeter/provider/codex/CodexUsageClient.kt",
    "app/src/main/java/com/qiuji/codemeter/provider/codex/CodexSessionClient.kt",
    "app/src/main/java/com/qiuji/codemeter/worker/ResetNotificationWorker.kt",
    "app/src/test/java/com/qiuji/codemeter/provider/claude/ClaudeUsageClientTest.kt",
    "app/src/test/java/com/qiuji/codemeter/provider/claude/ClaudeSessionClientTest.kt",
    "app/src/test/java/com/qiuji/codemeter/provider/codex/CodexSessionClientTest.kt",
    "app/src/test/java/com/qiuji/codemeter/model/SessionWindowStateTest.kt",
    ".github/workflows/android.yml",
]
missing = [p for p in required if not (root / p).is_file()]
if missing:
    print("Missing required files:")
    for item in missing:
        print(" -", item)
    sys.exit(1)

manifest_path = root / "app/src/main/AndroidManifest.xml"
manifest = manifest_path.read_text()
ET.parse(manifest_path)
assert 'android:allowBackup="false"' in manifest
assert 'android:usesCleartextTraffic="false"' in manifest

for xml in (root / "app/src/main/res").rglob("*.xml"):
    ET.parse(xml)

claude = (root / "app/src/main/java/com/qiuji/codemeter/provider/claude/ClaudeUsageClient.kt").read_text()
codex = (root / "app/src/main/java/com/qiuji/codemeter/provider/codex/CodexUsageClient.kt").read_text()
assert "https://api.anthropic.com/api/oauth/usage" in claude
assert "https://chatgpt.com/backend-api/wham/usage" in codex

screen = (root / "app/src/main/java/com/qiuji/codemeter/ui/AppScreen.kt").read_text()
models = (root / "app/src/main/java/com/qiuji/codemeter/model/Models.kt").read_text()
db = (root / "app/src/main/java/com/qiuji/codemeter/db/UsageHistoryDb.kt").read_text()
assert "Add Profile" in screen
assert "Usage left" in screen and "Usage used" in screen
assert "Compact view" in screen
assert "ClaudeOrange" in screen and "CodexBlue" in screen
assert "UsageProgressBar" in screen and "LinearProgressIndicator" not in screen
assert "ReorderProfilesPage" in screen and "detectDragGesturesAfterLongPress" in screen
assert "BackHandler(enabled = showSettings" in screen
assert "CodeMeter" in screen
assert "Nearly exhausted" in screen and "Limit reset" in screen
assert "Start session window" in screen and "Start session" in screen
assert "data class Profile" in models and "data class AppSettings" in models
assert 'SQLiteOpenHelper(context, "usage_history.db", null, 2)' in db
profile_store = (root / "app/src/main/java/com/qiuji/codemeter/data/ProfileStore.kt").read_text()
assert "legacy_claude" in profile_store
assert "fun reorder(profileIds: List<String>)" in profile_store
assert 'rootProject.name = "CodeMeter"' in (root / "settings.gradle.kts").read_text()
assert '<string name="app_name">CodeMeter</string>' in (root / "app/src/main/res/values/strings.xml").read_text()

source = "\n".join(
    p.read_text(errors="ignore")
    for p in (root / "app/src/main/java").rglob("*.kt")
)
assert "Log.d(" not in source and "Log.v(" not in source and "println(" not in source
assert "sk-ant-" not in source and "sk-proj-" not in source
assert not re.search(r'Bearer\s+[A-Za-z0-9_-]{20,}', source)

http = (root / "app/src/main/java/com/qiuji/codemeter/network/Http.kt").read_text()
repo = (root / "app/src/main/java/com/qiuji/codemeter/data/UsageRepository.kt").read_text()
app = (root / "app/src/main/java/com/qiuji/codemeter/CodeMeterApplication.kt").read_text()
assert "data class HttpResponseData" in http and "retryAfterEpochMs" in http
assert '"User-Agent" to "claude-code/2.1.69"' in claude
assert '"Content-Type" to "application/json"' in claude
assert 'item.optString("kind")' in claude and 'optString("display_name")' in claude
assert "weekly_all" in claude and "weekly_scoped" in claude and "namedJsonObjects" in claude
assert "dedupeWindows" in claude and "equivalentQuota" in claude
assert '"x-codex-primary-used-percent"' in codex and '"limit_window_seconds"' in codex
assert "RESET_CREDITS_URL" in codex and "rate-limit-reset-credits" in codex
assert "providerCooldownUntil" in repo and "staleUsage" in repo
assert "ProcessLifecycleOwner" in app and "override fun onStart" in app
assert "formatFreshness" in screen and "Rate limited" in repo
claude_session = (root / "app/src/main/java/com/qiuji/codemeter/provider/claude/ClaudeSessionClient.kt").read_text()
codex_session = (root / "app/src/main/java/com/qiuji/codemeter/provider/codex/CodexSessionClient.kt").read_text()
assert "https://api.anthropic.com/v1/messages" in claude_session and '"max_tokens", 1' in claude_session
assert "https://chatgpt.com/backend-api/codex/responses" in codex_session and 'put("store", false)' in codex_session
assert "startSessionWindow" in repo and "SESSION_START_CONFIRM_DELAY_MS" in repo
gradle = (root / "app/build.gradle.kts").read_text()
assert 'versionName = "0.4.2"' in gradle and "versionCode = 16" in gradle
workflow = (root / ".github/workflows/android.yml").read_text()
assert "CODEMETER_KEYSTORE_BASE64" in workflow and "apksigner" in workflow
assert "Release tags require CodeMeter signing secrets" in workflow

print("Project structure, migration, XML, and security checks passed.")
