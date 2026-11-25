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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
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

        val source = inputData.getString(KEY_SOURCE) ?: SOURCE_TRIGGER
        var hasMore = false
        var cancelled = false
        try {
            hasMore = scanMutex.withLock { scan(deps) }
        } catch (e: kotlinx.coroutines.CancellationException) {
            cancelled = true
            throw e
        } catch (e: Throwable) {
            Log.e(TAG, "Slip scan failed", e)
        } finally {
            // Only re-arm when not cancelled and still enabled: if WorkManager stops a worker,
            // it retries the worker itself; appending a child to a cancelling work cancels the child too.
            if (!cancelled && settings.enabled.first()) {
                if (source == SOURCE_TRIGGER) {
                    // Content URI triggers fire once, so wait for the next change again
                    scheduleTrigger(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE, runNow = hasMore)
                } else {
                    // Keep the content-URI trigger armed without stacking a duplicate behind it
                    scheduleTrigger(applicationContext, ExistingWorkPolicy.KEEP, runNow = false)
                    if (hasMore) {
                        enqueueCheckNow(applicationContext, ExistingWorkPolicy.APPEND_OR_REPLACE)
                    }
                }
            }
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
        val importedMediaIds = repository.getUsedSlipMediaIds()
        // Five minutes of overlap: images whose save finished after a previous scan aren't missed,
        // already scanned or already imported ones are skipped by id
        val images = SlipMedia.queryImages(applicationContext, folders, settings.watermark() - OVERLAP_SECONDS)
            .filter { it.mediaId !in processed && it.mediaId !in importedMediaIds }
        if (images.isEmpty()) return false

        val batch = images.take(MAX_PER_RUN)
        val nowSeconds = System.currentTimeMillis() / 1000

        // No active budget: nothing could be added
        if (repository.getBudget().first() <= BigDecimal.ZERO) {
            val watermark = minOf(batch.maxOf { it.dateAdded }, nowSeconds)
            settings.markProcessed(batch.map { it.mediaId }, watermark)
            return images.size > batch.size
        }

        val factory = SlipCandidateFactory.load(repository, deps.tagMemory())
        val found = mutableListOf<SlipCandidate>()
        val notAddable = mutableListOf<SlipCandidate>()
        val readIds = mutableListOf<Long>()
        var maxReadDate: Long? = null

        SlipTextRecognizer(applicationContext).use { recognizer ->
            batch.forEach { image ->
                val text = try {
                    recognizer.recognize(image.uri)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    // File may still be written by the bank app: leave unprocessed so the next run retries it
                    Log.w(TAG, "Can't read ${image.uri}", e)
                    return@forEach
                }
                readIds += image.mediaId
                maxReadDate = maxOf(maxReadDate ?: 0L, minOf(image.dateAdded, nowSeconds))

                val imageDate = SlipMedia.imageDate(applicationContext, image.uri)
                    ?: LocalDateTime.ofInstant(Instant.ofEpochSecond(image.dateAdded), ZoneId.systemDefault())
                // Other screenshots and photos in the folder are skipped silently
                if (!SlipParser.looksLikeSlip(text, imageDate)) return@forEach

                val candidate = factory.build(found.size, text, found, image.mediaId, imageDate = imageDate)
                when {
                    // Already added by hand: expected, no need to bother the user
                    SlipWarning.DUPLICATE in candidate.warnings -> Unit
                    // Notifications only add to the current period
                    factory.stillAddable(candidate) -> found += candidate
                    // Before this period or in the future: say so instead of dropping it silently
                    else -> notAddable += candidate
                }
            }
        }

        if (readIds.isNotEmpty()) {
            settings.markProcessed(readIds, maxReadDate ?: 0L)
        }
        settings.addPending(found)

        val currency = repository.getCurrency().first()
        found.forEach { SlipNotifications.show(applicationContext, it, currency) }
        notAddable.forEach { SlipNotifications.showNotAddable(applicationContext, it, currency) }

        Log.d(TAG, "Scanned ${readIds.size}/${batch.size} images, found ${found.size} slips")
        return images.size > batch.size
    }

    companion object {
        private const val TAG = "SlipWatch"
        private const val TRIGGER_WORK = "slipWatchTrigger"
        private const val PERIODIC_WORK = "slipWatchPeriodic"
        private const val CHECK_NOW_WORK = "slipWatchNow"
        private const val KEY_SOURCE = "source"
        private const val SOURCE_TRIGGER = "trigger"
        private const val SOURCE_PERIODIC = "periodic"
        private const val SOURCE_MANUAL = "manual"
        private const val OVERLAP_SECONDS = 300L

        // OCR takes a second or two an image, keep a run well under WorkManager's 10 minute limit
        private const val MAX_PER_RUN = 20

        private val scanMutex = Mutex()

        fun start(context: Context) {
            scheduleTrigger(context, ExistingWorkPolicy.REPLACE, runNow = false)
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_WORK,
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<SlipWatchWorker>(15, TimeUnit.MINUTES)
                    .setInputData(workDataOf(KEY_SOURCE to SOURCE_PERIODIC))
                    .build(),
            )
        }

        /** One run now; ensures the photo library trigger stays armed when it ends */
        fun checkNow(context: Context) {
            enqueueCheckNow(context, ExistingWorkPolicy.KEEP)
        }

        private fun enqueueCheckNow(context: Context, policy: ExistingWorkPolicy) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                CHECK_NOW_WORK,
                policy,
                OneTimeWorkRequestBuilder<SlipWatchWorker>()
                    .setInputData(workDataOf(KEY_SOURCE to SOURCE_MANUAL))
                    .build(),
            )
        }

        fun stop(context: Context) {
            WorkManager.getInstance(context).apply {
                cancelUniqueWork(TRIGGER_WORK)
                cancelUniqueWork(PERIODIC_WORK)
                cancelUniqueWork(CHECK_NOW_WORK)
            }
        }

        private fun scheduleTrigger(context: Context, policy: ExistingWorkPolicy, runNow: Boolean) {
            val constraints = if (runNow) {
                Constraints.NONE
            } else {
                Constraints.Builder().apply {
                    SlipMedia.watchUris.forEach { addContentUriTrigger(it, true) }
                    // Let the bank app finish saving, and batch several slips into one run
                    setTriggerContentUpdateDelay(Duration.ofSeconds(3))
                    setTriggerContentMaxDelay(Duration.ofSeconds(15))
                }.build()
            }

            WorkManager.getInstance(context).enqueueUniqueWork(
                TRIGGER_WORK,
                policy,
                OneTimeWorkRequestBuilder<SlipWatchWorker>()
                    .setInputData(workDataOf(KEY_SOURCE to SOURCE_TRIGGER))
                    .setConstraints(constraints)
                    .build(),
            )
        }
    }
}
