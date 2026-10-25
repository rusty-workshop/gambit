package com.rusty.gambit

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL

class ApiException(msg: String) : Exception(msg)

/**
 * The gambit server on the Latitude. LAN first with a short timeout (fast at home), then the
 * Funnel address if one is configured. The Lichess token lives on the server, never here.
 */
object Api {
    private val bases get() = listOf(BuildConfig.SERVER_LAN to 2000, BuildConfig.SERVER_FUNNEL to 7000).filter { it.first.isNotBlank() }

    private fun call(method: String, url: String, body: String?, connectMs: Int, readMs: Int): Pair<Int, String> {
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method
            conn.connectTimeout = connectMs
            conn.readTimeout = readMs
            conn.setRequestProperty("Authorization", "Bearer ${BuildConfig.SERVER_KEY}")
            if (body != null) {
                conn.doOutput = true
                conn.setRequestProperty("Content-Type", "application/json")
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val code = conn.responseCode
            val stream = if (code in 200..399) conn.inputStream else conn.errorStream
            return code to (stream?.bufferedReader()?.use { it.readText() } ?: "")
        } finally {
            conn.disconnect()
        }
    }

    suspend fun request(method: String, path: String, body: JsonObject? = null, readMs: Int = 20_000): JsonObject =
        withContext(Dispatchers.IO) {
            var last: Exception = ApiException("server not configured")
            for ((base, connectMs) in bases) {
                try {
                    val (code, text) = call(method, base + path, body?.toString(), connectMs, readMs)
                    val obj = runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrElse { JsonObject(emptyMap()) }
                    // Real answers (incl. 4xx from Lichess) are final; only retry the other address on 5xx/network.
                    if (code in 200..299) return@withContext obj
                    val err = obj.str("error") ?: "HTTP $code"
                    if (code < 500 || code == 502) throw ApiException(err)
                    last = ApiException(err)
                } catch (e: ApiException) {
                    throw e
                } catch (e: Exception) {
                    last = e
                }
            }
            throw if (last is ApiException) last else ApiException("Can't reach the Latitude")
        }

    suspend fun get(path: String, readMs: Int = 20_000) = request("GET", path, readMs = readMs)
    suspend fun post(path: String, vararg fields: Pair<String, Any?>) = request("POST", path, obj(*fields))
}

fun obj(vararg fields: Pair<String, Any?>): JsonObject = buildJsonObject {
    for ((k, v) in fields) put(k, when (v) {
        null -> JsonNull
        is String -> JsonPrimitive(v)
        is Number -> JsonPrimitive(v)
        is Boolean -> JsonPrimitive(v)
        is JsonElement -> v
        else -> JsonPrimitive(v.toString())
    })
}

fun JsonObject.str(k: String): String? = (this[k] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.content
fun JsonObject.int(k: String): Int? = (this[k] as? JsonPrimitive)?.intOrNull
fun JsonObject.long(k: String): Long? = (this[k] as? JsonPrimitive)?.longOrNull
fun JsonObject.bool(k: String): Boolean = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
fun JsonObject.obj(k: String): JsonObject? = this[k] as? JsonObject
fun JsonObject.arr(k: String): List<JsonObject> = (this[k] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
fun JsonObject.strs(k: String): List<String> = (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

/** Lichess-style time left: "3d", "5h", "12m". */
fun timeLeft(seconds: Int?): String? = when {
    seconds == null -> null
    seconds >= 86_400 -> "${seconds / 86_400}d ${seconds % 86_400 / 3600}h"
    seconds >= 3600 -> "${seconds / 3600}h ${seconds % 3600 / 60}m"
    else -> "${seconds / 60}m"
}
