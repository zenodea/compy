package dev.compy.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Terminal
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

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
        "clang" to "cplusplus", "java" to "openjdk",
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

/** Icons rendered from a local copy of Rex, which only exist in the owner's own builds. */
internal object LocalProgramIcons {
    private var index: Map<String, Boolean>? = null
    private val bitmaps = HashMap<String, Bitmap?>()

    private fun index(context: Context): Map<String, Boolean> = index ?: runCatching {
        val text = context.assets.open("rex-icons/index.json").bufferedReader().use { it.readText() }
        (Json.parseToJsonElement(text) as JsonObject).mapValues { (_, v) -> v.jsonObject["mono"]!!.jsonPrimitive.boolean }
    }.getOrDefault(emptyMap()).also { index = it }

    fun isMono(context: Context, name: String) = index(context)[name]

    fun bitmap(context: Context, name: String): Bitmap? = bitmaps.getOrPut(name) {
        runCatching { context.assets.open("rex-icons/$name.png").use(BitmapFactory::decodeStream) }.getOrNull()
    }
}

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
    val glyphs = remember(names) {
        names.firstNotNullOfOrNull { name ->
            val mono = LocalProgramIcons.isMono(context, name)
            val glyph = SimpleIconGlyphs[ProgramNames.slug(name)]
            if (mono != null || glyph != null) Triple(name, mono, glyph) else null
        }
    }
    val brand = glyphs?.third?.let { Color(it.brandColor) }
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
            val local = glyphs?.first?.let { name -> if (glyphs.second != null) LocalProgramIcons.bitmap(context, name) else null }
            when {
                local != null -> Image(
                    local.asImageBitmap(), null, Modifier.size(glyphSize),
                    colorFilter = if (glyphs.second == true) ColorFilter.tint(tint) else null,
                )
                glyphs?.third != null -> Icon(painterResource(glyphs.third!!.drawable), null, Modifier.size(glyphSize), tint = tint)
                else -> Icon(Icons.Rounded.Terminal, null, Modifier.size(glyphSize), tint = scheme.onSurfaceVariant)
            }
        }
    }
}
