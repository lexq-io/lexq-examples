# LexQ examples

How to integrate [LexQ](https://lexq.io) into your backend service.

LexQ is called server-side: your service gathers the facts, calls the LexQ
Execution API, and uses the decision it returns.

## The Execution API

- Base URL: `https://api.lexq.io/api/v1/execution`
- Auth: every request sends an `x-api-key` header

Create the key in the Console under **Management → API Keys**. The default
**Execute only** scope is all these examples need, and it is the right one for a
key embedded in your service. **Manage** exists for policy tooling such as the CLI,
and granting it here would hand your application write access it never uses.
See [API key scopes](https://docs.lexq.io/api/authentication#scope).

LexQ has three execution modes. This repo's example uses **Single Group**, the
standard path for evaluating one decision:

| Mode         | Endpoint                       |
| ------------ | ------------------------------ |
| Single Group | `POST /groups/{groupId}`       |
| Batch        | `POST /groups/{groupId}/batch` |
| Composite    | `POST /composite`              |

Full reference: https://docs.lexq.io/api/execution

### Request

    POST /api/v1/execution/groups/{groupId}
    x-api-key: <your-api-key>
    Content-Type: application/json
    Idempotency-Key: <your-order-id>      # optional, prevents duplicate execution

    { "facts": { "paymentAmount": 150000, "customerTier": "VIP" }, "contexts": {} }

`facts` is required. To see which facts a deployed policy needs:
`GET /api/v1/execution/groups/{groupId}/requirements`.

`contexts` is optional request metadata. Values your rules evaluate belong in
`facts`. LexQ uses `contexts` in two ways. While an A/B test is running,
`contexts.trafficKey` decides which version serves the request, and without it the
test version gets no traffic ([Traffic Identity Key](https://docs.lexq.io/guides/ab-testing#traffic-identity-key)).
The whole object is also recorded with the execution, so keep personal data out of it.

`facts` and `contexts` are the only top-level fields. Any other field fails with
`C-001` / `400`, even when its value is `{}` or `null`. That includes `context`, the
old name of `contexts`.

`Idempotency-Key` is capped at **255 characters**. LexQ rejects a longer key with
`I-003` / `400` rather than truncating it, because two different keys must never
collapse into one and replay each other's result. These examples pass the order ID
straight through, so if your IDs can exceed that, hash them first. Like `contexts`,
the key is recorded with the execution, so it must not carry personal data.

### Response

    {
      "result": "SUCCESS",
      "data": {
        "traceId": "2e31f2b8-...",       // the execution's audit handle
        "executedAt": "...",
        "totalProcessingTimeMs": ...,
        "inputFacts": { ... },
        "inputContexts": { ... },        // the contexts the request sent
        "mutatedFacts": { ... },         // facts changed by rule actions
        "generatedVariables": { ... },
        "executionTraces": [ ... ],
        "decisionTraces": [ ... ]
      }
    }

Store `data.traceId` next to your own records. It is how you later look up
exactly which rules produced this decision.

## Worked example

- [`typescript/`](./typescript): a runnable pricing service in Node.js (Express)
- [`java/`](./java): the same pricing service in Spring Boot

## Porting Contract

These properties are what the examples teach. Keep them when you port the code:

- **Stop the HTTP client from throwing on error statuses.** LexQ reports failures in
  the response body, for 5xx as well as 4xx, so the client has to return the body for
  every status. Spring's `RestClient` throws by default, and `axios` rejects anything
  outside 2xx. `requests` and `httpx` raise only when you call `raise_for_status()`.
  If the client throws, every LexQ error arrives as a transport failure with no status
  and no code.
- **Treat a body that is not JSON as a failure, and keep its status.** A load
  balancer's 502 page or the 404 from a wrong host is HTML, and a JSON parser throws
  on it.
- **Do not follow redirects, and treat a 3xx as a failure that keeps its status.** A
  client that follows one sends the `x-api-key` header to the host in the `Location`
  header, and on a 307 or 308 the request body as well. `fetch` and Spring Boot's
  auto-configured `RestClient` both follow redirects by default. A 3xx usually means
  the API URL is wrong: `http://api.lexq.io` answers with a 301 to `https`.
- **Handle both envelope branches.** `result` says which one you have. `"SUCCESS"`
  carries `data`, and `"ERROR"` carries `errorCode` and `message`. Both tracks treat
  any `result` other than `SUCCESS` as a failure, whatever the HTTP status. The key is
  `errorCode`, not `code`, and reading the wrong key fails silently: every error
  arrives without a code.
- **Branch on `errorCode`, not on the HTTP status.** `I-001` and `I-002` are both 409.
  Retry `I-002` later, because a request with the same key is still running. Never
  retry `I-001` with the same key, because that key already served a different body.
  Both tracks answer 503 for 429 and `I-002`, 502 when a call did not reach LexQ, ran
  out of time or got a 5xx, and 500 for a 3xx or any other 4xx, because the fix
  belongs in the calling service.
- **Read results from `mutatedFacts` and `generatedVariables`, and fall back to the
  input.** `mutatedFacts` holds facts a rule changed. Keys the request did not send,
  such as a `SET_FACT` target or `isBlocked`, arrive in `generatedVariables`. A key in
  neither means no rule changed it, and the value the caller sent stands.
- **Keep decision amounts decimal.** LexQ calculates in decimal, and an amount can
  carry 34 significant digits or more. Java: Jackson's `USE_BIG_DECIMAL_FOR_FLOATS`,
  applied through Spring Boot's injected `RestClient.Builder` (a client built with
  `RestClient.builder()` reads doubles). Python: read with
  `response.json(parse_float=Decimal)`, which works with `httpx` and `requests`, and
  send amounts as JSON numbers. `json.dumps` rejects `Decimal`, and `default=str`
  turns it into a string that no rule matches. Go: `json.Decoder.UseNumber()`.
  JavaScript has no decimal type, and the comment near the top of `lexqClient.ts` says
  when that matters.
- **Leave out a value you do not have instead of sending `null`.** Both tracks drop
  missing fields, and LexQ answers `P-015` with their names. In Python, drop `None`
  values before sending, for example with Pydantic's `model_dump(exclude_none=True)`.
- **Keep the API key on the server.** It goes in the `x-api-key` header, not in
  `Authorization`, and only from a backend.
- **Keep the idempotency key and the traceId.** A retry with the same key and the same
  body does not execute twice and returns the original traceId. The same key with a
  different body fails with `I-001`, so pricing a changed order again needs a new key.
  Keys are capped at 255 characters and are recorded with the execution, so hash long
  IDs and keep personal data out of them.
- **Put a time limit on every call, retry with the same key, and agree on the
  fallback.** Each attempt gets up to 5 seconds, and a call makes at most 3 attempts
  within 15 seconds. Both tracks retry when no complete response arrives, on a 502,
  503, 504 or 429, and on `I-002`, waiting at least 1 second, then at least 2. Every
  attempt sends the same `Idempotency-Key` and the same body, and a call without a key
  makes its own. If an earlier attempt succeeded, LexQ answers the retry with its
  stored response and does not bill it. Agree with whoever owns the flow that calls
  LexQ on whether it should fail when the attempts run out, as the examples do, or
  continue with a default decision.
  [When LexQ Is Slow or Unavailable](https://docs.lexq.io/api/execution#when-lexq-is-slow-or-unavailable)
  in the docs says what to do about each kind of failure.
- **Carry over the comments that explain the LexQ contract.** Leave behind the ones
  about the source language, such as the JavaScript number note.

## Mistakes that still return 200

LexQ does not report these as errors. When a decision looks wrong, read
`decisionTraces` in the response. Each rule's entry says whether it fired and why.

- **Send each fact in its declared type.** A number sent as a string (`"150000"`) does
  not match, and neither does a `LIST_STRING` or `LIST_NUMBER` fact sent as a
  comma-joined string instead of a JSON array. The rule does not fire, and its
  `decisionTraces[].reasonDetail` reads `Evaluation error: FACT_TYPE_MISMATCH`.
- **Check `isBlocked`.** A `BLOCK` action neither fails the call nor stops other
  rules. It sets `isBlocked` in `generatedVariables`, and the caller has to enforce it.
- **Use a stable `contexts.trafficKey` when A/B testing.** While a test is running, a
  request without it always gets the current version, so the test version receives no
  traffic. Use one opaque value per customer, such as a hashed customer ID. The order
  ID, or anything else that changes per request, splits one customer across both
  versions. Routing reads only `contexts`, so a `trafficKey` in `facts` is ignored.

## Maintenance

I'm Sanghyun Park, and I maintain these examples. I write most of the code,
comments, commit messages and pull request descriptions with AI coding tools,
mainly Claude Code. I decide each change, read every commit and diff before it is
pushed, and merge every pull request myself. The sample responses in `java/` and
`typescript/` come from runs against a LexQ test tenant. The rules for changing
these examples are in [CONTRIBUTING.md](CONTRIBUTING.md).
