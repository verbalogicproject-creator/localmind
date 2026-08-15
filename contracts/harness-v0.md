# Harness contract v0

The wire shape Localmind expects from a Knowledge Foundry Harness.

**Status: MOCK. No Foundry implements this yet.** It exists so the client can be built
and tested before F7 closes, and so both sides are arguing about the same artifact
instead of two prose descriptions that sound compatible.

A runnable implementation lives beside this file at `mock/harness_mock.py`. If the two
ever disagree, **the mock is authoritative** — it is the thing that gets tested.

## Who owns what

Settled with the Foundry side and restated here because a boundary is only useful if
both parties can name it identically:

| Verb | Owner |
|---|---|
| build, validate, version, install, activate, update, query `.kpack` | **Foundry** |
| expose activated packs over the wire | **Harness** |
| select an expert; render grounded answers, citations, receipts | **Localmind** |

Localmind never parses, validates or versions a pack. It lists what the Harness offers,
lets the user pick, and renders what comes back.

## The load-bearing design decision: an expert IS a model

The Harness speaks the **OpenAI-compatible surface**, and the `model` field names an
**expert pack**, not an LLM. Which LLM answers is the Harness's business.

That single choice means the client needs almost no new code. Localmind already routes
by `model` for llama-swap, so a Harness is another provider row with a different
`baseUrl`. The `mode` column exists to tell the *user* which they are talking to, not
to branch the transport.

It also matches the ownership table exactly: Localmind selects **knowledge**, never
weights, when a Harness is present.

## Endpoints

### `GET /v1/models` — list activated experts

Same shape llama-swap returns, so the existing status probe works unchanged.

```json
{
  "object": "list",
  "data": [
    {
      "id": "handbook-2026",
      "object": "model",
      "owned_by": "foundry",
      "name": "Employee Handbook 2026",
      "description": "Policy, benefits, leave. 412 documents.",
      "status": { "value": "loaded" },
      "kpack": { "version": "3.1.0", "documents": 412, "updated": "2026-08-01" }
    }
  ]
}
```

`status.value` is `loaded` or `unloaded`, exactly as llama-swap reports it, because the
client's status path already understands those two words.

**This endpoint must never trigger a load.** Localmind polls it to render the status
strip, and a probe that loads a pack would make opening the app cost whatever a cold
load costs. This is not hypothetical: `/upstream/<model>/props` on llama-swap does
exactly that — it started a model, evicted another, took six seconds and returned an
empty body.

### `POST /v1/chat/completions` — ask an expert

Request is the standard OpenAI body. `model` is the expert id.

Response is the standard OpenAI body **plus two additive fields**. Additive matters: a
client that ignores them still works, and the same parser handles direct llama.cpp.

```json
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": "Carry-over is capped at ten days [1], and expires 31 March [2].",
      "reasoning_content": ""
    },
    "finish_reason": "stop"
  }],
  "model": "handbook-2026",
  "timings": { "predicted_per_second": 24.2 },
  "citations": [
    { "n": 1, "document": "handbook-2026.pdf", "page": 34,
      "quote": "Employees may carry over a maximum of ten days.",
      "document_id": "d_8f21", "chunk_id": "c_00412", "score": 0.87 }
  ],
  "receipt": {
    "grounded": true,
    "chunks_retrieved": 24,
    "chunks_used": 2,
    "kpack": "handbook-2026@3.1.0",
    "retrieval_ms": 118
  }
}
```

**`receipt.grounded` is required on every response.** When retrieval found nothing
usable it must be `false` with `chunks_used: 0`, and the answer must say so rather than
inventing one.

This is the field that justifies the whole arrangement. An ungrounded answer rendered
identically to a grounded one is the single most damaging thing this UI could do — it
would make a confident guess indistinguishable from a cited fact. A missing `receipt`
is a **bug to surface**, not a field to skip.

`citations[].n` matches the `[1]` markers in `content`, so the renderer can link them
without parsing prose.

### `POST /v1/documents` — ingest

**Bytes, never a `content://` URI**, and this is a correctness constraint rather than a
preference.

A SAF permission grant is bound to the **granting app's UID**.
`takePersistableUriPermission` makes a URI durable for Localmind and nothing more. The
Harness is a separate Termux process under a different UID, so a URI handed across
resolves to `SecurityException` or an empty read. Localmind must open the descriptor
itself and stream the content.

```
POST /v1/documents
Content-Type: application/octet-stream
X-Document-Name: handbook.pdf
X-Document-Mime: application/pdf
X-Document-Modified: 1786800000
X-Kpack: handbook-2026

<raw bytes>
```

```json
{ "document_id": "d_8f21", "status": "queued", "bytes": 2481923 }
```

Room stores the returned `document_id` alongside the SAF URI. The URI is Localmind's
**re-open handle** — for showing the file again or re-ingesting after a change — and is
never sent anywhere.

`status` is `queued`, `indexed`, or `failed`. Ingest is asynchronous because chunking
and embedding a large PDF is not a request-response operation.

### `GET /v1/documents/{id}` — ingest progress

```json
{ "document_id": "d_8f21", "status": "indexed", "chunks": 218, "error": null }
```

## What Localmind will NOT do

Stated so the Harness side knows what it must own:

- parse, validate or version `.kpack` files
- store chunks, embeddings, or any durable knowledge — Room holds conversations,
  settings, provider rows, document *handles* and disposable caches, nothing else
- perform retrieval or reranking
- decide which LLM answers

## Open questions for the Foundry side

1. **Auth.** v0 assumes loopback with no auth. If the Harness is ever reachable off-host
   that is wrong, and the answer should land before anything ships.
2. **Streaming.** `stream: true` is unspecified. If citations arrive incrementally the
   renderer needs to know whether they can be revised mid-stream.
3. **Pack activation.** Localmind lists experts and selects one. Can it *activate* a
   pack that is installed but not loaded, or is that Foundry-only? The ownership table
   says Foundry, which implies the client can only choose among already-activated packs
   — worth confirming, because it decides whether `status: unloaded` is actionable in
   the UI or purely informational.
