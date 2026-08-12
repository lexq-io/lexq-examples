# LexQ integration example — Java (Spring Boot)

A minimal backend service that calls the LexQ Execution API to price an
order. The LexQ API key stays on the server.

## Requirements

- Java 17+

The Maven Wrapper (`./mvnw`) is included, so a separate Maven install is not
needed — the wrapper downloads Maven on first run.

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

       {"orderId":"order-1","finalPrice":135000.00,"lexqTraceId":"c35c3116-6076-48ff-a328-b63cc2ec217a"}

   `finalPrice` is whatever your deployed rules produced — 135000.00 here is the 10%
   VIP discount built in the [quickstart](https://docs.lexq.io/quickstart), applied
   to 150000. Amounts are `BigDecimal` end to end, so the scale the
   engine rounded to survives the trip. When no rule matches, `mutatedFacts` comes
   back empty and the amount you sent is returned at the scale you sent it.

   When the policy needs a fact this service did not send, the code reaches you
   instead of a bare failure:

       {"error":"rule evaluation failed","lexqErrorCode":"P-015"}

## Where to look

- `LexqClient.java` — the only file that talks to LexQ
- `PricingController.java` — the pricing endpoint that uses it
- `LexqProperties.java` — the three environment values, bound at startup