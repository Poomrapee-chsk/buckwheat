package com.danilkinkin.buckwheat.slip

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.slip.watch.SlipMedia
import com.danilkinkin.buckwheat.slip.watch.SlipNotifications
import com.danilkinkin.buckwheat.slip.watch.SlipWatchSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Date
import javax.inject.Inject

@HiltViewModel
class SlipImportViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val spendsRepository: SpendsRepository,
    private val tagMemory: SlipTagMemory,
    private val watchSettings: SlipWatchSettings,
) : ViewModel() {
    val candidates = mutableStateListOf<SlipCandidate>()
    var isReading by mutableStateOf(false)
        private set
    var readCount by mutableIntStateOf(0)
        private set
    var totalCount by mutableIntStateOf(0)
        private set

    /** The OCR engine couldn't start at all */
    var ocrError by mutableStateOf<String?>(null)
        private set

    /** Images of a folder scan that turned out not to be slips */
    var skippedCount by mutableIntStateOf(0)
        private set

    private var nextId = 0

    /** Slips found in watched folders and loaded here, cleared from the waiting list on add */
    private val shownPendingIds = mutableSetOf<Long>()

    private data class Source(val uri: Uri, val mediaId: Long?)

    /** Images the user picked: every one is shown, even when it doesn't look like a slip */
    fun readSlips(uris: List<Uri>) = read(uris.map { Source(it, null) }, onlySlips = false)

    /** Past images of the chosen folders: only the ones that look like slips are shown */
    fun scanFolders(bucketIds: Collection<Long>, from: Date) {
        if (isReading) return
        isReading = true

        viewModelScope.launch {
            val images = withContext(Dispatchers.IO) {
                SlipMedia.queryImages(context, bucketIds, from.time / 1000 - 1)
            }
            isReading = false
            read(images.map { Source(it.uri, it.mediaId) }, onlySlips = true)
        }
    }

    private fun read(sources: List<Source>, onlySlips: Boolean) {
        if (isReading) return

        isReading = true
        readCount = 0
        totalCount = sources.size
        skippedCount = 0
        ocrError = null

        viewModelScope.launch {
            try {
                val factory = SlipCandidateFactory.load(spendsRepository, tagMemory)
                val known = candidates.mapNotNull { it.mediaId }.toSet()

                SlipTextRecognizer(context).use { recognizer ->
                    sources.forEach { source ->
                        if (source.mediaId != null && source.mediaId in known) {
                            readCount += 1
                            return@forEach
                        }

                        var error: String? = null
                        val text = withContext(Dispatchers.Default) {
                            try {
                                recognizer.recognize(source.uri)
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Throwable) {
                                // Throwable: a native library that fails to load throws an Error
                                Log.e("SlipImport", "Failed to read slip ${source.uri}", e)
                                error = e.toString()
                                ""
                            }
                        }

                        if (onlySlips && error == null && !SlipParser.looksLikeSlip(text)) {
                            skippedCount += 1
                        } else {
                            candidates.add(factory.build(nextId++, text, candidates, source.mediaId, error))
                        }
                        readCount += 1
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("SlipImport", "OCR is not available", e)
                ocrError = e.toString()
            } finally {
                isReading = false
            }
        }
    }

    /** Adds slips found in watched folders (from notifications) to the review list */
    fun loadPending() {
        viewModelScope.launch {
            val pending = watchSettings.pending.first()
            val known = candidates.mapNotNull { it.mediaId }.toSet()
            val factory = SlipCandidateFactory.load(spendsRepository, tagMemory)

            pending.filter { it.mediaId !in known }.forEach { slip ->
                shownPendingIds += slip.mediaId!!
                // The budget may have changed since it was found
                candidates.add(slip.copy(id = nextId++, selected = factory.stillAddable(slip)))
            }
        }
    }

    fun update(candidate: SlipCandidate) {
        val index = candidates.indexOfFirst { it.id == candidate.id }
        if (index >= 0) candidates[index] = candidate
    }

    val selectedCount: Int
        get() = candidates.count { it.selected && it.canBeAdded && it.amount != null }

    /**
     * Adds the selected slips as spends, oldest first, and remembers the chosen tags.
     * Waiting slips that were shown here are cleared either way: the user has seen them.
     */
    fun addSelected(onDone: (count: Int) -> Unit) {
        val toAdd = candidates
            .filter { it.selected && it.canBeAdded && it.amount != null }
            .sortedBy { it.date }
        val seenPending = shownPendingIds.toList()

        viewModelScope.launch {
            toAdd.forEach { spendsRepository.addSpentFromSlip(it.toTransaction(), it.toDetails()) }

            tagMemory.remember(
                toAdd
                    .filter { !it.payee.isNullOrBlank() }
                    .associate { it.payee!! to it.tag.trim() }
            )

            watchSettings.removePending(seenPending)
            seenPending.forEach { SlipNotifications.cancel(context, it) }
            shownPendingIds.clear()

            candidates.clear()
            onDone(toAdd.size)
        }
    }

    fun clear() {
        if (isReading) return
        candidates.clear()
        shownPendingIds.clear()
        skippedCount = 0
    }
}
