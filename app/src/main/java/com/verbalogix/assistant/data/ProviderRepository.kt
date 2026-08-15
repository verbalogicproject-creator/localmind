package com.verbalogix.assistant.data

import androidx.room.withTransaction
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Owns which endpoint is current, and the one place providers get seeded.
 *
 * Seeding on the count == 0 path rather than inside MIGRATION_1_2 is deliberate: a
 * fresh install never runs a migration, so seeding there would leave new users with an
 * empty picker while upgraders got two entries. One path, one set of defaults.
 */
@Singleton
class ProviderRepository @Inject constructor(
    private val db: LocalmindDatabase,
    private val dao: ProviderDao,
) {
    fun observeAll(): Flow<List<Provider>> = dao.observeAll()

    /**
     * The defaults describe the machine this app was built for, and each is on the
     * silicon that actually suits it -- measured, not assumed:
     *
     *   LFM2.5-8B-A1B on Adreno OpenCL, because it is a hybrid conv + GQA model and
     *   the Hexagon backend has no kernels for the convolution half. It will not load
     *   on HTP0 at all.
     *
     *   Qwen3.5-4B on the NPU, because it is a pure transformer, which is exactly the
     *   shape HTP does have kernels for.
     *
     * If a port is not serving, the picker still lists it and the status strip says
     * unreachable. A configured-but-down provider is a fact worth showing, not an
     * entry to hide.
     */
    private val defaults = listOf(
        Provider(name = "LFM2.5 8B", baseUrl = "http://127.0.0.1:8080", isActive = true),
        Provider(name = "Qwen3.5 4B", baseUrl = "http://127.0.0.1:8081"),
    )

    suspend fun ensureSeeded() {
        if (dao.count() > 0) return
        db.withTransaction { defaults.forEach { dao.insert(it) } }
    }

    /**
     * Never null once ensureSeeded has run. Falls back to the first default rather
     * than throwing, because an assistant that refuses to open because of a settings
     * row is worse than one pointed at a plausible endpoint.
     */
    suspend fun active(): Provider = dao.active() ?: defaults.first()

    /** One statement could leave zero rows active; the pair cannot. */
    suspend fun select(id: Long) = db.withTransaction {
        dao.clearActive()
        dao.setActive(id)
    }
}
