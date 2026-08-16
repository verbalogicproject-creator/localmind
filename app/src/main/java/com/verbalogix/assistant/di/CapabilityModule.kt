package com.verbalogix.assistant.di

import com.verbalogix.assistant.data.capability.CapabilitySource
import com.verbalogix.assistant.data.capability.UnavailableCapabilitySource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds the capability seam.
 *
 * There is deliberately no debug variant of this module. A `@Module` that swapped in a
 * fake for debug builds would mean the wiring differs between what is developed against
 * and what ships -- and the gate being tested would be the one that never runs on a
 * user's phone. Debug fakes are constructed directly by previews and tests in the
 * source sets that own them, never injected, so the graph is identical in both builds.
 */
@Module
@InstallIn(SingletonComponent::class)
object CapabilityModule {

    @Provides
    @Singleton
    fun provideCapabilitySource(): CapabilitySource = UnavailableCapabilitySource()
}
