package io.github.xomel45.openschool

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

private const val NOW_CHANNEL = "now"
private const val NOW_ID = 4242

/**
 * «Текущий урок в шторке» (off by default): a silent notification with a live countdown to the next bell, from the weeks stored on
 * the phone (no network). Each run shows the state and plans the next run at the next bell; before the first lesson it appears an
 * hour ahead; after the last one it goes away until tomorrow morning.
 */
class NowWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = NewsStore(context).settings
        if (!s.nowInShade || SessionStore(context).load() == null) { hide(context); return Result.success() }
        val now = LocalDateTime.now()
        val chosen = context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("studentId", null)
        val lessons = WeekCache(context).allFor(chosen).flatMap { it.first.lessons }.filter { it.start.take(10) == now.toLocalDate().toString() }.distinctBy { it.id }
        val st = nowState(lessons, now)
        val next: LocalDateTime = when {
            st == null -> now.toLocalDate().plusDays(1).atTime(6, 0) // after the last lesson (or no lessons): tomorrow morning
            st.title == "Скоро первый урок" && Duration.between(now, st.until).toMinutes() > 60 -> { hide(context); st.until!!.minusMinutes(60) }
            else -> { show(context, st); st.until!!.plusSeconds(5) }
        }
        if (st == null) hide(context)
        plan(context, Duration.between(now, next))
        return Result.success()
    }

    companion object {
        private const val UNIQUE = "now-in-shade"

        private fun plan(context: Context, delay: Duration) {
            val request = OneTimeWorkRequestBuilder<NowWorker>().setInitialDelay(delay.toMillis().coerceAtLeast(0), TimeUnit.MILLISECONDS).build()
            WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request)
        }

        /** Start (or restart) when it is switched on; stop and hide when off. */
        fun schedule(context: Context, s: NewsSettings) {
            if (s.nowInShade) plan(context, Duration.ZERO) else cancel(context)
        }

        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
            hide(context)
        }

        private fun hide(context: Context) = NotificationManagerCompat.from(context).cancel(NOW_ID)

        private fun show(context: Context, st: NowState) {
            if (androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
            val nm = context.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(NOW_CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(NOW_CHANNEL, "Текущий урок", NotificationManager.IMPORTANCE_LOW).apply { setShowBadge(false) })
            // The minutes are counted by the system clock in the notification: the text keeps only what does not change.
            val text = st.text.substringBefore(" · до конца").substringBefore(" через ")
            val open = PendingIntent.getActivity(context, 1, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(context, NOW_CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_recent_history)
                .setContentTitle(st.title).setContentText(text)
                .setWhen(st.until!!.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
                .setUsesChronometer(true).setChronometerCountDown(true).setShowWhen(true)
                .setOngoing(true).setSilent(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_STATUS)
                .setContentIntent(open).build()
            NotificationManagerCompat.from(context).notify(NOW_ID, n)
        }
    }
}
