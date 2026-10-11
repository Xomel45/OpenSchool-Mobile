package io.github.xomel45.openschool

import org.json.JSONArray
import org.json.JSONObject
import uniffi.openschool_bridge.ClassInfo
import uniffi.openschool_bridge.Homework
import uniffi.openschool_bridge.Lesson
import uniffi.openschool_bridge.Student
import uniffi.openschool_bridge.Teacher
import uniffi.openschool_bridge.Week
import java.time.LocalDate
import java.time.temporal.IsoFields

/** Changes of one lesson; `null` means "as the server said". */
data class LessonEdit(
    val subject: String? = null, val start: String? = null, val end: String? = null, val room: String? = null,
    val teacher: String? = null, val theme: String? = null, val deleted: Boolean = false,
)

data class HwEdit(val text: String? = null, val deleted: Boolean = false)

data class AddedLesson(val id: String, val date: String, val start: String, val end: String, val subject: String, val room: String, val teacher: String, val theme: String)

data class AddedHomework(val id: String, val due: String, val subject: String, val text: String, val lessonId: String?)

/**
 * The developer mode's layer of changes on top of what the server says. It only changes what this phone shows; nothing is
 * sent anywhere and the real data is not touched. Marks are deliberately not editable (only the subject name is replaced).
 */
data class Overlay(
    /** firstName, lastName, middleName, region, schoolName, classNumber, classLetter */
    val profile: Map<String, String> = emptyMap(),
    /** the subject name from the server -> the name to show */
    val subjects: Map<String, String> = emptyMap(),
    /** "last|first|patronymic" from the server -> "Last First Patronymic" to show */
    val teachers: Map<String, String> = emptyMap(),
    val lessons: Map<String, LessonEdit> = emptyMap(),
    val homework: Map<String, HwEdit> = emptyMap(),
    val addedLessons: List<AddedLesson> = emptyList(),
    val addedHomework: List<AddedHomework> = emptyList(),
) {
    val isEmpty get() = this == Overlay()

    fun toJson(): JSONObject {
        fun map(m: Map<String, String>) = JSONObject().also { o -> m.forEach { (k, v) -> o.put(k, v) } }
        val lessonsJson = JSONObject()
        lessons.forEach { (id, e) ->
            lessonsJson.put(id, JSONObject().also { o ->
                e.subject?.let { o.put("subject", it) }; e.start?.let { o.put("start", it) }; e.end?.let { o.put("end", it) }; e.room?.let { o.put("room", it) }
                e.teacher?.let { o.put("teacher", it) }; e.theme?.let { o.put("theme", it) }; if (e.deleted) o.put("deleted", true)
            })
        }
        val hwJson = JSONObject()
        homework.forEach { (id, e) -> hwJson.put(id, JSONObject().also { o -> e.text?.let { o.put("text", it) }; if (e.deleted) o.put("deleted", true) }) }
        return JSONObject().put("profile", map(profile)).put("subjects", map(subjects)).put("teachers", map(teachers))
            .put("lessons", lessonsJson).put("homework", hwJson)
            .put("addedLessons", JSONArray().also { a -> addedLessons.forEach { l -> a.put(JSONObject().put("id", l.id).put("date", l.date).put("start", l.start).put("end", l.end).put("subject", l.subject).put("room", l.room).put("teacher", l.teacher).put("theme", l.theme)) } })
            .put("addedHomework", JSONArray().also { a -> addedHomework.forEach { h -> a.put(JSONObject().put("id", h.id).put("due", h.due).put("subject", h.subject).put("text", h.text).put("lessonId", h.lessonId ?: "")) } })
    }

    companion object {
        /** Anything unexpected (junk in storage) gives an empty overlay or skips that entry. */
        fun fromJson(text: String?): Overlay = try {
            val o = JSONObject(text ?: "{}")
            fun strings(name: String): Map<String, String> = o.optJSONObject(name)?.let { j -> j.keys().asSequence().associateWith { j.optString(it) } } ?: emptyMap()
            val lessons = o.optJSONObject("lessons")?.let { j ->
                j.keys().asSequence().associateWith { id ->
                    val e = j.getJSONObject(id)
                    fun s(k: String) = if (e.has(k)) e.optString(k) else null
                    LessonEdit(s("subject"), s("start"), s("end"), s("room"), s("teacher"), s("theme"), e.optBoolean("deleted"))
                }
            } ?: emptyMap()
            val hw = o.optJSONObject("homework")?.let { j -> j.keys().asSequence().associateWith { id -> j.getJSONObject(id).let { e -> HwEdit(if (e.has("text")) e.optString("text") else null, e.optBoolean("deleted")) } } } ?: emptyMap()
            val addedL = o.optJSONArray("addedLessons")?.let { a -> (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { l -> AddedLesson(l.getString("id"), l.getString("date"), l.getString("start"), l.getString("end"), l.getString("subject"), l.optString("room"), l.optString("teacher"), l.optString("theme")) } }.getOrNull() } } ?: emptyList()
            val addedH = o.optJSONArray("addedHomework")?.let { a -> (0 until a.length()).mapNotNull { i -> runCatching { a.getJSONObject(i).let { h -> AddedHomework(h.getString("id"), h.getString("due"), h.getString("subject"), h.getString("text"), h.optString("lessonId").ifEmpty { null }) } }.getOrNull() } } ?: emptyList()
            Overlay(strings("profile"), strings("subjects"), strings("teachers"), lessons, hw, addedL, addedH)
        } catch (e: Exception) { Overlay() }
    }
}

