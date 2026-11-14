package com.js.geode

import android.content.Context
import android.content.Intent
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** URL gate, in code (not the manifest) — the manifest accepts any shared
 * text, so unsupported shares get a clear message instead of vanishing.
 * Unlike Clera's shipped build (Instagram-only), Geode accepts
 * Instagram, Facebook, and YouTube Shorts from day one. */
object ShareGate {
    private val patterns = listOf(
        Regex("""https?://(?:www\.|m\.)?instagram\.com/(?:reel|reels|p|share/reel)/[A-Za-z0-9_\-]+"""),
        Regex("""https?://(?:www\.|m\.|web\.)?facebook\.com/(?:reel|share)/[A-Za-z0-9_\-/?=&.%]+"""),
        Regex("""https?://(?:www\.|m\.)?youtube\.com/shorts/[A-Za-z0-9_\-]+"""),
        Regex("""https?://youtu\.be/[A-Za-z0-9_\-]+""")
    )

    data class Match(val url: String, val source: Source)

    fun firstMatch(text: String): Match? {
        for ((index, regex) in patterns.withIndex()) {
            val found = regex.find(text)?.value ?: continue
            val source = when (index) {
                0 -> Source.INSTAGRAM
                1 -> Source.FACEBOOK
                else -> Source.YOUTUBE
            }
            return Match(found, source)
        }
        return null
    }
}

/** Durable pending queue (a plain JSON file), shared by the receiver and
 * the processing service. The toast fires before any network call. */
object PendingQueue {
    private const val FILE_NAME = "pending_reel_shares.json"

    fun append(context: Context, url: String, source: Source) {
        val file = File(context.filesDir, FILE_NAME)
        val arr = readAll(context)
        arr.put(JSONObject().apply {
            put("url", url)
            put("source", source.name)
            put("ts", System.currentTimeMillis() / 1000)
        })
        file.writeText(arr.toString())
    }

    fun readAll(context: Context): JSONArray {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return JSONArray()
        return runCatching { JSONArray(file.readText()) }.getOrDefault(JSONArray())
    }

    fun remove(context: Context, url: String) {
        val file = File(context.filesDir, FILE_NAME)
        val kept = JSONArray()
        val arr = readAll(context)
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            if (item.optString("url") != url) kept.put(item)
        }
        file.writeText(kept.toString())
    }
}

/**
 * Invisible share target. Toast first, queue the URL, start the processing
 * service, finish immediately — the user is back in Instagram within a tap.
 */
class ShareReceiverActivity : android.app.Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        handle(intent)
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        intent?.let { handle(it) }
    }

    private fun handle(intent: Intent) {
        val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
        val match = ShareGate.firstMatch(text)
        if (match == null) {
            Toast.makeText(
                this,
                "Geode can summarise Instagram Reels, Facebook Reels and YouTube Shorts.",
                Toast.LENGTH_LONG
            ).show()
            finish()
            return
        }
        PendingQueue.append(this, match.url, match.source)
        Toast.makeText(this, "Reel sent to Geode", Toast.LENGTH_SHORT).show()
        SummaryService.start(this, match.url, match.source)
        finish()
    }
}
