package io.github.xomel45.openschool

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.appwidget.updateAll
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.LocalSize
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import androidx.glance.LocalContext
import androidx.glance.color.ColorProvider as dayNight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime

/*
 * Home-screen widgets: «Расписание» (today's lessons, then the next school day) and «Домашнее задание» (what is not done yet).
 * They are drawn from the weeks kept on the phone (the offline copy), never from the network; the periodic background check
 * (NewsWorker) fetches the weeks and calls [WidgetUpdater.refresh]. Marks are not shown on a widget. The text is worked out in
 * WidgetModel.kt; this file only draws it.
 */

/** Redraws the widgets and tells the background check whether a widget needs it. */
object WidgetUpdater {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    suspend fun refresh(context: Context) {
        val app = context.applicationContext
        runCatching { ScheduleWidget().updateAll(app) }
        runCatching { HomeworkWidget().updateAll(app) }
    }

    fun refreshAsync(context: Context) {
        val app = context.applicationContext
        scope.launch { refresh(app) }
    }

    fun anyWidget(context: Context): Boolean {
        val manager = AppWidgetManager.getInstance(context)
        return listOf(ScheduleWidgetReceiver::class.java, HomeworkWidgetReceiver::class.java).any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
    }

    /** A widget was added or the last one removed: the periodic check starts or stops, and a first fetch runs at once. */
    fun widgetsChanged(context: Context) {
        NewsWorker.schedule(context, NewsStore(context).settings)
        if (anyWidget(context)) NewsWorker.checkNow(context)
    }
}

class ScheduleWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = ScheduleWidget()
    override fun onEnabled(context: Context) { super.onEnabled(context); WidgetUpdater.widgetsChanged(context) }
    override fun onDisabled(context: Context) { super.onDisabled(context); WidgetUpdater.widgetsChanged(context) }
}

class HomeworkWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HomeworkWidget()
    override fun onEnabled(context: Context) { super.onEnabled(context); WidgetUpdater.widgetsChanged(context) }
    override fun onDisabled(context: Context) { super.onDisabled(context); WidgetUpdater.widgetsChanged(context) }
}

/** The colours of a widget: the system's light or dark and the app's accent (the colour the user chose). */
private class Palette(context: Context) {
    val background = dayNight(day = Color(0xFFFFFFFF), night = Color(0xFF1C1B1F))
    val ink = dayNight(day = Color(0xFF1B1B1F), night = Color(0xFFE6E1E5))
    val muted = dayNight(day = Color(0xFF6B6B73), night = Color(0xFFA9A5AD))
    val accent: ColorProvider = UiPrefs(context).let { ui ->
        ui.accentHex?.let(::parseHex)?.let { dayNight(day = it, night = it) }
            ?: (ui.accent ?: Accent.Blue).let { dayNight(day = it.light, night = it.dark) }
    }
}

/** The weeks on the phone for the current student, and a hint when they are old. */
private fun loadWeeks(context: Context): Pair<List<uniffi.openschool_bridge.Week>, String?> {
    val chosen = context.getSharedPreferences("app", Context.MODE_PRIVATE).getString("studentId", null)
    val stored = WeekCache(context).allFor(chosen)
    val newest = stored.maxOfOrNull { it.second }
    return stored.map { it.first } to WidgetModels.staleNote(newest, LocalDateTime.now())
}

class ScheduleWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val model = withContext(Dispatchers.IO) { loadWeeks(context).let { (weeks, stale) -> WidgetModels.schedule(weeks, LocalDateTime.now(), stale) } }
        val palette = Palette(context)
        provideContent { ScheduleContent(model, palette) }
    }
}

class HomeworkWidget : GlanceAppWidget() {
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val model = withContext(Dispatchers.IO) {
            val (weeks, stale) = loadWeeks(context)
            val done = context.getSharedPreferences("homework-done", Context.MODE_PRIVATE).all.keys
            WidgetModels.homework(weeks, java.time.LocalDate.now(), done, stale)
        }
        val palette = Palette(context)
        provideContent { HomeworkContent(model, palette) }
    }
}

@Composable
private fun Frame(palette: Palette, content: @Composable () -> Unit) {
    val context = LocalContext.current
    Column(
        GlanceModifier.fillMaxSize().background(palette.background).cornerRadius(20.dp).padding(12.dp)
            .clickable(actionStartActivity(android.content.Intent(context, MainActivity::class.java))),
    ) { content() }
}

@Composable
private fun ScheduleContent(model: ScheduleModel, palette: Palette) {
    // Row height ~26 dp; the header and the hint take about 44 dp. A container holds at most 10 children.
    val rows = ((LocalSize.current.height.value - 44f) / 26f).toInt().coerceIn(1, 7)
    Frame(palette) {
        Text(model.title, style = TextStyle(color = palette.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp), maxLines = 1)
        Spacer(GlanceModifier.height(4.dp))
        if (model.lessons.isEmpty()) {
            Text(model.note ?: "", style = TextStyle(color = palette.muted, fontSize = 13.sp), maxLines = 3)
        } else {
            val shown = model.lessons.take(rows)
            shown.forEach { l ->
                val color = when (l.phase) { LessonPhase.Past -> palette.muted; LessonPhase.Now -> palette.accent; LessonPhase.Upcoming -> palette.ink }
                val weight = if (l.phase == LessonPhase.Now) FontWeight.Bold else FontWeight.Normal
                Row(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) {
                    Text("${l.number}", GlanceModifier.width(18.dp), style = TextStyle(color = palette.muted, fontSize = 13.sp))
                    Text(l.subject + if (l.homework) "  ·  д/з" else "", GlanceModifier.defaultWeight(), style = TextStyle(color = color, fontWeight = weight, fontSize = 13.sp), maxLines = 1)
                    Text(l.time, style = TextStyle(color = palette.muted, fontSize = 12.sp), maxLines = 1)
                }
            }
            val more = model.lessons.size - shown.size
            val hint = listOfNotNull(if (more > 0) "ещё $more" else null, model.note).joinToString(" · ")
            if (hint.isNotEmpty()) Text(hint, style = TextStyle(color = palette.muted, fontSize = 11.sp), maxLines = 1)
        }
    }
}

@Composable
private fun HomeworkContent(model: HomeworkModel, palette: Palette) {
    val items = ((LocalSize.current.height.value - 40f) / 44f).toInt().coerceIn(1, 5)
    Frame(palette) {
        Text("Домашнее задание", style = TextStyle(color = palette.accent, fontWeight = FontWeight.Bold, fontSize = 14.sp), maxLines = 1)
        Spacer(GlanceModifier.height(4.dp))
        if (model.lines.isEmpty()) {
            Text(model.note ?: "", style = TextStyle(color = palette.muted, fontSize = 13.sp), maxLines = 3)
        } else {
            val shown = model.lines.take(items)
            shown.forEach { h ->
                Column(GlanceModifier.fillMaxWidth().padding(vertical = 3.dp)) {
                    Text("${h.subject} · ${h.due}${if (h.files) " · 📎" else ""}", style = TextStyle(color = palette.ink, fontWeight = FontWeight.Medium, fontSize = 13.sp), maxLines = 1)
                    Text(h.text, style = TextStyle(color = palette.muted, fontSize = 12.sp), maxLines = 1)
                }
            }
            val more = model.lines.size - shown.size
            val hint = listOfNotNull(if (more > 0) "ещё $more" else null, model.note).joinToString(" · ")
            if (hint.isNotEmpty()) Text(hint, style = TextStyle(color = palette.muted, fontSize = 11.sp), maxLines = 1)
        }
    }
}
