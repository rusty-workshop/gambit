package com.rusty.gambit

import android.app.Activity
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.wear.compose.foundation.lazy.items
import androidx.wear.compose.material.Button
import androidx.wear.compose.material.ButtonDefaults
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.dialog.Alert
import androidx.wear.compose.material.dialog.Dialog
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject

// ------------------------------------------------------------------ home

@Composable
fun HomeScreen(nav: NavHostController) {
    val home = load("home", live = true) { Api.get("/home") }
    val scope = rememberCoroutineScope()
    Loaded(home) { h ->
        LaunchedEffect(h) { BoardTheme.name = h.str("theme") }
        val games = h.arr("games")
        val incoming = h.arr("challengesIn")
        val outgoing = h.arr("challengesOut")
        ListScreen {
            item {
                val me = h.obj("me")
                Text("Gambit · ${me?.str("name")}", color = Muted, fontSize = 12.sp, modifier = Modifier.padding(top = 8.dp))
            }
            if (games.isEmpty()) item {
                Text("No correspondence games yet", color = Muted, fontSize = 13.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(vertical = 6.dp))
            }
            for (g in games) {
                val mine = g.bool("myTurn")
                chip(
                    opponentLine(g.obj("opponent")),
                    (if (mine) "Your move" else "Their move") + (timeLeft(g.int("secondsLeft"))?.let { " · $it" } ?: ""),
                    primary = mine,
                ) { nav.go("game/${g.str("id")}") }
            }
            if (incoming.isNotEmpty()) header("Challenges")
            for (c in incoming) item { ChallengeRow(c) { accept ->
                scope.launch {
                    runCatching { Api.post("/challenge/${c.str("id")}/${if (accept) "accept" else "decline"}") }
                    home.reload()
                }
            } }
            for (c in outgoing) chip("Waiting: ${c.str("user")}", "${c.int("days")}d per move · tap to cancel") {
                scope.launch { runCatching { Api.post("/challenge/${c.str("id")}/cancel") }; home.reload() }
            }
            chip("New game", "Friend or open seek", primary = games.isEmpty()) { nav.go("new") }
            chip("Daily puzzle", h.obj("me")?.int("puzzle")?.let { "Puzzle rating $it" }) { nav.go("puzzle") }
            chip("Friends") { nav.go("friends") }
            chip("Finished games", "Stockfish review") { nav.go("finished") }
        }
    }
}

@Composable
private fun ChallengeRow(c: JsonObject, onAnswer: (Boolean) -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text("${c.str("user")}${c.int("rating")?.let { " ($it)" } ?: ""}", fontSize = 14.sp)
        Text("${c.int("days")}d/move · ${if (c.bool("rated")) "rated" else "casual"}", color = Muted, fontSize = 11.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp)) {
            Button(onClick = { onAnswer(false) }, colors = ButtonDefaults.secondaryButtonColors(), modifier = Modifier.size(40.dp)) { Text("✕") }
            Button(onClick = { onAnswer(true) }, colors = ButtonDefaults.primaryButtonColors(backgroundColor = Good),
                modifier = Modifier.size(40.dp)) { Text("✓", color = Color.Black) }
        }
    }
}

// ------------------------------------------------------------------ game

