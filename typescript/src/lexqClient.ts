// All LexQ HTTP communication is isolated in this file.
// API reference: https://docs.lexq.io/api/execution

import { randomUUID } from "node:crypto";
import { setTimeout as sleep } from "node:timers/promises";

// LIST_STRING and LIST_NUMBER facts travel as JSON arrays, never as joined strings.
export type Facts = Record<string, string | number | boolean | string[] | number[]>;

// Money and JavaScript numbers.
//
// LexQ does its arithmetic in decimal and rounds only where a rule sets `rounding`.
// Otherwise an amount keeps up to 34 significant digits. `JSON.parse` turns it into an
// IEEE-754 double. A decimal of up to 15 significant digits survives that round trip:
// print the double again and you get the same digits. This example only reads the
// amount and hands it back, so up to 15 digits it returns the same number, minus
// trailing zeros (`135000.00` comes back as `135000`). Longer amounts change: a
// three-way split of 100 arrives as 33.33333333333333333333333333333333 and leaves as
// 33.333333333333336. Rules that produce money should set `rounding` for this reason
// too.
//
// Arithmetic loses precision even on short amounts. `0.1 + 0.2` is the usual
// demonstration, and the error compounds across a cart. If you total, split, or tax a
// decision amount, parse the response body yourself and keep the amount as a string,
// then use a decimal library. The Java track has BigDecimal and uses it end to end;
// this is the one place the two tracks cannot match, because the language has no
// decimal type.

// Time limits and retries. What to do once every attempt has failed is the caller's
// decision: https://docs.lexq.io/api/execution#when-lexq-is-slow-or-unavailable
//
// An attempt may take up to 5 seconds, from sending the request to the last byte of the
// response. Without a limit, fetch waits five minutes for the response headers alone.
// A call makes at most 3 attempts, and starts one only if it can finish within 15
// seconds of the start of the call.
const ATTEMPT_TIMEOUT_MS = 5_000;
const MAX_ATTEMPTS = 3;
const DEADLINE_MS = 15_000;

export interface ExecutionTrace {
    policyGroupId: string;
    policyVersionId: string;
    ruleId: string;
    ruleName: string;
    matched: boolean;
    matchExpression: string;
    generatedActions: unknown[];
}

export interface DecisionTrace {
    ruleId: string;
    ruleName: string;
    policyGroupId: string;
    policyVersionId: string;
    status: string;
    reasonCode: string;
    reasonDetail: string | null;
}

export interface ExecutionResult {
    traceId: string;
    executedAt: string;
    totalProcessingTimeMs: number;
    inputFacts: Facts;
    inputContexts: Record<string, unknown>;
    mutatedFacts: Facts;
    generatedVariables: Record<string, unknown>;
    executionTraces: ExecutionTrace[];
    decisionTraces: DecisionTrace[];
}

/**
 * Thrown when LexQ returns result !== "SUCCESS", or when the call fails in transit.
 *
 * Both the HTTP status and the error code are kept: the status classifies the failure,
 * the code names it. Callers need the code to branch. The error code table is at
 * https://docs.lexq.io/api/execution#error-handling.
 */
export class LexqError extends Error {
    constructor(
        /** LexQ's HTTP status, or null if the call never reached LexQ or ran out of time. */
        readonly httpStatus: number | null,
        /**
         * The LexQ error code (e.g. "P-015"), or null for a transport error.
         * Branch on this value. Codes stay stable, while messages may be reworded.
         */
        readonly errorCode: string | null,
        message: string,
    ) {
        super(message);
        this.name = "LexqError";
    }
}

interface EvaluateOptions {
    contexts?: Record<string, unknown>;
    // Pass a stable key (e.g. your order ID) so a retry does not execute twice. Without
    // one, the call makes a key of its own, which only its own retries share.
    idempotencyKey?: string;
}

