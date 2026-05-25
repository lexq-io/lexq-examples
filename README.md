# LexQ examples

How to integrate [LexQ](https://lexq.io) into your backend service.

LexQ is called server-side: your service gathers the facts, calls the LexQ
Execution API, and uses the decision it returns. The API key never reaches
a browser.

## The Execution API

- Base URL: `https://api.lexq.io/api/v1/execution`
- Auth: every request sends an `x-api-key` header

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