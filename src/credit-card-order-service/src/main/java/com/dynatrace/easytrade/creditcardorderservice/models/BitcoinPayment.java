package com.dynatrace.easytrade.creditcardorderservice.models;

import java.math.BigDecimal;
import java.time.OffsetDateTime;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Server-side view of a bitcoin payment, returned to callers and persisted.
 *
 * <p>{@code paymentAddress} and {@code btcAmount} are populated by the gateway
 * once the payment leaves {@link BitcoinPaymentStatus#PENDING}; they are null
 * while the payment is still pending.
 */
@Schema(description = "The status of a bitcoin payment.")
public record BitcoinPayment(
        String paymentId,
        String orderId,
        Integer accountId,
        BigDecimal amount,
        String currency,
        @Schema(nullable = true, description = "BTC amount quoted by the gateway; null until quoted")
        BigDecimal btcAmount,
        @Schema(nullable = true, description = "On-chain address to send funds to; null until issued")
        String paymentAddress,
        BitcoinPaymentStatus status,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt) {
}
