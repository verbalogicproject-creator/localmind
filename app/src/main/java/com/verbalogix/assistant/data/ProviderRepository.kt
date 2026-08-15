package com.verbalogix.assistant.data

import android.os.Build
import androidx.room.withTransaction
import com.verbalogix.assistant.BuildConfig
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow

/**
 * Owns which endpoint is current, and the one place providers are seeded.
 *
 * Seeding lives here rather than inside a migration because a fresh install never runs
 * one -- seeding in MIGRATION_1_2 would leave new users with an empty picker while
 * upgraders got entries. One path, one set of defaults.
 */
@Singleton
class ProviderRepository @Inject constructor(
    private val db: LocalmindDatabase,
    private val dao: ProviderDao,
) {
    fun observeAll(): Flow<List<Provider>> = dao.observeAll()

    /**
     * The mock Harness, seeded in DEBUG BUILDS ONLY.
     *
     * The Foundry side is explicit that Localmind may implement the mock Harness
     * provider now, and must NOT advertise expert-pack support in any user-visible
     * surface until Stage 3 (Harness / mount / retrieval) closes. Stage 1 F7 is closed;
     * Stage 2 .kpack construction and Stage 3 integration are not.
     *
     * Gating on BuildConfig.DEBUG satisfies both: the path is built and testable
     * against contracts/mock/harness_mock.py today, and a release build offers nothing
     * that implies working expert packs. Deleting a line later is not required -- the
     * gate simply flips when there is something real behind it.
     */
    private val mockHarness = listOf(
        Provider(
            name = "Handbook (mock)",
            baseUrl = MOCK_HARNESS_URL,
            mode = Provider.MODE_HARNESS,
            model = "handbook-2026",
        ),
    )

    /**
     * Seeded once, now withdrawn: the two direct ports.
     *
     * They listed the same two models the swap endpoint already serves, so the picker
     * showed four entries for two models and the duplicates differed only by a port
     * number the user should not have to reason about. The swap entries are strictly
     * better -- they start the server themselves.
     *
     * The DIRECT CODE PATH IS UNCHANGED and stays the baseline: an empty `model` still
     * means no model field and /props for status, and re-adding a direct provider is
     * one line here. What is withdrawn is the default UI entry, not the capability.
     *
     * The tradeoff, stated because it is real: with these gone, nothing works if
     * llama-swap is not running. Direct entries were a fallback you could reach by
     * starting one loader by hand.
     */
    private val retired = listOf(
        "http://127.0.0.1:8080" to "",
        "http://127.0.0.1:8081" to "",
    )

    /**
     * On-device inference, seeded ONLY where it can work.
     *
     * Upstream's JNI calls __android_log_is_loggable, which arrived in Android 11, so
     * the library cannot load on API 28 or 29. Listing it there would put a permanently
     * broken entry in the picker of a device that is otherwise a perfectly good client.
     *
     * Gated at seed time rather than hidden at render time on purpose: ensureDefaults
     * runs on every launch and keys on (baseUrl, model), so a phone that later receives
     * an OS upgrade past 30 gains the entry by itself, with no migration.
     */
    private val embedded = if (Build.VERSION.SDK_INT >= EmbeddedEngine.MIN_API) {
        listOf(
            Provider(
                name = "On-device",
                baseUrl = Provider.EMBEDDED_URL,
                mode = Provider.MODE_EMBEDDED,
            ),
        )
    } else {
        emptyList()
    }

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
     *
     * BOTH WAYS OF REACHING THE SAME TWO MODELS ARE SEEDED, because they fail
     * differently and neither subsumes the other.
     *
     * Direct (:8080, :8081) is one llama-server per port, started by hand in Termux.
     * It is the only path proven end to end on a physical device, and it keeps working
     * with no proxy and nothing else running. It stays the baseline.
     *
     * Swap (:8090, the "⇄" entries) is llama-swap, which owns process lifecycle: ask
     * for a model by name and it starts that server, stopping the other one first.
     * Measured, that is not a convenience -- MemAvailable falls to 658 MB with the 8B
     * resident, so the two genuinely cannot co-reside, and switching previously meant
     * killing a server by hand in a terminal.
     *
     * Cost of a swap, measured: about 35s to load plus generation. That is OpenCL
     * kernel compilation and weight upload, not proxy overhead -- llama-swap adds no
     * measurable cost, since throughput through it matches the standalone numbers
     * exactly.
     */
    private val defaults = listOf(
        Provider(name = "LFM2.5 8B", baseUrl = SWAP_URL, model = "lfm-8b", isActive = true),
        Provider(name = "Qwen3.5 4B", baseUrl = SWAP_URL, model = "qwen-4b"),
        Provider(name = "Bonsai 8B \u00b7 1-bit", baseUrl = SWAP_URL, model = "bonsai-8b"),
    ) + embedded + if (BuildConfig.DEBUG) mockHarness else emptyList()

    /**
     * Idempotent: inserts any default whose (baseUrl, model) pair is absent and leaves
     * everything else alone.
     *
     * This replaced a `count == 0` guard, which seeded only a truly empty table and so
     * could never deliver a NEW default to an already-installed copy -- exactly the
     * situation the swap entries create. Keying on the pair rather than the name also
     * means renaming a provider in a later release will not resurrect the old entry
     * beside it.
     *
     * Consequence worth knowing: a deleted default returns on next launch. Nothing can
     * hit that today because there is no delete affordance -- but adding one means
     * adding a tombstone, not just a DELETE.
     */
    suspend fun ensureDefaults() {
        val rows = dao.all()
        val existing = rows.map { it.baseUrl to it.model }.toSet()
        val missing = defaults.filterNot { (it.baseUrl to it.model) in existing }
        val stale = rows.filter { (it.baseUrl to it.model) in retired }
        if (missing.isEmpty() && stale.isEmpty()) return

        db.withTransaction {
            missing.forEach { dao.insert(it) }
            stale.forEach { dao.delete(it) }
            // Deleting the active row would leave the picker with nothing selected and
            // the app pointed at a fallback it never chose. Re-elect explicitly.
            if (dao.active() == null) {
                dao.all().firstOrNull()?.let { dao.setActive(it.id) }
            }
        }
    }

    /**
     * Never null once ensureDefaults has run. Falls back to the first default rather
     * than throwing, because an assistant that refuses to open because of a settings
     * row is worse than one pointed at a plausible endpoint.
     */
    suspend fun active(): Provider = dao.active() ?: defaults.first()

    /** One statement could leave zero rows active; the pair cannot. */
    suspend fun select(id: Long) = db.withTransaction {
        dao.clearActive()
        dao.setActive(id)
    }

    /**
     * Whether this row is one this build seeds — which is exactly the set that must
     * not be deletable.
     *
     * [ensureDefaults] reinserts any default whose (baseUrl, model) pair is absent, so
     * deleting one removes it until the next launch and then it is back. That is a
     * worse experience than having no delete at all, because the user is told the
     * action worked. Removing a default needs a tombstone, and a tombstone needs a
     * schema version; a user-added endpoint needs neither, because nothing reseeds it.
     *
     * Keyed on the pair rather than the id, for the same reason the seeding is: ids are
     * assigned by insertion order and differ between a fresh install and an upgraded one.
     */
    fun isDefault(provider: Provider): Boolean =
        defaults.any { it.baseUrl == provider.baseUrl && it.model == provider.model }

    /**
     * Add or update an endpoint the user typed, and make it current.
     *
     * Selecting it is not a convenience: someone who has just finished typing an
     * address wants to use it, and leaving the previous provider active means the next
     * message goes somewhere else and appears to prove the new endpoint broken.
     *
     * The caller passes an already-normalised URL. Validation belongs to [EndpointUrl]
     * so the dialog can explain a rejection while the text is still on screen, rather
     * than storing something that fails later at the socket.
     */
    suspend fun saveEndpoint(id: Long?, name: String, baseUrl: String, model: String) {
        val label = name.trim().ifEmpty { baseUrl.substringAfter("://") }
        val cleanModel = model.trim()
        db.withTransaction {
            // Adding an endpoint that already exists selects it instead of inserting a
            // twin. Without this, typing the seeded address by hand produces a second
            // row that isDefault() then reports as seeded -- so it cannot be deleted,
            // and the picker shows the same endpoint twice forever.
            val duplicate = if (id == null) {
                dao.all().firstOrNull { it.baseUrl == baseUrl && it.model == cleanModel }
            } else {
                null
            }
            val newId = if (duplicate != null) {
                duplicate.id
            } else if (id == null) {
                dao.insert(Provider(name = label, baseUrl = baseUrl, model = cleanModel))
            } else {
                val existing = dao.all().firstOrNull { it.id == id } ?: return@withTransaction
                dao.update(
                    existing.copy(name = label, baseUrl = baseUrl, model = cleanModel),
                )
                id
            }
            dao.clearActive()
            dao.setActive(newId)
        }
    }

    /**
     * Remove a user-added endpoint. Refuses a default rather than silently doing
     * nothing, because a delete that reports success and reverses itself on next launch
     * is the failure this guard exists to prevent.
     */
    suspend fun remove(provider: Provider) {
        require(!isDefault(provider)) { "a seeded provider cannot be deleted; it would be reseeded" }
        db.withTransaction {
            dao.delete(provider)
            // Deleting the active row leaves nothing selected and the app pointed at a
            // fallback it never chose. Same re-election as ensureDefaults.
            if (dao.active() == null) {
                dao.all().firstOrNull()?.let { dao.setActive(it.id) }
            }
        }
    }

    private companion object {
        const val SWAP_URL = "http://127.0.0.1:8090"
        const val MOCK_HARNESS_URL = "http://127.0.0.1:8091"
    }
}
