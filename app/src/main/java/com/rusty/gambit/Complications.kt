package com.rusty.gambit

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import androidx.wear.watchface.complications.data.ComplicationData
import androidx.wear.watchface.complications.data.ComplicationText
import androidx.wear.watchface.complications.data.ComplicationType
import androidx.wear.watchface.complications.data.CountDownTimeReference
import androidx.wear.watchface.complications.data.LongTextComplicationData
import androidx.wear.watchface.complications.data.MonochromaticImage
import androidx.wear.watchface.complications.data.MonochromaticImageComplicationData
import androidx.wear.watchface.complications.data.NoDataComplicationData
import androidx.wear.watchface.complications.data.PlainComplicationText
import androidx.wear.watchface.complications.data.RangedValueComplicationData
import androidx.wear.watchface.complications.data.ShortTextComplicationData
import androidx.wear.watchface.complications.data.TimeDifferenceComplicationText
import androidx.wear.watchface.complications.data.TimeDifferenceStyle
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester
import androidx.wear.watchface.complications.datasource.ComplicationRequest
import androidx.wear.watchface.complications.datasource.SuspendingComplicationDataSourceService
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.time.Instant

/** One /summary fetch shared by all five complications (they're usually asked to update together). */
object Summary {
    private val lock = Mutex()
    private var cached: JsonObject? = null
    private var at = 0L

    suspend fun get(): JsonObject? = lock.withLock {
        if (cached == null || System.currentTimeMillis() - at > 15_000) {
            runCatching { Api.get("/summary", readMs = 10_000) }.onSuccess { cached = it; at = System.currentTimeMillis() }
        }
        cached
    }

    /** Ask every Gambit complication on the watch face to refresh (after an event, a move, a puzzle). */
    fun refresh(ctx: Context) {
        at = 0L
        for (cls in listOf(YourMoveComplication::class.java, UrgentGameComplication::class.java, ClockComplication::class.java,
            PuzzleComplication::class.java, RatingComplication::class.java)) {
            runCatching { ComplicationDataSourceUpdateRequester.create(ctx, ComponentName(ctx, cls)).requestUpdateAll() }
        }
    }
}

private fun openApp(ctx: Context, route: String): PendingIntent =
    PendingIntent.getActivity(
        ctx, route.hashCode(),
        Intent(ctx, MainActivity::class.java).putExtra("route", route)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

private fun text(t: String): ComplicationText = PlainComplicationText.Builder(t).build()

private fun countdown(to: Instant, template: String? = null): ComplicationText =
    TimeDifferenceComplicationText.Builder(TimeDifferenceStyle.SHORT_SINGLE_UNIT, CountDownTimeReference(to))
        .apply { template?.let { setText(it) } }
        .build()

/** Most urgent game where it's your move: id, opponent, deadline, and the per-move allowance in seconds. */
private class Urgent(val id: String, val opponent: String, val deadline: Instant?, val secondsLeft: Int?, val allowance: Int?)

private fun JsonObject.urgent(): Urgent? = obj("urgent")?.let {
    val left = it.int("secondsLeft")
    Urgent(it.str("id")!!, it.str("opponent") ?: "?", left?.let { s -> Instant.now().plusSeconds(s.toLong()) }, left,
        it.int("daysPerTurn")?.times(86_400))
}

abstract class GambitComplication : SuspendingComplicationDataSourceService() {
    protected val knight: MonochromaticImage get() = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_notif)).build()
    protected val puzzle: MonochromaticImage get() = MonochromaticImage.Builder(Icon.createWithResource(this, R.drawable.ic_puzzle)).build()

    abstract fun build(type: ComplicationType, s: JsonObject): ComplicationData

    /** What the editor shows while picking. */
    abstract fun preview(type: ComplicationType): ComplicationData?

    override fun getPreviewData(type: ComplicationType): ComplicationData? = preview(type)

    override suspend fun onComplicationRequest(request: ComplicationRequest): ComplicationData {
        val s = Summary.get()
            ?: return ShortTextComplicationData.Builder(text("--"), text("Latitude unreachable"))
                .setMonochromaticImage(knight).setTapAction(openApp(this, "home")).build()
        return build(request.complicationType, s)
    }

    protected fun short(t: ComplicationText, desc: String, route: String, image: MonochromaticImage? = knight, title: String? = null) =
        ShortTextComplicationData.Builder(t, text(desc))
            .setTapAction(openApp(this, route))
            .apply { image?.let { setMonochromaticImage(it) }; title?.let { setTitle(text(it)) } }
            .build()
}

