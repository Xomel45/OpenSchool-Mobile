package io.github.xomel45.openschool

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun TransferSettings(ui: UiPrefs, bg: BackgroundPrefs, news: NewsStore, net: NetPolicy) {
    val context = LocalContext.current
    var message by remember { mutableStateOf("") }
    val export = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/octet-stream")) { uri ->
        if (uri == null) { message = "Отменено."; return@rememberLauncherForActivityResult }
        message = try {
            context.contentResolver.openOutputStream(uri)?.use { it.write(SettingsFile.build(ui, bg, news, net)) }
            "Сохранено."
        } catch (e: Exception) { "Не удалось сохранить: ${e.message}" }
    }
    val import = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) { message = "Отменено."; return@rememberLauncherForActivityResult }
        message = try {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IllegalArgumentException("Не удалось открыть файл")
            if (bytes.size > MAX_IMAGE_BYTES + 100_000) throw IllegalArgumentException("Файл слишком большой")
            SettingsFile.apply(SettingsFile.read(bytes), ui, bg, news, net).also { NewsWorker.schedule(context, news.settings) }?.let { "Настройки применены, но картинка: $it" } ?: "Настройки применены."
        } catch (e: IllegalArgumentException) { "Не удалось загрузить: ${e.message}" } catch (e: Exception) { "Не удалось загрузить файл." }
    }
    Column {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton({ export.launch("openschool.cfg") }) { Text("Экспорт в файл…") }
            OutlinedButton({ import.launch(arrayOf("*/*")) }) { Text("Импорт из файла…") }
        }
        Text("Файл тот же, что на компьютере и на других телефонах: стиль, тема, акцент, фон, масштаб, уведомления, режим сети. Отметки о выполненных заданиях и сами оценки в него не входят.", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (message.isNotEmpty()) Text(message, Modifier.padding(top = 4.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
