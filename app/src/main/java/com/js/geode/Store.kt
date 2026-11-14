package com.js.geode

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray

private const val DB_NAME = "geode.db"
private const val DB_VERSION = 1

/** Plain SQLite (no Room dependency — the VM build cache has no Room
 * artifacts, and a single table does not need an ORM). */
class DbHelper(context: Context) : SQLiteOpenHelper(context, DB_NAME, null, DB_VERSION) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL(
            """CREATE TABLE summaries (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                title TEXT NOT NULL,
                category TEXT NOT NULL,
                source TEXT NOT NULL,
                source_url TEXT NOT NULL,
                takeaways TEXT NOT NULL,
                summary TEXT NOT NULL,
                created_at INTEGER NOT NULL,
                is_sample INTEGER NOT NULL DEFAULT 0,
                is_error INTEGER NOT NULL DEFAULT 0,
                error_message TEXT,
                helpful INTEGER
            )"""
        )
        db.execSQL("CREATE INDEX idx_summaries_created ON summaries(created_at DESC)")
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        // v0.1 has a single version; no migrations yet.
    }
}

private fun Summary.toValues(): ContentValues = ContentValues().apply {
    put("title", title)
    put("category", category)
    put("source", source)
    put("source_url", sourceUrl)
    put("takeaways", JSONArray(takeaways).toString())
    put("summary", summary)
    put("created_at", createdAt)
    put("is_sample", if (isSample) 1 else 0)
    put("is_error", if (isError) 1 else 0)
    put("error_message", errorMessage)
    if (helpful == null) putNull("helpful") else put("helpful", if (helpful) 1 else 0)
}

/**
 * ONE shared Repository for the whole process (v0.1.11, bug #1).
 *
 * The app used to build two Repository instances — one in MainActivity
 * for the UI, one in SummaryService for background processing — each
 * with its own MutableStateFlow. The service's inserts updated the
 * service's flow, never the UI's, so new summaries only appeared after
 * a process restart (clear from recents). Both sides now go through
 * this provider, so a service insert updates the flow the Library is
 * already collecting. The service runs in the app's process (no
 * android:process in the manifest), so one in-process instance is
 * genuinely shared. DbHelper takes the application context, so the
 * provider never holds an Activity.
 */
object RepositoryProvider {
    @Volatile
    private var instance: Repository? = null

    fun get(context: Context): Repository =
        instance ?: synchronized(this) {
            instance ?: Repository(DbHelper(context.applicationContext)).also { instance = it }
        }
}

class Repository(private val dbHelper: DbHelper) {
    private val _summaries = MutableStateFlow<List<Summary>>(emptyList())
    val summaries: StateFlow<List<Summary>> = _summaries.asStateFlow()

    suspend fun refresh() = withContext(Dispatchers.IO) {
        val list = mutableListOf<Summary>()
        dbHelper.readableDatabase.rawQuery(
            "SELECT * FROM summaries ORDER BY created_at DESC", null
        ).use { c ->
            while (c.moveToNext()) {
                val takeaways = JSONArray(c.getString(c.getColumnIndexOrThrow("takeaways")))
                    .let { arr -> List(arr.length()) { i -> arr.getString(i) } }
                val helpfulIdx = c.getColumnIndexOrThrow("helpful")
                list += Summary(
                    id = c.getLong(c.getColumnIndexOrThrow("id")),
                    title = c.getString(c.getColumnIndexOrThrow("title")),
                    category = c.getString(c.getColumnIndexOrThrow("category")),
                    source = c.getString(c.getColumnIndexOrThrow("source")),
                    sourceUrl = c.getString(c.getColumnIndexOrThrow("source_url")),
                    takeaways = takeaways,
                    summary = c.getString(c.getColumnIndexOrThrow("summary")),
                    createdAt = c.getLong(c.getColumnIndexOrThrow("created_at")),
                    isSample = c.getInt(c.getColumnIndexOrThrow("is_sample")) == 1,
                    isError = c.getInt(c.getColumnIndexOrThrow("is_error")) == 1,
                    errorMessage = c.getString(c.getColumnIndexOrThrow("error_message")),
                    helpful = if (c.isNull(helpfulIdx)) null else c.getInt(helpfulIdx) == 1
                )
            }
        }
        _summaries.value = list
    }

    suspend fun insert(summary: Summary): Long = withContext(Dispatchers.IO) {
        val id = dbHelper.writableDatabase.insert("summaries", null, summary.toValues())
        refresh()
        id
    }

    suspend fun delete(id: Long) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete("summaries", "id = ?", arrayOf(id.toString()))
        refresh()
    }

    suspend fun deleteSamples() = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete("summaries", "is_sample = 1", null)
        refresh()
    }

    /**
     * v0.1.14: failed reels/shorts are never saved to the Library — the
     * notification alone tells the user. This clears any error rows left
     * over from earlier versions (e.g. the two "Couldn't summarise this
     * reel" cards on JS's phone) when the app starts. Only is_error rows
     * are removed; samples and successful summaries are untouched.
     */
    suspend fun deleteErrors() = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete("summaries", "is_error = 1", null)
        refresh()
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.delete("summaries", null, null)
        refresh()
    }

    suspend fun setHelpful(id: Long, helpful: Boolean?) = withContext(Dispatchers.IO) {
        val values = ContentValues()
        if (helpful == null) values.putNull("helpful") else values.put("helpful", if (helpful) 1 else 0)
        dbHelper.writableDatabase.update("summaries", values, "id = ?", arrayOf(id.toString()))
        refresh()
    }

    suspend fun updateStatus(id: Long, summary: Summary) = withContext(Dispatchers.IO) {
        dbHelper.writableDatabase.update(
            "summaries", summary.toValues(), "id = ?", arrayOf(id.toString())
        )
        refresh()
    }
}

