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
import java.util.LinkedHashMap;
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
        //
        // Built with put, not Map.of: Map.of rejects null values, so one absent request
        // field would throw a NullPointerException here and LexQ would never be called.
        // Omit what is missing and let the engine answer instead — P-015 names exactly
        // which required facts were absent, which is better than anything this method
        // could work out on its own.
        Map<String, Object> facts = new LinkedHashMap<>();
        if (request.paymentAmount() != null) facts.put("payment_amount", request.paymentAmount());
        if (request.customerTier() != null) facts.put("customer_tier", request.customerTier());

        // Idempotency: a retry of the same order will not execute twice.
        ExecutionResult result = lexq.evaluate(facts, request.orderId());

        // Correlate LexQ's audit trail with the order: store traceId next to the
        // record so the exact rules that priced it can be looked up later.
        log.info("order {} priced — LexQ traceId {}", request.orderId(), result.traceId());

        // A fact only appears in mutatedFacts if a rule changed it. No matching discount
        // means no entry, and the amount the caller sent is already the final price.
        BigDecimal finalPrice = money(
                result.mutatedFacts().getOrDefault("payment_amount", request.paymentAmount()));

        return new PriceResponse(request.orderId(), finalPrice, result.traceId());
    }

    /**
     * Reads a decision amount as {@code BigDecimal}, whatever JSON shape it arrived in.
     *
     * <p>Facts are typed {@code Object} on the wire, so a whole number deserializes to
     * {@code Integer} and a decimal to {@code BigDecimal} (see the Jackson setting in
     * application.yml). Passing either straight through would make the response type
     * depend on whether a rule happened to match. Never convert through {@code double}:
     * {@code BigDecimal.valueOf(d)} and {@code new BigDecimal(d)} both reintroduce the
     * error this is here to avoid.
     */
    private static BigDecimal money(Object value) {
        return value instanceof BigDecimal decimal ? decimal : new BigDecimal(value.toString());
    }

    /**
     * Not every LexQ failure is an upstream outage, so they must not all become 502.
     * LexQ's status classifies the failure and the error code names it — the full table
     * is at https://docs.lexq.io/api/execution#error-handling.
     */
    @ExceptionHandler(LexqExecutionException.class)
    public ResponseEntity<Map<String, String>> onLexqFailure(LexqExecutionException e) {
        Integer lexqStatus = e.getHttpStatus();
        HttpStatus status;
        if (lexqStatus == null) {
            // Never reached LexQ. A genuine upstream problem.
            status = HttpStatus.BAD_GATEWAY;
        } else if (lexqStatus == 429 || "I-002".equals(e.getErrorCode())) {
            // C-007 rate limit, or I-002 same Idempotency-Key still in flight. Transient.
            //
            // Branch on the code, not the status: I-001 and I-002 are both 409 and their
            // correct handling is opposite. I-002 says "try again later"; I-001 says the
            // key is already spent, so retrying it forever returns the same 409.
            // A duplicate of an *identical* request never lands here at all — LexQ replays
            // the original response with 200 and the same traceId.
            status = HttpStatus.SERVICE_UNAVAILABLE;
        } else if (lexqStatus < 500) {
            // LexQ rejected *this service's* request — a missing fact (P-015), a malformed
            // body (C-001), a group with no deployed version (P-019). Retrying it unchanged
            // fails the same way, so paging the LexQ on-call would be wrong: the bug is here.
            status = HttpStatus.INTERNAL_SERVER_ERROR;
        } else {
            status = HttpStatus.BAD_GATEWAY;
        }

        log.error("rule evaluation failed — lexqStatus={} errorCode={}",
                lexqStatus, e.getErrorCode(), e);

        // Surface the code. It is the stable identifier callers and dashboards branch on;
        // the message is not. Collapsing it to a generic string is what hides these bugs.
        Map<String, String> body = new LinkedHashMap<>();
        body.put("error", "rule evaluation failed");
        if (e.getErrorCode() != null) {
            body.put("lexqErrorCode", e.getErrorCode());
        }
        return ResponseEntity.status(status).body(body);
    }

    public record PriceRequest(String orderId, BigDecimal paymentAmount, String customerTier) {}

    public record PriceResponse(String orderId, BigDecimal finalPrice, String lexqTraceId) {}
}