package com.danilkinkin.buckwheat.slip.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Re-arms [SlipWatchWorker] after a reboot or app update.
 *
 * JobScheduler drops non-persisted content-URI trigger jobs on reboot and package replacement,
 * so without re-scheduling them here watching would stay silent until the app is opened again.
 */
class SlipWatchBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        val appContext = context.applicationContext
        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val enabled = SlipWatchEntryPoint.get(appContext).watchSettings().enabled.first()
                if (enabled) {
                    SlipWatchWorker.start(appContext)
                    SlipWatchWorker.checkNow(appContext)
                }
            } catch (e: Exception) {
                Log.e("SlipWatch", "Failed to restart watcher after $action", e)
            } finally {
                result.finish()
            }
        }
    }
}
