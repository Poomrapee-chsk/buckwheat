package com.danilkinkin.buckwheat.slip.watch

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danilkinkin.buckwheat.settingsDataStore
import com.danilkinkin.buckwheat.slip.SlipCandidate
import com.danilkinkin.buckwheat.slip.SlipWarning
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton

private val watchEnabledKey = booleanPreferencesKey("slipWatchEnabled")
private val watchedFoldersKey = stringPreferencesKey("slipWatchedFolders")
private val watermarkKey = longPreferencesKey("slipWatchWatermark")
private val processedKey = stringPreferencesKey("slipWatchProcessed")
private val pendingKey = stringPreferencesKey("slipWatchPending")

data class WatchedFolder(val bucketId: Long, val name: String)

/** Which folders are watched for new slips, and slips found there waiting for the user. */
@Singleton
class SlipWatchSettings @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val store get() = context.settingsDataStore

    val enabled: Flow<Boolean> = store.data.map { it[watchEnabledKey] ?: false }

    val folders: Flow<List<WatchedFolder>> = store.data.map { prefs ->
        parseArray(prefs[watchedFoldersKey]).map {
            WatchedFolder(it.getLong("id"), it.optString("name"))
        }
    }

    val pending: Flow<List<SlipCandidate>> = store.data.map { prefs ->
        parseArray(prefs[pendingKey]).mapNotNull { pendingFromJson(it) }
    }

    suspend fun setEnabled(enabled: Boolean) {
        store.edit {
            it[watchEnabledKey] = enabled
            // Only slips that arrive from now on, older ones can be scanned on purpose
            if (enabled) it[watermarkKey] = System.currentTimeMillis() / 1000
        }
    }

    suspend fun setFolders(folders: List<WatchedFolder>) {
        store.edit { prefs ->
            prefs[watchedFoldersKey] = JSONArray(folders.map {
                JSONObject().put("id", it.bucketId).put("name", it.name)
            }).toString()
        }
    }

    suspend fun watermark(): Long = store.data.first()[watermarkKey] ?: (System.currentTimeMillis() / 1000)

    suspend fun processedIds(): Set<Long> =
        store.data.first()[processedKey].orEmpty().split(',').mapNotNull { it.toLongOrNull() }.toSet()

    /** Remembers scanned images, so a slip never notifies twice */
    suspend fun markProcessed(ids: Collection<Long>, newWatermark: Long) {
        store.edit { prefs ->
            val all = prefs[processedKey].orEmpty().split(',').mapNotNull { it.toLongOrNull() } + ids
            prefs[processedKey] = all.distinct().takeLast(MAX_PROCESSED).joinToString(",")
            prefs[watermarkKey] = maxOf(prefs[watermarkKey] ?: 0, newWatermark)
        }
    }

    suspend fun addPending(candidates: List<SlipCandidate>) {
        if (candidates.isEmpty()) return
        store.edit { prefs ->
            val current = parseArray(prefs[pendingKey])
            val known = current.map { it.optLong("mediaId") }.toSet()
            val added = candidates.filter { it.mediaId !in known }.map { pendingToJson(it) }
            prefs[pendingKey] = JSONArray(current + added).toString()
        }
    }

    suspend fun removePending(mediaIds: Collection<Long>) {
        if (mediaIds.isEmpty()) return
        store.edit { prefs ->
            val left = parseArray(prefs[pendingKey]).filter { it.optLong("mediaId") !in mediaIds }
            prefs[pendingKey] = JSONArray(left).toString()
        }
    }

    private fun parseArray(raw: String?): List<JSONObject> = try {
        val array = JSONArray(raw ?: "[]")
        (0 until array.length()).map { array.getJSONObject(it) }
    } catch (e: Exception) {
        emptyList()
    }

    private fun pendingToJson(candidate: SlipCandidate) = JSONObject()
        .put("mediaId", candidate.mediaId)
        .put("amount", candidate.amountText)
        .put("date", candidate.date.time)
        .put("tag", candidate.tag)
        .put("payee", candidate.payee ?: JSONObject.NULL)
        .put("note", candidate.note ?: JSONObject.NULL)
        .put("warnings", JSONArray(candidate.warnings.map { it.name }))
        .put("reference", candidate.reference ?: JSONObject.NULL)
        .put("rawText", candidate.rawText)

    private fun pendingFromJson(json: JSONObject): SlipCandidate? = try {
        val warnings = json.optJSONArray("warnings")
        SlipCandidate(
            id = -1,
            amountText = json.getString("amount"),
            date = Date(json.getLong("date")),
            tag = json.optString("tag"),
            payee = json.optString("payee").takeIf { !json.isNull("payee") && it.isNotEmpty() },
            note = json.optString("note").takeIf { !json.isNull("note") && it.isNotEmpty() },
            warnings = (0 until (warnings?.length() ?: 0))
                .mapNotNull { runCatching { SlipWarning.valueOf(warnings!!.getString(it)) }.getOrNull() }
                .toSet(),
            selected = true,
            mediaId = json.getLong("mediaId"),
            reference = json.optString("reference").takeIf { !json.isNull("reference") && it.isNotEmpty() },
            rawText = json.optString("rawText"),
        )
    } catch (e: Exception) {
        null
    }

    companion object {
        private const val MAX_PROCESSED = 1000
    }
}
