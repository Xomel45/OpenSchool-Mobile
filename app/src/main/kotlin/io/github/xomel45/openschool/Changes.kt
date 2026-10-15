package io.github.xomel45.openschool

import org.json.JSONArray
import org.json.JSONObject
import uniffi.openschool_bridge.Week

// «Замены»: the same rules as the desktop app (src-tauri/src/changes.rs). The background check keeps the lessons of today and the
// next 7 days; the next check tells what was replaced, cancelled, added, moved or got another room. Days not published before and
// days whose lessons all vanished say nothing (an empty answer of the server looks the same).

data class LessonSnap(val id: String, val subject: String, val start: String, val room: String)

/** Date (`YYYY-MM-DD`) -> its lessons by start time. */
typealias Snapshot = Map<String, List<LessonSnap>>

fun snapshotOf(weeks: List<Week>, from: String, to: String): Snapshot =
    weeks.flatMap { it.lessons }.filter { it.start.take(10) in from..to }.distinctBy { it.id }
        .groupBy { it.start.take(10) }
        .mapValues { (_, ls) -> ls.map { LessonSnap(it.id, it.subjectName, it.start.drop(11).take(5), it.room) }.sortedBy { it.start } }
        .toSortedMap()

fun snapshotToJson(s: Snapshot): String = JSONObject().also { o ->
    s.forEach { (d, ls) -> o.put(d, JSONArray().also { a -> ls.forEach { a.put(JSONObject().put("id", it.id).put("subject", it.subject).put("start", it.start).put("room", it.room)) } }) }
}.toString()

fun snapshotFromJson(json: String?): Snapshot = runCatching {
    val o = JSONObject(json ?: "{}")
    o.keys().asSequence().associateWith { d -> o.getJSONArray(d).let { a -> (0 until a.length()).map { a.getJSONObject(it) }.map { LessonSnap(it.getString("id"), it.getString("subject"), it.getString("start"), it.getString("room")) } } }.toSortedMap()
}.getOrDefault(emptyMap())

private fun hmOf(s: String) = s.removePrefix("0")
private fun roomOf(r: String) = if (r.isBlank()) "без кабинета" else "каб. $r"

fun scheduleChanges(old: Snapshot, new: Snapshot, today: String): List<NewsEvent> {
    val out = mutableListOf<NewsEvent>()
    fun ev(key: String, subject: String, detail: String, date: String) { out += NewsEvent(key, Kind.Change, subject, null, detail, date) }
    for ((date, now) in new.toSortedMap()) {
        if (date < today) continue
        val before = old[date] ?: continue
        val whenText = formatDate(date)
        val gone = before.filter { b -> now.none { it.id == b.id } }.toMutableList()
        val came = now.filter { n -> before.none { it.id == n.id } }.toMutableList()
        val it = gone.iterator()
        while (it.hasNext()) {
            val g = it.next()
            val c = came.firstOrNull { c -> c.start == g.start } ?: continue
            it.remove(); came.remove(c)
            if (g.subject != c.subject) ev("c:$date:${c.start}:sub:${g.subject}>${c.subject}", c.subject, "Замена: ${g.subject} → ${c.subject}, $whenText, ${hmOf(c.start)}", date)
            else if (g.room != c.room) ev("c:$date:${c.start}:room:${c.room}", c.subject, "Кабинет: ${roomOf(g.room)} → ${roomOf(c.room)}, $whenText, ${hmOf(c.start)}", date)
        }
        gone.forEach { g -> ev("c:$date:${g.start}:off:${g.subject}", g.subject, "Урок отменён: $whenText, ${hmOf(g.start)}", date) }
        came.forEach { c -> ev("c:$date:${c.start}:new:${c.subject}", c.subject, "Новый урок: $whenText, ${hmOf(c.start)}", date) }
        for (n in now) {
            val b = before.firstOrNull { it.id == n.id } ?: continue
            if (b.start != n.start) ev("c:$date:${n.start}:move:${n.id}", n.subject, "Перенос: ${hmOf(b.start)} → ${hmOf(n.start)}, $whenText", date)
            else if (b.room != n.room) ev("c:$date:${n.start}:room:${n.room}", n.subject, "Кабинет: ${roomOf(b.room)} → ${roomOf(n.room)}, $whenText, ${hmOf(n.start)}", date)
        }
    }
    return out
}
