package com.verbalogix.assistant.data.harness

/**
 * The only authority Localmind may ever hold.
 *
 * Localmind CONSUMES mounted knowledge packs. Knowledge Studio creates, reviews,
 * evaluates, builds and signs them. That boundary is worth enforcing in the type system
 * rather than in review comments, because the failure it prevents is silent: a client
 * that holds `mounts:write` can activate a pack, and an activation is a trust decision
 * the user did not make in the place trust decisions are supposed to be made.
 *
 * WHAT IS DELIBERATELY ABSENT, and must stay absent:
 *
 *   mounts:write   activation, deactivation, update, rollback
 *   pack:install   installation
 *   state:write    harness state initialization
 *   expert:plan    expert creation and refresh planning
 *   expert:audit   audit
 *
 * `docs/ui/api-bindings.json` maps `mount.activate` to `localmind_expert_detail_final`,
 * so an earlier reading of the design DID intend this client to activate mounts. That
 * grant has since been withdrawn: Foundry 0.3.2 issues Localmind read-only sessions.
 * The mapping in the bindings file is therefore stale with respect to authority, and
 * this enum is the authoritative statement of what may be requested.
 *
 * There is no `values()`-driven "request everything" path on purpose. A caller asks for
 * what it needs; [REQUESTED] is the whole set this app is entitled to, and it is small
 * enough to read.
 */
enum class HarnessScope(val wire: String) {

    /** Read the capability document, to learn what the Harness declares. */
    CAPABILITIES_READ("capabilities:read"),

    /** Read the expert catalog and inspect a release. Read-only by construction. */
    EXPERT_READ("expert:read"),

    /** Retrieve evidence for a query. Read-only. */
    QUERY_READ("query:read"),

    /**
     * Exchange an unexpired session for its successor.
     *
     * This is the scope that makes rotation workable: sessions are memory-only and
     * short-lived, so without refresh the user would re-pair continuously. It is NOT a
     * way to widen authority -- a refreshed session carries the same scopes or fewer,
     * and a refresh that returns MORE than was asked for is treated as a fault by
     * [HarnessSessionPolicy], not as a bonus.
     */
    TOKEN_REFRESH("token:refresh");

    companion object {
        /** Everything this client may ask for, and nothing else. */
        val REQUESTED: Set<HarnessScope> = setOf(
            CAPABILITIES_READ, EXPERT_READ, QUERY_READ, TOKEN_REFRESH,
        )

        /**
         * Parse a wire scope, returning null for anything unrecognised.
         *
         * Null rather than an exception because an unknown scope in a server response
         * is not a crash, it is a session this client must refuse -- see
         * [HarnessSessionPolicy.admit]. Fail closed, and say which one.
         */
        fun fromWire(value: String): HarnessScope? = entries.firstOrNull { it.wire == value }
    }
}
