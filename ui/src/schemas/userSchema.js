import { z } from "zod";

export const userSchema = z.object({
    name: z.string().min(1, "Name is required").max(100),
    email: z.email("Invalid email address"),
});