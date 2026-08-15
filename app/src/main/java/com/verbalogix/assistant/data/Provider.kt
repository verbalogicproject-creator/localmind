package com.verbalogix.assistant.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * An endpoint this app can talk to.
 *
 * THE PROVIDER SEAM
 *
 * Localmind is a client and owns no knowledge. Anything speaking the
 * OpenAI-compatible protocol on loopback is a valid provider, which makes two things
 * the same change:
 *
 *   - running several specialists at once, each on the silicon that suits it:
 *       :8080  LFM2.5-8B-A1B    Adreno OpenCL   reasoning, synthesis
 *       :8081  Qwen3.5-4B       Adreno OpenCL   code, structured output
 *     (both on the GPU: HTP decode measured 6.5x slower -- see ProviderRepository)
 *   - swapping the direct llama.cpp path for a Foundry Harness later, without the
 *     app learning anything new. Same protocol, different thing behind it.
 *
 * WHAT ROOM DOES NOT OWN
 *
 * Deliberately no documents, chunks or embeddings here. Durable knowledge -- chunks,
 * graphs, provenance, embeddings -- belongs to Knowledge Foundry, and Room owns app
 * state: conversations, settings, provider handles, disposable caches.
 *
 * That boundary arrived from a parallel session before this schema shipped, which
 * matters because the persisted schema is one of exactly three things that can never
 * change after a user installs. Building the RAG store here and moving it later would
 * have meant migrating away from a schema that should never have existed.
 */
@Entity(tableName = "providers")
data class Provider(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Shown in the picker. The user's name for it, not the model's. */
    val name: String,
    val baseUrl: String,
    /**
     * "direct"  -- llama.cpp itself; today's proven path
     * "harness" -- a Foundry Harness in front of it, adding retrieval, citations and
     *              tool calls. Same wire protocol, so the client code is identical;
     *              the mode exists so the UI can say which one is answering.
     */
    val mode: String = MODE_DIRECT,
    /**
     * The `model` field to send, or empty for none.
     *
     * Empty means one server, one model, on its own port -- the baseline, and the only
     * path proven end to end on a physical device. Non-empty means the endpoint is a
     * swap proxy that owns process lifecycle: several models behind ONE port, started
     * on demand by name, with the previous one stopped first.
     *
     * That distinction is why this is a column rather than a constant, and it also
     * decides how status must be read -- see LlamaClient.status(), where getting it
     * wrong costs the user 41 seconds.
     *
     * The SQL default is declared explicitly, and the Kotlin `= ""` is not a substitute
     * for it. A Kotlin default never reaches SQL, so without this annotation Room would
     * emit CREATE TABLE with no default while MIGRATION_2_3 must supply one -- SQLite
     * rejects ADD COLUMN NOT NULL without a default on a non-empty table.
     *
     * Room tolerates that mismatch, which is what makes it dangerous: a fresh install
     * and an upgraded install would end up with genuinely different table definitions,
     * and the first raw INSERT omitting this column would work on one and fail on the
     * other. Declaring it here makes both paths produce identical schemas.
     */
    // The inner quotes are load-bearing: Room splices this string into the DDL
    // verbatim, so "" would emit `DEFAULT ` followed by nothing.
    @ColumnInfo(defaultValue = "''")
    val model: String = "",
    val isActive: Boolean = false,
) {
    /** A swap proxy owns model lifecycle; a plain llama-server does not. */
    val isSwap: Boolean get() = model.isNotEmpty()

    companion object {
        const val MODE_DIRECT = "direct"
        const val MODE_HARNESS = "harness"
    }
}

@Dao
interface ProviderDao {
    @Query("SELECT * FROM providers ORDER BY id")
    fun observeAll(): Flow<List<Provider>>

    @Query("SELECT * FROM providers ORDER BY id")
    suspend fun all(): List<Provider>

    @Query("SELECT * FROM providers WHERE isActive = 1 LIMIT 1")
    suspend fun active(): Provider?

    @Insert
    suspend fun insert(provider: Provider): Long

    @Update
    suspend fun update(provider: Provider)

    /** Exactly one active at a time; the two statements run under one transaction. */
    @Query("UPDATE providers SET isActive = 0")
    suspend fun clearActive()

    @Query("UPDATE providers SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: Long)
}
