package com.danilkinkin.buckwheat.slip.watch

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.danilkinkin.buckwheat.di.SpendsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

@HiltViewModel
class SlipFoldersViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SlipWatchSettings,
    spendsRepository: SpendsRepository,
) : ViewModel() {
    var folders by mutableStateOf<List<SlipFolder>>(emptyList())
        private set
    var hasAccess by mutableStateOf(SlipMedia.hasAccess(context))
        private set

    val watchEnabled = settings.enabled.asLiveData()
    val watchedFolders = settings.folders.asLiveData()
    val periodStart = spendsRepository.getStartPeriodDate().asLiveData()

    fun refresh() {
        hasAccess = SlipMedia.hasAccess(context)
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) { SlipMedia.listFolders(context) }
            folders = found

            // First time: preselect folders that look like bank apps
            val watched = settings.folders.first()
            if (watched.isEmpty() && found.any { it.suggested }) {
                settings.setFolders(found.filter { it.suggested }.map { WatchedFolder(it.bucketId, it.name) })
            }
        }
    }

    fun setFolderWatched(folder: SlipFolder, watched: Boolean) {
        viewModelScope.launch {
            val current = settings.folders.first().filter { it.bucketId != folder.bucketId }
            settings.setFolders(if (watched) current + WatchedFolder(folder.bucketId, folder.name) else current)
        }
    }

    fun setWatchEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settings.setEnabled(enabled)
            if (enabled) SlipWatchWorker.start(context) else SlipWatchWorker.stop(context)
        }
    }
}
