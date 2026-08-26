package com.qiuji.codemeter.worker

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.qiuji.codemeter.CodeMeterApplication
import com.qiuji.codemeter.R
import com.qiuji.codemeter.notification.UsageNotifier

class ResetNotificationWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : Worker(appContext, workerParams) {
    companion object {
        const val KEY_PROFILE_ID = "profile_id"
        const val KEY_PROFILE_NAME = "profile_name"
        const val KEY_WINDOW_KEY = "window_key"
        const val KEY_WINDOW_LABEL = "window_label"
        const val KEY_RESET_AT = "reset_at"
    }

    override fun doWork(): Result {
        val app = applicationContext as CodeMeterApplication
        if (!app.graph.settingsStore.settings.value.resetNotificationsEnabled) return Result.success()
        if (!hasNotificationPermission()) return Result.success()

        val profileId = inputData.getString(KEY_PROFILE_ID) ?: return Result.success()
        val profileName = inputData.getString(KEY_PROFILE_NAME) ?: "CodeMeter"
        val windowKey = inputData.getString(KEY_WINDOW_KEY) ?: return Result.success()
        val windowLabel = inputData.getString(KEY_WINDOW_LABEL) ?: "Usage"
        val resetAt = inputData.getLong(KEY_RESET_AT, Long.MIN_VALUE)
        if (resetAt == Long.MIN_VALUE) return Result.success()

        val prefs = applicationContext.getSharedPreferences(UsageNotifier.STATE_PREFS, Context.MODE_PRIVATE)
        val notifiedKey = UsageNotifier.resetNotifiedKey(profileId, windowKey)
        if (prefs.getLong(notifiedKey, Long.MIN_VALUE) == resetAt) return Result.success()

        val notification = NotificationCompat.Builder(applicationContext, UsageNotifier.CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("$profileName $windowLabel reset")
            .setContentText("Quota is available again.")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(
            (profileId + windowKey + "reset" + resetAt).hashCode(),
            notification,
        )
        prefs.edit().putLong(notifiedKey, resetAt).apply()
        return Result.success()
    }

    private fun hasNotificationPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(applicationContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
}
