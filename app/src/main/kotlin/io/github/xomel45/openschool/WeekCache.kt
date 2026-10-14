package io.github.xomel45.openschool

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import uniffi.openschool_bridge.Absence
import uniffi.openschool_bridge.Homework
import uniffi.openschool_bridge.Lesson
import uniffi.openschool_bridge.Mark
import uniffi.openschool_bridge.Material
import uniffi.openschool_bridge.Teacher
import uniffi.openschool_bridge.Week
import java.io.File

private fun JSONObject.str(k: String): String? = if (has(k) && !isNull(k)) getString(k) else null
private fun JSONObject.opt(k: String, v: String?): JSONObject = if (v != null) put(k, v) else this

fun weekToJson(w: Week): JSONObject = JSONObject().put("year", w.year.toLong()).put("isoWeek", w.isoWeek.toLong())
    .put("lessons", JSONArray().also { a -> w.lessons.forEach { l ->
        a.put(JSONObject().put("id", l.id).put("subjectId", l.subjectId).put("subjectName", l.subjectName)
            .put("teacher", JSONObject().put("firstName", l.teacher.firstName).put("lastName", l.teacher.lastName).put("patronymic", l.teacher.patronymic))
            .put("start", l.start).put("end", l.end).put("room", l.room).opt("theme", l.theme)
            .also { o -> l.absence?.let { o.put("absence", JSONObject().put("code", it.code).put("description", it.description)) } })
    } })
    .put("homeworks", JSONArray().also { a -> w.homeworks.forEach { h ->
        a.put(JSONObject().put("id", h.id).opt("subjectId", h.subjectId).put("subjectName", h.subjectName).put("description", h.description)
            .put("issueDate", h.issueDate).put("dueDate", h.dueDate).opt("dueLessonId", h.dueLessonId)
            .put("materials", JSONArray().also { m -> h.materials.forEach { m.put(JSONObject().put("name", it.name).put("link", it.link)) } }))
    } })
    .put("marks", JSONArray().also { a -> w.marks.forEach { m ->
        a.put(JSONObject().put("id", m.id).put("subjectId", m.subjectId).put("subjectName", m.subjectName).put("value", m.value).opt("value2", m.value2)
            .put("date", m.date).put("workTypeCode", m.workTypeCode).put("workType", m.workType).opt("workName", m.workName).opt("comment", m.comment).opt("lessonId", m.lessonId))
    } })

/** The inverse of [weekToJson]; throws on a damaged file (the caller treats that as "nothing cached"). */
fun weekFromJson(o: JSONObject): Week {
    fun <T> list(name: String, f: (JSONObject) -> T): List<T> = o.getJSONArray(name).let { a -> (0 until a.length()).map { f(a.getJSONObject(it)) } }
    return Week(
        o.getLong("year").toUInt(), o.getLong("isoWeek").toUInt(),
        list("lessons") { l ->
            val t = l.getJSONObject("teacher")
            Lesson(
                l.getString("id"), l.getString("subjectId"), l.getString("subjectName"), Teacher(t.getString("firstName"), t.getString("lastName"), t.getString("patronymic")),
                l.getString("start"), l.getString("end"), l.getString("room"), l.str("theme"),
                l.optJSONObject("absence")?.let { Absence(it.getString("code"), it.getString("description")) },
            )
        },
        list("homeworks") { h ->
            Homework(
                h.getString("id"), h.str("subjectId"), h.getString("subjectName"), h.getString("description"), h.getString("issueDate"), h.getString("dueDate"), h.str("dueLessonId"),
                h.getJSONArray("materials").let { a -> (0 until a.length()).map { a.getJSONObject(it).let { m -> Material(m.getString("name"), m.getString("link")) } } },
            )
        },
        list("marks") { m ->
            Mark(
                m.getString("id"), m.getString("subjectId"), m.getString("subjectName"), m.getString("value"), m.str("value2"), m.getString("date"),
                m.getString("workTypeCode"), m.getString("workType"), m.str("workName"), m.str("comment"), m.str("lessonId"),
            )
        },
    )
}

/**
 * The last weeks that were loaded, kept on the phone so the app still shows something without a connection. One file per week and
 * student; the file's modification time is when the data was fetched. Removed on logout.
 */
class WeekCache(context: Context, private val limit: Int = 60) {
    private val root = File(context.filesDir, "week-cache")

    private fun dirName(studentId: String) = studentId.filter { it.isLetterOrDigit() || it == '-' || it == '_' }.ifEmpty { "x" }

    private fun file(studentId: String, year: UInt, week: UInt) = File(File(root, dirName(studentId)), "$year-$week.json")

    /** Every stored week of the student (the one with the newest data when [studentId] is unknown) and when each was fetched; for the widgets. */
    fun allFor(studentId: String?): List<Pair<Week, Long>> = runCatching {
        val dir = studentId?.let { File(root, dirName(it)) }?.takeIf { it.isDirectory }
            ?: root.listFiles()?.filter { it.isDirectory }?.maxByOrNull { d -> d.listFiles()?.maxOfOrNull { it.lastModified() } ?: 0L }
        dir?.listFiles()?.filter { it.name.endsWith(".json") }?.mapNotNull { f -> runCatching { weekFromJson(JSONObject(f.readText())) to f.lastModified() }.getOrNull() } ?: emptyList()
    }.getOrDefault(emptyList())

    fun put(studentId: String, w: Week) {
        runCatching {
            val f = file(studentId, w.year, w.isoWeek)
            f.parentFile?.mkdirs()
            val tmp = File(f.path + ".tmp")
            tmp.writeText(weekToJson(w).toString())
            tmp.renameTo(f)
            prune()
        }
    }

    /** The cached week and when it was fetched (epoch millis), or null. */
    fun get(studentId: String, year: UInt, week: UInt): Pair<Week, Long>? = runCatching {
        val f = file(studentId, year, week)
        if (f.exists()) weekFromJson(JSONObject(f.readText())) to f.lastModified() else null
    }.getOrNull()

    fun clear() { runCatching { root.deleteRecursively() } }

    /** How old each stored week is, in minutes, newest first (for the diagnostics; names and contents stay private). */
    fun agesMinutes(): List<Long> = runCatching {
        val now = System.currentTimeMillis()
        root.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.map { (now - it.lastModified()) / 60_000 }.sorted().toList()
    }.getOrDefault(emptyList())

    /** Keep the newest [limit] files. */
    private fun prune() {
        val files = root.walkTopDown().filter { it.isFile && it.name.endsWith(".json") }.sortedByDescending { it.lastModified() }.toList()
        files.drop(limit).forEach { it.delete() }
    }
}
