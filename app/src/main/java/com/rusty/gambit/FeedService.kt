package com.rusty.gambit

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.JsonObject

private const val CH_LINK = "link"
private const val CH_MOVES = "moves"
private const val CH_GAMES = "games"

/** Bumped on every event so open screens can refresh themselves. */
object Feed {
    val tick = MutableStateFlow(0)
}

/**
 * Foreground long-poll on the gambit server's /events feed. Same approach as Wrist's link: the
 * server holds each request ~25s, so the radio mostly sits idle on one open connection.
 */
class FeedService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CH_LINK, "Connection", NotificationManager.IMPORTANCE_MIN))
        nm.createNotificationChannel(NotificationChannel(CH_MOVES, "Opponent moves", NotificationManager.IMPORTANCE_HIGH)
            .apply { enableVibration(true) })
        nm.createNotificationChannel(NotificationChannel(CH_GAMES, "Challenges & results", NotificationManager.IMPORTANCE_DEFAULT))
        startForeground(
            1,
            NotificationCompat.Builder(this, CH_LINK)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Gambit")
                .setContentText("Watching your games")
                .setOngoing(true)
                .build(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
        )
        scope.launch { loop() }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_STICKY

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun loop() {
        var after = -1
        var failures = 0
        while (scope.isActive) {
            runCatching { Api.get("/events?after=$after&wait=25", readMs = 40_000) }
                .onSuccess { o ->
                    failures = 0
                    val latest = o.int("latest") ?: after
                    val items = o.arr("items")
                    if (after >= 0 && !o.bool("reset")) items.forEach { show(it) }
                    if (items.isNotEmpty()) { Feed.tick.value++; Summary.refresh(this@FeedService) }
                    after = latest
                }
                .onFailure {
                    failures++
                    delay(minOf(10_000L * failures, 120_000L)) // Latitude or Wi-Fi gone: back off
                }
        }
    }

    private fun show(e: JsonObject) {
        val game = e.str("game")
        val opp = e.str("opponent") ?: e.str("user") ?: "?"
        val (channel, title, text) = when (e.str("kind")) {
            "opponent_moved" -> Triple(CH_MOVES, "$opp played ${e.str("san")}", "Your move")
            "game_started" -> Triple(CH_GAMES, "New game vs $opp", if (e.bool("myTurn")) "Your move" else "Their move")
            "game_over" -> Triple(CH_GAMES, when (e.str("result")) {
                "win" -> "You beat $opp"; "loss" -> "You lost to $opp"; else -> "Draw with $opp"
            }, "Tap to review with Stockfish")
            "challenge" -> Triple(CH_GAMES, "$opp challenges you", e.int("days")?.let { "$it days per move" } ?: "Correspondence")
            "challenge_declined" -> Triple(CH_GAMES, "$opp declined", "Your challenge was declined")
            else -> return
        }
        val open = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra("route", when (e.str("kind")) {
                "opponent_moved", "game_started" -> "game/$game"
                "game_over" -> "review/$game"
                else -> "home"
            })
        val id = (game ?: e.str("id") ?: opp).hashCode()
        val pi = PendingIntent.getActivity(this, id, open, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        getSystemService(NotificationManager::class.java).notify(
            id,
            NotificationCompat.Builder(this, channel)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle(title)
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                .build(),
        )
    }

    companion object {
        fun start(c: Context) = c.startForegroundService(Intent(c, FeedService::class.java))

        fun clearFor(c: Context, game: String) = c.getSystemService(NotificationManager::class.java).cancel(game.hashCode())
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(c: Context, i: Intent) {
        runCatching { FeedService.start(c) }
    }
}
