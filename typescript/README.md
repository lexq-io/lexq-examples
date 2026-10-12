# LexQ integration example: TypeScript (Node.js, Express)

A minimal backend service that calls the LexQ Execution API to price an
order. The LexQ API key stays on the server.

For AI agents porting this code: the contract to keep is in [AGENTS.md](../AGENTS.md).

## Requirements

- Node.js 22 or later, and the npm that ships with it. The example uses the
  built-in `fetch`, and Node's `--env-file` reads `.env`

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

       {"orderId":"order-1","finalPrice":135000,"lexqTraceId":"331bb7af-efdc-465a-be15-a28a652a436a"}

   `finalPrice` is whatever your deployed rules produced. The
   [quickstart](https://docs.lexq.io/quickstart) policy takes 10% off any order of
   100000 or more, so it returns 135000 for 150000. When no rule matches,
   `mutatedFacts` comes back empty and the amount you sent is returned unchanged.

   When the policy needs a fact this service did not send, you get LexQ's error
   code instead of a bare failure. Send the request again as `order-2`, so the
   idempotency key is new, and leave out `paymentAmount`:

       {"error":"rule evaluation failed","lexqErrorCode":"P-015"}

## Where to look

- `src/lexqClient.ts`: the only file that talks to LexQ
- `src/server.ts`: the pricing endpoint that uses it