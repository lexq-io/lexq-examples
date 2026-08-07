# AGENTS.md — LexQ Examples

> **For AI agents and AI-powered IDEs** (Claude Code, Cursor, Windsurf, Cline, Gemini CLI, and others).

Runnable integration examples for [LexQ](https://lexq.io). One shared scenario,
one track per language (`java/`, `typescript/`, more to come). Public repo —
everything in English, including commits (`type. description`).

Humans: start with the README in each track. This file is the contract layer.

## Porting Contract — copying these examples into another codebase

You are probably here because a user asked you to integrate LexQ. When you port
this code, these properties are the example — do not optimize them away:

- **Stop the HTTP client from throwing on 4xx.** LexQ reports failures in the
  response envelope, so the client has to hand you the body for every status.
  Spring's `RestClient` throws by default, `axios` rejects on 4xx, `requests`
  throws as soon as you call `raise_for_status()`. Miss this and every LexQ error
  arrives as a transport failure with no status and no code — the envelope
  handling below never runs at all.
- **Handle both envelope branches.** The success branch carries `data`; the
  failure branch carries `errorCode` + `message`. Surface both — never collapse
  to a bare "request failed". The failure key is `errorCode`, not `code`: reading
  the wrong one is invisible at runtime, and every error arrives without a code.
- **Branch on `errorCode`, not on the HTTP status.** `I-001` and `I-002` are both
  409 and their correct handling is opposite — retry one, never the other.
- **The API key stays server-side.** It travels in the `x-api-key` header from a
  backend only — never a browser. Placeholders (`<your-api-key>`) stay
  placeholders: ask the user for theirs, never invent or hardcode one.
- **Keep the idempotency key and the traceId log line.** Retries not executing
  twice, and decisions being auditable later, are the product — not boilerplate.
- **Keep the *why* comments.** They explain contract, not syntax.

### Engine behaviour these examples do not yet demonstrate

Not in the code below — no track exercises them yet. Listed because porting a
*different* LexQ integration runs into them, and both fail silently.

- **Accumulator facts need a caller-sent seed.** The engine is stateless, so a
  fact an action adds to must arrive with a starting value (e.g.
  `"shipping_cost_usd": 0`). Omitting the seed changes decisions without erroring.
- **List-operator values are JSON arrays.** `HAS_ANY` / `HAS_ALL` / `HAS_NONE`
  take an array. A comma-joined string doesn't error — it evaluates to no match.

## Authoring Contract — adding or changing examples in this repo

- **Executed before it lands.** Every example runs against a live tenant first.
  Expected outputs in READMEs and comments are pasted from real runs, never
  typed from memory.
- **Sanitize pasted output.** API keys, tenant and user IDs become placeholders.
  Decision payloads stay verbatim — that's what the reader came to see.
- **All language tracks stay in lock-step.** Every track implements the same
  scenario with the same envelope handling. A contract-level fix lands across
  all tracks in the same PR, or the PR says why not.
- **IDE-clean.** Zero warnings in each track's default inspection profile — an
  example teaches its warnings along with its code.
- **Boring dependencies.** Stack defaults a junior already knows. An exotic
  library teaches the library, not LexQ.
- **Runnable end-to-end.** Each track's README survives clone → set env vars →
  run, with no implied steps.
- **No internal document references.** Every pointer a reader can't follow is a
  dead end. Link docs.lexq.io.

## What this file does not contain

Example inventory, run commands, stack versions — each track's README and the
code are the source of truth.