package io.github.xomel45.openschool

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The launcher icons the user can choose from (the same set and ids as on the desktop, see OpenSchool-Bridge/docs/cfg/format.md).
 * Each one is an `<activity-alias>` in the manifest; exactly one of them is enabled at a time. `devOnly` ones are offered, and
 * used, only while the developer mode is on.
 */
enum class AppIcon(val id: String, val title: String, val devOnly: Boolean) {
    Graphite("graphite", "Графит", false),
    Ink("ink", "Контур", false),
    Night("night", "Ночь", false),
    Paper("paper", "Бумага", false),
    Dusk("dusk", "Закат", false),
    Indigo("indigo", "Индиго", false),
    Dawn("dawn", "Заря", false),
    Ring("ring", "Круг", false),
    Disc("disc", "Диск", true),
    Spark("spark", "Искра", true);

    /** The name of the manifest alias that carries this icon. */
    val alias: String get() = "$PACKAGE.Icon${id.replaceFirstChar { it.uppercase() }}"

    companion object {
        const val PACKAGE = "io.github.xomel45.openschool"
        val DEFAULT = Graphite

        fun fromId(id: String?): AppIcon? = entries.firstOrNull { it.id == id }

        /** The icon actually in use: a developer-only choice falls back to the default while the mode is off (the choice stays). */
        fun effective(chosen: AppIcon, devEnabled: Boolean): AppIcon = if (chosen.devOnly && !devEnabled) DEFAULT else chosen
    }
}

/** Switches which alias is the launcher entry. */
object LauncherIcon {
    /** Makes [target] the only enabled alias. Safe to call again and again: it touches only what differs. */
    fun sync(context: Context, target: AppIcon) {
        val pm = context.packageManager
        fun isOn(icon: AppIcon) = when (pm.getComponentEnabledSetting(ComponentName(context.packageName, icon.alias))) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED -> false
            else -> icon == AppIcon.DEFAULT // the manifest decides
        }
        fun set(icon: AppIcon, on: Boolean) = pm.setComponentEnabledSetting(
            ComponentName(context.packageName, icon.alias),
            if (on) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP,
        )
        if (!isOn(target)) set(target, true) // the new entry first, so the app never has none
        AppIcon.entries.filter { it != target && isOn(it) }.forEach { set(it, false) }
    }
}
