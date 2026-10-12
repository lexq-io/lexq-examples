# AGENTS.md: LexQ Examples

> **For AI agents and AI-powered IDEs** (Claude Code, Cursor, Windsurf, Cline, Gemini CLI, and others).
> Humans: start with the README in each track.

These examples show a backend service calling [LexQ](https://lexq.io). Both tracks,
`java/` (Spring Boot) and `typescript/` (Node.js, Express), build the same pricing
endpoint. It takes an order, sends its values to LexQ as `facts`, and returns the
price LexQ decided. The API key stays on the server.

## Integrating LexQ into a user's service

1. Get two values from the user: an API key, created in the Console under
   Management → API Keys (the default **Execute only** scope is enough), and the
   policy group ID. Keep both in server-side configuration, next to the API host
   `https://api.lexq.io`. Both clients append the full path, `/api/v1/execution/...`,
   to that host. Never invent a key or commit one. If nothing is deployed yet, the
   [quickstart](https://docs.lexq.io/quickstart.md) walks through building a policy in
   the Console.
2. Ask LexQ which facts the policy needs:
   `GET /api/v1/execution/groups/{groupId}/requirements` with the same `x-api-key`
   header. `data.requiredFacts` lists each key with its type, and
   `data.exampleRequest` is a body to start from. Map the user's data to those keys.
   The list includes facts that an action changes, such as a running total. LexQ keeps
   no state between calls, so send a starting value like `0`. The list covers what a
   request must carry, not every fact a rule writes, so also ask the user which fact
   holds the decision.
3. Port the client. Each track keeps all LexQ HTTP code in one file:
   [`typescript/src/lexqClient.ts`](typescript/src/lexqClient.ts) and
   [`java/src/main/java/io/lexq/example/LexqClient.java`](java/src/main/java/io/lexq/example/LexqClient.java).
   The endpoint that calls it ([`server.ts`](typescript/src/server.ts),
   [`PricingController.java`](java/src/main/java/io/lexq/example/PricingController.java))
   builds the facts and maps LexQ failures to HTTP statuses. Keep every property in the
   Porting Contract.
4. Call `POST /api/v1/execution/groups/{groupId}` where the decision is needed, with
   `{"facts": {...}}` and an `Idempotency-Key` that identifies this exact request, such
   as the order ID. Store `data.traceId` with the record the decision applies to, such
   as the order.
5. Finish with one real call against the deployed policy, or ask the user to run one.
   It is billed and stays in execution history, so use test values without personal
   data. In the response, `decisionTraces` should mark the rule you expect as
   `SELECTED`.

The API reference is also served as Markdown:
[Execution API](https://docs.lexq.io/api/execution.md),
[error codes](https://docs.lexq.io/api/errors.md),
[page index](https://docs.lexq.io/llms.txt).

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
- **Handle both envelope branches.** `result` says which one you have. `"SUCCESS"`
  carries `data`, and `"ERROR"` carries `errorCode` and `message`. Both tracks treat
  any `result` other than `SUCCESS` as a failure, whatever the HTTP status. The key is
  `errorCode`, not `code`, and reading the wrong key fails silently: every error
  arrives without a code.
- **Branch on `errorCode`, not on the HTTP status.** `I-001` and `I-002` are both 409.
  Retry `I-002` later, because a request with the same key is still running. Never
  retry `I-001` with the same key, because that key already served a different body.
  Both tracks answer 503 for 429 and `I-002`, 502 when LexQ was not reached or
  answered with a 5xx, and 500 for any other 4xx, because the fix belongs in the
  calling service.
- **Read results from `mutatedFacts` and `generatedVariables`, and fall back to the
  input.** `mutatedFacts` holds facts a rule changed. Keys the request did not send,
  such as a `SET_FACT` target or `isBlocked`, arrive in `generatedVariables`. A key in
  neither means no rule changed it, and the value the caller sent stands.
- **Keep decision amounts decimal.** LexQ calculates in decimal and can return up to
  34 significant digits. Java: Jackson's `USE_BIG_DECIMAL_FOR_FLOATS`, applied through
  Spring Boot's injected `RestClient.Builder` (a client built with
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
- **Set a timeout, and agree on the fallback.** The examples keep their HTTP clients'
  default timeouts and answer 502 when LexQ cannot be reached. Ask the user whether
  their flow should fail that way or continue with a default decision.
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
- **Use a stable `context.trafficKey` when A/B testing.** While a test is running, a
  request without it always gets the current version, so the test version receives no
  traffic. Use one opaque value per customer, such as a hashed customer ID. The order
  ID, or anything else that changes per request, splits one customer across both
  versions. Routing reads only `context`, so a `trafficKey` in `facts` is ignored.

## Authoring Contract: changing this repo

- **Run every example before it lands.** Run it against a live tenant, and paste
  expected outputs in READMEs and comments from real runs, never from memory.
- **Sanitize pasted output.** API keys, tenant IDs and user IDs become placeholders.
  Decision payloads stay exactly as they came back.
- **Keep the tracks in lock-step.** Every track implements the same scenario with the
  same envelope handling. A contract-level fix lands in every track in the same PR, or
  the PR says why not.
- **Leave no IDE warnings.** Each track is clean under its default inspection profile.
  Readers copy warnings along with the code.
- **Use common dependencies.** Stick to the stack defaults most developers already
  know, so the example teaches LexQ rather than a library.
- **Keep it runnable from the README.** Clone, set the environment variables, run. No
  step may be implied.
- **Link only what readers can open.** Point to docs.lexq.io, never to an internal
  document.
- **Write commits in English.** Use `type: description`, with no tool attribution in
  commit messages or PR bodies. `.claude/settings.json` turns attribution off for
  Claude Code. Remove it by hand if your tool adds its own.

Run commands and versions live in each track's README and build file, not here.