/**
 * App preferences. The Gemini API key lives in EncryptedSharedPreferences
 * (AES256-GCM); if the encrypted store ever fails to initialise on a
 * device, we fall back to plain prefs rather than losing the key — the
 * key is the user's own, and the fallback is reported in Settings.
 */
class Prefs(context: Context) {
    private val plain: SharedPreferences =
        context.getSharedPreferences("geode_prefs", Context.MODE_PRIVATE)

    private val secure: SharedPreferences by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                "geode_keys",
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Encrypted store unavailable on this device; fall back.
            keyStoreEncrypted = false
            context.getSharedPreferences("geode_keys_fallback", Context.MODE_PRIVATE)
        }
    }

    var keyStoreEncrypted: Boolean
        get() = plain.getBoolean("key_store_encrypted", true)
        private set(v) = plain.edit().putBoolean("key_store_encrypted", v).apply()

    var onboardingDone: Boolean
        get() = plain.getBoolean("onboarding_done", false)
        set(v) = plain.edit().putBoolean("onboarding_done", v).apply()

    var samplesSeeded: Boolean
        get() = plain.getBoolean("samples_seeded", false)
        set(v) = plain.edit().putBoolean("samples_seeded", v).apply()

    var notificationsEnabled: Boolean
        get() = plain.getBoolean("notifications_enabled", true)
        set(v) = plain.edit().putBoolean("notifications_enabled", v).apply()

    /** "system" | "light" | "dark" */
    var theme: String
        get() = plain.getString("theme", "system") ?: "system"
        set(v) = plain.edit().putString("theme", v).apply()

    fun getApiKey(): String? = secure.getString("gemini_api_key", null)?.takeIf { it.isNotBlank() }

    fun setApiKey(key: String) {
        secure.edit().putString("gemini_api_key", key.trim()).apply()
    }

    fun clearApiKey() {
        secure.edit().remove("gemini_api_key").apply()
    }

    /** Masked for display, e.g. "AQ.…x7Qa". Never log the full key. */
    fun maskedApiKey(): String? {
        val key = getApiKey() ?: return null
        if (key.length <= 8) return "••••••••"
        return key.take(3) + ".…" + key.takeLast(4)
    }
}

/** The three sample cards, taken from JS's real Clera screenshots.
 * The promo takeaway ("Get more recommendations at BENTOANIME.COM") is
 * deliberately dropped — it is source self-promotion, not a takeaway. */
object Samples {
    fun all(now: Long): List<Summary> = listOf(
        Summary(
            title = "The Perfect Comedy Anime Recommendations",
            category = "Other",
            source = "Instagram",
            sourceUrl = "https://www.instagram.com/reel/",
            takeaways = listOf(
                "Daily Lives of High School Boys: 2012, Sunrise, 12 episodes — sketch comedy about everyday teenage life.",
                "Ouran High School Host Club: 2006, Bones, 26 episodes — rom-com about a scholarship student in an elite host club.",
                "The Disastrous Life of Saiki K.: 2016, J.C.Staff, 120 episodes — rapid-fire gag comedy about an overpowered psychic.",
                "Nichijou: 2011, Kyoto Animation, 26 episodes — absurdist comedy about an ordinary school year with unusual characters."
            ),
            summary = "Bento Anime recommends four comedy anime series: Daily Lives of High School Boys, Ouran High School Host Club, The Disastrous Life of Saiki K., and Nichijou, offering a lighthearted break from shonen battles.",
            createdAt = now - 2 * 86_400_000L,
            isSample = true
        ),
        Summary(
            title = "JEE Aspirant Guide: Physics Topics for 99 Percentile",
            category = "Study",
            source = "Instagram",
            sourceUrl = "https://www.instagram.com/reel/",
            takeaways = listOf(
                "Ajay Sharma, a 4-time 99 percentile scorer, guides millions of aspirants.",
                "The reel focuses on achieving 99 percentile in JEE.",
                "Physics topics for 'Free Marks': Kinematics, NLM, WPE, SHM, Circular Motion, Thermodynamics, Gravitation, Modern Physics, Current Electricity, Electrostatics, Magnetism, and Ray Optics.",
                "Physics topics for 'Ignored Rank': Wave Optics, Fluids, and Rotation."
            ),
            summary = "This reel, featuring author Ajay Sharma, highlights key Physics topics for JEE aspirants aiming for 99 percentile, categorizing them into 'Free Marks' and 'Ignored Rank' sections.",
            createdAt = now - 86_400_000L,
            isSample = true
        ),
        Summary(
            title = "5 Essential Steps After Launching Your Website for Visibility",
            category = "Tutorial",
            source = "Instagram",
            sourceUrl = "https://www.instagram.com/reel/",
            takeaways = listOf(
                "Set up Google Search Console to manually submit your website to Google.",
                "Create a sitemap, a file that informs search engines about every page on your website.",
                "Create an LLMS.txt file, similar to a sitemap but built for AI platforms like ChatGPT, Grok, Claude, and Gemini.",
                "Implement OpenGraph tags to control how your links appear when shared, preventing ugly, broken previews.",
                "Don't miss Bing Webmaster Tools to gain free traffic from Bing, even though it's often overlooked."
            ),
            summary = "This video outlines five crucial steps to take after launching a website to ensure it's discoverable by search engines and AI platforms, emphasizing the importance of manual submission and proper configuration.",
            createdAt = now - 3_600_000L,
            isSample = true
        )
    )
}