@Composable
fun GameScreen(nav: NavHostController, id: String) {
    val ctx = LocalContext.current
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(id) { FeedService.clearFor(ctx, id) }
    val game = load("game/$id", live = true) { Api.get("/game/$id") }
    var override by remember(id) { mutableStateOf<JsonObject?>(null) }
    var selected by remember(id) { mutableStateOf<String?>(null) }
    var pending by remember(id) { mutableStateOf<String?>(null) }
    var promo by remember(id) { mutableStateOf<String?>(null) } // from+to waiting for a piece choice
    var sending by remember(id) { mutableStateOf(false) }
    var error by remember(id) { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(game.value) { override = null; selected = null }

    Loaded(game) { g0 ->
        val g = override ?: g0
        val fen = g.str("fen")!!
        val legal = remember(g) {
            g.obj("legal")?.mapValues { (_, v) -> (v as kotlinx.serialization.json.JsonArray).map { it.toString().trim('"') } } ?: emptyMap()
        }
        val myTurn = g.bool("myTurn") && !sending
        val targets = selected?.let { s -> legal[s]?.map { it.take(2) }?.toSet() } ?: emptySet()
        val boardSize = (LocalConfiguration.current.screenWidthDp * 0.72f).dp

        Box(Modifier.fillMaxSize().background(Color.Black)) {
            Text(
                opponentLine(g.obj("opponent")), fontSize = 12.sp, color = Muted, maxLines = 1,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 14.dp),
            )
            ChessBoard(
                fen = fen, flipped = g.str("color") == "black", size = boardSize, theme = null,
                modifier = Modifier.align(Alignment.Center),
                lastMove = g.str("lastMove"), check = g.str("check"),
                selected = selected, targets = targets,
                snapTo = if (!myTurn) emptySet() else legal.keys + targets,
                onTap = if (!myTurn) null else { sq ->
                    val s = selected
                    if (s != null && sq != null && sq in targets) {
                        val options = legal[s].orEmpty().filter { it.startsWith(sq) }
                        if (options.any { it.length == 3 }) promo = s + sq else pending = s + sq
                    } else {
                        selected = if (sq != null && sq in legal.keys && sq != s) sq else null
                    }
                },
            )
            Column(
                Modifier.align(Alignment.BottomCenter).padding(bottom = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                val status = when {
                    error != null -> error!!
                    sending -> "Sending…"
                    g.str("status") != "started" -> "Game over (${g.str("status")})"
                    g.bool("drawOffered") -> "Draw offered · see menu"
                    g.bool("myTurn") -> "Your move" + (timeLeft(g.int("secondsLeft"))?.let { " · $it" } ?: "")
                    else -> "Their move"
                }
                Text(status, fontSize = 11.sp, color = if (error != null) Bad else if (g.bool("myTurn")) Accent else Muted)
                Button(
                    onClick = { nav.go("gamemenu/$id") },
                    colors = ButtonDefaults.secondaryButtonColors(),
                    modifier = Modifier.size(26.dp),
                ) { Text("•••", fontSize = 10.sp) }
            }
        }

        promo?.let { base ->
            Dialog(showDialog = true, onDismissRequest = { promo = null }) {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Promote to", color = Muted, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                        for ((c, label) in listOf('q' to "Q", 'r' to "R", 'b' to "B", 'n' to "N")) {
                            Button(onClick = { promo = null; pending = base + c }, modifier = Modifier.size(40.dp)) { Text(label) }
                        }
                    }
                }
            }
        }

        pending?.let { uci ->
            Dialog(showDialog = true, onDismissRequest = { pending = null }) {
                Alert(
                    title = { Text(describeMove(fen, uci), textAlign = TextAlign.Center) },
                    negativeButton = {
                        Button(onClick = { pending = null }, colors = ButtonDefaults.secondaryButtonColors()) { Text("✕") }
                    },
                    positiveButton = {
                        Button(onClick = {
                            pending = null; selected = null; sending = true; error = null
                            scope.launch {
                                runCatching { Api.post("/game/$id/move", "uci" to uci) }
                                    .onSuccess { override = it; haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                                    .onFailure { error = it.message }
                                sending = false
                            }
                        }, colors = ButtonDefaults.primaryButtonColors(backgroundColor = Good)) { Text("✓", color = Color.Black) }
                    },
                ) { Text("Send this move?", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center) }
            }
        }
    }
}

