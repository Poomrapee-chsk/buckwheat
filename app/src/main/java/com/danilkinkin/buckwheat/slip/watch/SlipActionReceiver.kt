package com.danilkinkin.buckwheat.slip.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.danilkinkin.buckwheat.slip.SlipCandidateFactory
import com.danilkinkin.buckwheat.widget.extend.ExtendWidgetReceiver
import com.danilkinkin.buckwheat.widget.minimal.MinimalWidgetReceiver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/** "Add" and "Dismiss" buttons of a new slip notification */
class SlipActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val mediaId = intent.getLongExtra(EXTRA_MEDIA_ID, -1)
        if (mediaId < 0) return

        val result = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Default).launch {
            try {
                val deps = SlipWatchEntryPoint.get(context)
                when (intent.action) {
                    ACTION_ADD -> add(context, deps, mediaId)
                    ACTION_DISMISS -> deps.watchSettings().removePending(listOf(mediaId))
                }
                SlipNotifications.cancel(context, mediaId)
            } catch (e: Exception) {
                Log.e("SlipWatch", "Notification action failed", e)
            } finally {
                result.finish()
            }
        }
    }

    private suspend fun add(context: Context, deps: SlipWatchEntryPoint, mediaId: Long) {
        val settings = deps.watchSettings()
        val slip = settings.pending.first().find { it.mediaId == mediaId } ?: return

        // The budget may have changed since the notification: a new period, or the spend
        // added by hand meanwhile. Then leave it for the review screen instead.
        val factory = SlipCandidateFactory.load(deps.spendsRepository(), deps.tagMemory())
        if (!factory.stillAddable(slip)) return

        deps.spendsRepository().addSpent(slip.toTransaction())
        slip.payee?.let { deps.tagMemory().remember(mapOf(it to slip.tag)) }
        settings.removePending(listOf(mediaId))

        ExtendWidgetReceiver.requestUpdateData(context)
        MinimalWidgetReceiver.requestUpdateData(context)
    }

    companion object {
        const val ACTION_ADD = "com.danilkinkin.buckwheat.slip.ADD"
        const val ACTION_DISMISS = "com.danilkinkin.buckwheat.slip.DISMISS"
        const val EXTRA_MEDIA_ID = "mediaId"
    }
}
