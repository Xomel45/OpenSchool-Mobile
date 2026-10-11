package io.github.xomel45.openschool

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private enum class Tab(val title: String, val icon: ImageVector) {
    Home("Главная", Icons.Filled.Home),
    Schedule("Расписание", Icons.Filled.DateRange),
    Tasks("Задания", Icons.Filled.Edit),
    Marks("Оценки", Icons.Filled.Star),
    Settings("Настройки", Icons.Filled.Settings),
}

/** The signed-in app: the pages of the desktop menu as a bottom bar. */
@Composable
fun AppShell(data: SchoolData, dev: DevStore, ui: UiPrefs, background: BackgroundPrefs, news: NewsStore, net: NetPolicy, account: AccountActions, onLogout: () -> Unit, onExpired: () -> Unit) {
    var tab by rememberSaveable { mutableStateOf(Tab.Home) }
    var feed by rememberSaveable { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(feed) { news.reload() } // the background check may have added items
    androidx.compose.runtime.LaunchedEffect(dev.overlay, dev.enabled) { data.rebuild() } // the changes of the developer mode decide what the screens are built from
    androidx.compose.runtime.CompositionLocalProvider(LocalFiles provides data.files) {
    ProvideDev(dev) {
    Scaffold(
        topBar = { Column { if (dev.enabled) DevBanner(dev); OfflineBanner(data) } },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(selected = tab == t, onClick = { tab = t }, icon = { Icon(t.icon, contentDescription = null) }, label = { Text(t.title, fontSize = 11.sp, maxLines = 1, softWrap = false) })
                }
            }
        },
    ) { padding ->
        if (feed) FeedSheet(news, onDismiss = { feed = false }, onOpen = { kind -> feed = false; tab = when (kind) { Kind.Mark -> Tab.Marks; Kind.Homework -> Tab.Tasks; Kind.Absence -> Tab.Schedule } })
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (tab) {
                Tab.Home -> HomeScreen(data, news, onBell = { feed = true }, onExpired = onExpired)
                Tab.Marks -> MarksScreen(data, onExpired)
                Tab.Settings -> SettingsScreen(data, ui, background, news, net, account, onLogout)
                Tab.Schedule -> ScheduleScreen(data, onExpired)
                Tab.Tasks -> TasksScreen(data, onExpired)
            }
        }
    }
    }
    }
}
