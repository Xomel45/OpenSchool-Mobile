package io.github.xomel45.openschool

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

val NEWS_INTERVALS = listOf(15, 30, 60) // minutes; Android does not run periodic work more often than every 15

/** One entry of the "what's new" feed. */
data class FeedItem(val id: String, val kind: Kind, val title: String, val text: String, val value: String?, val date: String, val ts: Long, val read: Boolean)

const val FEED_LIMIT = 50

data class NewsSettings(
    val marks: Boolean = true,
    val homework: Boolean = true,
    val absences: Boolean = false,
    val intervalMin: Int = 15,
    /** Show the subject and the mark in the notification, not just "new mark". */
    val detail: Boolean = true,
    /** "Do not disturb at night": no system notifications between [quietFrom] and [quietTo] (minutes since midnight). The check goes on. */
    val quiet: Boolean = false,
    val quietFrom: Int = 22 * 60,
    val quietTo: Int = 7 * 60,
) {
    fun wants(kind: Kind) = when (kind) { Kind.Mark -> marks; Kind.Homework -> homework; Kind.Absence -> absences }

    /** Is [minute] (minutes since local midnight) inside the quiet hours? The start is quiet, the end is not; a span may run over
     *  midnight (22:00-07:00); equal times mean "no quiet hours". */
    fun isQuiet(minute: Int): Boolean {
        if (!quiet || quietFrom == quietTo) return false
        return if (quietFrom < quietTo) minute in quietFrom until quietTo else minute >= quietFrom || minute < quietTo
    }
}

/** `HH:MM` <-> minutes since midnight (the settings file writes the quiet hours this way). */
fun minutesToHm(m: Int): String = "%02d:%02d".format(m / 60, m % 60)
fun parseHm(s: String?): Int? = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$").matchEntire(s ?: "")?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }

/** Settings, the ids already seen and the "expired" flag of the background check. The seen ids belong to one account and go on logout. */
class NewsStore(context: Context) {
    private val prefs = context.getSharedPreferences("news", Context.MODE_PRIVATE)

    var settings by mutableStateOf(
        NewsSettings(
            marks = prefs.getBoolean("marks", true),
            homework = prefs.getBoolean("homework", true),
            absences = prefs.getBoolean("absences", false),
            intervalMin = prefs.getInt("interval", 15).takeIf { it in NEWS_INTERVALS } ?: 15,
            detail = prefs.getBoolean("detail", true),
            quiet = prefs.getBoolean("quiet", false),
            quietFrom = prefs.getInt("quietFrom", 22 * 60).takeIf { it in 0..1439 } ?: (22 * 60),
            quietTo = prefs.getInt("quietTo", 7 * 60).takeIf { it in 0..1439 } ?: (7 * 60),
        )
    )
        private set

    fun update(s: NewsSettings) {
        settings = s
        prefs.edit().putBoolean("marks", s.marks).putBoolean("homework", s.homework).putBoolean("absences", s.absences)
            .putInt("interval", s.intervalMin).putBoolean("detail", s.detail)
            .putBoolean("quiet", s.quiet).putInt("quietFrom", s.quietFrom).putInt("quietTo", s.quietTo).apply()
    }

    /** The feed, newest first; call [reload] after something else (the background check) may have changed it. */
    var feed by mutableStateOf(readFeed())
        private set

    val unread: Int get() = feed.count { !it.read }

    fun reload() { feed = readFeed() }

    private fun readFeed(): List<FeedItem> = runCatching {
        val a = org.json.JSONArray(prefs.getString("feed", "[]"))
        (0 until a.length()).map { a.getJSONObject(it) }.map { o ->
            FeedItem(o.getString("id"), Kind.valueOf(o.getString("kind")), o.getString("title"), o.getString("text"), o.optString("value").ifEmpty { null }, o.getString("date"), o.getLong("ts"), o.getBoolean("read"))
        }
    }.getOrDefault(emptyList())

    private fun writeFeed(items: List<FeedItem>) {
        val a = org.json.JSONArray()
        items.forEach { i -> a.put(org.json.JSONObject().put("id", i.id).put("kind", i.kind.name).put("title", i.title).put("text", i.text).put("value", i.value ?: "").put("date", i.date).put("ts", i.ts).put("read", i.read)) }
        prefs.edit().putString("feed", a.toString()).apply()
        feed = items
    }

    /** Empty the feed (the seen ids stay, so nothing comes back). */
    @Synchronized
    fun clearFeed() = writeFeed(emptyList())

    /** Mark the given ids read (all of them when empty). */
    @Synchronized
    fun markRead(ids: Set<String> = emptySet()) = writeFeed(readFeed().map { if (ids.isEmpty() || it.id in ids) it.copy(read = true) else it })

    /** True once the session was seen dead: checks pause until the next login. */
    var expired: Boolean
        get() = prefs.getBoolean("expired", false)
        set(v) { prefs.edit().putBoolean("expired", v).apply() }

    /**
     * Take in what a check found for `account`. Returns the events that are new and wanted; the very first run (and the first
     * run for another account) only records what is there, so nobody gets forty notifications at once. Everything found counts
     * as seen, even kinds that are switched off: turning one on later must not flood.
     */
    @Synchronized
    fun absorb(account: String, found: List<NewsEvent>): List<NewsEvent> {
        val first = prefs.getString("account", null) != account || !prefs.getBoolean("initialized", false)
        val seen = if (first) HashSet() else HashSet(prefs.getStringSet("seen", emptySet()) ?: emptySet())
        val batch = HashSet<String>()
        val new = found.filter { it.key !in seen && batch.add(it.key) }
        seen += new.map { it.key }
        prefs.edit().putString("account", account).putBoolean("initialized", true).putStringSet("seen", seen).apply()
        val wanted = if (first) emptyList() else new.filter { settings.wants(it.kind) }
        if (wanted.isNotEmpty()) {
            val now = System.currentTimeMillis() / 1000
            val added = wanted.map { FeedItem(it.key, it.kind, it.subject, it.detail, it.value, it.date, now, false) }
            writeFeed((added + readFeed()).take(FEED_LIMIT))
        }
        return wanted
    }

    /** Logout: the seen ids and the flag belong to the account. */
    @Synchronized
    fun reset() {
        prefs.edit().remove("account").remove("initialized").remove("seen").remove("expired").remove("feed").apply()
        feed = emptyList()
    }
}
