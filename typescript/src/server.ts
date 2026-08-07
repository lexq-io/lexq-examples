import "dotenv/config";
import express from "express";
import { evaluate, LexqError } from "./lexqClient.js";

const app = express();
app.use(express.json());

// A pricing endpoint: asks LexQ to apply discount rules to an order.
// The decision is made server-side; the LexQ API key never reaches a browser.
app.post("/price", async (req, res) => {
    const { orderId, paymentAmount, customerTier } = req.body;

    try {
        const result = await evaluate(
            // Facts your deployed policy expects — see GET /groups/{id}/requirements.
            { payment_amount: paymentAmount, customer_tier: customerTier },
            // Idempotency: a retry of the same order won't execute twice.
            { idempotencyKey: orderId },
        );

        // Correlate LexQ's audit trail with your own records: store result.traceId
        // next to the order so you can later see exactly which rules priced it.
        console.log(`order ${orderId} priced — LexQ traceId ${result.traceId}`);

        res.json({
            orderId,
            finalPrice: result.mutatedFacts.payment_amount ?? paymentAmount,
            lexqTraceId: result.traceId,
        });
    } catch (err) {
        if (!(err instanceof LexqError)) throw err;

        // Not every LexQ failure is an upstream outage, so they must not all become 502.
        // LexQ's status classifies the failure and the error code names it — the full
        // table is at https://docs.lexq.io/api/execution#error-handling.
        let status: number;
        if (err.httpStatus === null) {
            // Never reached LexQ. A genuine upstream problem.
            status = 502;
        } else if (err.httpStatus === 429 || err.errorCode === "I-002") {
            // C-007 rate limit, or I-002 same Idempotency-Key still in flight. Transient.
            //
            // Branch on the code, not the status: I-001 and I-002 are both 409 and their
            // correct handling is opposite. I-002 says "try again later"; I-001 says the
            // key is already spent, so retrying it forever returns the same 409.
            // A duplicate of an *identical* request never lands here at all — LexQ replays
            // the original response with 200 and the same traceId.
            status = 503;
        } else if (err.httpStatus < 500) {
            // LexQ rejected *this service's* request — a missing fact (P-015), a malformed
            // body (C-001), a group with no deployed version (P-019). Retrying it unchanged
            // fails the same way, so paging the LexQ on-call would be wrong: the bug is here.
            status = 500;
        } else {
            status = 502;
        }

        console.error(
            `rule evaluation failed — lexqStatus=${err.httpStatus} errorCode=${err.errorCode}`,
            err,
        );

        // Surface the code. It is the stable identifier callers and dashboards branch on;
        // the message is not. Collapsing it to a generic string is what hides these bugs.
        res.status(status).json({
            error: "rule evaluation failed",
            ...(err.errorCode ? { lexqErrorCode: err.errorCode } : {}),
        });
    }
});

app.listen(3000, () => console.log("listening on :3000"));