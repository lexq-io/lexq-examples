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
   - `LEXQ_API_KEY` — your API key
   - `LEXQ_GROUP_ID` — the policy group to evaluate against
3. `pnpm dev`
4. Test it:

       curl -X POST localhost:3000/price \
         -H "Content-Type: application/json" \
         -d '{"orderId": "order-1", "paymentAmount": 150000, "customerTier": "VIP"}'

## Where to look

- `src/lexqClient.ts` — the only file that talks to LexQ
- `src/server.ts` — the pricing endpoint that uses it