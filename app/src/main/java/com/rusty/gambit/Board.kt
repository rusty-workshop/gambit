package com.rusty.gambit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.rotary.onRotaryScrollEvent
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.graphics.FilterQuality
import kotlin.math.roundToInt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One drawable part of a cburnett piece (see Pieces.kt). Colors are ARGB, null = none. */
internal class PiecePart(
    val d: String, val fill: Long?, val stroke: Long?, val width: Float,
    val cap: String, val join: String, val evenOdd: Boolean,
) {
    val path: Path by lazy {
        PathParser().parsePathString(d).toPath().apply { if (evenOdd) fillType = PathFillType.EvenOdd }
    }
}

/** Board colors per Lichess board theme name (from your Lichess preferences). */
fun themeColors(name: String?): Pair<Color, Color> = when (name) {
    "blue", "blue2", "blue3", "blue-marble" -> Color(0xFFDEE3E6) to Color(0xFF8CA2AD)
    "green", "green-plastic" -> Color(0xFFFFFFDD) to Color(0xFF86A666)
    "purple", "purple-diag" -> Color(0xFF9F90B0) to Color(0xFF7D4A8D)
    "grey", "metal", "olive" -> Color(0xFFB8B8B8) to Color(0xFF7D7D7D)
    "ic" -> Color(0xFFECECEC) to Color(0xFFC1C18E)
    "pink" -> Color(0xFFF1F1C9) to Color(0xFFF07272)
    "maple", "maple2", "wood", "wood2", "wood3", "wood4", "leather" -> Color(0xFFE8CEAB) to Color(0xFFBC7944)
    else -> Color(0xFFF0D9B5) to Color(0xFFB58863) // brown, Lichess's default
}

/** Your Lichess board theme, learned from /home. */
object BoardTheme {
    var name by mutableStateOf<String?>(null)
}

data class Arrow(val from: String, val to: String, val color: Color)

/** Square name -> piece code like "wN", from the placement part of a FEN. */
fun parseFen(fen: String): Map<String, String> {
    val out = HashMap<String, String>()
    val rows = fen.substringBefore(' ').split('/')
    for ((r, row) in rows.withIndex()) {
        var f = 0
        for (c in row) {
            if (c.isDigit()) { f += c - '0'; continue }
            val color = if (c.isUpperCase()) "w" else "b"
            out["${'a' + f}${8 - r}"] = color + c.uppercaseChar()
            f++
        }
    }
    return out
}

fun sideToMove(fen: String): String = fen.split(' ').getOrNull(1) ?: "w"

private val FILES = "abcdefgh"

/** Column/row on screen (0,0 = top-left) for a square, given orientation. */
private fun cell(sq: String, flipped: Boolean): Pair<Int, Int> {
    val f = FILES.indexOf(sq[0]); val r = sq[1] - '1'
    return if (flipped) (7 - f) to r else f to (7 - r)
}

private fun squareAt(col: Int, row: Int, flipped: Boolean): String =
    if (flipped) "${FILES[7 - col]}${row + 1}" else "${FILES[col]}${8 - row}"

/**
 * The board. Taps snap to the nearest square in [snapTo] (movable pieces / legal targets), which
 * makes a 40-px square forgiving on a watch. The bezel zooms (1x-2.4x) and a drag pans while zoomed.
 */
