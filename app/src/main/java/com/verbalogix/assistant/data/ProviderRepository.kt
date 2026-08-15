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
     * The defaults describe the machine this app was built for. BOTH generation
     * endpoints are on the GPU, and that is a measured correction to what this comment
     * said first.
     *
     * The original claim was that Qwen belongs on the Hexagon NPU "because it is a pure
     * transformer, which is exactly the shape HTP has kernels for". The kernel half is
     * true. The throughput half is false, and by a wide margin:
     *
     *   LFM2.5-8B-A1B   Adreno OpenCL   22.7 - 24.9 tok/s
     *   Qwen3.5-4B      Adreno OpenCL   11.7 tok/s
     *   Qwen3.5-4B      HTP0 (NPU)      1.8 tok/s     <- 6.5x SLOWER than the GPU
     *
     * HTP wins at prefill and loses at decode. Prefill is one large batched matmul,
     * compute-bound, which is what a DSP is built for. Decode is a single token at a
     * time -- memory-bandwidth-bound, with a FastRPC round trip per token, and that
     * fixed IPC cost dominates everything at batch size 1.
     *
     * Which is why embeddinggemma and the reranker ARE fast on HTP0: embedding and
     * cross-encoder scoring are pure prefill and never decode. On this device the NPU
     * is a RAG accelerator, not a chat accelerator.
     *
     * Note also that LFM2.5-8B-A1B is both larger and twice as fast as the dense 4B --
     * 8B of weights at roughly 1B of active compute per token. For chat there is no
     * trade-off to make; the MoE simply wins.
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