@Composable
fun GameMenuScreen(nav: NavHostController, id: String) {
    val game = load("game/$id") { Api.get("/game/$id") }
    val scope = rememberCoroutineScope()
    var confirmResign by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    Loaded(game) { g ->
        val opp = g.obj("opponent")
        val started = g.str("status") == "started"
        fun act(path: String, vararg f: Pair<String, Any?>, done: String) = scope.launch {
            note = runCatching { Api.post(path, *f) }.fold({ done }, { it.message })
            game.reload()
        }
        ListScreen {
            header("vs ${opp?.str("name")}")
            note?.let { item { Text(it, color = Accent, fontSize = 12.sp, textAlign = TextAlign.Center) } }
            chip("Chat") { nav.go("chat/$id") }
            chip("Moves", "${g.strs("sans").size} played") { nav.go("moves/$id") }
            if (started) {
                when {
                    g.bool("drawOffered") -> {
                        chip("Accept draw", primary = true) { act("/game/$id/draw", "accept" to true, done = "Draw accepted") }
                        chip("Decline draw") { act("/game/$id/draw", "accept" to false, done = "Declined") }
                    }
                    g.bool("iOfferedDraw") -> item { Text("You offered a draw", color = Muted, fontSize = 12.sp) }
                    else -> chip("Offer draw") { act("/game/$id/draw", "accept" to true, done = "Draw offered") }
                }
                chip("Resign") { confirmResign = true }
            } else {
                chip("Stockfish review", primary = true) { nav.go("review/$id") }
            }
            opp?.str("id")?.let { u -> chip("${opp.str("name")}'s profile", "Follow, message, note") { nav.go("player/$u") } }
        }
    }
    if (confirmResign) Dialog(showDialog = true, onDismissRequest = { confirmResign = false }) {
        Alert(
            title = { Text("Resign this game?", textAlign = TextAlign.Center) },
            negativeButton = { Button(onClick = { confirmResign = false }, colors = ButtonDefaults.secondaryButtonColors()) { Text("✕") } },
            positiveButton = {
                Button(onClick = {
                    confirmResign = false
                    scope.launch { runCatching { Api.post("/game/$id/resign") }.onSuccess { nav.popBackStack("home", false) } }
                }, colors = ButtonDefaults.primaryButtonColors(backgroundColor = Bad)) { Text("✓", color = Color.Black) }
            },
        )
    }
}

@Composable
fun MovesScreen(id: String) {
    val game = load("game/$id") { Api.get("/game/$id") }
    Loaded(game) { g ->
        val sans = g.strs("sans")
        ListScreen {
            header("Moves")
            if (sans.isEmpty()) item { Text("No moves yet", color = Muted) }
            items(sans.chunked(2).withIndex().toList().reversed()) { (i, pair) ->
                Text("${i + 1}. ${pair[0]}  ${pair.getOrElse(1) { "" }}", fontSize = 14.sp)
            }
        }
    }
}

// ------------------------------------------------------------------ chat & voice

private val QUICK = listOf("Good luck!", "Have fun", "Thanks!", "Nice move", "Sorry, slow move", "gg")

/** Launches the system speech recognizer; calls back with the text (if any). */
@Composable
fun rememberVoice(prompt: String, onText: (String) -> Unit): () -> Unit {
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        if (r.resultCode == Activity.RESULT_OK)
            r.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()?.takeIf { it.isNotBlank() }?.let(onText)
    }
    return {
        runCatching {
            launcher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PROMPT, prompt))
        }
    }
}

@Composable
fun ChatScreen(id: String) {
    val chat = load("chat/$id", live = true) { Api.get("/game/$id/chat") }
    val scope = rememberCoroutineScope()
    var note by remember { mutableStateOf<String?>(null) }
    fun send(text: String) = scope.launch {
        note = runCatching { Api.post("/game/$id/chat", "text" to text) }.fold({ null }, { it.message })
        chat.reload()
    }
    val speak = rememberVoice("Say a message") { send(it) }
    Loaded(chat) { c ->
        val lines = c.arr("lines")
        ListScreen {
            header("Chat")
            if (lines.isEmpty()) item { Text("No messages", color = Muted, fontSize = 12.sp) }
            items(lines.takeLast(12)) { l ->
                Text(
                    (if (l.bool("mine")) "" else "${l.str("user")}: ") + l.str("text"),
                    color = if (l.bool("mine")) Accent else Color.White, fontSize = 13.sp,
                    textAlign = if (l.bool("mine")) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
                )
            }
            note?.let { item { Text(it, color = Bad, fontSize = 11.sp) } }
            chip("Speak a message", primary = true) { speak() }
            for (q in QUICK) chip(q) { send(q) }
        }
    }
}

/** Small colored dot used in lists. */
@Composable
fun Dot(color: Color) = Box(Modifier.size(8.dp).background(color, CircleShape))
