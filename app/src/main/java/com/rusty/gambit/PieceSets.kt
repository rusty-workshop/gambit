package com.rusty.gambit

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

private val CODES = listOf("wK", "wQ", "wR", "wB", "wN", "wP", "bK", "bQ", "bR", "bB", "bN", "bP")
private const val SIZE = 128 // px per piece: sharp up to the board's 2.4x zoom

/**
 * Which piece set the board draws. cburnett is built in (vector paths, Pieces.kt); any other Lichess set is
 * rendered to PNGs by the gambit server and cached in the app's files, so each set downloads once.
 */
object PieceSet {
    /** Your Lichess account preference (from /home). */
    var lichess by mutableStateOf<String?>(null)
    /** A set picked on the watch; null = follow Lichess. */
    var chosen by mutableStateOf<String?>(null)
    val current: String get() = chosen ?: lichess ?: "cburnett"

    /** Images for [loadedSet]; empty while loading or for cburnett. */
    val images = mutableStateMapOf<String, ImageBitmap>()
    var loadedSet by mutableStateOf<String?>(null)
        private set
    private val lock = Mutex()

    fun init(ctx: Context) {
        chosen = ctx.getSharedPreferences("gambit", Context.MODE_PRIVATE).getString("pieceSet", null)
    }

    fun choose(ctx: Context, set: String?) {
        chosen = set
        ctx.getSharedPreferences("gambit", Context.MODE_PRIVATE).edit().putString("pieceSet", set).apply()
    }

    suspend fun image(ctx: Context, set: String, code: String, size: Int = SIZE): ImageBitmap? = withContext(Dispatchers.IO) {
        val file = File(ctx.filesDir, "pieces/$set/${code}_$size.png")
        val bytes = if (file.exists()) file.readBytes() else runCatching { Api.bytes("/pieces/$set/$code?size=$size") }
            .getOrNull()?.also { file.parentFile?.mkdirs(); file.writeBytes(it) }
        bytes?.let { BitmapFactory.decodeByteArray(it, 0, it.size)?.asImageBitmap() }
    }

    /** Make sure [images] holds the current set (no-op for cburnett, which is drawn from paths). */
    suspend fun ensure(ctx: Context) = lock.withLock {
        val set = current
        if (set == loadedSet) return@withLock
        if (set == "cburnett") { images.clear(); loadedSet = set; return@withLock }
        val loaded = CODES.associateWith { image(ctx, set, it) }
        if (loaded.values.any { it == null }) return@withLock // server unreachable: keep the old set, retry later
        if (set != current) return@withLock
        images.clear()
        loaded.forEach { (k, v) -> images[k] = v!! }
        loadedSet = set
    }
}

@Composable
fun PieceSetScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val sets = load("piecesets") { Api.get("/piecesets") }
    Loaded(sets) { s ->
        val lichess = s.str("lichess")
        ListScreen {
            header("Piece set")
            item {
                SetChip(ctx, lichess ?: "cburnett", "Follow Lichess", "Now: ${lichess ?: "cburnett"}", PieceSet.chosen == null) {
                    PieceSet.choose(ctx, null); scope.launch { PieceSet.ensure(ctx) }; nav.popBackStack()
                }
            }
            for (name in s.strs("sets")) item {
                SetChip(ctx, name, name, null, PieceSet.chosen == name) {
                    PieceSet.choose(ctx, name); scope.launch { PieceSet.ensure(ctx) }; nav.popBackStack()
                }
            }
        }
    }
}

@Composable
private fun SetChip(ctx: Context, set: String, label: String, secondary: String?, selected: Boolean, onClick: () -> Unit) {
    val preview by produceState<ImageBitmap?>(null, set) { value = PieceSet.image(ctx, set, "wN", 64) }
    Chip(
        onClick = onClick,
        label = { Text(if (selected) "✓ $label" else label, maxLines = 1) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 1) } },
        icon = { preview?.let { Image(it, contentDescription = null, modifier = Modifier.size(28.dp)) } },
        colors = if (selected) ChipDefaults.primaryChipColors(backgroundColor = Accent, contentColor = androidx.compose.ui.graphics.Color.Black)
        else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

/** Keeps [PieceSet.images] in step with the chosen / Lichess set. */
@Composable
fun PieceSetLoader() {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { PieceSet.init(ctx) }
    LaunchedEffect(PieceSet.current) { PieceSet.ensure(ctx) }
}
