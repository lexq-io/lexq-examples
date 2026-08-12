# LexQ integration example — TypeScript

A minimal backend service that calls the LexQ Execution API to price an
order. The LexQ API key stays on the server.

## Requirements

- Node.js 20+ — the example uses the built-in `fetch`
- pnpm (`corepack enable` ships it with Node)

## Run

1. `pnpm install`
2. `cp .env.example .env` and fill in:
   - `LEXQ_API_URL` — `https://api.lexq.io`
   - `LEXQ_API_KEY` — your API key. The default **Execute only** scope is enough
   - `LEXQ_GROUP_ID` — the policy group to evaluate against
3. `pnpm dev`
4. Test it:

       curl -X POST localhost:3000/price \
         -H "Content-Type: application/json" \
         -d '{"orderId": "order-1", "paymentAmount": 150000, "customerTier": "VIP"}'

   The response carries the price LexQ returned and the trace handle for it:

       {"orderId":"order-1","finalPrice":135000,"lexqTraceId":"c25dcd49-19c2-4365-8d06-cd4d5c953b0c"}

   `finalPrice` is whatever your deployed rules produced — 135000 here is the 10%
   VIP discount built in the [quickstart](https://docs.lexq.io/quickstart), applied
   to 150000. When no rule matches, `mutatedFacts` comes back empty
   and the amount you sent is returned unchanged.

   When the policy needs a fact this service did not send, the code reaches you
   instead of a bare failure:

       {"error":"rule evaluation failed","lexqErrorCode":"P-015"}

## Where to look

- `src/lexqClient.ts` — the only file that talks to LexQ
- `src/server.ts` — the pricing endpoint that uses it