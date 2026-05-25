import "dotenv/config";
import express from "express";
import { evaluate } from "./lexqClient.js";

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
        console.error(err);
        res.status(502).json({ error: "rule evaluation failed" });
    }
});

app.listen(3000, () => console.log("listening on :3000"));