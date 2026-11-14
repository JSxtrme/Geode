package com.js.geode

import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Background processing.
 *
 * NOTE (deliberate deviation from the brief): this is a foreground Service,
 * not WorkManager. WorkManager's runtime pulls in a Room transitive chain
 * that is not in the VM's offline Gradle cache and cannot be fetched by
 * Java tooling on this VM, so vendoring it would have meant hand-wiring
 * half of Jetpack. A foreground service gives the same guarantees for a
 * 10–45 second job — it survives the app being closed, shows honest
 * progress, and posts "Summary ready" when done — with zero extra deps.
 */
class SummaryService : Service() {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var prefs: Prefs
    private lateinit var repository: Repository
    private lateinit var gemini: GeminiClient
    private lateinit var notificationManager: NotificationManager

    override fun onCreate() {
        super.onCreate()
        prefs = Prefs(this)
        repository = RepositoryProvider.get(this)
        gemini = GeminiClient(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val url = intent?.getStringExtra(EXTRA_URL) ?: run {
            // No URL in this intent: process anything left in the queue.
            processQueue()
            return START_NOT_STICKY
        }
        val source = runCatching {
            Source.valueOf(intent.getStringExtra(EXTRA_SOURCE) ?: "INSTAGRAM")
        }.getOrDefault(Source.INSTAGRAM)
        val deleteErrorId = intent.getLongExtra(EXTRA_DELETE_ERROR_ID, 0L)
        scope.launch { processOne(url, source, deleteErrorId) }
        return START_NOT_STICKY
    }

    private fun processQueue() {
        scope.launch {
            val items = PendingQueue.readAll(this@SummaryService)
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                val url = item.optString("url")
                val source = runCatching {
                    Source.valueOf(item.optString("source"))
                }.getOrDefault(Source.INSTAGRAM)
                if (url.isNotBlank()) processOne(url, source, 0L)
            }
        }
    }

    private suspend fun processOne(url: String, source: Source, deleteErrorId: Long) {
        if (deleteErrorId != 0L) repository.delete(deleteErrorId)
        startForeground(Notifications.PROGRESS_ID, Notifications.progressNotification(this))
        val apiKey = prefs.getApiKey()
        if (apiKey.isNullOrBlank()) {
            // v0.1.14: failures are never saved to the Library — the
            // notification alone tells the user (it deep-links to
            // Settings, so the key can still be added from it). Retry by
            // sharing the reel again once the key is added.
            if (prefs.notificationsEnabled) {
                Notifications.post(
                    notificationManager, Notifications.DONE_BASE_ID + 1,
                    Notifications.needKeyNotification(this, Notifications.DONE_BASE_ID + 1)
                )
            }
            PendingQueue.remove(this, url)
            stopForegroundAndSelf()
            return
        }

        val result = gemini.summarizeUrl(apiKey, url, source)
        result.fold(
            onSuccess = { r ->
                val id = repository.insert(
                    Summary(
                        title = r.title,
                        category = r.category,
                        source = source.label,
                        sourceUrl = url,
                        takeaways = r.takeaways,
                        summary = r.summary
                    )
                )
                if (prefs.notificationsEnabled) {
                    Notifications.post(
                        notificationManager,
                        Notifications.DONE_BASE_ID + id.toInt(),
                        Notifications.doneNotification(this, id.toInt(), r.title, id)
                    )
                }
            },
            onFailure = { e ->
                // v0.1.14: no error card is saved for a failure — the
                // notification alone tells the user. Retry by sharing
                // the reel again.
                val isPrivate = e is GeminiClient.ApiException && e.isPrivate
                val message = e.message ?: "Something went wrong. Please try again."
                if (prefs.notificationsEnabled) {
                    val nid = Notifications.DONE_BASE_ID + url.hashCode().rem(10_000).let { if (it < 0) -it else it }
                    Notifications.post(
                        notificationManager, nid,
                        if (isPrivate) Notifications.privateNotification(this, nid)
                        else Notifications.failedNotification(this, 0, message)
                    )
                }
            }
        )
        PendingQueue.remove(this, url)
        stopForegroundAndSelf()
    }

    private fun stopForegroundAndSelf() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } catch (e: Exception) {
            // Very old fallback; on API 24+ the call above is used.
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val EXTRA_URL = "url"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_DELETE_ERROR_ID = "delete_error_id"

        fun start(context: Context, url: String, source: Source, deleteErrorId: Long = 0L) {
            val intent = Intent(context, SummaryService::class.java).apply {
                putExtra(EXTRA_URL, url)
                putExtra(EXTRA_SOURCE, source.name)
                putExtra(EXTRA_DELETE_ERROR_ID, deleteErrorId)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
    }
}

/** On boot, resume anything left in the pending queue. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        if (PendingQueue.readAll(context).length() == 0) return
        val serviceIntent = Intent(context, SummaryService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.startForegroundService(serviceIntent)
        } else {
            context.startService(serviceIntent)
        }
    }
}
