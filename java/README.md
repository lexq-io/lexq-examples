# LexQ integration example — Java (Spring Boot)

A minimal backend service that calls the LexQ Execution API to price an
order. The LexQ API key stays on the server.

## Requirements

- Java 17+

The Maven Wrapper (`./mvnw`) is included, so a separate Maven install is not
needed — the wrapper downloads Maven on first run.

## Run

1. Set the environment:

       export LEXQ_API_URL=https://api.lexq.io
       export LEXQ_API_KEY=<your-api-key>
       export LEXQ_GROUP_ID=<the-policy-group-to-evaluate-against>

2. `./mvnw spring-boot:run`
3. Test it:

       curl -X POST localhost:3000/price \
         -H "Content-Type: application/json" \
         -d '{"orderId": "order-1", "paymentAmount": 150000, "customerTier": "VIP"}'

## Where to look

- `LexqClient.java` — the only file that talks to LexQ
- `PricingController.java` — the pricing endpoint that uses it
- `LexqProperties.java` — the three environment values, bound at startup