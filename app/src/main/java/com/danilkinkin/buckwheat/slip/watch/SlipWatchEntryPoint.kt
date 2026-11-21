package com.danilkinkin.buckwheat.slip.watch

import android.content.Context
import com.danilkinkin.buckwheat.di.SpendsRepository
import com.danilkinkin.buckwheat.slip.SlipTagMemory
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent

/** Dependencies for the worker and receiver, which WorkManager / the system create without Hilt */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface SlipWatchEntryPoint {
    fun spendsRepository(): SpendsRepository
    fun tagMemory(): SlipTagMemory
    fun watchSettings(): SlipWatchSettings

    companion object {
        fun get(context: Context): SlipWatchEntryPoint =
            EntryPointAccessors.fromApplication(context.applicationContext, SlipWatchEntryPoint::class.java)
    }
}
