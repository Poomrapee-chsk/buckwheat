package com.danilkinkin.buckwheat

import OverrideLocalize
import android.content.Context
import android.content.pm.ActivityInfo
import android.content.Intent
import android.os.Bundle
import androidx.hilt.navigation.compose.hiltViewModel
import com.danilkinkin.buckwheat.data.AppViewModel
import com.danilkinkin.buckwheat.data.PathState
import com.danilkinkin.buckwheat.slip.SLIP_IMPORT_SHEET
import androidx.activity.ComponentActivity
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.systemBars
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.core.view.WindowCompat
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.lifecycleScope
import com.danilkinkin.buckwheat.base.balloon.BalloonProvider
import com.danilkinkin.buckwheat.data.dao.StorageDao
import com.danilkinkin.buckwheat.di.migrateToDataStore
import com.danilkinkin.buckwheat.home.MainScreen
import dagger.hilt.android.AndroidEntryPoint
import com.danilkinkin.buckwheat.ui.BuckwheatTheme
import com.danilkinkin.buckwheat.ui.ThemeMode
import com.danilkinkin.buckwheat.ui.syncTheme
import com.danilkinkin.buckwheat.util.locScreenOrientation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import syncOverrideLocale
import java.util.*
import javax.inject.Inject

val Context.budgetDataStore by preferencesDataStore("budget")
val Context.settingsDataStore by preferencesDataStore("settings")
var Context.appTheme by mutableStateOf(ThemeMode.SYSTEM)
var Context.appLocale: Locale? by mutableStateOf(null)
var Context.systemLocale: Locale? by mutableStateOf(null)
var Context.errorForReport: String? by mutableStateOf(null)

val LocalWindowSize = compositionLocalOf { WindowWidthSizeClass.Compact }
val LocalWindowInsets = compositionLocalOf { PaddingValues(0.dp) }

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    // Broke tests. Set to true for tests
    private val isDone: MutableState<Boolean> = mutableStateOf(false)
    private val isReady: MutableState<Boolean> = mutableStateOf(false)

    /** Sheet requested by a notification, see [EXTRA_OPEN_SHEET] */
    private val sheetToOpen: MutableState<String?> = mutableStateOf(null)

    private fun readSheetToOpen(intent: Intent?) {
        // The activity is exported: only open sheets that are meant to be opened from outside
        intent?.getStringExtra(EXTRA_OPEN_SHEET)
            ?.takeIf { it in OPENABLE_SHEETS }
            ?.let { sheetToOpen.value = it }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        readSheetToOpen(intent)
    }

    //TODO: Remove after 01.01.2024. Need for migration to DataStore
    @Inject
    lateinit var storageDao: StorageDao

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        val context = this.applicationContext
        WindowCompat.setDecorFitsSystemWindows(window, false)
        installSplashScreen().setKeepOnScreenCondition { !isDone.value }
        lifecycleScope.launch {
            context.settingsDataStore.data.first()
        }

        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) readSheetToOpen(intent)

        setContent {
            val localContext = LocalContext.current
            val activityResultRegistryOwner = LocalActivityResultRegistryOwner.current

            LaunchedEffect(Unit) {
                syncTheme(localContext)
                syncOverrideLocale(localContext)
                migrateToDataStore(context, storageDao)

                // App ready for work
                isReady.value = true
            }

            val widthSizeClass = calculateWindowSizeClass(this).widthSizeClass

            if (widthSizeClass == WindowWidthSizeClass.Compact) {
                locScreenOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT)
            }

            val windowInsets = WindowInsets
                .systemBars
                .asPaddingValues()

            CatchAndSendCrashReport()

            if (isReady.value) {
                BuckwheatTheme {
                    OverrideLocalize {
                        BalloonProvider {
                            CompositionLocalProvider(
                                LocalWindowSize provides widthSizeClass,
                                LocalWindowInsets provides windowInsets,
                            ) {
                                MainScreen(activityResultRegistryOwner)

                                val appViewModel: AppViewModel = hiltViewModel()
                                LaunchedEffect(sheetToOpen.value) {
                                    sheetToOpen.value?.let {
                                        appViewModel.openSheet(PathState(it))
                                        sheetToOpen.value = null
                                    }
                                }

                                LaunchedEffect(Unit) {
                                    // App rendered and splash screen can be hidden
                                    isDone.value = true
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    companion object {
        const val EXTRA_OPEN_SHEET = "openSheet"
        private val OPENABLE_SHEETS = setOf(SLIP_IMPORT_SHEET)
    }
}
