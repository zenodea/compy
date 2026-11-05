package dev.compy.system

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.annotation.DrawableRes
import dev.compy.R

/** The launcher icons to choose from. Each is an activity alias in the manifest. */
enum class AppIcon(val label: String, val alias: String, @param:DrawableRes val background: Int, @param:DrawableRes val foreground: Int) {
    Refined("Refined", "dev.compy.IconRefined", R.drawable.ic_launcher_bg_refined, R.drawable.ic_launcher_fg_refined),
    Pastel("Pastel", "dev.compy.IconPastel", R.drawable.ic_launcher_bg_pastel, R.drawable.ic_launcher_fg_pastel),
    Midnight("Midnight", "dev.compy.IconMidnight", R.drawable.ic_launcher_bg_midnight, R.drawable.ic_launcher_fg_midnight),
    Rainbow("Rainbow", "dev.compy.IconRainbow", R.drawable.ic_launcher_bg_rainbow, R.drawable.ic_launcher_fg_rainbow),
    Paper("Paper", "dev.compy.IconPaper", R.drawable.ic_launcher_bg_paper, R.drawable.ic_launcher_fg_paper),
    Stripes("Stripes", "dev.compy.IconStripes", R.drawable.ic_launcher_bg_stripes, R.drawable.ic_launcher_fg_stripes),
}

/**
 * Android keeps which alias is enabled, so the choice needs no setting of its own. Disabling an
 * alias closes any window opened through it, so a change waits until Compy leaves the screen.
 */
object AppIcons {
    @Volatile private var pending: AppIcon? = null

    private fun component(context: Context, icon: AppIcon) = ComponentName(context.packageName, icon.alias)

    fun current(context: Context): AppIcon {
        pending?.let { return it }
        val packages = context.packageManager
        return AppIcon.entries.firstOrNull {
            packages.getComponentEnabledSetting(component(context, it)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        } ?: AppIcon.Refined
    }

    fun choose(icon: AppIcon) {
        pending = icon
    }

    /** Switches in one step, so the launcher never sees Compy with no icon or two. */
    fun applyChoice(context: Context) {
        val icon = pending ?: return
        pending = null
        context.packageManager.setComponentEnabledSettings(
            AppIcon.entries.map {
                val state = if (it == icon) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                PackageManager.ComponentEnabledSetting(component(context, it), state, PackageManager.DONT_KILL_APP)
            },
        )
    }
}