@Composable
fun ChessBoard(
    fen: String,
    flipped: Boolean,
    size: Dp,
    theme: String?,
    modifier: Modifier = Modifier,
    lastMove: String? = null,
    check: String? = null,
    selected: String? = null,
    targets: Set<String> = emptySet(),
    snapTo: Set<String> = emptySet(),
    arrows: List<Arrow> = emptyList(),
    onTap: ((String?) -> Unit)? = null,
) {
    val pieces = remember(fen) { parseFen(fen) }
    val themeName = theme ?: BoardTheme.name
    val (light, dark) = remember(themeName) { themeColors(themeName) }
    var zoom by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    val tapHandler by rememberUpdatedState(onTap)
    val snap by rememberUpdatedState(snapTo)
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }

    Box(
        modifier
            .size(size)
            .onRotaryScrollEvent { e ->
                zoom = (zoom + e.verticalScrollPixels / 400f).coerceIn(1f, 2.4f)
                if (zoom == 1f) pan = Offset.Zero
                true
            }
            .focusRequester(focus)
            .focusable()
            .pointerInput(Unit) {
                detectDragGestures { change, drag ->
                    if (zoom > 1.01f) {
                        change.consume()
                        val limit = this.size.width * (zoom - 1f) / 2f
                        pan = Offset((pan.x + drag.x).coerceIn(-limit, limit), (pan.y + drag.y).coerceIn(-limit, limit))
                    }
                }
            },
    ) {
        Canvas(
            Modifier
                .size(size)
                .graphicsLayer { scaleX = zoom; scaleY = zoom; translationX = pan.x; translationY = pan.y }
                .pointerInput(flipped) {
                    detectTapGestures { p ->
                        val onTap = tapHandler ?: return@detectTapGestures
                        val sq = this.size.width / 8f
                        val best = snap.minByOrNull { s ->
                            val (c, r) = cell(s, flipped)
                            hypot(p.x - (c + .5f) * sq, p.y - (r + .5f) * sq)
                        }
                        val bestDist = best?.let { s ->
                            val (c, r) = cell(s, flipped); hypot(p.x - (c + .5f) * sq, p.y - (r + .5f) * sq)
                        }
                        val col = (p.x / sq).toInt().coerceIn(0, 7); val row = (p.y / sq).toInt().coerceIn(0, 7)
                        // Snap within ~1 square; otherwise report the exact square tapped (deselects).
                        onTap(if (best != null && bestDist!! < sq * 1.0f) best else squareAt(col, row, flipped))
                    }
                },
        ) {
            val sq = this.size.width / 8f
            for (r in 0..7) for (c in 0..7) {
                drawRect(if ((r + c) % 2 == 0) light else dark, Offset(c * sq, r * sq), Size(sq, sq))
            }
            fun tint(s: String, color: Color) {
                val (c, r) = cell(s, flipped); drawRect(color, Offset(c * sq, r * sq), Size(sq, sq))
            }
            lastMove?.takeIf { it.length >= 4 }?.let {
                tint(it.substring(0, 2), Color(0x669BC700)); tint(it.substring(2, 4), Color(0x669BC700))
            }
            selected?.let { tint(it, Color(0x8814551E)) }
            check?.let { tint(it, Color(0xAAE53935)) }
            val imgs = if (PieceSet.loadedSet == PieceSet.current && PieceSet.current != "cburnett") PieceSet.images else null
            for ((s, code) in pieces) {
                val (c, r) = cell(s, flipped)
                val img = imgs?.get(code)
                if (img != null) drawImage(
                    img, dstOffset = IntOffset((c * sq).roundToInt(), (r * sq).roundToInt()),
                    dstSize = IntSize(sq.roundToInt(), sq.roundToInt()), filterQuality = FilterQuality.Medium,
                ) else drawPiece(code, Offset(c * sq, r * sq), sq)
            }
            for (t in targets) {
                val (c, r) = cell(t, flipped)
                val center = Offset((c + .5f) * sq, (r + .5f) * sq)
                if (pieces.containsKey(t)) drawCircle(Color(0x9914551E), sq * .46f, center, style = Stroke(sq * .09f))
                else drawCircle(Color(0x9914551E), sq * .16f, center)
            }
            for (a in arrows) drawArrow(a, sq, flipped)
        }
    }
}

private fun DrawScope.drawPiece(code: String, topLeft: Offset, sq: Float) {
    val parts = PIECE_PARTS[code] ?: return
    translate(topLeft.x, topLeft.y) {
        scale(sq / 45f, Offset.Zero) {
            for (p in parts) {
                p.fill?.let { drawPath(p.path, Color(it), style = Fill) }
                p.stroke?.let {
                    drawPath(
                        p.path, Color(it), style = Stroke(
                            width = p.width,
                            cap = when (p.cap) { "round" -> StrokeCap.Round; "square" -> StrokeCap.Square; else -> StrokeCap.Butt },
                            join = when (p.join) { "round" -> StrokeJoin.Round; "bevel" -> StrokeJoin.Bevel; else -> StrokeJoin.Miter },
                        )
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawArrow(a: Arrow, sq: Float, flipped: Boolean) {
    val (c1, r1) = cell(a.from, flipped); val (c2, r2) = cell(a.to, flipped)
    val from = Offset((c1 + .5f) * sq, (r1 + .5f) * sq)
    val to = Offset((c2 + .5f) * sq, (r2 + .5f) * sq)
    val ang = atan2(to.y - from.y, to.x - from.x)
    val head = sq * .45f
    val shaftEnd = Offset(to.x - cos(ang) * head * .8f, to.y - sin(ang) * head * .8f)
    drawLine(a.color, from, shaftEnd, strokeWidth = sq * .18f, cap = StrokeCap.Round)
    val path = Path().apply {
        moveTo(to.x, to.y)
        lineTo(to.x - cos(ang - .5f) * head, to.y - sin(ang - .5f) * head)
        lineTo(to.x - cos(ang + .5f) * head, to.y - sin(ang + .5f) * head)
        close()
    }
    drawPath(path, a.color)
}

private val NAMES = mapOf('K' to "King", 'Q' to "Queen", 'R' to "Rook", 'B' to "Bishop", 'N' to "Knight", 'P' to "Pawn")

/** "Knight takes f3", "Pawn to e8 = Queen", "Castle short" for the confirm dialog. */
fun describeMove(fen: String, uci: String): String {
    val pieces = parseFen(fen)
    val from = uci.substring(0, 2); val to = uci.substring(2, 4)
    val piece = pieces[from] ?: return uci
    if (piece[1] == 'K' && from[0] == 'e' && (to[0] == 'g' || to[0] == 'c'))
        return if (to[0] == 'g') "Castle short" else "Castle long"
    val capture = pieces.containsKey(to) || (piece[1] == 'P' && from[0] != to[0])
    val promo = uci.getOrNull(4)?.let { " = " + NAMES[it.uppercaseChar()] }.orEmpty()
    return "${NAMES[piece[1]]} ${if (capture) "takes" else "to"} $to$promo"
}
