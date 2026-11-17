package dev.compy.ui

import dev.compy.AppState
import dev.compy.Selection
import dev.compy.rex.ClientCommand
import dev.compy.rex.neighbor
import dev.compy.rex.tabMoveTarget

/** The parts of the screen around a session that an action may open or switch. */
internal class WorkspaceUi(
    val palette: () -> Unit,
    val find: () -> Unit,
    val overview: () -> Unit,
    val sidebar: () -> Unit,
    val board: () -> Unit,
    val settings: () -> Unit,
    val addHost: () -> Unit,
)

/**
 * Carries out what a Rex action asks of the app, on the session a workspace shows. Rex's key
 * bindings and Lua actions reach Compy this way: a key bound to `client.tab.new` on the host
 * opens a window here. False when it can't be done just now, such as with no pane to act on.
 */
internal fun perform(command: ClientCommand, controller: WorkspaceController, ui: WorkspaceUi): Boolean {
    val app = controller.app
    val connection = controller.connection
    val sessionId = controller.sessionId
    val view = connection.views.value[sessionId]
    val window = view?.activeWindow
    val focused = controller.focusedBlockId
    val message = controller.showMessage

    fun focus(blockId: String) {
        controller.focusedBlockId = blockId
        connection.focusBlock(sessionId, blockId)
    }

    when (command) {
        ClientCommand.NewTab -> connection.newWindow(sessionId, message)
        ClientCommand.CloseTab -> connection.closeWindow(sessionId, window?.id ?: return false, message)
        ClientCommand.CloseOtherTabs -> {
            val keep = window?.id ?: return false
            view?.windows.orEmpty().filter { it.id != keep }.forEach { connection.closeWindow(sessionId, it.id, message) }
        }
        ClientCommand.RenameTab -> controller.sheet = WorkspaceController.Sheet.RenameWindow
        is ClientCommand.StepTab -> {
            val windows = view?.windows.orEmpty()
            val at = windows.indexOfFirst { it.id == window?.id }
            if (at < 0) return false
            connection.focusWindow(sessionId, windows[(at + command.by).mod(windows.size)].id)
        }
        is ClientCommand.GoToTab -> connection.focusWindow(sessionId, view?.windows?.getOrNull(command.index)?.id ?: return false)
        is ClientCommand.MoveTab -> {
            val shown = window?.id ?: return false
            // At either end there is nowhere further to go, which isn't a failure.
            view?.tabMoveTarget(shown, command.forward)?.let { connection.moveWindow(sessionId, shown, it.beforeWindowId, message) }
        }

        ClientCommand.ZoomPane -> connection.zoomBlock(sessionId, focused ?: return false, window?.zoomedBlockId == null)
        ClientCommand.ClosePane -> connection.closeBlock(sessionId, focused ?: return false, message)
        ClientCommand.CloseOtherPanes -> {
            val keep = focused ?: return false
            window?.blocks?.filter { it.id != keep }?.forEach { connection.closeBlock(sessionId, it.id, message) }
        }
        ClientCommand.RenamePane -> controller.sheet = WorkspaceController.Sheet.RenamePane(focused ?: return false)
        ClientCommand.FloatPane -> {
            val block = focused ?: return false
            val shown = window ?: return false
            if (shown.layerOf(block)?.floating == true) {
                // Back into the layout, beside its first pane.
                val anchor = shown.tiledLayer?.blocks?.firstOrNull()?.id ?: return false
                connection.moveBlock(sessionId, block, anchor, horizontal = true, before = false, onError = message)
            } else {
                // With no pane left in the layout, a floating one would have nowhere to dock.
                if ((shown.tiledLayer?.blocks?.size ?: 0) < 2) return false
                connection.floatBlock(sessionId, block, nextFloatBounds(shown), message)
            }
        }
        ClientCommand.NewFloatingPane -> {
            val shown = window ?: return false
            connection.newFloatingPane(sessionId, shown.id, nextFloatBounds(shown), message)
        }
        ClientCommand.MinimizePane -> {
            val layer = focused?.let { window?.layerOf(it) }?.takeIf { it.floating } ?: return false
            connection.setLayerMinimized(sessionId, layer.id, true, message)
        }
        ClientCommand.MinimizeFloatingPanes ->
            window?.floatingLayers?.filterNot { it.minimized }?.forEach { connection.setLayerMinimized(sessionId, it.id, true, message) }
        ClientCommand.MovePaneToNewTab -> connection.placeInNewWindow(sessionId, focused ?: return false, message)
        is ClientCommand.MovePaneToTab -> {
            val block = focused ?: return false
            val index = command.index
            if (index == null) {
                controller.sheet = WorkspaceController.Sheet.MoveToWindow(block)
            } else {
                controller.moveToWindow(block, view?.windows?.getOrNull(index) ?: return false)
            }
        }
        ClientCommand.CopyPaneId -> {
            controller.copy(focused ?: return false)
            message("Pane ID copied")
        }
        ClientCommand.Paste -> controller.pasteClipboard(focused ?: return false)
        is ClientCommand.SplitPane -> {
            val block = focused ?: return false
            val side = command.side
            // With no side named, the new pane takes half of the pane's longer side.
            val horizontal = side?.horizontal ?: (controller.view(block)?.let { it.width >= it.height * 2 } ?: false)
            connection.newSplit(sessionId, block, horizontal, before = side?.before ?: false, onError = message)
        }
        is ClientCommand.FocusPane -> {
            val block = focused ?: return false
            // At the window's edge there is no pane to go to, which isn't a failure.
            window?.neighbor(block, command.side)?.let(::focus)
        }
        is ClientCommand.StepPane -> {
            val panes = window?.shownBlocks.orEmpty()
            val at = panes.indexOfFirst { it.id == focused }
            if (at < 0) return false
            focus(panes[(at + command.by).mod(panes.size)].id)
        }
        is ClientCommand.MovePane -> {
            val block = focused ?: return false
            val side = command.side
            window?.neighbor(block, side)?.let { connection.moveBlock(sessionId, block, it, side.horizontal, side.before, message) }
        }
        is ClientCommand.SendKey -> controller.sendKey(command.key, focused ?: return false)

        ClientCommand.Find -> ui.find()
        is ClientCommand.FindStep -> {
            // Opens the bar when it's closed, and steps it when it's open.
            ui.find()
            controller.findSteps.tryEmit(command.older)
        }
        is ClientCommand.FontSize -> {
            val block = focused ?: return false
            if (command.step == 0) controller.resetFontSize(block) else controller.stepFontSize(block, command.step)
        }
        is ClientCommand.JumpPrompt -> (controller.focusedView() ?: return false).jumpPrompt(command.by)
        is ClientCommand.ScrollPage -> (controller.focusedView() ?: return false).scrollPage(command.up)

        ClientCommand.Palette -> ui.palette()
        ClientCommand.Overview -> ui.overview()
        ClientCommand.GoToAnything -> controller.sheet = WorkspaceController.Sheet.GoTo
        ClientCommand.GoToDirectory -> controller.sheet = WorkspaceController.Sheet.Browse(focused ?: return false, insert = false)
        ClientCommand.InsertPath -> controller.sheet = WorkspaceController.Sheet.Browse(focused ?: return false, insert = true)
        ClientCommand.Sidebar -> ui.sidebar()
        ClientCommand.Board -> ui.board()
        ClientCommand.Settings -> ui.settings()
        ClientCommand.AddHost -> ui.addHost()

        ClientCommand.NewSession -> connection.act(message) {
            connection.createSession(null)?.let { app.requestOpen(Selection(connection.config.id, it)) }
        }
        ClientCommand.RenameSession -> controller.sheet = WorkspaceController.Sheet.RenameSession
        ClientCommand.EndSession -> controller.sheet = WorkspaceController.Sheet.EndSession
        is ClientCommand.StepSession -> {
            val sessions = connection.sessions.value
            val at = sessions.indexOfFirst { it.id == sessionId }
            if (at < 0) return false
            app.requestOpen(Selection(connection.config.id, sessions[(at + command.by).mod(sessions.size)].id))
        }
        is ClientCommand.FoldSessions -> {
            val others = connection.sessions.value.map { it.id }.filter { it != sessionId }.toSet()
            app.updateSettings { s ->
                s.copy(
                    collapsedSessions = when {
                        command.others -> s.collapsedSessions + others
                        command.fold -> s.collapsedSessions + sessionId
                        else -> s.collapsedSessions - sessionId
                    },
                )
            }
        }
        ClientCommand.ReloadConfig -> connection.reloadConfig(message)
        ClientCommand.CopyVersion -> {
            controller.copy("Compy for Android ${AppState.BuildConfigVersion}")
            message("Version copied")
        }
        ClientCommand.Done -> Unit
    }
    return true
}
