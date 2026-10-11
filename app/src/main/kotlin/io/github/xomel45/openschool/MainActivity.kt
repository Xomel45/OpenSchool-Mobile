package io.github.xomel45.openschool

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = UiPrefs(applicationContext)
        // `adb shell am start -n ... --ez demo true` in a debug build: invented data instead of an account.
        val offline = intent.getBooleanExtra("offline", false)
        val demo = intent.getBooleanExtra("demo", false) && (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        val background = BackgroundPrefs(applicationContext)
        // `--ez ai true` (debug builds): the diagnostics for an AI are offered without the developer mode.
        AiLog.flag = intent.getBooleanExtra("ai", false) && (applicationInfo.flags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
        setContent {
            AppTheme(ui, background) {
                Surface(Modifier.fillMaxSize(), color = Color.Transparent, contentColor = MaterialTheme.colorScheme.onBackground) { Root(ui, background, demo, offline) }
            }
        }
    }
}

@Composable
private fun Root(ui: UiPrefs, background: BackgroundPrefs, demo: Boolean, offline: Boolean, model: AppViewModel = viewModel()) {
    androidx.compose.runtime.LaunchedEffect(demo) { if (demo) model.startDemo(offline) }
    val screen by model.screen.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    // The launcher icon follows the choice and the developer mode (a developer-only icon falls back while the mode is off).
    androidx.compose.runtime.LaunchedEffect(model.dev.enabled) { AiLog.enabled = AiLog.flag || model.dev.enabled }
    androidx.compose.runtime.LaunchedEffect(ui.icon, model.dev.enabled) { LauncherIcon.sync(context, AppIcon.effective(ui.icon, model.dev.enabled)) }
    when (val s = screen) {
        Screen.Boot -> Centered { CircularProgressIndicator() }
        is Screen.Login -> Centered {
            Text("OpenSchool", style = MaterialTheme.typography.headlineLarge)
            Text("Войдите через Госуслуги. Пароль вводится в окне Госуслуг, приложение его не видит.", Modifier.padding(top = 12.dp), textAlign = TextAlign.Center)
            Button(onClick = model::openWeb, modifier = Modifier.padding(top = 24.dp)) { Text("Войти через Госуслуги") }
            if (s.message != null) Text(s.message, Modifier.padding(top = 16.dp), color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
        }
        Screen.Web -> {
            BackHandler(onBack = model::closeWeb) // back leaves the Gosuslugi window for the first screen instead of closing the app
            LoginScreen(model::tryLogin)
        }
        is Screen.Home -> AppShell(s.data, model.dev, ui, background, model.news, model.net, AccountActions(s.students, model::selectStudent, model::checkSession), onLogout = model::logout, onExpired = model::expired)
        is Screen.Failed -> Centered {
            Text(s.text)
            Button(onClick = model::start, modifier = Modifier.padding(top = 16.dp)) { Text("Повторить") }
        }
    }
}

@Composable
private fun Centered(content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) { content() }
}
