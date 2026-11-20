import express from "express";
import { LexqClient, LexqError } from "@lexq/sdk";

// One client for the whole service. Its circuit breaker counts failed calls across
// requests, so a client created for each request would never open it.
const lexq = new LexqClient({
    // The API key lives on the server only. The client refuses to start in a browser.
    apiKey: process.env.LEXQ_API_KEY!,
    baseUrl: process.env.LEXQ_API_URL,
});
const groupId = process.env.LEXQ_GROUP_ID!;

const app = express();
app.use(express.json());

// A pricing endpoint: asks LexQ to apply discount rules to an order.
app.post("/price", async (req, res) => {
    // Express 5 leaves req.body undefined when the request is not JSON. Fall back to an
    // empty object so the call still reaches LexQ, which names the missing facts (P-015).
    const { orderId, paymentAmount, customerTier } = req.body ?? {};

    const result = await lexq.execute(groupId, {
        // Facts your deployed policy expects. See GET /groups/{id}/requirements.
        //
        // The client leaves out a fact whose value is null or undefined, so a field
        // missing from the request is not sent at all. P-015 names exactly which required
        // facts were absent.
        facts: { paymentAmount, customerTier },
        // Idempotency: a retry of the same order won't execute twice. The client accepts
        // only a string key, so an order ID sent as a JSON number is converted first.
        idempotencyKey: orderId == null ? undefined : String(orderId),
        // What this service does when it cannot get LexQ's decision. It does not price an
        // order without LexQ, so the fallback picks the error status to answer with. A
        // checkout that can go ahead without a discount would return the amount it was
        // sent instead.
        fallback: failureStatus,
    });

    if (result.source === "fallback") {
        const { error } = result;
        console.error("rule evaluation failed", error);

        // Surface the code. It is the stable identifier callers and dashboards branch on;
        // the message is not. JSON.stringify leaves it out when there is none.
        res.status(result.value).json({
            error: "rule evaluation failed",
            lexqErrorCode: error instanceof LexqError ? error.errorCode : undefined,
        });
        return;
    }

    // Correlate LexQ's audit trail with your own records: store result.traceId
    // next to the order so you can later see exactly which rules priced it.
    console.log(`order ${orderId} priced, LexQ traceId ${result.traceId}`);

    res.json({
        orderId,
        // result.facts holds the facts LexQ received, updated with every change a rule
        // made. The amount is a Decimal, which the response writes with the digits LexQ
        // sent.
        finalPrice: result.facts.paymentAmount,
        lexqTraceId: result.traceId,
    });
});

// Not every LexQ failure is an upstream outage, so they must not all become 502.
// LexQ's status classifies the failure and the error code names it. The full table is
// at https://docs.lexq.io/api/execution#error-handling.
function failureStatus(error: LexqError | TypeError): number {
    if (!(error instanceof LexqError)) {
        // A TypeError: the client refused to send what this service passed to it, such as
        // an empty order ID or one that a header cannot carry. Nothing reached LexQ, and
        // the fix belongs in this service.
        return 500;
    }
    if (error.reason !== "response") {
        // No answer from LexQ: the call could not connect or ran out of time, or the
        // circuit breaker is open after repeated failures, so this one was not sent. A
        // genuine upstream problem.
        return 502;
    }
    if (error.status === 429 || error.errorCode === "I-002") {
        // C-007 rate limit, or I-002 same Idempotency-Key still in flight. Transient.
        //
        // Branch on the code, not the status: I-001 and I-002 are both 409 and their
        // correct handling is opposite. I-002 says "try again later"; I-001 says the
        // key is already spent, so retrying it forever returns the same 409.
        // A duplicate of an identical request never lands here at all. LexQ replays
        // the original response with 200 and the same traceId.
        return 503;
    }
    if (error.status !== undefined && error.status >= 500) {
        return 502;
    }
    // LexQ rejected this service's request, for example a missing fact (P-015), a
    // malformed body (C-001) or a group with no deployed version (P-019). Retrying
    // it unchanged fails the same way. The fix belongs in this service, so do not
    // report it as an upstream outage.
    return 500;
}

// Express 5 hands a listen failure such as EADDRINUSE to this callback instead of
// crashing. A callback that ignores it reports "listening" and the process exits.
app.listen(3000, (error) => {
    if (error) throw error;
    console.log("listening on :3000");
});
