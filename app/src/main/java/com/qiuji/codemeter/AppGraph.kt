package com.qiuji.codemeter

import android.content.Context
import com.qiuji.codemeter.data.ProfileStore
import com.qiuji.codemeter.data.SettingsStore
import com.qiuji.codemeter.data.UsageRepository
import com.qiuji.codemeter.db.UsageHistoryDb
import com.qiuji.codemeter.network.Http
import com.qiuji.codemeter.notification.UsageNotifier
import com.qiuji.codemeter.provider.claude.ClaudeAuth
import com.qiuji.codemeter.provider.claude.ClaudeUsageClient
import com.qiuji.codemeter.provider.codex.CodexAuth
import com.qiuji.codemeter.provider.codex.CodexUsageClient
import com.qiuji.codemeter.security.SecureStore

class AppGraph(context: Context) {
    val secureStore = SecureStore(context)
    val settingsStore = SettingsStore(context)
    val profileStore = ProfileStore(context, secureStore)
    val http = Http()
    val historyDb = UsageHistoryDb(context)
    val notifier = UsageNotifier(context, settingsStore)
    val claudeAuth = ClaudeAuth(http, secureStore)
    val claudeUsage = ClaudeUsageClient(http)
    val codexAuth = CodexAuth(http, secureStore)
    val codexUsage = CodexUsageClient(http)
    val repository = UsageRepository(
        secureStore = secureStore,
        profileStore = profileStore,
        historyDb = historyDb,
        claudeAuth = claudeAuth,
        claudeUsageClient = claudeUsage,
        codexAuth = codexAuth,
        codexUsageClient = codexUsage,
        notifier = notifier,
    )
}
