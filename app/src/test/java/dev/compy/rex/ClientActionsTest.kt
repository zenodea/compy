package dev.compy.rex

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ClientActionsTest {
    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (k, v) -> if (v is Int) put(k, v) else put(k, v.toString()) }
    }

    @Test fun readsTabActions() {
        assertEquals(ClientCommand.NewTab, ClientActions.parse("client.tab.new", null))
        assertEquals(ClientCommand.StepTab(-1), ClientActions.parse("client.tab.previous", null))
        assertEquals(ClientCommand.MoveTab(false), ClientActions.parse("client.tab.move", args("offset" to -1)))
    }

    @Test fun countsTabsFromOneAsRexDoes() {
        assertEquals(ClientCommand.GoToTab(2), ClientActions.parse("client.tab.goto", args("index" to 3)))
        assertNull(ClientActions.parse("client.tab.goto", null))
        assertEquals(ClientCommand.MovePaneToTab(0), ClientActions.parse("pane.move_to_tab", args("index" to 1)))
        assertEquals(ClientCommand.MovePaneToTab(null), ClientActions.parse("pane.move_to_tab", null))
    }

    @Test fun readsASideFromTheNameOrTheArguments() {
        assertEquals(ClientCommand.SplitPane(Side.Left), ClientActions.parse("pane.split.left", null))
        assertEquals(ClientCommand.SplitPane(Side.Down), ClientActions.parse("pane.split", args("direction" to "vertical")))
        assertEquals(ClientCommand.SplitPane(Side.Right), ClientActions.parse("pane.split", null))
        assertEquals(ClientCommand.SplitPane(null), ClientActions.parse("pane.split.auto", null))
        assertEquals(ClientCommand.FocusPane(Side.Up), ClientActions.parse("pane.focus.up", null))
        assertEquals(ClientCommand.FocusPane(Side.Down), ClientActions.parse("session.focus_direction", args("direction" to "down")))
        assertEquals(ClientCommand.MovePane(Side.Right), ClientActions.parse("pane.move", args("direction" to "right")))
        assertNull(ClientActions.parse("pane.focus.sideways", null))
        assertNull(ClientActions.parse("pane.move", null))
    }

    @Test fun opensThePaletteForAnyOfItsActions() {
        assertEquals(ClientCommand.Palette, ClientActions.parse("client.palette.toggle", null))
        assertEquals(ClientCommand.Palette, ClientActions.parse("client.palette.commands", null))
    }

    @Test fun refusesWhatCompyCannotDo() {
        assertNull(ClientActions.parse("client.window.new", null))
        assertNull(ClientActions.parse("client.update.check", null))
        assertNull(ClientActions.parse("pane.copy_mode.enter", null))
    }

    @Test fun namesKeysAsRexConfigsDo() {
        assertEquals(RexKey.forChar('a'.code, RexKey.CTRL), ClientActions.keyNamed("ctrl+a"))
        assertEquals(RexKey("tab", "shift"), ClientActions.keyNamed("shift+tab"))
        assertEquals(RexKey("escape"), ClientActions.keyNamed("escape"))
        assertEquals(RexKey.forChar('N'.code), ClientActions.keyNamed("shift+n"))
        assertEquals(RexKey.forChar('&'.code), ClientActions.keyNamed("shift+7"))
        assertEquals(RexKey.forChar('+'.code, RexKey.CTRL), ClientActions.keyNamed("ctrl++"))
        assertEquals(RexKey.forChar(' '.code), ClientActions.keyNamed("space"))
        assertNull(ClientActions.keyNamed("hyper+a"))
        assertNull(ClientActions.keyNamed("f13"))
        assertNull(ClientActions.keyNamed(""))
    }

    @Test fun passesAKeyThrough() {
        assertEquals(ClientCommand.SendKey(RexKey.forChar('a'.code, RexKey.CTRL)), ClientActions.parse("pane.send_key", args("key" to "ctrl+a")))
        assertNull(ClientActions.parse("pane.send_key", null))
    }
}
