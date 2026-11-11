# LexQ examples

How to integrate [LexQ](https://lexq.io) into your backend service.

LexQ is called server-side: your service gathers the facts, calls the LexQ
Execution API, and uses the decision it returns. The API key never reaches
a browser.

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

## Maintenance

I'm Sanghyun Park, and I maintain these examples. I write most of the code,
comments, commit messages and pull request descriptions with AI coding tools,
mainly Claude Code. I decide each change, read every commit and diff before it is
pushed, and merge every pull request myself. The sample responses in `java/` and
`typescript/` come from runs against a LexQ test tenant.
