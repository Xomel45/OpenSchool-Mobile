package io.github.xomel45.openschool

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NetworkSettings(net: NetPolicy) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var checking by remember { mutableStateOf(false) }
    var checked by remember { mutableStateOf(false) }
    Column {
        Text("Прокси окна входа", fontSize = 14.sp)
        FlowRow(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NetMode.entries.forEach { m -> FilterChip(net.mode == m, { net.choose(m) }, label = { Text(m.title) }) }
        }
        Text(
            when (net.mode) {
                NetMode.Auto -> "Если выходной адрес не из России, окно входа само идёт к Госуслугам без прокси Wi-Fi."
                NetMode.Direct -> "Окно входа игнорирует прокси, заданный для сети Wi-Fi."
                NetMode.System -> "Окно входа работает так, как настроено в системе."
            } + if (!net.overrideSupported) " (Эта версия системного WebView не умеет обходить прокси.)" else "",
            Modifier.padding(top = 4.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(
            onClick = { checking = true; scope.launch { net.resolve(); net.applyToWebView(context); checking = false; checked = true } },
            enabled = !checking, modifier = Modifier.padding(top = 10.dp),
        ) { Text(if (checking) "Проверяю…" else "Проверить соединение") }
        if (checked && !checking) {
            val c = net.country
            Text(
                when {
                    c == null -> "Не удалось определить страну выходного адреса. Проверьте интернет."
                    c.equals("RU", ignoreCase = true) -> "Выходной адрес: $c. С ним Госуслуги работают."
                    else -> "Выходной адрес: $c. Госуслуги не пускают с зарубежных адресов. Если это VPN, исключите OpenSchool из VPN (раздельное туннелирование): приложение не может обойти VPN само."
                },
                Modifier.padding(top = 6.dp), fontSize = 13.sp,
                color = if (c != null && !c.equals("RU", ignoreCase = true)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
