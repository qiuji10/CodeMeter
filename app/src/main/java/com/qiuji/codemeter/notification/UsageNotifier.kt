package com.qiuji.codemeter.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.qiuji.codemeter.R
import com.qiuji.codemeter.data.SettingsStore
import com.qiuji.codemeter.model.ProviderUsage
import com.qiuji.codemeter.worker.ResetNotificationWorker
import java.util.concurrent.TimeUnit
import kotlin.math.roundToInt

class UsageNotifier(
    private val context: Context,
    private val settingsStore: SettingsStore,
) {
    companion object {
        const val CHANNEL_ID = "quota_alerts"
        const val STATE_PREFS = "notification_state"
        const val RESET_PROFILE_TAG_PREFIX = "quota_reset_profile_"

        fun resetNotifiedKey(profileId: String, windowKey: String): String =
            "reset_notified_${profileId}_${windowKey}"
    }

    private val prefs = context.getSharedPreferences(STATE_PREFS, Context.MODE_PRIVATE)

    fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Quota alerts",
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = "Alerts when usage is nearly exhausted or a quota window resets."
        }
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun maybeNotify(usage: ProviderUsage) {
        val settings = settingsStore.settings.value
        val notificationsAllowed = hasNotificationPermission()
        val now = System.currentTimeMillis()

        usage.windows.forEach { window ->
            val stateKey = "${usage.profileId}_${window.key}"
            val percent = window.usedPercent.roundToInt().coerceIn(0, 100)

            if (settings.resetNotificationsEnabled) {
                window.resetsAtEpochMs?.let { resetAt ->
                    if (resetAt > now) scheduleResetNotification(usage, window.key, window.label, resetAt, now)
                }
            }

            val previousPercentKey = "${stateKey}_previous_percent"
            val previousResetKey = "${stateKey}_previous_reset"
            val fallbackResetBlockedKey = "${stateKey}_fallback_reset_blocked"
            val hadPreviousPercent = prefs.contains(previousPercentKey)
            val previousPercent = prefs.getInt(previousPercentKey, percent)
            val previousResetAt = prefs.getLong(previousResetKey, Long.MIN_VALUE)

            // Fallback only for windows without a provider reset timestamp. Keep it conservative
            // so ordinary percentage fluctuations do not look like a quota reset.
            if (window.resetsAtEpochMs == null) {
                var fallbackBlocked = prefs.getBoolean(fallbackResetBlockedKey, false)
                if (percent >= 50) fallbackBlocked = false
                if (settings.resetNotificationsEnabled && notificationsAllowed && !fallbackBlocked &&
                    hadPreviousPercent && previousPercent >= 50 && percent <= 20 && previousPercent - percent >= 20
                ) {
                    postResetNotification(usage.profileId, usage.profileName, window.key, window.label, now)
                    fallbackBlocked = true
                }
                prefs.edit().putBoolean(fallbackResetBlockedKey, fallbackBlocked).apply()
            }

            if (settings.nearExhaustedNotificationsEnabled && notificationsAllowed) {
                maybeNotifyNearlyExhausted(
                    profileId = usage.profileId,
                    profileName = usage.profileName,
                    windowKey = window.key,
                    windowLabel = window.label,
                    percent = percent,
                    resetAtEpochMs = window.resetsAtEpochMs,
                    threshold = settings.nearExhaustedThreshold,
                )
            }

            prefs.edit()
                .putInt(previousPercentKey, percent)
                .apply {
                    if (window.resetsAtEpochMs != null) putLong(previousResetKey, window.resetsAtEpochMs)
                    else if (previousResetAt != Long.MIN_VALUE) remove(previousResetKey)
                }
                .apply()
        }
    }

    private fun maybeNotifyNearlyExhausted(
        profileId: String,
        profileName: String,
        windowKey: String,
        windowLabel: String,
        percent: Int,
        resetAtEpochMs: Long?,
        threshold: Int,
    ) {
        val stateKey = "${profileId}_${windowKey}"
        val nearWindowKey = "${stateKey}_near_window"
        val nearFallbackKey = "${stateKey}_near_fallback"

        if (percent < threshold) {
            if (resetAtEpochMs == null && percent <= (threshold - 10).coerceAtLeast(0)) {
                prefs.edit().putBoolean(nearFallbackKey, false).apply()
            }
            return
        }

        val alreadySent = if (resetAtEpochMs != null) {
            prefs.getLong(nearWindowKey, Long.MIN_VALUE) == resetAtEpochMs
        } else {
            prefs.getBoolean(nearFallbackKey, false)
        }
        if (alreadySent) return

        val left = (100 - percent).coerceAtLeast(0)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$profileName $windowLabel nearly exhausted")
            .setContentText("$percent% used · $left% left")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(
            (profileId + windowKey + "near").hashCode(),
            notification,
        )
        prefs.edit().apply {
            if (resetAtEpochMs != null) putLong(nearWindowKey, resetAtEpochMs)
            else putBoolean(nearFallbackKey, true)
        }.apply()
    }

    private fun scheduleResetNotification(
        usage: ProviderUsage,
        windowKey: String,
        windowLabel: String,
        resetAtEpochMs: Long,
        nowEpochMs: Long,
    ) {
        if (prefs.getLong(resetNotifiedKey(usage.profileId, windowKey), Long.MIN_VALUE) == resetAtEpochMs) return

        val delayMs = (resetAtEpochMs - nowEpochMs).coerceAtLeast(1_000L)
        val data = Data.Builder()
            .putString(ResetNotificationWorker.KEY_PROFILE_ID, usage.profileId)
            .putString(ResetNotificationWorker.KEY_PROFILE_NAME, usage.profileName)
            .putString(ResetNotificationWorker.KEY_WINDOW_KEY, windowKey)
            .putString(ResetNotificationWorker.KEY_WINDOW_LABEL, windowLabel)
            .putLong(ResetNotificationWorker.KEY_RESET_AT, resetAtEpochMs)
            .build()
        val request = OneTimeWorkRequestBuilder<ResetNotificationWorker>()
            .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
            .setInputData(data)
            .addTag("$RESET_PROFILE_TAG_PREFIX${usage.profileId}")
            .build()

        WorkManager.getInstance(context).enqueueUniqueWork(
            resetWorkName(usage.profileId, windowKey),
            ExistingWorkPolicy.REPLACE,
            request,
        )
    }

    private fun postResetNotification(
        profileId: String,
        profileName: String,
        windowKey: String,
        windowLabel: String,
        resetMarker: Long,
    ) {
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$profileName $windowLabel reset")
            .setContentText("Quota is available again.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(context).notify(
            (profileId + windowKey + "reset" + resetMarker).hashCode(),
            notification,
        )
    }

    fun clearProfile(profileId: String) {
        val editor = prefs.edit()
        prefs.all.keys.filter { it.startsWith("${profileId}_") || it.startsWith("reset_notified_${profileId}_") }
            .forEach(editor::remove)
        editor.apply()
        WorkManager.getInstance(context).cancelAllWorkByTag("$RESET_PROFILE_TAG_PREFIX$profileId")
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    private fun resetWorkName(profileId: String, windowKey: String): String =
        "quota_reset_${profileId}_${windowKey}"
}
