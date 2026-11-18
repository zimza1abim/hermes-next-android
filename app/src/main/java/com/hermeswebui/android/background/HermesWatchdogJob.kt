package com.hermeswebui.android.background

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.os.PowerManager
import com.hermeswebui.android.webapp.WebappGatewayCoordinator

/**
 * Brings background monitoring back after Android ends the process without a restart and without a
 * broadcast to the app, as an Android System WebView update does. The system runs this job about every
 * 15 minutes, starting the process when it is gone.
 */
class HermesWatchdogJob : JobService() {
    override fun onStartJob(params: JobParameters?): Boolean {
        if (needsRestart(this)) HermesKeepAliveService.start(this)
        return false
    }

    override fun onStopJob(params: JobParameters?): Boolean = false

    companion object {
        private const val JOB_ID = 20_031
        private const val PERIOD_MS = 15 * 60 * 1000L

        /** Idempotent: an already scheduled job is left as it is. */
        fun schedule(context: Context) {
            val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
            if (scheduler.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, HermesWatchdogJob::class.java))
                .setPeriodic(PERIOD_MS)
                .setPersisted(true)
                .build()
            runCatching { scheduler.schedule(job) }
        }

        /**
         * Only a process without a coordinator lost its monitoring; a live one manages the service itself.
         * Android lets the job start a foreground service from the background only for an app with
         * unrestricted battery use, so without it the job does nothing instead of posting a stop warning.
         */
        private fun needsRestart(context: Context): Boolean =
            !WebappGatewayCoordinator.isRunning() &&
                WebappGatewayCoordinator.wantsHeadlessStart(context) &&
                context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
    }
}
