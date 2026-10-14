package io.github.xomel45.openschool

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.openschool_bridge.BridgeException
import uniffi.openschool_bridge.Client
import java.time.Duration
import java.time.LocalDateTime
import java.time.temporal.IsoFields
import java.util.concurrent.TimeUnit

/**
 * «Вечерняя сводка»: once a day at the chosen time, one notification about the next school day (lessons, open homework, tests,
 * my notes; see [eveningText]). Each run plans the next one for tomorrow. A run that gets the network only late at night is dropped.
 */
class EveningWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val s = NewsStore(context).settings
        try {
            run(s)
        } finally {
            schedule(context, s) // tomorrow
        }
        Result.success()
    }

    private suspend fun run(s: NewsSettings) {
        if (!s.evening && !s.testReminders) return
        val now = LocalDateTime.now()
        val today = now.toLocalDate()
        val prefs = context.getSharedPreferences("evening", Context.MODE_PRIVATE)
        if (prefs.getString("sent", null) == today.toString()) return
        if (now.hour * 60 + now.minute < s.eveningAt || now.hour >= 23) return // too early (a stray run) or too late
        val header = SessionStore(context).load() ?: return
        if (NewsStore(context).expired) return
        val client = Client()
        client.setSessionFromHeader(header)
        val weeks = try {
            val all = client.students()
            val chosen = context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("studentId", null)
            val student = all.firstOrNull { it.id == chosen } ?: all.firstOrNull() ?: return
            listOf(0L, 1L).map { shift ->
                val d = today.plusWeeks(shift)
                client.week(student.id, d.get(IsoFields.WEEK_BASED_YEAR).toUInt(), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toUInt())
            }
        } catch (e: BridgeException) { return }
        prefs.edit().putString("sent", today.toString()).apply() // one try per day, whatever comes below
        val day = nextSchoolDay(weeks, today) ?: return
        val done = context.getSharedPreferences("homework-done", Context.MODE_PRIVATE).all.keys
        val (title, body) = eveningText(plan(weeks, day, done, NotesStore.load(context)), today, s.evening, s.testReminders) ?: return
        notifyPlain(context, title, body)
    }

    companion object {
        private const val UNIQUE = "evening-summary"

        /** The delay until the next evening time (today if it is still ahead, else tomorrow). */
        fun delayUntil(now: LocalDateTime, minute: Int): Duration {
            var at = now.toLocalDate().atStartOfDay().plusMinutes(minute.toLong())
            if (!at.isAfter(now)) at = at.plusDays(1)
            return Duration.between(now, at)
        }

        fun schedule(context: Context, s: NewsSettings) {
            val wm = WorkManager.getInstance(context)
            if (!s.evening && !s.testReminders) { wm.cancelUniqueWork(UNIQUE); return }
            val request = OneTimeWorkRequestBuilder<EveningWorker>()
                .setInitialDelay(delayUntil(LocalDateTime.now(), s.eveningAt).toMillis(), TimeUnit.MILLISECONDS)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniqueWork(UNIQUE, ExistingWorkPolicy.REPLACE, request)
        }

        /** Logout: nothing more until the next login; the "sent today" mark goes too. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)
            context.getSharedPreferences("evening", Context.MODE_PRIVATE).edit().clear().apply()
        }

    }
}
