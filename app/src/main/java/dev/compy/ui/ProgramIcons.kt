package dev.compy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Typeface
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.compy.term.TerminalFonts

/** Maps what a terminal is running to an icon name. */
internal object ProgramNames {
    private val ALIASES = mapOf(
        "claude-code" to "claude", "cursor-agent" to "cursor", "gh" to "github", "cargo" to "rust",
        "rustc" to "rust", "rustup" to "rust", "nvim" to "neovim", "vi" to "vim", "emacsclient" to "emacs",
        "python3" to "python", "ipython" to "python", "pip" to "python", "pip3" to "python", "npm" to "npm",
        "npx" to "node", "nodejs" to "node", "psql" to "postgres", "sqlite3" to "sqlite", "kubectl" to "kube",
        "kubernetes" to "kube", "tofu" to "terraform", "nu" to "nushell", "swiftc" to "swift", "javac" to "java",
        "clang++" to "clang", "gcc" to "clang", "g++" to "clang", "brew" to "brew", "nix-shell" to "nix",
        "gem" to "ruby", "bundle" to "ruby", "rake" to "ruby", "irb" to "ruby", "mix" to "elixir", "iex" to "elixir",
        "ghc" to "haskell", "cabal" to "haskell", "stack" to "haskell", "redis-server" to "redis",
        "redis-cli" to "redis", "mysqld" to "mysql", "xcodebuild" to "xcode", "hx" to "helix",
    )

    /** Simple Icons names for the programs whose icon name differs. */
    private val SLUGS = mapOf(
        "node" to "nodedotjs", "fish" to "fishshell", "bash" to "gnubash", "copilot" to "githubcopilot",
        "gemini" to "googlegemini", "emacs" to "gnuemacs", "hugging-face" to "huggingface", "kube" to "kubernetes",
        "mistral" to "mistralai", "nix" to "nixos", "postgres" to "postgresql", "brew" to "homebrew",
        "clang" to "cplusplus", "java" to "openjdk", "proton-drive" to "protondrive",
        "unreal-engine" to "unrealengine", "apple-container" to "apple",
    )

    /**
     * The best icon name for a block. A program's own status report wins, then its script, then
     * its process, as `name/script`.
     */
    fun candidates(statusApp: String?, process: String?): List<String> {
        val parts = process?.split('/').orEmpty()
        val script = parts.getOrNull(1)?.substringBeforeLast('.')
        val name = parts.getOrNull(0)?.removePrefix("-")
        return listOfNotNull(statusApp, script, name)
            .map { it.lowercase().trim() }
            .filter { it.isNotEmpty() }
            .map { ALIASES[it] ?: it }
            .distinct()
    }

    fun slug(name: String) = SLUGS[name] ?: name
}

/** Logos with no open vector icon that the bundled Nerd Font includes, with their brand colors. */
private data class FontGlyph(val text: String, val color: Long)

private val FontGlyphs = mapOf(
    "rust" to FontGlyph("\uE7A8", 0xFFDEA584),
    "ruby" to FontGlyph("\uE739", 0xFFCC342D),
    "neovim" to FontGlyph("\uF36F", 0xFF57A143),
    "zig" to FontGlyph("\uE6A9", 0xFFF7A41D),
    "php" to FontGlyph("\uE73D", 0xFF777BB4),
    "emacs" to FontGlyph("\uE632", 0xFF7F5AB6),
)

/**
 * A program's rounded icon tile, using the brand color when there is one. With several panes, a
 * second card peeks out behind it, like Rex's stacked icons.
 */
@Composable
fun ProgramTile(names: List<String>, modifier: Modifier = Modifier, size: Dp = 28.dp, stacked: Boolean = false) {
    val context = LocalContext.current
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(size * 0.28f)
    val canvasIsDark = scheme.background.luminance() < 0.3f
    val match = remember(names) {
        names.firstNotNullOfOrNull { name ->
            val vector = SimpleIconGlyphs[ProgramNames.slug(name)]
            val font = FontGlyphs[name]
            if (vector != null || font != null) vector to font else null
        }
    }
    val brand = match?.first?.let { Color(it.brandColor) } ?: match?.second?.let { Color(it.color) }
    // A near-black or near-white logo would vanish against the canvas, so give it a neutral tile.
    val usable = brand?.takeIf { if (canvasIsDark) it.luminance() > 0.04f else it.luminance() < 0.85f }
    val tile = usable ?: scheme.surfaceContainerHighest
    val tint = if (usable != null) {
        if (usable.luminance() > 0.6f) Color(0xFF111111) else Color.White
    } else {
        scheme.onSurface
    }
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        if (stacked) {
            Box(
                Modifier
                    .size(size * 0.86f)
                    .offset(y = -(size * 0.14f))
                    .clip(shape)
                    .background(scheme.onSurface.copy(alpha = 0.22f)),
            )
        }
        Box(Modifier.size(size).clip(shape).background(tile), contentAlignment = Alignment.Center) {
            val glyphSize = size * 0.62f
            val vector = match?.first
            val font = match?.second
            when {
                vector != null -> Icon(painterResource(vector.drawable), null, Modifier.size(glyphSize), tint = tint)
                font != null -> {
                    val family = remember { FontFamily(Typeface(TerminalFonts.bundled(context).regular)) }
                    Text(font.text, color = tint, fontFamily = family, fontSize = with(LocalDensity.current) { glyphSize.toSp() }, lineHeight = with(LocalDensity.current) { size.toSp() }, softWrap = false)
                }
                else -> Icon(Icons.Rounded.Terminal, null, Modifier.size(glyphSize), tint = scheme.onSurfaceVariant)
            }
        }
    }
}
