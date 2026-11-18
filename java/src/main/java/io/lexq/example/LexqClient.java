package io.lexq.example;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.boot.http.client.HttpRedirects;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.UnknownContentTypeException;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * All LexQ HTTP communication is isolated in this class. The nested records are
 * the Execution API contract: the internal wire envelope and the response shapes.
 * API reference: https://docs.lexq.io/api/execution
 */
@Component
public class LexqClient {

    private static final Logger log = LoggerFactory.getLogger(LexqClient.class);

    /**
     * Claim every status for a handler that does nothing, which stops RestClient from
     * throwing on 4xx/5xx. {@link #send} inspects the LexQ envelope instead.
     *
     * <p>The predicate selects which statuses this handler owns, not which ones count as
     * errors. A predicate that matches nothing leaves Spring's default handler in place,
     * so every LexQ error surfaces as a transport failure with no status and no code.
     */
    private static final Predicate<HttpStatusCode> ALL = status -> true;

    /**
     * Time limits and retries. What to do once every attempt has failed is the caller's
     * decision: https://docs.lexq.io/api/execution#when-lexq-is-slow-or-unavailable
     *
     * <p>An attempt may take up to 5 seconds, from sending the request to the last byte of
     * the response. The JDK client that Spring Boot uses here has no read timeout by
     * default, so without one a call waits as long as LexQ takes. A call makes at most 3
     * attempts, and starts one only if it can finish within 15 seconds of the start of the
     * call.
     */
    private static final Duration ATTEMPT_TIMEOUT = Duration.ofSeconds(5);
    private static final int MAX_ATTEMPTS = 3;
    private static final Duration DEADLINE = Duration.ofSeconds(15);

    private final RestClient restClient;
    private final String apiKey;
    private final String groupId;

