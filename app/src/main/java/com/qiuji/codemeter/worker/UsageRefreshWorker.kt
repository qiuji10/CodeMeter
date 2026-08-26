package com.qiuji.codemeter.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.qiuji.codemeter.CodeMeterApplication
import com.qiuji.codemeter.network.HttpStatusException
import java.io.IOException

class UsageRefreshWorker(
    appContext: Context,
    workerParams: WorkerParameters,
) : CoroutineWorker(appContext, workerParams) {
    override suspend fun doWork(): Result {
        val app = applicationContext as CodeMeterApplication
        if (!app.graph.settingsStore.settings.value.backgroundFetchEnabled) return Result.success()
        return try {
            val results = app.graph.repository.refreshAll()
            val failures = results.values.mapNotNull { it.exceptionOrNull() }
            when {
                failures.isEmpty() -> Result.success()
                failures.any { it is IOException && (it !is HttpStatusException || it.statusCode >= 500) } -> Result.retry()
                failures.any { it is HttpStatusException && it.statusCode == 429 } -> Result.retry()
                else -> Result.success()
            }
        } catch (_: IOException) {
            Result.retry()
        } catch (_: Throwable) {
            Result.success()
        }
    }
}
