import { z } from "zod";

export const transferSchema = z.object({
    fromAccountId: z.number().positive("Source account is required"),
    toAccountId: z.number().positive("Destination account is required"),
    // We use a string or number for the UI input, then validate
    displayAmount: z
        .string()
        .refine((val) => !isNaN(parseFloat(val)) && parseFloat(val) > 0, {
            message: "Amount must be greater than 0",
        }),
    idempotencyKey: z.uuid("Invalid transaction identifier"),
}).refine((data) => data.fromAccountId !== data.toAccountId, {
    message: "Cannot transfer money to the same account",
    path: ["toAccountId"],
});

/**
 * Helper to convert the UI displayAmount to Cents for the BIGINT backend
 * Usage: const payload = toCentsPayload(formData)
 */
export const toCentsPayload = (data) => ({
    fromAccountId: data.fromAccountId,
    toAccountId: data.toAccountId,
    amount: Math.round(parseFloat(data.displayAmount) * 100), // $10.50 -> 1050
    idempotencyKey: data.idempotencyKey,
});