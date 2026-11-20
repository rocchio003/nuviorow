package com.nuviorows

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Process
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

class RefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result {
        try {
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
        } catch (e: Exception) {
            // ignora
        }
        return try {
            RefreshEngine.refresh(applicationContext)
            try {
                // La guida è pesante da scaricare e leggere: la rinnovo al massimo ogni 11 ore.
                val age = System.currentTimeMillis() - GuideData.updatedAt(applicationContext)
                if (Store(applicationContext).m3uUrl.isNotEmpty() && age > 11L * 3600_000L) {
                    GuideData.refresh(applicationContext)
                }
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

    private fun constraints(): Constraints =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    // Aggiornamento periodico ogni 2 ore (Android non permette meno di 15 minuti).
    fun schedule(context: Context) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(2, TimeUnit.HOURS)
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniquePeriodicWork("nuviorows-refresh", ExistingPeriodicWorkPolicy.UPDATE, req)
    }

    // Un aggiornamento subito (all'accensione della TV o dopo un aggiornamento dell'app).
    fun refreshSoon(context: Context) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>()
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(context.applicationContext)
            .enqueueUniqueWork("nuviorows-now", ExistingWorkPolicy.REPLACE, req)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        Scheduler.schedule(context)
        Scheduler.refreshSoon(context)
    }
}
