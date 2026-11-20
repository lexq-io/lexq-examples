# AGENTS.md: LexQ Examples

These examples show a backend service calling [LexQ](https://lexq.io). Both tracks,
`java/` (Spring Boot) and `typescript/` (Node.js, Express), build the same pricing
endpoint. It takes an order, sends its values to LexQ as `facts`, and returns the
price LexQ decided.

Read these before you port the client or change this repository:

- [Porting Contract](README.md#porting-contract): what a port must keep
- [Mistakes that still return 200](README.md#mistakes-that-still-return-200): what
  LexQ does not report as an error
- [CONTRIBUTING.md](CONTRIBUTING.md): the rules for changing these examples

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
3. Use the SDK in Node.js, or port the Java client to another language. Install the
   SDK with `npm install @lexq/sdk`
   ([README](https://github.com/lexq-io/lexq-sdk-node#readme)).
   [`typescript/src/server.ts`](typescript/src/server.ts) creates one client for the
   service, builds the facts, and picks an HTTP status for each LexQ failure in its
   `fallback`.
   [`java/src/main/java/io/lexq/example/LexqClient.java`](java/src/main/java/io/lexq/example/LexqClient.java)
   keeps all LexQ HTTP code in one file, and
   [`PricingController.java`](java/src/main/java/io/lexq/example/PricingController.java)
   builds the facts and maps LexQ failures to HTTP statuses. Keep every property in the
   [Porting Contract](README.md#porting-contract).
4. Call `POST /api/v1/execution/groups/{groupId}` where the decision is needed, with
   `{"facts": {...}}` and an `Idempotency-Key` that identifies this exact request, such
   as the order ID. Store `data.traceId` with the record the decision applies to, such
   as the order. With the SDK, the call is
   `execute(groupId, { facts, idempotencyKey, fallback })`, and the traceId is
   `result.traceId` when `result.source` is `"lexq"`. The `fallback` returns what the
   service uses when the call fails, so ask the user what that should be.
5. Finish with one real call against the deployed policy, or ask the user to run one.
   It is billed and stays in execution history, so use test values without personal
   data. In the response, `decisionTraces` should mark the rule you expect as
   `SELECTED`.

The API reference is also served as Markdown:
[Execution API](https://docs.lexq.io/api/execution.md),
[error codes](https://docs.lexq.io/api/errors.md),
[page index](https://docs.lexq.io/llms.txt).
