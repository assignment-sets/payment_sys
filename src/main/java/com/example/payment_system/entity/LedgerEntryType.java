package com.example.payment_system.entity;

public enum LedgerEntryType {
    DEBIT,
    CREDIT,

    /**
     * Manual balancing entry written by reconciliation when an account's
     * balance has drifted from its ledger sum. Unlike DEBIT/CREDIT it is not
     * tied to a transfer and may carry either sign. Resolution always pairs
     * an ADJUSTMENT entry with a balance correction in one explicit tx.
     */
    ADJUSTMENT
}
