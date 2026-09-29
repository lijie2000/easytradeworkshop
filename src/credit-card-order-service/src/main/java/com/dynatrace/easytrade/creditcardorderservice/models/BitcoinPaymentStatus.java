package com.dynatrace.easytrade.creditcardorderservice.models;

/**
 * Lifecycle of a bitcoin payment.
 *
 * <p>The flow is deliberately asynchronous: the client-facing request only ever
 * moves a payment to {@link #PENDING} and returns immediately. A background
 * worker (see {@code BitcoinPaymentScheduler}) drives the slow, tail-latency-prone
 * calls to the external bitcoin gateway, so a request thread is never blocked on
 * that dependency. This is the direct lesson from production: the external
 * payment dependency is the component that saturates request threads first.
 */
public enum BitcoinPaymentStatus {
    /** Persisted, accepted, not yet sent to the gateway. */
    PENDING,
    /** Address/invoice issued by the gateway; awaiting on-chain confirmation. */
    AWAITING_CONFIRMATION,
    /** Confirmed on-chain and settled. */
    CONFIRMED,
    /** Gateway rejected, expired, or underpaid. */
    FAILED;

    public boolean isTerminal() {
        return this == CONFIRMED || this == FAILED;
    }
}
