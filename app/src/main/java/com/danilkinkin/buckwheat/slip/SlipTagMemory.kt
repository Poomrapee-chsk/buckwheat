package com.danilkinkin.buckwheat.slip

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.danilkinkin.buckwheat.budgetDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import javax.inject.Inject

private val slipPayeeTagsStoreKey = stringPreferencesKey("slipPayeeTags")

/** Remembers which tag the user picked for each payee, so the next slip from them gets it. */
class SlipTagMemory @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    suspend fun getAll(): Map<String, String> {
        val raw = context.budgetDataStore.data.first()[slipPayeeTagsStoreKey] ?: return emptyMap()

        return try {
            val json = JSONObject(raw)
            json.keys().asSequence().associateWith { json.getString(it) }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    suspend fun remember(choices: Map<String, String>) {
        if (choices.isEmpty()) return

        context.budgetDataStore.edit { prefs ->
            val json = try {
                JSONObject(prefs[slipPayeeTagsStoreKey] ?: "{}")
            } catch (e: Exception) {
                JSONObject()
            }
            choices.forEach { (payee, tag) ->
                val key = TagSuggester.payeeKey(payee)
                if (key.isEmpty()) return@forEach
                if (tag.isBlank()) json.remove(key) else json.put(key, tag)
            }
            prefs[slipPayeeTagsStoreKey] = json.toString()
        }
    }
}
