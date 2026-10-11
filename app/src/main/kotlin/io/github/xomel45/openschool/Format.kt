package io.github.xomel45.openschool

import java.time.LocalDateTime

val MONTHS = listOf("января", "февраля", "марта", "апреля", "мая", "июня", "июля", "августа", "сентября", "октября", "ноября", "декабря")
private val MONTHS_SHORT = listOf("янв", "фев", "мар", "апр", "мая", "июн", "июл", "авг", "сен", "окт", "ноя", "дек")

/** "2026-10-08 08:30" (the server's lesson time) -> "8:30". */
fun hm(s: String): String = s.drop(11).take(5).removePrefix("0")

/** "2026-10-08 08:30" -> a date-time. */
fun parseDateTime(s: String): LocalDateTime = LocalDateTime.parse(s.take(16).replace(' ', 'T'))

/** "2026-10-08" -> "8 окт". */
fun shortDate(s: String): String {
    val m = s.drop(5).take(2).toIntOrNull() ?: return s
    return "${s.drop(8).take(2).toIntOrNull() ?: ""} ${MONTHS_SHORT.getOrElse(m - 1) { "" }}"
}

fun titleCase(s: String): String = s.lowercase().replace(Regex("(^|[\\s-])(\\S)")) { it.groupValues[1] + it.groupValues[2].uppercase() }

/** "Иванов И.П." */
fun teacherName(last: String, first: String, patronymic: String): String {
    if (last.isBlank()) return ""
    fun ini(x: String) = if (x.isBlank()) "" else x.first().uppercase() + "."
    return "${titleCase(last)} ${ini(first)}${ini(patronymic)}".trim()
}
