package io.lexq.example;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
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

    /** Never treat a status code as an error: the LexQ envelope is inspected below instead. */
    private static final Predicate<HttpStatusCode> NEVER = status -> false;

    private final RestClient restClient;
    private final String apiKey;
    private final String groupId;

    LexqClient(LexqProperties props) {
        this.apiKey = props.apiKey();
        this.groupId = props.groupId();
        this.restClient = RestClient.builder()
                .baseUrl(props.apiUrl())
                // A non-2xx status and a 2xx body with result != SUCCESS are both
                // handled explicitly in evaluate(); disable RestClient's default throw.
                .defaultStatusHandler(NEVER, (request, response) -> { })
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
        ExecutionEnvelope envelope;
        try {
            envelope = restClient.post()
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
                    .body(ExecutionEnvelope.class);
        } catch (RestClientException e) {
            throw new LexqExecutionException(null, "transport error: " + e.getMessage());
        }

        if (envelope == null || !"SUCCESS".equals(envelope.result())) {
            String code = envelope != null ? envelope.code() : null;
            String message = envelope != null ? envelope.message() : "empty response";
            throw new LexqExecutionException(code, message);
        }
        return envelope.data();
    }

    // ── Execution API contract ──────────────────────────────────────────────
    // Nested on purpose: these records exist only as this client's wire types.
    // ExecutionEnvelope is internal plumbing (private); the rest are the
    // response contract returned to callers (public).

    /** The internal {result, data, code, message} wrapper around every response. */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record ExecutionEnvelope(String result, ExecutionResult data,
                                     String code, String message) { }

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