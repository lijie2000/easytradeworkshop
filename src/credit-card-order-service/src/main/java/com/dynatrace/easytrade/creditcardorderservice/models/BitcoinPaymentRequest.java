package com.dynatrace.easytrade.creditcardorderservice.models;

import java.math.BigDecimal;
import java.util.Objects;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * Request to initiate a bitcoin payment for a credit-card order.
 *
 * <p>The amount is denominated in the account's fiat currency; the gateway is
 * responsible for converting to BTC at settlement time. We intentionally do NOT
 * accept a client-supplied BTC amount or exchange rate — that is quoted by the
 * gateway to avoid a client controlling the settlement value.
 */
@Schema(description = "Initiate a bitcoin payment for a credit card order.")
public record BitcoinPaymentRequest(
        @Schema(description = "Account initiating the payment", example = "13")
        Integer accountId,
        @Schema(description = "Credit-card order id the payment settles", example = "d3bfb8ac-9ba5-433c-a431-06b64eac2162")
        String orderId,
        @Schema(description = "Amount in the account's fiat currency (minor units not used; decimal)", example = "49.99")
        BigDecimal amount,
        @Schema(description = "ISO-4217 fiat currency code", example = "USD")
        String currency) {

    public BitcoinPaymentRequest {
        Objects.requireNonNull(accountId, "accountId is required");
        Objects.requireNonNull(orderId, "orderId is required");
        Objects.requireNonNull(amount, "amount is required");
        Objects.requireNonNull(currency, "currency is required");
        if (amount.signum() <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (currency.length() != 3) {
            throw new IllegalArgumentException("currency must be a 3-letter ISO-4217 code");
        }
    }
}
