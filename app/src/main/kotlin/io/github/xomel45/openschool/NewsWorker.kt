package io.github.xomel45.openschool

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import uniffi.openschool_bridge.BridgeException
import uniffi.openschool_bridge.Client
import java.time.LocalDate
import java.time.temporal.IsoFields
import java.util.concurrent.TimeUnit

private const val CHANNEL = "news"

/** Looks at the previous, the current and the next week and tells about what is new (the same rules as the desktop app). */
class NewsWorker(private val context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val news = NewsStore(context)
        val header = SessionStore(context).load() ?: return@withContext done(0, null)
        if (news.expired) return@withContext done(0, "expired")
        val client = Client()
        client.setSessionFromHeader(header)
        try {
            val all = AiLog.timed("background_students") { client.students() }
            val chosen = context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("studentId", null) // the student the user looks at
            val student = all.firstOrNull { it.id == chosen } ?: all.firstOrNull() ?: return@withContext done(0, null)
            val today = LocalDate.now()
            val weeks = (-1L..1L).map { shift ->
                val d = today.plusWeeks(shift)
                AiLog.timed("background_week") { client.week(student.id, d.get(IsoFields.WEEK_BASED_YEAR).toUInt(), d.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR).toUInt()) }
            }
            // The weeks go to the phone's copy and the widgets are redrawn from it (this run may exist only for the widgets).
            val cache = WeekCache(context)
            weeks.forEach { cache.put(student.id, it) }
            WidgetUpdater.refresh(context)
            if (news.settings.nowInShade) NowWorker.schedule(context, news.settings) // fresh lessons: the shade notification follows them
            if (!news.settings.anyNews) return@withContext done(0, null)
            val events = weeks.flatMap { eventsOf(it) }
            val lessons = snapshotOf(weeks, today.toString(), today.plusDays(7).toString())
            val fresh = news.absorb(student.id, events, lessons, today.toString())
            if (fresh.isNotEmpty()) notifyAbout(context, fresh, news.settings.detail)
            done(fresh.size, null)
        } catch (e: BridgeException.Auth) {
            news.expired = true // once per expiry: checks pause until the next login
            notifyPlain(context, "Сессия истекла", "Войдите в OpenSchool снова, чтобы получать уведомления.")
            done(0, "expired")
        } catch (e: BridgeException) {
            done(0, if (e is BridgeException.Network && !e.v1.startsWith("HTTP ")) "offline" else "error")
        }
    }

    private fun done(found: Int, problem: String?): Result = Result.success(Data.Builder().putInt("found", found).also { if (problem != null) it.putString("problem", problem) }.build())

    companion object {
        const val UNIQUE = "news-check"

        /** (Re)start the periodic check with the saved interval, or stop it when nothing is wanted. */
        fun schedule(context: Context, settings: NewsSettings) {
            val wm = WorkManager.getInstance(context)
            // Notifications are off: the check still runs while a widget is on the screen (it keeps the widget's data fresh).
            if (!settings.anyNews && !WidgetUpdater.anyWidget(context)) { wm.cancelUniqueWork(UNIQUE); return }
            val request = PeriodicWorkRequestBuilder<NewsWorker>(settings.intervalMin.toLong(), TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build()
            wm.enqueueUniquePeriodicWork(UNIQUE, ExistingPeriodicWorkPolicy.UPDATE, request)
        }

        fun cancel(context: Context) = WorkManager.getInstance(context).cancelUniqueWork(UNIQUE)

        /** One check now; returns the id to watch for its result. */
        fun checkNow(context: Context): java.util.UUID {
            val request = OneTimeWorkRequestBuilder<NewsWorker>().build()
            WorkManager.getInstance(context).enqueue(request)
            return request.id
        }
    }
}

private fun channel(context: Context) {
    val nm = context.getSystemService(NotificationManager::class.java)
    if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(NotificationChannel(CHANNEL, "Что нового", NotificationManager.IMPORTANCE_DEFAULT))
}

private fun canNotify(context: Context) =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

private fun notifyAbout(context: Context, items: List<NewsEvent>, detail: Boolean) {
    val (title, body) = notificationText(items, detail)
    notifyPlain(context, title, body)
}

internal fun notifyPlain(context: Context, title: String, body: String) {
    if (!canNotify(context)) return
    // Quiet hours: nothing pops up (the items are in the feed all the same; the expiry is told when the app is opened).
    if (java.time.LocalTime.now().let { NewsStore(context).settings.isQuiet(it.hour * 60 + it.minute) }) return
    channel(context)
    val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
    val n = NotificationCompat.Builder(context, CHANNEL)
        .setSmallIcon(android.R.drawable.ic_dialog_info)
        .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
        .setContentIntent(open).setAutoCancel(true).build()
    NotificationManagerCompat.from(context).notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), n)
}
