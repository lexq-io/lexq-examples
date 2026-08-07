package io.lexq.example;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * All LexQ HTTP communication is isolated in this class. The nested records are
 * the Execution API contract: the internal wire envelope and the response shapes.
 * API reference: https://docs.lexq.io/api/execution
 */
@Component
public class LexqClient {

    /**
     * Claim every status for a handler that does nothing, which stops RestClient from
     * throwing on 4xx/5xx — {@link #evaluate} inspects the LexQ envelope instead.
     *
     * <p>The predicate selects which statuses this handler owns, not which ones count as
     * errors. A predicate that matches nothing leaves Spring's default handler in place,
     * so every LexQ error surfaces as a transport failure with no status and no code.
     */
    private static final Predicate<HttpStatusCode> ALL = status -> true;

    private final RestClient restClient;
    private final String apiKey;
    private final String groupId;

    /**
     * Takes Spring Boot's auto-configured {@code RestClient.Builder} rather than
     * {@code RestClient.builder()}. The injected one carries the application's Jackson
     * setup, which is where {@code use-big-decimal-for-floats} lives — build a client
     * from the static factory and decision amounts come back as {@code Double}.
     */
    LexqClient(LexqProperties props, RestClient.Builder builder) {
        this.apiKey = props.apiKey();
        this.groupId = props.groupId();
        this.restClient = builder
                .baseUrl(props.apiUrl())
                // A non-2xx status and a 2xx body with result != SUCCESS are both
                // handled explicitly in evaluate(); disable RestClient's default throw.
                .defaultStatusHandler(ALL, (request, response) -> { })
                .build();
    }

    /** Evaluate the configured policy group against a set of facts. */
    public ExecutionResult evaluate(Map<String, Object> facts) {
        return evaluate(facts, null);
    }

    /**
     * Evaluate the configured policy group against a set of facts.
     *
     * @param idempotencyKey a stable key (e.g. your order ID) so a retry of the
     *                       same request does not execute twice; may be {@code null}.
     */
    public ExecutionResult evaluate(Map<String, Object> facts, String idempotencyKey) {
        ResponseEntity<ExecutionEnvelope> response;
        try {
            response = restClient.post()
                    .uri("/api/v1/execution/groups/{groupId}", groupId)
                    // The API key lives on the server only — it never ships to a browser.
                    .header("x-api-key", apiKey)
                    .headers(headers -> {
                        if (idempotencyKey != null) {
                            headers.set("Idempotency-Key", idempotencyKey);
                        }
                    })
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("facts", facts, "context", Map.of()))
                    .retrieve()
                    // toEntity, not body: the HTTP status classifies the failure and the
                    // exception handler needs it. body() would discard it.
                    .toEntity(ExecutionEnvelope.class);
        } catch (RestClientException e) {
            // No status — the request never reached LexQ.
            throw new LexqExecutionException(null, null, "transport error: " + e.getMessage());
        }

        ExecutionEnvelope envelope = response.getBody();
        if (envelope == null || !"SUCCESS".equals(envelope.result())) {
            throw new LexqExecutionException(
                    response.getStatusCode().value(),
                    envelope != null ? envelope.errorCode() : null,
                    envelope != null ? envelope.message() : "empty response");
        }
        return envelope.data();
    }

    // ── Execution API contract ──────────────────────────────────────────────
    // Nested on purpose: these records exist only as this client's wire types.
    // ExecutionEnvelope is internal plumbing (private); the rest are the
    // response contract returned to callers (public).

    /**
     * The internal wrapper around every response. The success branch carries {@code data};
     * the failure branch carries {@code errorCode} + {@code message}.
     *
     * <p>The failure key is {@code errorCode}, not {@code code}. Getting this wrong is
     * invisible at runtime: {@code ignoreUnknown = true} drops the real field and leaves
     * the mistyped one null, so every error arrives without a code.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ExecutionEnvelope(String result, ExecutionResult data,
                                     String errorCode, String message) { }

    /** The {@code data} block of a successful execution. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExecutionResult(
            String traceId,                          // the execution's audit handle
            Map<String, Object> inputFacts,
            Map<String, Object> mutatedFacts,        // facts changed by rule actions
            Map<String, Object> generatedVariables,
            List<ExecutionTrace> executionTraces,
            List<DecisionTrace> decisionTraces
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExecutionTrace(
            String tenantId,
            String policyGroupId,
            String policyVersionId,
            String ruleId,
            String ruleName,
            String executedAt,
            boolean matched,
            String matchExpression,
            Map<String, Object> inputFacts,
            List<Object> generatedActions
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record DecisionTrace(
            String ruleId,
            String ruleName,
            String policyGroupId,
            String policyVersionId,
            String status,
            String reasonCode,
            String reasonDetail
    ) { }
}