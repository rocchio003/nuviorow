package com.nuviorows

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class RefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        return try {
            RefreshEngine.refresh(applicationContext)
            try {
                if (Store(applicationContext).m3uUrl.isNotEmpty()) GuideData.refresh(applicationContext)
            } catch (e: Exception) {
                // la guida si riprova alla prossima esecuzione
            }
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}

object Scheduler {
    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(6, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork("nuviorows-refresh", ExistingPeriodicWorkPolicy.KEEP, req)
    }
}
