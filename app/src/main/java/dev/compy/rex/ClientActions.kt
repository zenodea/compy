package dev.compy.rex

import kotlinx.serialization.json.JsonObject

/** A side of a pane, for the actions that split, move or change focus in a direction. */
enum class Side(val horizontal: Boolean, val before: Boolean) {
    Left(true, true),
    Right(true, false),
    Up(false, true),
    Down(false, false),
    ;

    companion object {
        fun named(name: String?): Side? = when (name) {
            "left" -> Left
            "right", "horizontal" -> Right
            "up" -> Up
            "down", "vertical" -> Down
            else -> null
        }
    }
}

/**
 * Something a Rex action asks the app showing a session to do. Key bindings live in the person's
 * Lua config on the server: the server runs its own actions and asks the client for these.
 */
sealed interface ClientCommand {
    // Tabs, which Compy calls windows.
    data object NewTab : ClientCommand
    data object CloseTab : ClientCommand
    data object CloseOtherTabs : ClientCommand
    data object RenameTab : ClientCommand
    data class StepTab(val by: Int) : ClientCommand
    /** [index] counts from 0. */
    data class GoToTab(val index: Int) : ClientCommand
    data class MoveTab(val forward: Boolean) : ClientCommand

    // Panes.
    data object ZoomPane : ClientCommand
    data object ClosePane : ClientCommand
    data object CloseOtherPanes : ClientCommand
    data object RenamePane : ClientCommand
    data object FloatPane : ClientCommand
    data object NewFloatingPane : ClientCommand
    data object MinimizePane : ClientCommand
    data object MinimizeFloatingPanes : ClientCommand
    data object MovePaneToNewTab : ClientCommand
    data object CopyPaneId : ClientCommand
    data object Paste : ClientCommand
    /** A null [side] splits along the pane's longer side. */
    data class SplitPane(val side: Side?) : ClientCommand
    data class FocusPane(val side: Side) : ClientCommand
    data class StepPane(val by: Int) : ClientCommand
    data class MovePane(val side: Side) : ClientCommand
    /** [index] counts from 0, and null asks which tab. */
    data class MovePaneToTab(val index: Int?) : ClientCommand
    data class SendKey(val key: RexKey) : ClientCommand

    // The terminal with the keyboard.
    data object Find : ClientCommand
    data class FindStep(val older: Boolean) : ClientCommand
    /** [step] is 1 for larger, -1 for smaller and 0 for the size from Settings. */
    data class FontSize(val step: Int) : ClientCommand
    data class JumpPrompt(val by: Int) : ClientCommand
    data class ScrollPage(val up: Boolean) : ClientCommand

    // Getting around.
    data object Palette : ClientCommand
    data object Overview : ClientCommand
    data object GoToAnything : ClientCommand
    data object GoToDirectory : ClientCommand
    data object InsertPath : ClientCommand
    data object Sidebar : ClientCommand
    data object Board : ClientCommand
    data object Settings : ClientCommand
    data object AddHost : ClientCommand

    // Sessions.
    data object NewSession : ClientCommand
    data object RenameSession : ClientCommand
    data object EndSession : ClientCommand
    data class StepSession(val by: Int) : ClientCommand
    /** Folds or unfolds this session in the list, or folds every other one. */
    data class FoldSessions(val others: Boolean, val fold: Boolean) : ClientCommand

    data object ReloadConfig : ClientCommand
    data object CopyVersion : ClientCommand
    /** Asked for and already true, such as dismissing a notice Compy doesn't show. */
    data object Done : ClientCommand
}

/** Reads the name and arguments of a Rex client action. Null for one Compy can't carry out. */
object ClientActions {
    fun parse(action: String, args: JsonObject?): ClientCommand? {
        if (action.startsWith("pane.")) return pane(action.removePrefix("pane."), args)
        if (action.startsWith("client.palette.")) return ClientCommand.Palette
        return when (action) {
            "client.tab.new", "session.new_window" -> ClientCommand.NewTab
            "client.tab.close", "session.close_window" -> ClientCommand.CloseTab
            "client.tab.close_others" -> ClientCommand.CloseOtherTabs
            "client.tab.rename" -> ClientCommand.RenameTab
            "client.tab.next", "session.focus_next_window" -> ClientCommand.StepTab(1)
            "client.tab.previous", "session.focus_previous_window" -> ClientCommand.StepTab(-1)
            "client.tab.goto" -> args?.get("index").int()?.let { ClientCommand.GoToTab(it - 1) }
            "client.tab.move.forward" -> ClientCommand.MoveTab(true)
            "client.tab.move.backward" -> ClientCommand.MoveTab(false)
            "client.tab.move" -> ClientCommand.MoveTab((args?.get("offset").int() ?: args?.get("delta").int() ?: 1) > 0)
            "client.find.open" -> ClientCommand.Find
            "client.find.next" -> ClientCommand.FindStep(older = false)
            "client.find.previous" -> ClientCommand.FindStep(older = true)
            "client.font.increase" -> ClientCommand.FontSize(1)
            "client.font.decrease" -> ClientCommand.FontSize(-1)
            "client.font.reset" -> ClientCommand.FontSize(0)
            "client.prompt.next" -> ClientCommand.JumpPrompt(1)
            "client.prompt.previous" -> ClientCommand.JumpPrompt(-1)
            "client.scroll.page_up" -> ClientCommand.ScrollPage(up = true)
            "client.scroll.page_down" -> ClientCommand.ScrollPage(up = false)
            "client.overview.toggle" -> ClientCommand.Overview
            "client.sidebar.toggle", "client.sidebar.focus" -> ClientCommand.Sidebar
            "client.board.toggle" -> ClientCommand.Board
            "client.settings", "client.theme.change", "client.shortcuts.edit" -> ClientCommand.Settings
            "client.host.add" -> ClientCommand.AddHost
            "client.config.reload" -> ClientCommand.ReloadConfig
            "client.version.copy" -> ClientCommand.CopyVersion
            "client.notice.dismiss" -> ClientCommand.Done
            "session.new" -> ClientCommand.NewSession
            "session.rename" -> ClientCommand.RenameSession
            "session.close" -> ClientCommand.EndSession
            "session.next" -> ClientCommand.StepSession(1)
            "session.previous" -> ClientCommand.StepSession(-1)
            "session.switch" -> ClientCommand.GoToAnything
            "session.collapse" -> ClientCommand.FoldSessions(others = false, fold = true)
            "session.expand" -> ClientCommand.FoldSessions(others = false, fold = false)
            "session.collapse_others" -> ClientCommand.FoldSessions(others = true, fold = true)
            "session.zoom_block" -> ClientCommand.ZoomPane
            "session.focus_next_pane" -> ClientCommand.StepPane(1)
            "session.focus_previous_pane" -> ClientCommand.StepPane(-1)
            "session.focus_direction" -> Side.named(args?.get("direction").str())?.let { ClientCommand.FocusPane(it) }
            else -> null
        }
    }

