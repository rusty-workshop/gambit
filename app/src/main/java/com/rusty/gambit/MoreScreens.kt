package com.rusty.gambit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

private fun JsonObject.legalMap(): Map<String, List<String>> =
    obj("legal")?.mapValues { (_, v) -> (v as JsonArray).map { it.jsonPrimitive.content } } ?: emptyMap()

// ------------------------------------------------------------------ new game

private val DAYS = listOf(1, 2, 3, 5, 7, 14)

@Composable
fun NewGameScreen(nav: NavHostController, user: String?) {
    var days by remember { mutableStateOf(3) }
    var rated by remember { mutableStateOf(true) }
    var color by remember { mutableStateOf("random") }
    var note by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ListScreen {
        header(if (user != null) "Challenge $user" else "New game")
        chip("$days day${if (days > 1) "s" else ""} per move", "Tap to change") { days = DAYS[(DAYS.indexOf(days) + 1) % DAYS.size] }
        chip(if (rated) "Rated" else "Casual", "Tap to change") { rated = !rated }
        chip("Play as $color", "Tap to change") {
            color = when (color) { "random" -> "white"; "white" -> "black"; else -> "random" }
        }
        note?.let { item { Text(it, color = Accent, fontSize = 12.sp, textAlign = TextAlign.Center) } }
        chip(if (busy) "Sending…" else if (user != null) "Send challenge" else "Post open seek",
            if (user == null) "Anyone can accept" else null, primary = true) {
            if (busy) return@chip
            busy = true
            scope.launch {
                note = runCatching {
                    Api.post("/new", "days" to days, "rated" to rated, "color" to color, "user" to user)
                }.fold({ if (user != null) "Challenge sent" else "Seek posted: you'll get a notification when it starts" }, { it.message })
                busy = false
            }
        }
        if (user == null) chip("Challenge a friend instead") { nav.go("friends") }
    }
}

// ------------------------------------------------------------------ friends & players

