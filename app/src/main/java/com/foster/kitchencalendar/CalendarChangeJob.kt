package com.foster.kitchencalendar

import android.app.job.JobInfo
import android.app.job.JobParameters
import android.app.job.JobScheduler
import android.app.job.JobService
import android.content.ComponentName
import android.content.Context
import android.provider.CalendarContract

/**
 * Wakes up whenever anything in the device calendar changes (an event is added on
 * someone's phone and syncs down, etc.) and redraws the widget a few seconds later.
 * Content-trigger jobs fire once, so it re-arms itself after each run.
 */
class CalendarChangeJob : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val app = applicationContext
        WidgetUpdater.request(app) {
            jobFinished(params, false)
            schedule(app)
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        schedule(applicationContext)
        return false
    }

    companion object {
        private const val JOB_ID = 4242

        fun schedule(context: Context) {
            val js = context.getSystemService(JobScheduler::class.java) ?: return
            if (js.getPendingJob(JOB_ID) != null) return
            val job = JobInfo.Builder(JOB_ID, ComponentName(context, CalendarChangeJob::class.java))
                .addTriggerContentUri(
                    JobInfo.TriggerContentUri(
                        CalendarContract.CONTENT_URI,
                        JobInfo.TriggerContentUri.FLAG_NOTIFY_FOR_DESCENDANTS,
                    )
                )
                .setTriggerContentUpdateDelay(3_000)
                .setTriggerContentMaxDelay(20_000)
                .build()
            js.schedule(job)
        }

        fun cancel(context: Context) {
            context.getSystemService(JobScheduler::class.java)?.cancel(JOB_ID)
        }
    }
}
