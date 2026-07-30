package com.sultansgame.modmanager.platform.workshop

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.sultansgame.modmanager.model.DownloadTask
import java.util.concurrent.TimeUnit

class WorkshopDownloadScheduler(context: Context) {
    private val workManager = WorkManager.getInstance(context.applicationContext)

    fun enqueue(task: DownloadTask) {
        val request = OneTimeWorkRequestBuilder<WorkshopDownloadWorker>()
            .setInputData(
                workDataOf(
                    WorkshopDownloadWorker.KEY_TASK_ID to task.id,
                    WorkshopDownloadWorker.KEY_ATTEMPT_GENERATION to task.attemptGeneration,
                ),
            )
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build(),
            )
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        workManager.enqueueUniqueWork(workName(task.id), ExistingWorkPolicy.REPLACE, request)
    }

    fun cancel(taskId: String) {
        workManager.cancelUniqueWork(workName(taskId))
    }

    private fun workName(taskId: String): String = "sultan-workshop-$taskId"
}
