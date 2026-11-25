package com.danilkinkin.buckwheat.slip.watch

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Watches the photo library directly while the app process is alive.
 *
 * WorkManager's content-URI trigger runs through JobScheduler, which Android and OEMs can delay
 * heavily when an app is in the background. While the process is alive in memory, this observer
 * catches new slips within a few seconds and kicks [SlipWatchWorker.checkNow].
 */
object SlipWatchObserver {
    private const val TAG = "SlipWatch"
    private const val DEBOUNCE_MS = 3_000L

    private val registered = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var pendingCheck: Job? = null

    fun register(context: Context) {
        if (!registered.compareAndSet(false, true)) return

        val appContext = context.applicationContext
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean, uri: Uri?) {
                onMediaChanged(appContext)
            }
        }

        val resolver = appContext.contentResolver
        SlipMedia.watchUris.forEach { uri ->
            try {
                resolver.registerContentObserver(uri, true, observer)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to register observer for $uri", e)
            }
        }
    }

    private fun onMediaChanged(context: Context) {
        pendingCheck?.cancel()
        pendingCheck = scope.launch {
            delay(DEBOUNCE_MS)
            try {
                val enabled = SlipWatchEntryPoint.get(context).watchSettings().enabled.first()
                if (enabled && SlipMedia.hasAccess(context)) {
                    SlipWatchWorker.checkNow(context)
                }
            } catch (e: Exception) {
                Log.w(TAG, "Media change check failed", e)
            }
        }
    }
}
