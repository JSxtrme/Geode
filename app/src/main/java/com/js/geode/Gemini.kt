package com.js.geode

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.util.concurrent.TimeUnit

data class SummaryResult(
    val title: String,
    val category: String,
    val takeaways: List<String>,
    val summary: String
)

/**
 * Gemini client (BYOK — the user's own key, never shipped in the app).
 *
 * YouTube Shorts: the Gemini API accepts a YouTube URL as fileData, so the
 * video itself is understood, not just its title. Instagram / Facebook:
 * best-effort public video fetch (page HTML -> direct mp4 URL), download
 * to cache, upload via the Files API, then generateContent on the file.
 * Anything that cannot be fetched or read becomes an honest error —
 * takeaways are never invented.
 *
 * @param appContext only used for the hidden-WebView fallback (Instagram /
 *   Facebook when the raw fetch is blocked). Null means no WebView path —
 *   the caller (MainActivity, key test only) never needs it.
 */
class GeminiClient(private val appContext: Context? = null) {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private val jsonMedia = "application/json".toMediaType()

    // ---------------------------------------------------------------- test
    /** Real tiny call: proves the key works and the model is reachable. */
    suspend fun testKey(apiKey: String): Result<Unit> {
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply {
                put("parts", JSONArray().put(JSONObject().apply {
                    put("text", "Reply with the single word OK.")
                }))
            }))
            put("generationConfig", JSONObject().apply {
                put("maxOutputTokens", 8)
            })
        }
        return runCatching {
            // No video is involved here at all — a 404 from this call is a
            // model problem, never a "couldn't find this video" problem.
            var lastError: ApiException? = null
            for (model in MODELS) {
                try {
                    val reply = extractText(JSONObject(postGenerate(apiKey, model, body)))
                    if (reply.contains("OK", ignoreCase = true)) return@runCatching Unit
                    throw ApiException("Gemini replied, but not as expected. Try again.")
                } catch (e: ApiException) {
                    // Only a model being unavailable retries on the fallback.
                    // A bad/restricted key (400/401/403) fails fast, never
                    // retries another model.
                    if (isModelUnavailable(e) && model != MODELS.last()) {
                        lastError = e
                        continue
                    }
                    throw e
                }
            }
            throw lastError ?: ApiException("Gemini replied, but not as expected. Try again.")
        }.mapFailure()
    }

    // ------------------------------------------------------------ summarise
    suspend fun summarizeUrl(apiKey: String, url: String, source: Source): Result<SummaryResult> {
        return runCatching {
            when (source) {
                Source.YOUTUBE -> summarizeYouTube(apiKey, url)
                Source.INSTAGRAM, Source.FACEBOOK -> summarizeFetchedVideo(apiKey, url)
            }
        }.mapFailure()
    }

    private fun summarizeYouTube(apiKey: String, url: String): SummaryResult {
        // Gemini understands YouTube URLs passed as fileData.
        val filePart = JSONObject().apply {
            put("fileData", JSONObject().apply {
                put("mimeType", "video/mp4")
                put("fileUri", url)
            })
        }
        return generateStructured(apiKey, listOf(filePart), VIDEO_PROMPT)
            ?: throw ApiException(
                "Gemini couldn't read this video from its link. " +
                    "It may be private, age-restricted, or region-locked."
            )
    }

    private suspend fun summarizeFetchedVideo(apiKey: String, url: String): SummaryResult {
        val videoUrl = findDirectVideoUrl(url) ?: webViewVideoUrl(url)
            ?: throw ApiException(
                "Couldn't download this reel — the platform blocked the fetch, " +
                    "or the reel is private / needs login. Public YouTube Shorts " +
                    "work best; try one of those."
            )
        val local = downloadVideo(videoUrl)
            ?: throw ApiException("The video file couldn't be downloaded. Please try again.")
        try {
            val fileUri = uploadFile(apiKey, local)
            val filePart = JSONObject().apply {
                put("fileData", JSONObject().apply {
                    put("mimeType", "video/mp4")
                    put("fileUri", fileUri)
                })
            }
            return generateStructured(apiKey, listOf(filePart), VIDEO_PROMPT)
                ?: throw ApiException("Gemini couldn't read this video. Please try again.")
        } finally {
            local.delete()
        }
    }

    // ------------------------------------------------------- generateContent
    /**
     * POSTs a generateContent request to one model. Throws ApiException
     * carrying the HTTP code, so callers can tell a model being
     * unavailable (404/503 — retry on the fallback) from a bad key
     * (400/401/403 — fail fast, never retry).
     */
    private fun postGenerate(apiKey: String, model: String, body: JSONObject): String {
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/v1beta/models/$model:generateContent?key=$apiKey")
            .post(body.toString().toRequestBody(jsonMedia))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw ApiException(friendlyError(res.code, text), res.code)
            return text
        }
    }

    private fun isModelUnavailable(e: ApiException) = e.code == 404 || e.code == 503

    /** Returns null when the model cannot produce a usable structured card. */
    private fun generateStructured(
        apiKey: String,
        extraParts: List<JSONObject>,
        prompt: String
    ): SummaryResult? {
        val parts = JSONArray()
        extraParts.forEach { parts.put(it) }
        parts.put(JSONObject().apply { put("text", prompt) })
        val body = JSONObject().apply {
            put("contents", JSONArray().put(JSONObject().apply { put("parts", parts) }))
            put("generationConfig", JSONObject().apply {
                put("responseMimeType", "application/json")
                put("temperature", 0.2)
                put("maxOutputTokens", 2048)
            })
        }
        var lastError: ApiException? = null
        for (model in MODELS) {
            try {
                val text = postGenerate(apiKey, model, body)
                val raw = extractText(JSONObject(text))
                val json = parseStructuredJson(raw) ?: return null
                val takeaways = json.optJSONArray("key_takeaways")?.let { arr ->
                    List(arr.length()) { i -> arr.optString(i) }.filter { it.isNotBlank() }
                } ?: emptyList()
                val title = json.optString("title").trim()
                val summary = json.optString("summary").trim()
                if (title.isBlank() || summary.isBlank()) return null
                if (takeaways.isEmpty()) return null // honest: no content, no card
                return SummaryResult(
                    title = title,
                    category = Category.fromLabel(json.optString("category")).label,
                    takeaways = takeaways.take(5),
                    summary = summary
                )
            } catch (e: ApiException) {
                if (isModelUnavailable(e) && model != MODELS.last()) {
                    lastError = e
                    continue
                }
                throw e
            }
        }
        // Unreachable in practice (the loop always throws or returns), but
        // keeps the compiler honest if MODELS is ever emptied.
        throw lastError ?: ApiException("Gemini replied, but not as expected. Try again.")
    }

    private fun parseStructuredJson(raw: String): JSONObject? {
        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return try {
            JSONObject(cleaned)
        } catch (e: Exception) {
            // Last resort: first {...} block in the reply.
            val start = cleaned.indexOf('{')
            val end = cleaned.lastIndexOf('}')
            if (start >= 0 && end > start) {
                try { JSONObject(cleaned.substring(start, end + 1)) } catch (e2: Exception) { null }
            } else null
        }
    }

    private fun extractText(response: JSONObject): String {
        val candidates = response.optJSONArray("candidates") ?: return ""
        if (candidates.length() == 0) return ""
        val parts = candidates.optJSONObject(0)
            ?.optJSONObject("content")?.optJSONArray("parts") ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) sb.append(parts.optJSONObject(i)?.optString("text").orEmpty())
        return sb.toString()
    }

    // ------------------------------------------------------------ video fetch
    /**
     * Hidden-WebView fallback for Instagram / Facebook, used ONLY when the
     * raw fetchHtml path returns nothing (the platform blocks anonymous
     * raw fetches most of the time). No login, no credentials — the
     * WebView just reads the public page like a browser. A confirmed
     * login wall / private reel throws a DISTINCT private ApiException
     * (isPrivate = true) so the service can say so plainly, instead of
     * lumping it in with generic failures. Returns null when the WebView
     * also finds nothing (generic failure — never labelled private).
     */
    private suspend fun webViewVideoUrl(pageUrl: String): String? {
        val context = appContext ?: return null
        return when (val outcome = WebViewExtractor.extractVideoUrl(context, pageUrl)) {
            is WebViewOutcome.Found -> outcome.videoUrl
            WebViewOutcome.Private -> throw ApiException(PRIVATE_REEL_MESSAGE, isPrivate = true)
            WebViewOutcome.NotFound -> null
        }
    }

    private fun findDirectVideoUrl(pageUrl: String): String? {
        val html = fetchHtml(pageUrl) ?: return null
        val candidates = listOf(
            Regex("""<meta[^>]+property=["']og:video:secure_url["'][^>]+content=["']([^"']+)["']"""),
            Regex("""<meta[^>]+content=["']([^"']+)["'][^>]+property=["']og:video:secure_url["']"""),
            Regex("""<meta[^>]+property=["']og:video["'][^>]+content=["']([^"']+)["']"""),
            Regex(""""video_url"\s*:\s*"([^"]+)""""),
            Regex(""""playable_url"\s*:\s*"([^"]+)""""),
            Regex(""""videoUrl"\s*:\s*"([^"]+)"""")
        )
        for (regex in candidates) {
            val match = regex.find(html)?.groupValues?.getOrNull(1) ?: continue
            val decoded = match
                .replace("\\u0026", "&")
                .replace("\\/", "/")
                .let { runCatching { URLDecoder.decode(it, "UTF-8") }.getOrDefault(it) }
            if (decoded.startsWith("http") && (decoded.contains(".mp4") || decoded.contains("video"))) {
                return decoded
            }
        }
        return null
    }

    private fun fetchHtml(pageUrl: String): String? {
        return try {
            val req = Request.Builder()
                .url(pageUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                )
                .header("Accept", "text/html,application/xhtml+xml")
                .header("Accept-Language", "en-US,en;q=0.9")
                .build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) null else res.body?.string()
            }
        } catch (e: Exception) {
            null
        }
    }

    /** Downloads to a temp file, capped at 80 MB. Returns null on failure. */
    private fun downloadVideo(videoUrl: String): File? {
        return try {
            val req = Request.Builder()
                .url(videoUrl)
                .header(
                    "User-Agent",
                    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
                        "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
                )
                .build()
            http.newCall(req).execute().use { res ->
                if (!res.isSuccessful) return null
                val file = File.createTempFile("geode-video", ".mp4")
                var written = 0L
                res.body?.byteStream()?.use { input ->
                    file.outputStream().use { output ->
                        val buffer = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            written += n
                            if (written > 80L * 1024 * 1024) return null.also { file.delete() }
                            output.write(buffer, 0, n)
                        }
                    }
                }
                if (written < 10_000) {
                    file.delete()
                    null
                } else file
            }
        } catch (e: Exception) {
            null
        }
    }

    // ------------------------------------------------------------- Files API
    private fun uploadFile(apiKey: String, file: File): String {
        val boundary = "geode-${System.currentTimeMillis()}"
        val header = (
            "--$boundary\r\n" +
                "Content-Type: application/json; charset=utf-8\r\n\r\n" +
                "{\"file\":{\"displayName\":\"reel.mp4\"}}\r\n" +
                "--$boundary\r\n" +
                "Content-Type: video/mp4\r\n\r\n"
            ).toByteArray(Charsets.UTF_8)
        val footer = "\r\n--$boundary--\r\n".toByteArray(Charsets.UTF_8)
        val bodyBytes = header + file.readBytes() + footer
        val req = Request.Builder()
            .url("https://generativelanguage.googleapis.com/upload/v1beta/files?key=$apiKey")
            .header("X-Goog-Upload-Protocol", "multipart")
            .post(bodyBytes.toRequestBody("multipart/related; boundary=$boundary".toMediaType()))
            .build()
        http.newCall(req).execute().use { res ->
            val text = res.body?.string().orEmpty()
            if (!res.isSuccessful) throw ApiException(friendlyError(res.code, text), res.code)
            val fileObj = JSONObject(text).optJSONObject("file")
                ?: throw ApiException("Upload finished, but Google didn't return a file. Try again.")
            val name = fileObj.optString("name")
            val uri = fileObj.optString("uri")
            if (uri.isBlank()) throw ApiException("Upload finished, but Google didn't return a file. Try again.")
            waitForFileActive(apiKey, name)
            return uri
        }
    }

    private fun waitForFileActive(apiKey: String, name: String) {
        repeat(15) {
            Thread.sleep(2000)
            try {
                val req = Request.Builder()
                    .url("https://generativelanguage.googleapis.com/v1beta/$name?key=$apiKey")
                    .get()
                    .build()
                http.newCall(req).execute().use { res ->
                    if (res.isSuccessful) {
                        val state = JSONObject(res.body?.string().orEmpty()).optString("state")
                        if (state == "ACTIVE") return
                        if (state == "FAILED") throw ApiException("Google couldn't process this video. Try another reel.")
                    }
                }
            } catch (e: ApiException) {
                throw e
            } catch (e: Exception) {
                // Transient poll failure; keep waiting.
            }
        }
        throw ApiException("Google is taking too long to process this video. Please try again.")
    }

    // ---------------------------------------------------------------- errors
    /**
     * Turns a Google HTTP error into a message the user can act on.
     * Google's own error text is shown whenever it exists ("Google: ...")
     * — it is never replaced by a canned line about a video. The old
     * 404 branch did exactly that, which is how a text-only key test
     * ended up saying "couldn't find this video". A video-specific hint
     * only ever comes from generateStructured() returning null (the
     * model genuinely couldn't read the video), never from an HTTP error.
     */
    private fun friendlyError(code: Int, body: String): String {
        val detail = runCatching {
            JSONObject(body).optJSONObject("error")?.optString("message")
        }.getOrNull().orEmpty()
        val googleSays = detail.ifBlank { null }?.let { "Google: $it" }
        return when (code) {
            400 -> if (detail.contains("API_KEY_INVALID", ignoreCase = true) ||
                detail.contains("API key not valid", ignoreCase = true)
            ) {
                "That key doesn't look right. Check you copied the whole key from AI Studio."
            } else {
                googleSays ?: "Google couldn't understand that request. Please try again."
            }
            401, 403 -> "Google rejected this key. It may be restricted or deleted — check it in AI Studio."
            404 -> googleSays
                ?: "Google couldn't find that model. It may no longer be available for new keys — try again, or check in AI Studio."
            429 -> "You've hit Google's free-tier limit for now. Wait a little and try again."
            in 500..599 -> googleSays ?: "Google's service had a problem. Please try again in a moment."
            else -> googleSays ?: "Something went wrong (error $code). Please try again."
        }
    }

    private fun <T> Result<T>.mapFailure(): Result<T> = recoverCatching { e ->
        if (e is ApiException) throw e
        val msg = e.message.orEmpty()
        if (msg.contains("Unable to resolve host", ignoreCase = true) ||
            msg.contains("Failed to connect", ignoreCase = true) ||
            msg.contains("timeout", ignoreCase = true) ||
            msg.contains("SocketTimeout", ignoreCase = true)
        ) {
            throw ApiException("Couldn't reach Google. Check your connection and try again.")
        }
        throw ApiException("Something went wrong. Please try again.")
    }

    class ApiException(message: String, val code: Int? = null, val isPrivate: Boolean = false) : Exception(message)

    companion object {
        /**
         * Models, in try order. gemini-2.5-flash is gone: Google limited
         * the 2.5 family to projects that "have actively used them in the
         * past" (release notes 18 Sep 2026), so a brand-new key/project
         * gets HTTP 404 on it. Primary is gemini-3.5-flash-lite, Google's
         * named equivalent; fallback is gemini-3.8-flash, tried once and
         * only when the primary comes back 404/503 (model unavailable).
         * A bad key (400/401/403) never retries — it fails fast.
         */
        const val MODEL_PRIMARY = "gemini-3.5-flash-lite"
        const val MODEL_FALLBACK = "gemini-3.8-flash"
        private val MODELS = listOf(MODEL_PRIMARY, MODEL_FALLBACK)

        /** Shown (notification + error card) only for a CONFIRMED private reel. */
        const val PRIVATE_REEL_MESSAGE =
            "This reel is private — Geode couldn't see it or reach it. " +
                "Only public reels can be summarised."

        const val VIDEO_PROMPT = "Watch this short video and summarise it in English, " +
            "even if the spoken audio is in another language (for example Hindi). " +
            "Return ONLY a JSON object with these keys: " +
            "\"title\" (a short title), " +
            "\"category\" (exactly one of: Tutorial, Recipe, Travel, Study, Tech, Other), " +
            "\"key_takeaways\" (an array of 4 to 5 short factual strings, each one concrete detail from the video), " +
            "\"summary\" (2 to 3 sentences). " +
            "Do not include promotions, calls to action, sponsor messages, or links from the creator as takeaways. " +
            "If the video contains no useful information, return an empty key_takeaways array."
    }
}