    /**
     * Takes Spring Boot's auto-configured {@code RestClient.Builder} rather than
     * {@code RestClient.builder()}. The injected one carries the application's Jackson
     * setup, which is where {@code use-big-decimal-for-floats} lives. A client built
     * from the static factory reads decision amounts as {@code Double}.
     *
     * <p>Spring Boot built the injected builder's request factory from
     * {@code requestFactoryBuilder} and {@code httpClientSettings}. Building a new factory
     * from the same two keeps the application's other HTTP client settings, such as an SSL
     * bundle, and changes only the redirects and the read timeout of calls to LexQ.
     */
    LexqClient(LexqProperties props, RestClient.Builder builder,
               ClientHttpRequestFactoryBuilder<?> requestFactoryBuilder,
               HttpClientSettings httpClientSettings) {
        this.apiKey = props.apiKey();
        this.groupId = props.groupId();
        this.restClient = builder
                .baseUrl(props.apiUrl())
                // Do not follow redirects. Spring Boot follows them by default and sends the
                // x-api-key header to whatever host the Location header names, and on a 307
                // or 308 the body too. With DONT_FOLLOW, a 3xx reaches send() and fails
                // with its status. The usual cause of a 3xx is a wrong LEXQ_API_URL:
                // http://api.lexq.io answers with a 301 to https.
                .requestFactory(requestFactoryBuilder.build(httpClientSettings
                        .withRedirects(HttpRedirects.DONT_FOLLOW)
                        .withReadTimeout(ATTEMPT_TIMEOUT)))
                // A non-2xx status and a 2xx body with result != SUCCESS are both
                // handled explicitly in send(); disable RestClient's default throw.
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
     *                       Without one, the call makes a key of its own, which only its
     *                       own retries share.
     */
    public ExecutionResult evaluate(Map<String, Object> facts, String idempotencyKey) {
        // Every attempt sends the same key and the same body. If an earlier attempt
        // succeeded, LexQ answers with its stored response instead of running the decision
        // again, and does not bill the retry. Without a key, each attempt would run and be
        // billed.
        String key = idempotencyKey == null || idempotencyKey.isEmpty()
                ? UUID.randomUUID().toString()
                : idempotencyKey;
        Map<String, Object> body = Map.of("facts", facts, "contexts", Map.of());

        long deadline = System.nanoTime() + DEADLINE.toNanos();
        for (int attempt = 1; ; attempt++) {
            try {
                return send(key, body);
            } catch (LexqExecutionException e) {
                if (!isRetryable(e) || attempt == MAX_ATTEMPTS) {
                    throw e;
                }
                // Wait 1 second, then 2, each plus a random extra of up to half, so that calls
                // that failed together do not all retry at once. The docs ask for a wait of a
                // second after a 429.
                long waitMillis = (long) (1000L * (1L << (attempt - 1))
                        * (1 + ThreadLocalRandom.current().nextDouble() / 2));
                if (Duration.ofMillis(waitMillis).plus(ATTEMPT_TIMEOUT).toNanos()
                        > deadline - System.nanoTime()) {
                    throw e;
                }
                log.warn("Attempt {} failed: {}. Retrying in {} ms",
                        attempt, e.getMessage(), waitMillis);
                try {
                    Thread.sleep(waitMillis);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
    }

    /**
     * One attempt. A failure throws {@link LexqExecutionException}, which
     * {@code evaluate()} retries or passes on.
     */
    private ExecutionResult send(String idempotencyKey, Map<String, Object> body) {
        ResponseEntity<ExecutionEnvelope> response;
        try {
            response = restClient.post()
                    .uri("/api/v1/execution/groups/{groupId}", groupId)
                    // The API key lives on the server only. It never ships to a browser.
                    .header("x-api-key", apiKey)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(body)
                    .retrieve()
                    // toEntity, not body: the HTTP status classifies the failure and the
                    // exception handler needs it. body() would discard it.
                    .toEntity(ExecutionEnvelope.class);
        } catch (UnknownContentTypeException e) {
            // Not every response is JSON. A load balancer's 502 page, or the 404 from a
            // wrong base URL, is HTML, and no converter reads HTML into the envelope. The
            // status is known here, unlike a transport error, so it is worth keeping.
            throw new LexqExecutionException(e.getStatusCode().value(), null, "non-JSON response");
        } catch (RestClientException e) {
            // No status: the request never reached LexQ, ran out of time, or its response
            // could not be read.
            throw new LexqExecutionException(null, null, "transport error: " + e.getMessage());
        }

        ExecutionEnvelope envelope = response.getBody();
        if (envelope == null || !response.getStatusCode().is2xxSuccessful()
                || !"SUCCESS".equals(envelope.result())) {
            throw new LexqExecutionException(
                    response.getStatusCode().value(),
                    envelope != null ? envelope.errorCode() : null,
                    envelope != null ? envelope.message() : "empty response");
        }
        return envelope.data();
    }

    /**
     * Retry only failures that can clear up on their own within seconds: no complete
     * response, a 502, 503 or 504, a 429, and I-002, which means a request with this key is
     * still running. Any other failure goes back to the caller at once.
     */
    private static boolean isRetryable(LexqExecutionException e) {
        Integer status = e.getHttpStatus();
        return status == null
                || status == 429 || status == 502 || status == 503 || status == 504
                || "I-002".equals(e.getErrorCode());
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
            String executedAt,
            // Long, not long. Jackson 3 refuses to read a missing field into a primitive,
            // and that error fails the whole response, not just this field.
            Long totalProcessingTimeMs,
            Map<String, Object> inputFacts,
            Map<String, Object> inputContexts,
            Map<String, Object> mutatedFacts,        // facts changed by rule actions
            Map<String, Object> generatedVariables,
            List<ExecutionTrace> executionTraces,
            List<DecisionTrace> decisionTraces
    ) { }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record ExecutionTrace(
            String policyGroupId,
            String policyVersionId,
            String ruleId,
            String ruleName,
            boolean matched,
            String matchExpression,
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