@Composable
fun FriendsScreen(nav: NavHostController) {
    val friends = load("friends") { Api.get("/friends") }
    Loaded(friends) { f ->
        val list = f.arr("friends")
        ListScreen {
            header("Following")
            if (list.isEmpty()) item {
                Text("You don't follow anyone yet. Follow opponents from their profile in a game's menu.",
                    color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
            }
            for (u in list) item {
                Chip(
                    onClick = { nav.go("player/${u.str("id")}") },
                    label = { Text(u.str("name") ?: "?", maxLines = 1) },
                    secondaryLabel = { Text(if (u.bool("playing")) "Playing" else if (u.bool("online")) "Online" else "Offline") },
                    icon = { Dot(if (u.bool("online")) Good else Color.DarkGray) },
                    colors = ChipDefaults.secondaryChipColors(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
fun PlayerScreen(nav: NavHostController, user: String) {
    val p = load("player/$user", live = true) { Api.get("/player/$user") }
    var override by remember { mutableStateOf<JsonObject?>(null) }
    val scope = rememberCoroutineScope()
    Loaded(p) { p0 ->
        val pl = override ?: p0
        ListScreen {
            header(pl.str("name") ?: user)
            item {
                Text(
                    listOfNotNull(if (pl.bool("online")) "Online" else "Offline", pl.int("corr")?.let { "corr $it" }).joinToString(" · "),
                    color = Muted, fontSize = 12.sp,
                )
            }
            pl.str("note")?.takeIf { it.isNotBlank() }?.let { n -> item { Text("“$n”", color = Accent, fontSize = 12.sp, textAlign = TextAlign.Center) } }
            chip("Challenge", primary = true) { nav.go("new?user=$user") }
            chip("Message") { nav.go("message/$user") }
            chip("Private note", "Only you see it") { nav.go("note/$user") }
            chip(if (pl.bool("following")) "Unfollow" else "Follow") {
                scope.launch { runCatching { Api.post("/follow/$user", "follow" to !pl.bool("following")) }.onSuccess { override = it } }
            }
        }
    }
}

@Composable
fun MessageScreen(nav: NavHostController, user: String) {
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    fun send(text: String) = scope.launch {
        note = runCatching { Api.post("/message/$user", "text" to text) }.fold({ "Sent: $text" }, { it.message })
    }
    val speak = rememberVoice("Message $user") { send(it) }
    ListScreen {
        header("Message $user")
        note?.let { item { Text(it, color = Accent, fontSize = 12.sp, textAlign = TextAlign.Center) } }
        chip("Speak a message", primary = true) { speak() }
        for (q in listOf("Want to play?", "Good game!", "Rematch?", "Thanks for the game")) chip(q) { send(q) }
        item { Text("Lichess doesn't let apps read your inbox, so replies show up on lichess.org.",
            color = Muted, fontSize = 10.sp, textAlign = TextAlign.Center) }
    }
}

@Composable
fun NoteScreen(nav: NavHostController, user: String) {
    val p = load("player/$user") { Api.get("/player/$user") }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<String?>(null) }
    val speak = rememberVoice("Note about $user") { text ->
        scope.launch {
            status = runCatching { Api.post("/note/$user", "text" to text) }.fold({ "Saved" }, { it.message })
            p.reload()
        }
    }
    Loaded(p) { pl ->
        ListScreen {
            header("Note on $user")
            item {
                Text(pl.str("note")?.takeIf { it.isNotBlank() } ?: "No note yet", color = if (pl.str("note").isNullOrBlank()) Muted else Color.White,
                    fontSize = 13.sp, textAlign = TextAlign.Center)
            }
            status?.let { item { Text(it, color = Accent, fontSize = 11.sp) } }
            chip("Speak a new note", primary = true) { speak() }
        }
    }
}

// ------------------------------------------------------------------ daily puzzle

@Composable
fun PuzzleScreen() {
    val pz = load("puzzle") { Api.get("/puzzle/daily") }
    Loaded(pz) { p -> PuzzleBoard(p) }
}

@Composable
private fun PuzzleBoard(p: JsonObject) {
    val steps = p.arr("steps")
    var idx by remember { mutableStateOf(0) }
    var lastMove by remember { mutableStateOf<String?>(null) }
    var selected by remember { mutableStateOf<String?>(null) }
    var message by remember { mutableStateOf("${p.str("color")!!.replaceFirstChar { it.uppercase() }} to move · ${p.int("rating")}") }
    var failed by remember { mutableStateOf(false) }
    var reported by remember { mutableStateOf(false) }
    var solved by remember { mutableStateOf(false) }
    var hintArrow by remember { mutableStateOf<Arrow?>(null) }
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    val step = steps[idx.coerceAtMost(steps.lastIndex)]
    val legal = remember(idx) { step.legalMap() }
    val targets = selected?.let { s -> legal[s]?.map { it.take(2) }?.toSet() } ?: emptySet()
    val fen = step.str("fen")!!

    fun report(win: Boolean) {
        if (reported) return
        reported = true
        scope.launch { runCatching { Api.post("/puzzle/${p.str("id")}/result", "win" to win) }.onSuccess { Summary.refresh(ctx) } }
    }

    fun play(uci: String) {
        val expect = step.str("expect")!!
        val ok = uci == expect || uci in step.strs("mates")
        selected = null
        if (!ok) {
            if (!failed) { failed = true; report(false) }
            message = "Not that one, try again"
            return
        }
        hintArrow = null
        val reply = step.str("reply")
        if (reply == null || idx + 1 > steps.lastIndex) {
            solved = true; lastMove = uci
            message = if (failed) "Solved (after a miss)" else "Solved!"
            report(!failed)
            return
        }
        idx += 1; lastMove = reply
        message = "Good. Keep going"
    }

    val boardSize = (LocalConfiguration.current.screenWidthDp * 0.72f).dp
    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Text("Daily puzzle", fontSize = 12.sp, color = Muted, modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
        ChessBoard(
            fen = fen, flipped = p.str("color") == "black", size = boardSize, theme = null,
            modifier = Modifier.align(Alignment.Center),
            lastMove = lastMove, check = step.str("check"), selected = selected, targets = targets,
            snapTo = if (solved) emptySet() else legal.keys + targets,
            arrows = listOfNotNull(hintArrow),
            onTap = if (solved) null else { sq ->
                val s = selected
                if (s != null && sq != null && sq in targets) {
                    val options = legal[s].orEmpty().filter { it.startsWith(sq) }
                    val expect = step.str("expect")!!
                    // Promotions: take the solution's piece if it's this square, otherwise a queen.
                    val suffix = when {
                        options.none { it.length == 3 } -> ""
                        expect.startsWith(s + sq) && expect.length == 5 -> expect.substring(4)
                        else -> "q"
                    }
                    play(s + sq + suffix)
                } else selected = if (sq != null && sq in legal.keys && sq != s) sq else null
            },
        )
        Column(Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(message, fontSize = 11.sp, color = if (solved) Good else if (failed) Accent else Muted)
            if (!solved) Button(
                onClick = {
                    val e = step.str("expect")!!
                    hintArrow = Arrow(e.take(2), e.substring(2, 4), Color(0xCC15781B))
                    if (!failed) { failed = true; report(false) }
                },
                colors = ButtonDefaults.secondaryButtonColors(), modifier = Modifier.size(26.dp),
            ) { Text("?", fontSize = 11.sp) }
        }
    }
}

// ------------------------------------------------------------------ finished games + Stockfish review

@Composable
fun FinishedScreen(nav: NavHostController) {
    val list = load("finished") { Api.get("/finished") }
    Loaded(list) { l ->
        val games = l.arr("games")
        ListScreen {
            header("Finished games")
            if (games.isEmpty()) item { Text("Nothing finished yet", color = Muted) }
            for (g in games) {
                val res = when (g.str("result")) { "win" -> "Won"; "loss" -> "Lost"; else -> "Draw" }
                chip("$res vs ${g.str("opponent")}", "${g.str("speed")}${if (g.bool("analysed")) " · reviewed" else ""}") {
                    nav.go("review/${g.str("id")}")
                }
            }
        }
    }
}

@Composable
fun ReviewScreen(nav: NavHostController, id: String) {
    var state by remember { mutableStateOf<JsonObject?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var nonce by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(id, nonce) {
        while (true) {
            val r = runCatching { Api.get("/analysis/$id") }.onFailure { error = it.message }.getOrNull() ?: break
            state = r; error = null
            if (r.str("state") != "running") break
            delay(2000)
        }
    }
    val s = state
    when {
        error != null && s == null -> Centered(error!!, Bad) { nonce++ }
        s == null -> Loading()
        s.str("state") == "none" || s.str("state") == "error" -> ListScreen {
            header("Stockfish review")
            if (s.str("state") == "error") item { Text(s.str("error") ?: "Failed", color = Bad, fontSize = 12.sp, textAlign = TextAlign.Center) }
            item { Text("Runs on the Latitude. Finished games only.", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center) }
            chip("Analyze with Stockfish", primary = true) {
                scope.launch {
                    runCatching { Api.post("/analyze/$id") }.onSuccess { nonce++ }.onFailure { error = it.message }
                }
            }
        }
        s.str("state") == "running" -> Centered("Stockfish is thinking… ${((s.str("progress")?.toFloatOrNull() ?: 0f) * 100).toInt()}%", Accent)
        else -> {
            val moments = s.arr("moments")
            ListScreen {
                header(when (s.str("result")) { "win" -> "Won"; "loss" -> "Lost"; else -> "Draw" } + " vs ${s.str("opponent")}")
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        s.int("accuracy")?.let { Text("Accuracy $it%", fontSize = 16.sp, color = Accent) }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("?! ${s.int("inaccuracy")}", fontSize = 12.sp, color = Color(0xFF56B4E9))
                            Text("? ${s.int("mistake")}", fontSize = 12.sp, color = Color(0xFFE69F00))
                            Text("?? ${s.int("blunder")}", fontSize = 12.sp, color = Bad)
                        }
                        s.int("acpl")?.let { Text("Avg. centipawn loss $it", fontSize = 10.sp, color = Muted) }
                    }
                }
                if (moments.isEmpty()) item { Text("No mistakes found. Clean game!", color = Good, fontSize = 12.sp) }
                moments.forEachIndexed { i, m ->
                    val mark = when (m.str("kind")) { "blunder" -> "??"; "mistake" -> "?"; else -> "?!" }
                    val dots = if (m.int("ply")!! % 2 == 0) "…" else "."
                    chip("${m.int("moveNo")}$dots ${m.str("san")}$mark", m.str("best")?.let { "Best was $it" }) { nav.go("moment/$id/$i") }
                }
            }
        }
    }
}

@Composable
fun MomentScreen(id: String, index: Int) {
    val a = load("analysis/$id") { Api.get("/analysis/$id") }
    Loaded(a) { s ->
        val m = s.arr("moments").getOrNull(index) ?: return@Loaded Centered("Not found")
        val boardSize = (LocalConfiguration.current.screenWidthDp * 0.72f).dp
        val played = m.str("played")!!; val best = m.str("bestUci")
        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Text("${m.int("moveNo")}. ${m.str("san")} → ${m.str("best") ?: "?"}", fontSize = 12.sp, color = Muted,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp))
            ChessBoard(
                fen = m.str("fen")!!, flipped = s.str("color") == "black", size = boardSize, theme = null,
                modifier = Modifier.align(Alignment.Center),
                arrows = listOfNotNull(
                    Arrow(played.take(2), played.substring(2, 4), Color(0xCCD32F2F)),
                    best?.let { Arrow(it.take(2), it.substring(2, 4), Color(0xCC15781B)) },
                ),
            )
            Text(
                "${fmtEval(m.int("evalBefore"))} → ${fmtEval(m.int("evalAfter"))}", fontSize = 11.sp, color = Muted,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
            )
        }
    }
}

/** Centipawns from your side to "+1.4" / "-3.0" / "mate". */
private fun fmtEval(cp: Int?): String = when {
    cp == null -> "?"
    cp >= 1000 -> "winning"
    cp <= -1000 -> "losing"
    else -> (if (cp >= 0) "+" else "") + "%.1f".format(cp / 100f)
}
