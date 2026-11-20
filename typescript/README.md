# LexQ integration example: TypeScript (Node.js, Express)

A minimal backend service that calls the LexQ Execution API to price an
order. It uses [`@lexq/sdk`](https://github.com/lexq-io/lexq-sdk-node), the Node.js
client, which sets the time limits, retries temporary failures and keeps amounts
decimal.

When you port this code, keep the [Porting Contract](../README.md#porting-contract).

## Requirements

- Node.js 22.12 or later, the minimum `@lexq/sdk` supports, and the npm that ships
  with Node.js. Node's `--env-file` reads `.env`

## Run

1. `npm install`
2. `cp .env.example .env` and fill in:
   - `LEXQ_API_URL`: `https://api.lexq.io`
   - `LEXQ_API_KEY`: your API key. The default **Execute only** scope is enough
   - `LEXQ_GROUP_ID`: the policy group to evaluate against
3. `npm run dev`
4. Test it:

       curl -X POST localhost:3000/price \
         -H "Content-Type: application/json" \
         -d '{"orderId": "order-1", "paymentAmount": 150000, "customerTier": "VIP"}'

   The response carries the price LexQ returned and the trace handle for it:

       {"orderId":"order-1","finalPrice":135000,"lexqTraceId":"aeae1ab1-b7c6-4454-ae06-8a353c59a482"}

   `finalPrice` is whatever your deployed rules produced. The
   [quickstart](https://docs.lexq.io/quickstart) policy takes 10% off any order of
   100000 or more, so it returns 135000 for 150000. The SDK reads every number in the
   decision as a `Decimal`, and `finalPrice` keeps every digit LexQ sent, even more
   than a JavaScript number holds. When no rule matches, `mutatedFacts` comes back
   empty and the amount you sent is returned unchanged.

   When the policy needs a fact this service did not send, you get LexQ's error
   code instead of a bare failure. Send the request again as `order-2`, so the
   idempotency key is new, and leave out `paymentAmount`:

       {"error":"rule evaluation failed","lexqErrorCode":"P-015"}

## Where to look

- `src/server.ts`: the pricing endpoint. It creates one `LexqClient` for the
  service, and the `fallback` it passes picks the status to answer with when the
  call fails
- The `@lexq/sdk` [README](https://github.com/lexq-io/lexq-sdk-node#readme): the
  client's defaults and options