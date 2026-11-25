package com.danilkinkin.buckwheat

import android.app.Activity
import android.app.Application
import android.os.Bundle
import com.danilkinkin.buckwheat.slip.watch.SlipWatchEntryPoint
import com.danilkinkin.buckwheat.slip.watch.SlipWatchObserver
import com.danilkinkin.buckwheat.slip.watch.SlipWatchWorker
import com.danilkinkin.buckwheat.widget.extend.ExtendWidgetReceiver
import com.danilkinkin.buckwheat.widget.minimal.MinimalWidgetReceiver
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@HiltAndroidApp
class Application : Application() {
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var startedActivities = 0

    override fun onCreate() {
        super.onCreate()

        SlipWatchObserver.register(this)
        appScope.launch {
            if (SlipWatchEntryPoint.get(this@Application).watchSettings().enabled.first()) {
                SlipWatchWorker.start(this@Application)
                SlipWatchWorker.checkNow(this@Application)
            }
        }

        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {

            }

            override fun onActivityStarted(activity: Activity) {
                val fromBackground = startedActivities == 0
                startedActivities += 1
                if (fromBackground) {
                    val context = activity.applicationContext
                    appScope.launch {
                        if (SlipWatchEntryPoint.get(context).watchSettings().enabled.first()) {
                            SlipWatchWorker.checkNow(context)
                        }
                    }
                }
            }

            override fun onActivityResumed(activity: Activity) {

            }

            override fun onActivityPaused(activity: Activity) {
                ExtendWidgetReceiver.requestUpdateData(activity.applicationContext)
                MinimalWidgetReceiver.requestUpdateData(activity.applicationContext)
            }

            override fun onActivityStopped(activity: Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
            }

            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {

            }

            override fun onActivityDestroyed(activity: Activity) {

            }
        })
    }
}
