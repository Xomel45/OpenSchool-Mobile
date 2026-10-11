package io.github.xomel45.openschool

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.res.painterResource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** The settings pages: the list shows these, a tap opens one. */
private enum class Section(val title: String) {
    Look("Оформление"), Developer("Для разработчика"), Background("Фон"), Notifications("Уведомления"), Network("Сеть"), Transfer("Перенос настроек"), Account("Аккаунт"), About("О приложении"),
}

@Composable
fun SettingsScreen(
    data: SchoolData, ui: UiPrefs, background: BackgroundPrefs, news: NewsStore, net: NetPolicy, account: AccountActions, onLogout: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf<Section?>(null) }
    val context = LocalContext.current
    val version = remember(context) { runCatching { context.packageManager.getPackageInfo(context.packageName, 0).versionName }.getOrNull() ?: "dev" }

    val section = open
    if (section == null) {
        LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item { Text("Настройки", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold) }
            Section.entries.filter { it != Section.Developer || data.dev.enabled }.forEach { s ->
                item(key = s.name) {
                    val summary = when (s) {
                        Section.Look -> "${ui.style.title} · ${if (ui.style == StylePref.Terminal) "тёмная" else ui.scheme.title.lowercase()} · ${ui.zoom.let { (it * 100).toInt() }}%"
                        Section.Background -> when (background.bg.mode) { "solid" -> "Цвет"; "gradient" -> "Градиент"; "media" -> if (background.image != null) "Картинка" else "По стилю"; else -> "По стилю" }
                        Section.Notifications -> news.settings.let { n ->
                            listOfNotNull("оценки".takeIf { n.marks }, "задания".takeIf { n.homework }, "пропуски".takeIf { n.absences }).joinToString(", ").ifEmpty { "Выключены" }
                        }
                        Section.Network -> net.mode.title
                        Section.Transfer -> "Экспорт и импорт файла"
                        Section.Account -> "${titleCase(data.student.firstName)} ${titleCase(data.student.lastName)}".trim()
                        Section.About -> "Версия $version"
                        Section.Developer -> if (data.dev.overlay.isEmpty) "Правки не заданы" else "Есть изменённые данные"
                    }
                    Card(Modifier.fillMaxWidth().clickable { open = s }) {
                        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text(s.title, fontWeight = FontWeight.SemiBold)
                                Text(summary, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    } else {
        BackHandler { open = null } // back returns to the list, not out of the app
        Column(Modifier.fillMaxWidth()) {
            Row(Modifier.padding(start = 4.dp, end = 16.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton({ open = null }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад") }
                Text(section.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            LazyColumn(Modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                when (section) {
                    Section.Look -> item { LookSettings(ui, data.dev.enabled) }
                    Section.Background -> item { BackgroundSettings(background) }
                    Section.Notifications -> item { NotificationSettings(news) }
                    Section.Network -> item { NetworkSettings(net) }
                    Section.Transfer -> item { TransferSettings(ui, background, news, net) }
                    Section.Account -> item { AccountSettings(data, news, account, onLogout) }
                    Section.About -> item {
                        val tap = rememberVersionTapper(data.dev)
                        Text("OpenSchool $version. Неофициальный клиент «Моя школа» (Госуслуги).", Modifier.clickable(onClick = tap), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Section.Developer -> item { DevSettings(data, data.dev) }
                }
            }
        }
    }
}

/** Style, theme, accent colour and the size of the interface. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LookSettings(ui: UiPrefs, dev: Boolean) {
    val dark = isDark(ui)
    Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Block("Стиль") {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StylePref.entries.forEach { s ->
                    Card(
                        Modifier.fillMaxWidth().clickable { ui.chooseStyle(s) },
                        border = if (ui.style == s) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    ) {
                        Column(Modifier.padding(10.dp)) {
                            StylePreview(s, dark)
                            Text(s.title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 8.dp, start = 2.dp))
                            Text(s.note, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 2.dp))
                        }
                    }
                }
            }
        }
        Block("Тема") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SchemePref.entries.forEach { s -> FilterChip(ui.scheme == s, { ui.chooseScheme(s) }, enabled = ui.style != StylePref.Terminal, label = { Text(s.title) }) }
            }
            if (ui.style == StylePref.Terminal) Text("В стиле «Терминал» тема не меняется: он всегда тёмный.", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        var picker by remember { mutableStateOf(false) }
        if (picker) ColorPickerDialog("Свой цвет", ui.accentHex ?: "#2563EB", onDismiss = { picker = false }, onDone = { ui.chooseAccentHex(it); picker = false })
        Block("Акцентный цвет") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                FilterChip(ui.accent == null && ui.accentHex == null, { ui.chooseAccent(null) }, label = { Text("Как в приложении") })
                Accent.entries.forEach { a ->
                    val colour = if (dark) a.dark else a.light
                    Box(
                        Modifier.size(40.dp).clip(CircleShape).background(colour)
                            .then(if (ui.accent == a && ui.accentHex == null) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                            .clickable { ui.chooseAccent(a) },
                    )
                }
                FilterChip(ui.accentHex != null, { picker = true }, label = { Text("Свой цвет") }, leadingIcon = {
                    Box(Modifier.size(18.dp).clip(CircleShape).background(ui.accentHex?.let(::parseHex) ?: MaterialTheme.colorScheme.outline))
                })
            }
            Text(ui.accentHex?.let { "Свой цвет $it" } ?: ui.accent?.title ?: "Цвет задаёт приложение.", Modifier.padding(top = 8.dp), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Block("Масштаб интерфейса") {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { ZOOMS.forEach { z -> FilterChip(ui.zoom == z, { ui.chooseZoom(z) }, label = { Text("${(z * 100).toInt()}%") }) } }
            Text("Увеличивает или уменьшает всё приложение целиком.", Modifier.padding(top = 6.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Block("Иконка приложения") {
            val now = AppIcon.effective(ui.icon, dev)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                AppIcon.entries.filter { !it.devOnly || dev }.forEach { icon ->
                    Card(
                        Modifier.width(88.dp).clickable { ui.chooseIcon(icon) },
                        border = if (now == icon) androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else null,
                    ) {
                        Column(Modifier.padding(8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            androidx.compose.foundation.Image(painterResource(iconPreview(icon)), null, Modifier.size(64.dp).clip(RoundedCornerShape(14.dp)))
                            Text(icon.title, Modifier.padding(top = 6.dp), fontSize = 12.sp)
                        }
                    }
                }
            }
            Text("Меняет значок приложения в списке программ. Некоторые лаунчеры при этом убирают ярлык с главного экрана: тогда добавьте его снова.", Modifier.padding(top = 8.dp), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Block(title: String, content: @Composable () -> Unit) {
    Column {
        Text(title, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(bottom = 8.dp))
        content()
    }
}

/** The picture of an icon for the picker (made by icons/make_icons.py in the Desktop repository). */
private fun iconPreview(icon: AppIcon): Int = when (icon) {
    AppIcon.Graphite -> R.drawable.ic_prev_graphite
    AppIcon.Ink -> R.drawable.ic_prev_ink
    AppIcon.Night -> R.drawable.ic_prev_night
    AppIcon.Paper -> R.drawable.ic_prev_paper
    AppIcon.Dusk -> R.drawable.ic_prev_dusk
    AppIcon.Indigo -> R.drawable.ic_prev_indigo
    AppIcon.Dawn -> R.drawable.ic_prev_dawn
    AppIcon.Ring -> R.drawable.ic_prev_ring
    AppIcon.Disc -> R.drawable.ic_prev_disc
    AppIcon.Spark -> R.drawable.ic_prev_spark
}
