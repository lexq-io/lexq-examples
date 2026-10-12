# LexQ integration example: Java (Spring Boot)

A minimal backend service that calls the LexQ Execution API to price an
order. The LexQ API key stays on the server.

For AI agents porting this code: the contract to keep is in [AGENTS.md](../AGENTS.md).

## Requirements

- Java 17+

The Maven Wrapper (`./mvnw`) is included, so a separate Maven install is not
needed. The wrapper downloads Maven on first run.

## Run

1. Set the environment (the key's default **Execute only** scope is enough):

       export LEXQ_API_URL=https://api.lexq.io
       export LEXQ_API_KEY=<your-api-key>
       export LEXQ_GROUP_ID=<the-policy-group-to-evaluate-against>

2. `./mvnw spring-boot:run`
3. Test it:

       curl -X POST localhost:3000/price \
         -H "Content-Type: application/json" \
         -d '{"orderId": "order-1", "paymentAmount": 150000, "customerTier": "VIP"}'

   The response carries the price LexQ returned and the trace handle for it:

       {"orderId":"order-1","finalPrice":135000,"lexqTraceId":"6783994d-36f1-49af-a98e-c35279718f48"}

   `finalPrice` is whatever your deployed rules produced. The
   [quickstart](https://docs.lexq.io/quickstart) policy takes 10% off any order of
   100000 or more, so it returns 135000 for 150000. Amounts are `BigDecimal` end to
   end, so every digit the engine computed survives the trip, even in results too
   long for a double. When no rule matches, `mutatedFacts` comes back empty and the
   amount you sent is returned at the scale you sent it.

   When the policy needs a fact this service did not send, you get LexQ's error
   code instead of a bare failure. Send the request again as `order-2`, so the
   idempotency key is new, and leave out `paymentAmount`:

       {"error":"rule evaluation failed","lexqErrorCode":"P-015"}

## Where to look

- `LexqClient.java`: the only file that talks to LexQ
- `PricingController.java`: the pricing endpoint that uses it
- `LexqProperties.java`: the three environment values, bound at startup