fun teacherKey(t: Teacher) = "${t.lastName}|${t.firstName}|${t.patronymic}"

/** "Last First Patronymic" (the patronymic may be several words, or missing). */
fun parseTeacher(text: String): Teacher {
    val p = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    return Teacher(p.getOrElse(1) { "" }, p.getOrElse(0) { "" }, p.drop(2).joinToString(" "))
}

fun teacherText(t: Teacher) = listOf(t.lastName, t.firstName, t.patronymic).filter { it.isNotBlank() }.joinToString(" ")

fun Overlay.applyStudent(s: Student): Student = s.copy(
    firstName = profile["firstName"] ?: s.firstName, lastName = profile["lastName"] ?: s.lastName,
    middleName = profile["middleName"] ?: s.middleName, region = profile["region"] ?: s.region,
)

fun Overlay.applyClass(c: ClassInfo?): ClassInfo? = c?.copy(
    schoolName = profile["schoolName"] ?: c.schoolName, classNumber = profile["classNumber"] ?: c.classNumber, classLetter = profile["classLetter"] ?: c.classLetter,
)

private fun LocalDate.isoKey() = "${get(IsoFields.WEEK_BASED_YEAR)}-${get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)}"

/** One week as the phone should show it: the server's week with this overlay applied. */
fun Overlay.applyWeek(w: Week): Week {
    if (isEmpty) return w
    val weekKey = "${w.year}-${w.isoWeek}"
    fun subject(name: String) = subjects[name] ?: name
    val lessons = w.lessons.filter { lessons[it.id]?.deleted != true }.map { l ->
        val e = lessons[l.id]
        val date = l.start.take(10)
        l.copy(
            subjectName = e?.subject ?: subject(l.subjectName),
            teacher = (e?.teacher ?: teachers[teacherKey(l.teacher)])?.let(::parseTeacher) ?: l.teacher,
            start = e?.start?.let { "$date $it" } ?: l.start, end = e?.end?.let { "$date $it" } ?: l.end,
            room = e?.room ?: l.room, theme = e?.theme ?: l.theme,
        )
    } + addedLessons.filter { runCatching { LocalDate.parse(it.date).isoKey() }.getOrNull() == weekKey }.map { a ->
        Lesson(a.id, "dev-s-${a.subject}", a.subject, parseTeacher(a.teacher), "${a.date} ${a.start}", "${a.date} ${a.end}", a.room, a.theme.ifBlank { null }, null)
    }
    val homeworks = w.homeworks.filter { homework[it.id]?.deleted != true }.map { h ->
        h.copy(subjectName = subject(h.subjectName), description = homework[h.id]?.text ?: h.description)
    } + addedHomework.filter { runCatching { LocalDate.parse(it.due).isoKey() }.getOrNull() == weekKey }.map { a ->
        Homework(a.id, "dev-s-${a.subject}", a.subject, a.text, a.due, a.due, a.lessonId, emptyList())
    }
    return w.copy(lessons = lessons, homeworks = homeworks, marks = w.marks.map { it.copy(subjectName = subject(it.subjectName)) })
}