    /** Rex's pane actions, named here without their `pane.` */
    private fun pane(name: String, args: JsonObject?): ClientCommand? {
        fun side(prefix: String) = Side.named(name.removePrefix(prefix))
        val direction = args?.get("direction").str()
        return when {
            name == "split.auto" || (name == "split" && direction == "auto") -> ClientCommand.SplitPane(null)
            name.startsWith("split.") -> side("split.")?.let { ClientCommand.SplitPane(it) }
            name == "split" -> ClientCommand.SplitPane(Side.named(direction ?: "right") ?: return null)
            name.startsWith("focus.") -> side("focus.")?.let { ClientCommand.FocusPane(it) }
            name == "focus" -> Side.named(direction)?.let { ClientCommand.FocusPane(it) }
            name.startsWith("move.") -> side("move.")?.let { ClientCommand.MovePane(it) }
            name == "move" -> Side.named(direction)?.let { ClientCommand.MovePane(it) }
            name == "focus_next" -> ClientCommand.StepPane(1)
            name == "focus_previous" -> ClientCommand.StepPane(-1)
            name == "zoom" -> ClientCommand.ZoomPane
            name == "close" -> ClientCommand.ClosePane
            name == "close_others" -> ClientCommand.CloseOtherPanes
            name == "rename" -> ClientCommand.RenamePane
            name == "float" -> ClientCommand.FloatPane
            name == "new_floating" -> ClientCommand.NewFloatingPane
            name == "minimize_floating" -> ClientCommand.MinimizePane
            name == "minimize_all_floating" -> ClientCommand.MinimizeFloatingPanes
            name == "move_to_new_tab" -> ClientCommand.MovePaneToNewTab
            name == "move_to_tab" -> ClientCommand.MovePaneToTab(args?.get("index").int()?.let { it - 1 })
            name == "go_to" -> ClientCommand.GoToAnything
            name == "go_to_directory" -> ClientCommand.GoToDirectory
            name == "insert_path" -> ClientCommand.InsertPath
            name == "copy_id" -> ClientCommand.CopyPaneId
            name == "paste" -> ClientCommand.Paste
            // A key the config passes through, such as the prefix itself pressed twice.
            name == "send_key" -> keyNamed(args?.get("key").str())?.let { ClientCommand.SendKey(it) }
            else -> null
        }
    }

    /** A key as a Rex config names it: `ctrl+a`, `shift+tab`, `escape`. Null for one Compy has no key for. */
    fun keyNamed(name: String?): RexKey? {
        if (name.isNullOrEmpty()) return null
        // The key itself may be a plus, as in `ctrl++`.
        val base = if (name.endsWith("+")) "+" else name.substringAfterLast('+')
        var mods = 0
        for (mod in name.dropLast(base.length).split('+')) {
            mods = mods or when (mod) {
                "" -> 0
                "shift" -> RexKey.SHIFT
                "ctrl", "control" -> RexKey.CTRL
                "alt", "option" -> RexKey.ALT
                "super", "cmd", "command" -> RexKey.SUPER
                else -> return null
            }
        }
        val named = when (base) {
            "enter" -> "enter"
            "tab" -> "tab"
            "escape" -> "escape"
            "backspace" -> "backspace"
            "up" -> "arrow_up"
            "down" -> "arrow_down"
            "left" -> "arrow_left"
            "right" -> "arrow_right"
            else -> null
        }
        if (named != null) return RexKey(named, RexKey.modsString(mods))
        if (base == "space") return RexKey.forChar(' '.code, mods)
        if (base.codePointCount(0, base.length) != 1) return null
        var c = base.codePointAt(0)
        // forChar adds shift itself for a capital or a shifted sign, so give it the sign shift makes.
        if (mods and RexKey.SHIFT != 0) {
            c = if (c in 'a'.code..'z'.code) c - 32 else SHIFTED[c.toChar()]?.code ?: c
            mods = mods and RexKey.SHIFT.inv()
        }
        return RexKey.forChar(c, mods).takeIf { it.key != "unidentified" }
    }

    private val SHIFTED = "1!2@3#4$5%6^7&8*9(0)-_=+[{]}\\|;:'\"`~,<.>/?".chunked(2).associate { it[0] to it[1] }
}
