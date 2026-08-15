#!/usr/bin/env python3
"""A runnable mock Knowledge Foundry Harness.

WHY THIS IS CODE AND NOT A DOCUMENT

The Foundry side asked for the provider seam to be built against a "committed mock
contract". A prose contract is exactly the artifact this pipeline exists to distrust:
plausible, correctly shaped, and wrong in a way nobody notices until integration. Two
teams can read the same paragraph and build incompatible things.

A server cannot be ambiguous. If Localmind talks to this and works, the client half is
done, and the remaining risk is confined to whether the real Harness matches -- which
is now a diffable question rather than an interpretive one.

WHAT IT DOES NOT DO

No model, no retrieval, no embeddings. Answers are canned. This tests the WIRE, and
deliberately nothing else: a mock that tried to be clever would start having bugs of
its own, and then a client failure would have two possible causes instead of one.

    python3 harness_mock.py [--port 8091]

Standard library only, so it runs anywhere Python does -- including Termux, where
installing packages to test a contract would be its own obstacle.
"""

import argparse
import json
import re
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Two experts, because one cannot reveal a selection bug: a client that ignores the
# `model` field entirely would still look correct against a single-expert mock.
EXPERTS = {
    "handbook-2026": {
        "name": "Employee Handbook 2026",
        "description": "Policy, benefits, leave. 412 documents.",
        "status": "loaded",
        "kpack": {"version": "3.1.0", "documents": 412, "updated": "2026-08-01"},
    },
    "codebase-atlas": {
        "name": "Codebase Atlas",
        "description": "Architecture notes and ADRs. 88 documents.",
        # Deliberately not loaded, so the client's status strip has to render the
        # state honestly instead of assuming everything offered is ready.
        "status": "unloaded",
        "kpack": {"version": "0.9.2", "documents": 88, "updated": "2026-07-19"},
    },
}

# Keyed by the last user message, lowercased. The UNGROUNDED case is the important
# one and is easy to forget to implement, so it is reachable by asking anything the
# mock does not recognise.
CANNED = {
    "leave": (
        "Carry-over is capped at ten days [1], and unused days expire on 31 March [2].",
        [
            {"n": 1, "document": "handbook-2026.pdf", "page": 34,
             "quote": "Employees may carry over a maximum of ten days.",
             "document_id": "d_8f21", "chunk_id": "c_00412", "score": 0.87},
            {"n": 2, "document": "handbook-2026.pdf", "page": 35,
             "quote": "Carried-over leave expires on 31 March of the following year.",
             "document_id": "d_8f21", "chunk_id": "c_00418", "score": 0.81},
        ],
    ),
}

DOCUMENTS = {}
_doc_seq = [0]


