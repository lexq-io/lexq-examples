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

LexQ has four execution modes. This repo's example uses **Single Group** —
the standard path for evaluating one decision:

| Mode             | Endpoint                                      |
| ---------------- | --------------------------------------------- |
| Single Group     | `POST /groups/{groupId}`                      |
| Specific Version | `POST /groups/{groupId}/versions/{versionId}` |
| Batch            | `POST /groups/{groupId}/batch`                |
| Composite        | `POST /composite`                             |

Full reference: https://docs.lexq.io/api/execution

### Request

    POST /api/v1/execution/groups/{groupId}
    x-api-key: <your-api-key>
    Content-Type: application/json
    Idempotency-Key: <your-order-id>      # optional — prevents duplicate execution

    { "facts": { "payment_amount": 150000, "customer_tier": "VIP" }, "context": {} }

`facts` is required; `context` is optional metadata passed to actions. To see
which facts a deployed policy needs: `GET /api/v1/execution/groups/{groupId}/requirements`.

`Idempotency-Key` is capped at **255 characters**. LexQ rejects a longer key with
`I-003` / `400` rather than truncating it, because two different keys must never
collapse into one and replay each other's result. These examples pass the order ID
straight through, so if your IDs can exceed that, hash them first.

### Response

    {
      "result": "SUCCESS",
      "data": {
        "traceId": "2e31f2b8-...",       // the execution's audit handle
        "inputFacts": { ... },
        "mutatedFacts": { ... },         // facts changed by rule actions
        "generatedVariables": { ... },
        "executionTraces": [ ... ],
        "decisionTraces": [ ... ]
      }
    }

Store `data.traceId` next to your own records — it is how you later look up
exactly which rules produced this decision.

## Worked example

- [`typescript/`](./typescript) — a runnable pricing service
- [`java/`](./java) — the same pricing service, Spring Boot