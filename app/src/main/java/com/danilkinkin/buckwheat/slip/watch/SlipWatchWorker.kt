package com.danilkinkin.buckwheat.slip.watch

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.danilkinkin.buckwheat.slip.SlipCandidate
import com.danilkinkin.buckwheat.slip.SlipCandidateFactory
import com.danilkinkin.buckwheat.slip.SlipParser
import com.danilkinkin.buckwheat.slip.SlipTextRecognizer
import com.danilkinkin.buckwheat.slip.SlipWarning
import kotlinx.coroutines.flow.first
import java.math.BigDecimal
import java.time.Duration
import java.util.concurrent.TimeUnit

/**
 * Looks for new images in watched folders, reads them and notifies about the ones that are slips.
 * Runs when the photo library changes (content URI trigger), with a periodic run as a safety net.
 */
class SlipWatchWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val deps = SlipWatchEntryPoint.get(applicationContext)
        val settings = deps.watchSettings()
        if (!settings.enabled.first()) return Result.success()

        var hasMore = false
        try {
            hasMore = scan(deps)
        } catch (e: Exception) {
            Log.e(TAG, "Slip scan failed", e)
        } finally {
            // Content URI triggers fire once, so wait for the next change again
            val fromTrigger = !inputData.getBoolean(KEY_PERIODIC, false)
            scheduleTrigger(
                applicationContext,
                if (fromTrigger) ExistingWorkPolicy.APPEND_OR_REPLACE else ExistingWorkPolicy.KEEP,
                runNow = hasMore,
            )
        }

        return Result.success()
    }

    /** @return true when there are more images left than one run handles */
    private suspend fun scan(deps: SlipWatchEntryPoint): Boolean {
        val settings = deps.watchSettings()
        val repository = deps.spendsRepository()
        if (!SlipMedia.hasAccess(applicationContext)) return false

        val folders = settings.folders.first().map { it.bucketId }
        val processed = settings.processedIds()
        // A minute of overlap: images added in the same second as the last scan aren't missed,
        // already scanned ones are skipped by id
        val images = SlipMedia.queryImages(applicationContext, folders, settings.watermark() - 60)
            .filter { it.mediaId !in processed }
        if (images.isEmpty()) return false

        val batch = images.take(MAX_PER_RUN)
        val newWatermark = batch.maxOf { it.dateAdded }

        // No active budget: nothing could be added
        if (repository.getBudget().first() <= BigDecimal.ZERO) {
            settings.markProcessed(batch.map { it.mediaId }, newWatermark)
            return images.size > batch.size
        }

        val factory = SlipCandidateFactory.load(repository, deps.tagMemory())
        val found = mutableListOf<SlipCandidate>()
        val notAddable = mutableListOf<SlipCandidate>()

        SlipTextRecognizer(applicationContext).use { recognizer ->
            batch.forEach { image ->
                val text = try {
                    recognizer.recognize(image.uri)
                } catch (e: Exception) {
                    Log.w(TAG, "Can't read ${image.uri}", e)
                    ""
                }
                // Other screenshots and photos in the folder are skipped silently
                if (!SlipParser.looksLikeSlip(text)) return@forEach

                val candidate = factory.build(found.size, text, found, image.mediaId)
                when {
                    // Already added by hand: expected, no need to bother the user
                    SlipWarning.DUPLICATE in candidate.warnings -> Unit
                    candidate.canBeAdded -> found += candidate
                    // Before this period or in the future: say so instead of dropping it silently
                    else -> notAddable += candidate
                }
            }
        }

        settings.markProcessed(batch.map { it.mediaId }, newWatermark)
        settings.addPending(found)

        val currency = repository.getCurrency().first()
        found.forEach { SlipNotifications.show(applicationContext, it, currency) }
        notAddable.forEach { SlipNotifications.showNotAddable(applicationContext, it, currency) }

        Log.d(TAG, "Scanned ${batch.size} images, found ${found.size} slips")
        return images.size > batch.size
    }

    companion object {
        private const val TAG = "SlipWatch"
        private const val TRIGGER_WORK = "slipWatchTrigger"
        private const val PERIODIC_WORK = "slipWatchPeriodic"
        private const val KEY_PERIODIC = "periodic"

        // OCR takes a second or two an image, keep a run well under WorkManager's 10 minute limit
        private const val MAX_PER_RUN = 20

        fun start(context: Context) {
            scheduleTrigger(context, ExistingWorkPolicy.REPLACE, runNow = false)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<SlipWatchWorker>(6, TimeUnit.HOURS)
                    .setInputData(workDataOf(KEY_PERIODIC to true))
                    .build(),
            )
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(TRIGGER_WORK)
                cancelUniqueWork(PERIODIC_WORK)
            }
        }

        private fun scheduleTrigger(context: Context, policy: ExistingWorkPolicy, runNow: Boolean) {
            val constraints = if (runNow) {
                Constraints.NONE
            } else {
                Constraints.Builder()
                    .addContentUriTrigger(SlipMedia.imagesUri, true)
                    // Let the bank app finish saving, and batch several slips into one run
                    .setTriggerContentUpdateDelay(Duration.ofSeconds(5))
                    .setTriggerContentMaxDelay(Duration.ofSeconds(30))
                    .build()
            }

            WorkManager.getInstance(context).enqueueUniqueWork(
                TRIGGER_WORK,
                policy,
                OneTimeWorkRequestBuilder<SlipWatchWorker>().setConstraints(constraints).build(),
            )
        }
    }
}
