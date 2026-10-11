package io.github.xomel45.openschool

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import uniffi.openschool_bridge.Student

/** What the account page can ask the app to do. */
class AccountActions(val students: List<Student>, val select: (String) -> Unit, val checkSession: suspend () -> String)

private enum class Confirm { Logout, ResetDone, ClearFeed }

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AccountSettings(data: SchoolData, news: NewsStore, account: AccountActions, onLogout: () -> Unit) {
    val s = data.student
    val c = data.classInfo
    val scope = rememberCoroutineScope()
    var message by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Confirm?>(null) }
    val fullName = listOf(s.lastName, s.firstName, s.middleName).filter { it.isNotBlank() }.joinToString(" ") { titleCase(it) }
    val period = run {
        val t = data.today
        data.quarters.firstOrNull { t >= it.second && t <= it.third }?.let { q -> "${q.first} четверть: ${q.second.dayOfMonth} ${MONTHS[q.second.monthValue - 1].take(3)} – ${q.third.dayOfMonth} ${MONTHS[q.third.monthValue - 1].take(3)}" }
            ?: data.quarters.firstOrNull { it.second > t }?.let { q -> "Ближайшая: ${q.first} четверть с ${q.second.dayOfMonth} ${MONTHS[q.second.monthValue - 1]}" }
    }

    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Card(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Avatar((s.firstName.take(1) + s.lastName.take(1)).uppercase(), 52, MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary, 18)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(fullName, fontWeight = FontWeight.SemiBold)
                    if (c != null) Text("${c.classNumber} «${c.classLetter}»" + c.schoolName.takeIf { it.isNotBlank() }?.let { " · $it" }.orEmpty(), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }

        if (account.students.size > 1) Block("Ученик") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                account.students.forEach { st -> FilterChip(st.id == s.id, { if (st.id != s.id) account.select(st.id) }, label = { Text(titleCase(st.firstName)) }) }
            }
            Text("К этому входу привязано несколько учеников. Выбранный показывается во всех разделах и в уведомлениях.", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Block("Сведения") {
            Fact("Учебный год", c?.let { "${it.academicYear}/${it.academicYear + 1u}" } ?: "—")
            Fact("Сейчас", period ?: "—")
            if (s.region.isNotBlank()) Fact("Код региона", s.region)
            Fact("Вход", "через Госуслуги")
        }

        Block("Сессия") {
            OutlinedButton({ checking = true; message = "Проверяю…"; scope.launch { message = account.checkSession(); checking = false } }, enabled = !checking) { Text("Проверить сессию") }
            if (message.isNotEmpty()) Text(message, Modifier.padding(top = 6.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Пароль приложение не хранит: оно запоминает только сессию Госуслуг, зашифрованную ключом телефона.", Modifier.padding(top = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        Block("Данные на телефоне") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton({ confirm = Confirm.ResetDone }) { Text("Сбросить «сделано»") }
                OutlinedButton({ confirm = Confirm.ClearFeed }) { Text("Очистить ленту") }
            }
            Text("Отметки «сделано» у домашних заданий и лента «Что нового» хранятся только на этом телефоне.", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        OutlinedButton({ confirm = Confirm.Logout }, Modifier.fillMaxWidth()) { Text("Выйти из аккаунта", color = MaterialTheme.colorScheme.error) }
    }

    confirm?.let { what ->
        val (title, text, action) = when (what) {
            Confirm.Logout -> Triple("Выйти из аккаунта?", "Сохранённая сессия и лента «Что нового» будут удалены, окно входа Госуслуг забудет вход. Настройки внешнего вида останутся.", onLogout)
            Confirm.ResetDone -> Triple("Сбросить отметки «сделано»?", "Все домашние задания снова станут невыполненными.", { data.clearDone() })
            Confirm.ClearFeed -> Triple("Очистить ленту?", "Записи в ленте исчезнут. Новые оценки и задания по-прежнему будут приходить.", { news.clearFeed() })
        }
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text(title) }, text = { Text(text) },
            confirmButton = { TextButton({ confirm = null; action() }) { Text(if (what == Confirm.Logout) "Выйти" else "Да") } },
            dismissButton = { TextButton({ confirm = null }) { Text("Отмена") } },
        )
    }
}

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

@Composable
private fun Fact(name: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
        Text(name, Modifier.weight(0.4f), color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.weight(0.6f))
    }
}