export async function evaluate(
    facts: Facts,
    options: EvaluateOptions = {},
): Promise<ExecutionResult> {
    const apiUrl = process.env.LEXQ_API_URL!;
    const apiKey = process.env.LEXQ_API_KEY!;
    const groupId = process.env.LEXQ_GROUP_ID!;
    const url = `${apiUrl}/api/v1/execution/groups/${groupId}`;

    // Every attempt sends the same key and the same bytes. If an earlier attempt
    // succeeded, LexQ answers with its stored response instead of running the decision
    // again, and does not bill the retry. Without a key, each attempt would run and be
    // billed.
    const headers = new Headers({
        "Content-Type": "application/json",
        // The API key lives on the server only. It never ships to a browser.
        "x-api-key": apiKey,
        "Idempotency-Key": options.idempotencyKey || randomUUID(),
    });
    const requestBody = JSON.stringify({ facts, contexts: options.contexts ?? {} });

    const deadline = Date.now() + DEADLINE_MS;
    for (let attempt = 1; ; attempt++) {
        try {
            return await send(url, headers, requestBody);
        } catch (err) {
            if (!(err instanceof LexqError) || !isRetryable(err) || attempt === MAX_ATTEMPTS) {
                throw err;
            }
            // Wait 1 second, then 2, each plus a random extra of up to half, so that calls
            // that failed together do not all retry at once. The docs ask for a wait of a
            // second after a 429.
            const wait = 1_000 * 2 ** (attempt - 1) * (1 + Math.random() / 2);
            if (Date.now() + wait + ATTEMPT_TIMEOUT_MS > deadline) throw err;
            console.warn(
                `LexQ attempt ${attempt} failed: ${err.message}. Retrying in ${Math.round(wait)} ms`,
            );
            await sleep(wait);
        }
    }
}

// One attempt. A failure throws a LexqError, which evaluate() retries or passes on.
async function send(url: string, headers: Headers, requestBody: string): Promise<ExecutionResult> {
    // One signal for the whole attempt. It also stops res.json() below, so a body that
    // stops arriving midway runs out of time too.
    const signal = AbortSignal.timeout(ATTEMPT_TIMEOUT_MS);

    let res: Response;
    try {
        res = await fetch(url, {
            method: "POST",
            headers,
            body: requestBody,
            // Do not follow redirects. fetch follows them by default and sends the
            // x-api-key header to whatever host the Location header names, and on a 307
            // or 308 the body too. With "manual", a 3xx comes back as it is and fails with
            // its status like any other error response. The usual cause of a 3xx is a
            // wrong LEXQ_API_URL: http://api.lexq.io answers with a 301 to https.
            redirect: "manual",
            signal,
        });
    } catch (cause) {
        // No status: the request never reached LexQ, or it ran out of time.
        throw new LexqError(null, null, `transport error: ${(cause as Error).message}`);
    }

    // Not every response is JSON. A load balancer returning 502, or a 404 from a
    // wrong base URL, sends HTML, and res.json() throws a SyntaxError on it. Left
    // uncaught that error escapes as-is, so the caller's `instanceof LexqError`
    // check misses it and the failure surfaces as an unhandled 500 instead of a
    // classified one.
    let body: {
        result?: string;
        data?: ExecutionResult;
        errorCode?: string;
        message?: string;
    };
    try {
        body = await res.json();
    } catch {
        // Running out of time while the body arrives is a timeout, not a non-JSON body.
        // Reporting it with the status of the unfinished response would turn a 200 into
        // a 500.
        if (signal.aborted) {
            throw new LexqError(null, null, "transport error: timed out reading the response");
        }
        // The status is known here, unlike a transport error, so it is worth keeping.
        throw new LexqError(res.status, null, `non-JSON response (${res.status})`);
    }

    if (!res.ok || body.result !== "SUCCESS") {
        // The failure key is `errorCode`, not `code`. Reading the wrong one is invisible
        // at runtime: it is simply undefined, so every error arrives without a code.
        throw new LexqError(
            res.status,
            body.errorCode ?? null,
            body.message ?? "execution failed",
        );
    }
    return body.data as ExecutionResult;
}

// Retry only failures that can clear up on their own within seconds: no complete
// response, a 502, 503 or 504, a 429, and I-002, which means a request with this key is
// still running. Any other failure goes back to the caller at once.
function isRetryable(err: LexqError): boolean {
    return (
        err.httpStatus === null ||
        [429, 502, 503, 504].includes(err.httpStatus) ||
        err.errorCode === "I-002"
    );
}