class Handler(BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):
        print(f"  {self.command:6s} {self.path:34s} {fmt % args}")

    def _send(self, code, payload):
        body = json.dumps(payload, indent=2).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    # ---- GET -------------------------------------------------------------

    def do_GET(self):
        if self.path.rstrip("/") in ("/v1/models", "/models"):
            # Must never trigger a load. The client polls this to draw its status
            # strip, and a probe with side effects would make opening the app cost a
            # cold load -- which is exactly the trap /upstream/<model>/props turned
            # out to be on llama-swap.
            return self._send(200, {
                "object": "list",
                "data": [
                    {"id": eid, "object": "model", "owned_by": "foundry",
                     "name": e["name"], "description": e["description"],
                     "status": {"value": e["status"]}, "kpack": e["kpack"]}
                    for eid, e in EXPERTS.items()
                ],
            })

        m = re.fullmatch(r"/v1/documents/([\w-]+)", self.path)
        if m:
            doc = DOCUMENTS.get(m.group(1))
            if not doc:
                return self._send(404, {"error": "no such document"})
            # Pretend indexing takes a few seconds, so the client has to handle
            # "queued" rather than assuming ingest is synchronous.
            if doc["status"] == "queued" and time.monotonic() - doc["_t"] > 5:
                doc["status"], doc["chunks"] = "indexed", 218
            return self._send(200, {k: v for k, v in doc.items() if not k.startswith("_")})

        if self.path == "/health":
            return self._send(200, {"status": "ok"})
        self._send(404, {"error": "not found"})

    # ---- POST ------------------------------------------------------------

    def do_POST(self):
        length = int(self.headers.get("Content-Length") or 0)
        raw = self.rfile.read(length) if length else b""

        if self.path.rstrip("/") in ("/v1/chat/completions", "/chat/completions"):
            return self._chat(raw)
        if self.path.rstrip("/") == "/v1/documents":
            return self._ingest(raw)
        self._send(404, {"error": "not found"})

    def _chat(self, raw):
        try:
            req = json.loads(raw or b"{}")
        except ValueError:
            return self._send(400, {"error": "body is not JSON"})

        expert = req.get("model")
        if expert not in EXPERTS:
            # A real Harness cannot answer for a pack it does not have, and the client
            # must render that rather than showing an empty bubble.
            return self._send(404, {
                "error": f"no such expert: {expert!r}",
                "available": list(EXPERTS),
            })

        messages = req.get("messages") or []
        last = next((m.get("content", "") for m in reversed(messages)
                     if m.get("role") == "user"), "")

        answer, citations = None, []
        for key, (text, cites) in CANNED.items():
            if key in last.lower():
                answer, citations = text, cites
                break

        if answer is None:
            # THE UNGROUNDED PATH. Retrieval found nothing usable, so the honest
            # response says so and cites nothing. A client that renders this
            # identically to a grounded answer has the worst bug this UI can have:
            # a confident guess indistinguishable from a cited fact.
            answer = ("I could not find anything about that in "
                      f"{EXPERTS[expert]['name']}.")
            grounded, used, retrieved = False, 0, 12
        else:
            grounded, used, retrieved = True, len(citations), 24

        self._send(200, {
            "id": "chatcmpl-mock",
            "object": "chat.completion",
            "model": expert,
            "choices": [{
                "index": 0,
                "message": {"role": "assistant", "content": answer,
                            "reasoning_content": ""},
                "finish_reason": "stop",
            }],
            "timings": {"predicted_per_second": 24.2,
                        "predicted_n": len(answer.split())},
            "citations": citations,
            "receipt": {
                "grounded": grounded,
                "chunks_retrieved": retrieved,
                "chunks_used": used,
                "kpack": f"{expert}@{EXPERTS[expert]['kpack']['version']}",
                "retrieval_ms": 118,
            },
        })

    def _ingest(self, raw):
        name = self.headers.get("X-Document-Name")
        if not name:
            return self._send(400, {"error": "X-Document-Name is required"})
        if self.headers.get("Content-Type", "").startswith("application/json"):
            # Guard the exact mistake the contract forbids. A client that passes a
            # content:// URI instead of bytes gets a loud, named failure here rather
            # than a SecurityException inside the Harness -- where the cause would be
            # much harder to see.
            return self._send(400, {
                "error": "send raw bytes, not a reference. A SAF content:// URI is "
                         "scoped to the granting app's UID and cannot be read by "
                         "another process.",
            })
        if not raw:
            return self._send(400, {"error": "empty body"})

        _doc_seq[0] += 1
        doc_id = f"d_{_doc_seq[0]:04x}"
        DOCUMENTS[doc_id] = {
            "document_id": doc_id, "status": "queued", "bytes": len(raw),
            "chunks": 0, "error": None, "_t": time.monotonic(),
        }
        self._send(200, {"document_id": doc_id, "status": "queued", "bytes": len(raw)})


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--port", type=int, default=8091)
    ap.add_argument("--host", default="127.0.0.1")
    args = ap.parse_args()
    print(f"mock harness on http://{args.host}:{args.port}")
    print(f"  experts: {', '.join(EXPERTS)}")
    print("  ask about 'leave' for a grounded answer; anything else is UNGROUNDED")
    ThreadingHTTPServer((args.host, args.port), Handler).serve_forever()


if __name__ == "__main__":
    main()