/** 1. How many games are waiting on you; empty when none. */
class YourMoveComplication : GambitComplication() {
    override fun build(type: ComplicationType, s: JsonObject): ComplicationData {
        val n = s.int("myTurn") ?: 0
        if (n == 0) return NoDataComplicationData()
        return short(text("$n"), "$n game${if (n > 1) "s" else ""} waiting on your move", s.urgent()?.let { "game/${it.id}" } ?: "home")
    }

    override fun preview(type: ComplicationType) = short(text("2"), "2 games waiting on your move", "home")
}

/** 2. Opponent + time left in the game where your clock is lowest. */
class UrgentGameComplication : GambitComplication() {
    override fun build(type: ComplicationType, s: JsonObject): ComplicationData {
        val u = s.urgent()
        if (u == null) return when (type) {
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("No moves due"), text("No games waiting on you"))
                .setMonochromaticImage(knight).setTapAction(openApp(this, "home")).build()
            else -> short(text("–"), "No games waiting on you", "home")
        }
        val route = "game/${u.id}"
        return when (type) {
            ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(
                u.deadline?.let { countdown(it, "vs ${u.opponent} · ^1") } ?: text("vs ${u.opponent}"),
                text("Your move against ${u.opponent}"),
            ).setMonochromaticImage(knight).setTapAction(openApp(this, route)).build()
            else -> short(u.deadline?.let { countdown(it) } ?: text("Go"), "Your move against ${u.opponent}", route,
                image = null, title = u.opponent.take(7))
        }
    }

    override fun preview(type: ComplicationType): ComplicationData = when (type) {
        ComplicationType.LONG_TEXT -> LongTextComplicationData.Builder(text("vs Kong-E · 1d"), text("Preview"))
            .setMonochromaticImage(knight).build()
        else -> short(text("1d"), "Preview", "home", image = null, title = "Kong-E")
    }
}

/** 3. Ring that empties as your time in the most urgent game runs out. */
class ClockComplication : GambitComplication() {
    override fun build(type: ComplicationType, s: JsonObject): ComplicationData {
        val u = s.urgent()
        if (u?.deadline == null || u.secondsLeft == null) return NoDataComplicationData()
        val route = "game/${u.id}"
        val allowance = (u.allowance ?: u.secondsLeft).coerceAtLeast(u.secondsLeft).coerceAtLeast(1)
        return when (type) {
            ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(
                u.secondsLeft.toFloat(), 0f, allowance.toFloat(), text("Time left against ${u.opponent}"),
            ).setText(countdown(u.deadline)).setMonochromaticImage(knight).setTapAction(openApp(this, route)).build()
            else -> short(countdown(u.deadline), "Time left against ${u.opponent}", route)
        }
    }

    override fun preview(type: ComplicationType): ComplicationData = when (type) {
        ComplicationType.RANGED_VALUE -> RangedValueComplicationData.Builder(60f, 0f, 100f, text("Preview"))
            .setText(text("1d")).setMonochromaticImage(knight).build()
        else -> short(text("1d"), "Preview", "home")
    }
}

/** 4. Today's puzzle: a tick once solved. */
class PuzzleComplication : GambitComplication() {
    override fun build(type: ComplicationType, s: JsonObject): ComplicationData {
        val solved = s.bool("puzzleSolved")
        return when (type) {
            ComplicationType.MONOCHROMATIC_IMAGE ->
                MonochromaticImageComplicationData.Builder(puzzle, text(if (solved) "Daily puzzle solved" else "Daily puzzle"))
                    .setTapAction(openApp(this, "puzzle")).build()
            else -> short(text(if (solved) "✓" else "New"), if (solved) "Daily puzzle solved" else "Daily puzzle not solved yet",
                "puzzle", image = puzzle)
        }
    }

    override fun preview(type: ComplicationType): ComplicationData = when (type) {
        ComplicationType.MONOCHROMATIC_IMAGE -> MonochromaticImageComplicationData.Builder(puzzle, text("Preview")).build()
        else -> short(text("New"), "Preview", "puzzle", image = puzzle)
    }
}

/** 5. Correspondence rating ("1500?" while provisional). */
class RatingComplication : GambitComplication() {
    override fun build(type: ComplicationType, s: JsonObject): ComplicationData {
        val r = s.int("corr") ?: return short(text("--"), "No correspondence rating yet", "home")
        val shown = "$r${if (s.bool("corrProv")) "?" else ""}"
        return short(text(shown), "Correspondence rating $shown", "home")
    }

    override fun preview(type: ComplicationType) = short(text("1500"), "Preview", "home")
}
