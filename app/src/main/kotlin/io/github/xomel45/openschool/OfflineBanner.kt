package io.github.xomel45.openschool

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId

/** "11 окт, 10:20" */
fun formatFetched(epochMillis: Long): String {
    val t = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault())
    return "${t.dayOfMonth} ${MONTHS[t.monthValue - 1].take(3)}, %02d:%02d".format(t.hour, t.minute)
}

/** Shown while a week on screen comes from the phone's copy because Gosuslugi cannot be reached. */
@Composable
fun OfflineBanner(data: SchoolData) {
    val since = data.offlineSince ?: return
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.tertiaryContainer).padding(horizontal = 12.dp, vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Нет связи с Госуслугами. Показаны данные от ${formatFetched(since)}.", Modifier.weight(1f), fontSize = 12.sp, color = MaterialTheme.colorScheme.onTertiaryContainer)
        TextButton({ busy = true; scope.launch { data.refreshStale(); busy = false } }, enabled = !busy) { Text("Обновить", fontSize = 12.sp) }
    }
}
