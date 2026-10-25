package com.rusty.gambit

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.foundation.lazy.ScalingLazyColumn
import androidx.wear.compose.foundation.lazy.ScalingLazyListScope
import androidx.wear.compose.foundation.lazy.rememberScalingLazyListState
import androidx.wear.compose.material.Chip
import androidx.wear.compose.material.ChipDefaults
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.ListHeader
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.PositionIndicator
import androidx.wear.compose.material.Scaffold
import androidx.wear.compose.material.Text
import androidx.wear.compose.material.TimeText
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import kotlinx.serialization.json.JsonObject

class MainActivity : ComponentActivity() {
    private val pendingRoute = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        FeedService.start(this)
        pendingRoute.value = intent?.getStringExtra("route")
        setContent { App(pendingRoute.value) { pendingRoute.value = null } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        pendingRoute.value = intent.getStringExtra("route")
    }
}

val Accent = Color(0xFFD9A04A)
val Muted = Color(0xFFAAAAAA)
val Good = Color(0xFF7FC97F)
val Bad = Color(0xFFE57373)

@Composable
fun App(route: String?, consumed: () -> Unit) {
    val nav = rememberSwipeDismissableNavController()
    LaunchedEffect(route) {
        if (route != null && route != "home") nav.navigate(route) { launchSingleTop = true }
        if (route != null) consumed()
    }
    MaterialTheme {
        SwipeDismissableNavHost(nav, startDestination = "home") {
            composable("home") { HomeScreen(nav) }
            composable("game/{id}") { GameScreen(nav, it.arguments?.getString("id")!!) }
            composable("gamemenu/{id}") { GameMenuScreen(nav, it.arguments?.getString("id")!!) }
            composable("chat/{id}") { ChatScreen(it.arguments?.getString("id")!!) }
            composable("moves/{id}") { MovesScreen(it.arguments?.getString("id")!!) }
            composable("new?user={user}", arguments = listOf(navArgument("user") { type = NavType.StringType; nullable = true; defaultValue = null })) {
                NewGameScreen(nav, it.arguments?.getString("user"))
            }
            composable("friends") { FriendsScreen(nav) }
            composable("player/{user}") { PlayerScreen(nav, it.arguments?.getString("user")!!) }
            composable("message/{user}") { MessageScreen(nav, it.arguments?.getString("user")!!) }
            composable("note/{user}") { NoteScreen(nav, it.arguments?.getString("user")!!) }
            composable("puzzle") { PuzzleScreen() }
            composable("finished") { FinishedScreen(nav) }
            composable("review/{id}") { ReviewScreen(nav, it.arguments?.getString("id")!!) }
            composable("moment/{id}/{index}") {
                MomentScreen(it.arguments?.getString("id")!!, it.arguments?.getString("index")!!.toInt())
            }
        }
    }
}

/** A remembered, refreshable request. `reload()` re-runs it; Feed events re-run it automatically if [live]. */
class Loader<T>(val value: T?, val error: String?, val loading: Boolean, val reload: () -> Unit)

@Composable
fun <T> load(key: Any?, live: Boolean = false, fetch: suspend () -> T): Loader<T> {
    var value by remember(key) { mutableStateOf<T?>(null) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var loading by remember(key) { mutableStateOf(true) }
    var nonce by remember(key) { mutableStateOf(0) }
    val tick by Feed.tick.collectAsState()
    LaunchedEffect(key, nonce, if (live) tick else 0) {
        loading = true
        runCatching { fetch() }
            .onSuccess { value = it; error = null }
            .onFailure { error = it.message ?: "Something went wrong" }
        loading = false
    }
    return Loader(value, error, loading) { nonce++ }
}

/** Standard scrolling list screen with the time at the top. */
@Composable
fun ListScreen(content: ScalingLazyListScope.() -> Unit) {
    val state = rememberScalingLazyListState()
    Scaffold(timeText = { TimeText() }, positionIndicator = { PositionIndicator(state) }) {
        ScalingLazyColumn(Modifier.fillMaxSize(), state = state, content = content)
    }
}

@Composable
fun Centered(text: String, color: Color = Muted, onRetry: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(20.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text, color = color, textAlign = TextAlign.Center, fontSize = 13.sp)
            if (onRetry != null) Chip(onClick = onRetry, label = { Text("Retry") }, colors = ChipDefaults.secondaryChipColors())
        }
    }
}

@Composable
fun Loading() = Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }

/** Shows loading / error states, then [content] once there's a value. */
@Composable
fun <T> Loaded(l: Loader<T>, content: @Composable (T) -> Unit) {
    val v = l.value
    when {
        v != null -> content(v)
        l.error != null -> Centered(l.error, Bad, l.reload)
        else -> Loading()
    }
}

fun ScalingLazyListScope.header(text: String) = item { ListHeader { Text(text, color = Accent) } }

fun ScalingLazyListScope.chip(label: String, secondary: String? = null, primary: Boolean = false, onClick: () -> Unit) = item {
    Chip(
        onClick = onClick,
        label = { Text(label, maxLines = 1) },
        secondaryLabel = secondary?.let { { Text(it, maxLines = 1) } },
        colors = if (primary) ChipDefaults.primaryChipColors(backgroundColor = Accent, contentColor = Color.Black)
        else ChipDefaults.secondaryChipColors(),
        modifier = Modifier.fillMaxWidth(),
    )
}

fun opponentLine(o: JsonObject?): String {
    val name = o?.str("name") ?: "?"
    val r = o?.int("rating")
    return if (r != null) "$name ($r)" else name
}

fun NavHostController.go(route: String) = navigate(route) { launchSingleTop = true }
