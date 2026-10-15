package io.github.xomel45.openschool

import androidx.compose.ui.graphics.Color
import uniffi.openschool_bridge.Mark

/** A subject in a period: its marks and their average (`null` when there is nothing numeric). */
data class GradeRow(val name: String, val marks: List<Mark>, val average: Double?)

/** "4,5" -> 4.5; "н" and other non-numbers -> null (they are shown but never counted). */
fun toNumber(value: String): Double? = value.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }

fun mean(xs: List<Double>): Double? = if (xs.isEmpty()) null else xs.sum() / xs.size

fun formatAverage(x: Double): String = (Math.round(x * 100) / 100.0).toString().replace('.', ',').removeSuffix(",0")

/** An average is coloured like a mark: from 4.5 a five, from 3.5 a four, from 2.6 a three, below that a two. */
fun averageColor(x: Double): Color = markColor(if (x >= 4.5) "5" else if (x >= 3.5) "4" else if (x >= 2.6) "3" else "2")

/** How many fives lift the average of [nums] to the next band (2,6 → 3, 3,5 → 4, 4,5 → 5): (grade, fives), or null when there is
 *  nothing to count or the average is already a five. The same rule as the desktop app (ui/js/marks.js). */
fun nextBand(nums: List<Double>): Pair<Int, Int>? {
    if (nums.isEmpty()) return null
    val sum = nums.sum(); val n = nums.size; val avg = sum / n
    val (t, grade) = listOf(2.6 to 3, 3.5 to 4, 4.5 to 5).firstOrNull { avg < it.first } ?: return null
    return grade to maxOf(1, kotlin.math.ceil((t * n - sum) / (5 - t) - 1e-9).toInt())
}

fun fivesWord(k: Int) = when {
    k % 10 == 1 && k % 100 != 11 -> "пятёрка"
    k % 10 in 2..4 && k % 100 !in 12..14 -> "пятёрки"
    else -> "пятёрок"
}
