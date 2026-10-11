package io.github.xomel45.openschool

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** The diagnostics for an AI: one button builds the report, another copies or shares it. See AiDebug.kt for what is in it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AiSettings(data: SchoolData, ui: UiPrefs, background: BackgroundPrefs, news: NewsStore, net: NetPolicy, students: Int) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var text by rememberSaveable { mutableStateOf("") }
    var message by remember { mutableStateOf("Нажмите «Обновить»: соберу отчёт о состоянии приложения без личных данных, его можно показать ИИ.") }

    fun build(then: (String) -> Unit = {}) {
        message = "Собираю…"
        scope.launch {
            runCatching { AiCollect.report(context, ui, background, news, net, data, students) }
                .onSuccess { text = it; message = "Готово. В отчёте нет имён, номеров, предметов, текстов и оценок; перед отправкой всё равно просмотрите его."; then(it) }
                .onFailure { message = "Не удалось: ${it.javaClass.simpleName}" }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Отчёт о состоянии приложения: версии, настройки, число и форма загруженных данных, исходы запросов, последний сбой. Включён режимом разработчика.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ build() }) { Text("Обновить") }
            Button({
                val copy = { t: String ->
                    (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("OpenSchool diagnostics", t))
                    message = "Скопировано."
                }
                if (text.isEmpty()) build(copy) else copy(text)
            }) { Text("Скопировать") }
            OutlinedButton({
                val share = { t: String ->
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, t), "Отчёт OpenSchool"))
                }
                if (text.isEmpty()) build(share) else share(text)
            }) { Text("Поделиться") }
        }
        Text(message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (text.isNotEmpty()) SelectionContainer { Text(text, Modifier.fillMaxWidth().padding(top = 4.dp), fontFamily = FontFamily.Monospace, fontSize = 10.sp, lineHeight = 14.sp) }
    }
}
