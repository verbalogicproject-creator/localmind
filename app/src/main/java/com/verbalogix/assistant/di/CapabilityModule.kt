package com.verbalogix.assistant.di

import com.verbalogix.assistant.data.capability.CapabilitySource
import com.verbalogix.assistant.data.harness.HarnessSessionRepository
import com.verbalogix.assistant.data.harness.ManualPairingCredentialSource
import com.verbalogix.assistant.data.harness.PairingCredentialSource
import dagger.Binds
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

    /**
     * Capabilities now come from the Harness, not from a hardcoded refusal.
     *
     * This returned `UnavailableCapabilitySource()` for as long as there was no client to
     * ask. The answer is usually still "unavailable" -- an unpaired session declares
     * nothing -- but it is now an OBSERVED unavailability rather than an asserted one,
     * and it becomes availability the moment a session is live without any code change
     * here.
     */
    @Provides
    @Singleton
    fun provideCapabilitySource(repository: HarnessSessionRepository): CapabilitySource =
        repository
}

/**
 * Where a pairing credential may come from.
 *
 * A separate module with a `@Binds` because the implementation is expected to change: the
 * Foundry is building a Termux→Localmind pairing bridge, and when its contract is
 * observed it arrives as a second implementation bound here. Everything downstream
 * consumes the interface and needs no edit.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PairingModule {

    @Binds
    @Singleton
    abstract fun bindPairingCredentialSource(
        impl: ManualPairingCredentialSource,
    ): PairingCredentialSource
}
