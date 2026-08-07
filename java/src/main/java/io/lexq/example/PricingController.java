package io.lexq.example;

import io.lexq.example.LexqClient.ExecutionResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.Map;

/**
 * A pricing endpoint: asks LexQ to apply discount rules to an order.
 * The decision is made server-side; the LexQ API key never reaches a browser.
 */
@RestController
public class PricingController {

    private static final Logger log = LoggerFactory.getLogger(PricingController.class);

    private final LexqClient lexq;

    PricingController(LexqClient lexq) {
        this.lexq = lexq;
    }

    @PostMapping("/price")
    public PriceResponse price(@RequestBody PriceRequest request) {
        // Facts the deployed policy expects — see GET /groups/{id}/requirements.
        Map<String, Object> facts = Map.of(
                "payment_amount", request.paymentAmount(),
                "customer_tier", request.customerTier()
        );

        // Idempotency: a retry of the same order will not execute twice.
        ExecutionResult result = lexq.evaluate(facts, request.orderId());

        // Correlate LexQ's audit trail with the order: store traceId next to the
        // record so the exact rules that priced it can be looked up later.
        log.info("order {} priced — LexQ traceId {}", request.orderId(), result.traceId());

        Object finalPrice = result.mutatedFacts()
                .getOrDefault("payment_amount", request.paymentAmount());

        return new PriceResponse(request.orderId(), finalPrice, result.traceId());
    }

    /** A rule evaluation failure is an upstream fault — surface it as 502. */
    @ExceptionHandler(LexqExecutionException.class)
    public ResponseEntity<Map<String, String>> onLexqFailure(LexqExecutionException e) {
        log.error("rule evaluation failed", e);
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                .body(Map.of("error", "rule evaluation failed"));
    }

    public record PriceRequest(String orderId, BigDecimal paymentAmount, String customerTier) {}

    public record PriceResponse(String orderId, Object finalPrice, String lexqTraceId) {}
}