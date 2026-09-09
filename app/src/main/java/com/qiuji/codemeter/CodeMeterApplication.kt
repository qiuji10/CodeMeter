package com.qiuji.codemeter

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.qiuji.codemeter.worker.UsageRefreshWorker
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class CodeMeterApplication : Application(), DefaultLifecycleObserver {
    lateinit var graph: AppGraph
        private set

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(applicationContext)
        graph.notifier.createChannel()
        observeBackgroundRefreshSettings()
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
    }

    /**
     * Process lifecycle START means the whole app entered the foreground. This fires once on a cold
     * launch and once on each warm return from background, but not for Activity recreation/rotation.
     * It intentionally ignores the periodic Auto refresh toggle: opening CodeMeter should always ask
     * for fresh quota data. Provider Retry-After cooldowns are still authoritative in the repository.
     */
    override fun onStart(owner: LifecycleOwner) {
        applicationScope.launch { graph.repository.refreshAll() }
    }

    private fun observeBackgroundRefreshSettings() {
        applicationScope.launch {
            graph.settingsStore.settings
                .map { it.backgroundFetchEnabled to it.autoRefreshIntervalMinutes }
                .distinctUntilChanged()
                .collect { (enabled, intervalMinutes) ->
                    configureBackgroundRefresh(enabled, intervalMinutes)
                }
        }
    }

    private fun configureBackgroundRefresh(enabled: Boolean, intervalMinutes: Int) {
        val workManager = WorkManager.getInstance(this)
        if (!enabled) {
            workManager.cancelUniqueWork(USAGE_REFRESH_WORK)
            return
        }

        // Android WorkManager requires periodic work to use at least a 15-minute interval.
        val backgroundIntervalMinutes = intervalMinutes.coerceAtLeast(15).toLong()
        val request = PeriodicWorkRequestBuilder<UsageRefreshWorker>(backgroundIntervalMinutes, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .build()
        workManager.enqueueUniquePeriodicWork(
            USAGE_REFRESH_WORK,
            ExistingPeriodicWorkPolicy.UPDATE,
            request,
        )
    }

    private companion object {
        const val USAGE_REFRESH_WORK = "usage_refresh"
    }
}
