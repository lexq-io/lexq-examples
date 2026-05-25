// All LexQ HTTP communication is isolated in this file.
// API reference: https://docs.lexq.io/api/execution

export type Facts = Record<string, string | number | boolean>;

export interface ExecutionTrace {
    tenantId: string;
    policyGroupId: string;
    policyVersionId: string;
    ruleId: string;
    ruleName: string;
    executedAt: string;
    matched: boolean;
    matchExpression: string;
    inputFacts: Record<string, unknown>;
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
    inputFacts: Facts;
    mutatedFacts: Facts;
    generatedVariables: Record<string, unknown>;
    executionTraces: ExecutionTrace[];
    decisionTraces: DecisionTrace[];
}

interface EvaluateOptions {
    context?: Record<string, unknown>;
    // Pass a stable key (e.g. your order ID) so a retry does not execute twice.
    idempotencyKey?: string;
}

export async function evaluate(
    facts: Facts,
    options: EvaluateOptions = {},
): Promise<ExecutionResult> {
    const apiUrl = process.env.LEXQ_API_URL!;
    const apiKey = process.env.LEXQ_API_KEY!;
    const groupId = process.env.LEXQ_GROUP_ID!;

    const headers: Record<string, string> = {
        "Content-Type": "application/json",
        // The API key lives on the server only — it never ships to a browser.
        "x-api-key": apiKey,
    };
    if (options.idempotencyKey) headers["Idempotency-Key"] = options.idempotencyKey;

    const res = await fetch(`${apiUrl}/api/v1/execution/groups/${groupId}`, {
        method: "POST",
        headers,
        body: JSON.stringify({ facts, context: options.context ?? {} }),
    });

    const body = await res.json();
    if (!res.ok || body.result !== "SUCCESS") {
        throw new Error(
            `LexQ ${res.status} ${body.code ?? ""}: ${body.message ?? "execution failed"}`,
        );
    }
    return body.data as ExecutionResult;
}