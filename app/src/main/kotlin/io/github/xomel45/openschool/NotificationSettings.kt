package io.github.xomel45.openschool

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotificationSettings(news: NewsStore) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val s = news.settings
    var message by remember { mutableStateOf("") }
    var checking by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        message = if (granted) "" else "Без разрешения система не покажет уведомления. Его можно включить в настройках телефона."
    }
    fun change(new: NewsSettings) {
        news.update(new)
        NewsWorker.schedule(context, new)
        // Android 13+ asks before the first notification.
        if (Build.VERSION.SDK_INT >= 33 && (new.marks || new.homework || new.absences)) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    Column {
        SwitchRow("Новые оценки", s.marks) { change(s.copy(marks = it)) }
        SwitchRow("Новые домашние задания", s.homework) { change(s.copy(homework = it)) }
        SwitchRow("Отметки о пропусках", s.absences) { change(s.copy(absences = it)) }
        Text("Как часто проверять", Modifier.padding(top = 10.dp), fontSize = 14.sp)
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NEWS_INTERVALS.forEach { m -> FilterChip(s.intervalMin == m, { change(s.copy(intervalMin = m)) }, label = { Text(if (m == 60) "1 час" else "$m мин") }) }
        }
        Text("Android не запускает проверку чаще, чем раз в 15 минут, и может отложить её в режиме экономии заряда.", Modifier.padding(top = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        SwitchRow("Показывать предмет и оценку", s.detail) { change(s.copy(detail = it)) }
        OutlinedButton(
            onClick = {
                checking = true
                message = "Проверяю…"
                scope.launch {
                    val id = NewsWorker.checkNow(context)
                    val info = WorkManager.getInstance(context).getWorkInfoByIdFlow(id).first { it != null && it.state.isFinished }
                    checking = false
                    message = when {
                        info?.state != WorkInfo.State.SUCCEEDED -> "Проверка не удалась."
                        info.outputData.getString("problem") == "expired" -> "Сессия истекла. Выйдите и войдите снова."
                        info.outputData.getString("problem") == "offline" -> "Нет связи с Госуслугами."
                        info.outputData.getString("problem") != null -> "Госуслуги ответили ошибкой."
                        info.outputData.getInt("found", 0) > 0 -> "Найдено нового: ${info.outputData.getInt("found", 0)}."
                        else -> "Ничего нового."
                    }
                }
            },
            enabled = !checking, modifier = Modifier.padding(top = 10.dp),
        ) { Text("Проверить сейчас") }
        if (message.isNotEmpty()) Text(message, Modifier.padding(top = 6.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}
