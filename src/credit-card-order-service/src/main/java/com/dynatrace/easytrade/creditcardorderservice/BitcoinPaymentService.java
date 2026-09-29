package com.dynatrace.easytrade.creditcardorderservice;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPayment;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentRequest;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentStatus;

import dev.openfeature.sdk.Client;
import dev.openfeature.sdk.OpenFeatureAPI;

/**
 * Orchestrates bitcoin payments.
 *
 * <p>Design intent (grounded in production telemetry):
 * <ul>
 *   <li><b>The request path never blocks on the gateway.</b> {@link #initiate}
 *       only validates, checks the feature flag, and persists a PENDING payment,
 *       then returns. The slow, tail-latency-prone gateway calls happen on a
 *       background scheduler ({@code BitcoinPaymentScheduler}). This is the
 *       direct lesson from the {@code third-party-service} p99 ~4.7s tail: an
 *       external payment call is what saturates request threads first, so it is
 *       moved off the request path.</li>
 *   <li><b>Feature-flag gated.</b> Rollout is controlled by the
 *       {@code bitcoin_payments_enabled} OpenFeature flag, mirroring the existing
 *       flag pattern — the feature can be dark-launched and instantly disabled
 *       without a redeploy.</li>
 * </ul>
 */
@Service
public class BitcoinPaymentService {
    private static final Logger logger = LoggerFactory.getLogger(BitcoinPaymentService.class);

    public static final String FEATURE_FLAG = "bitcoin_payments_enabled";

    private final BitcoinPaymentRepository repository;
    private final BitcoinGatewayClient gatewayClient;
    private final OpenFeatureAPI openFeatureAPI;

    public BitcoinPaymentService(BitcoinPaymentRepository repository,
            BitcoinGatewayClient gatewayClient,
            OpenFeatureAPI openFeatureAPI) {
        this.repository = repository;
        this.gatewayClient = gatewayClient;
        this.openFeatureAPI = openFeatureAPI;
    }

    public boolean isEnabled() {
        final Client client = openFeatureAPI.getClient();
        return client.getBooleanValue(FEATURE_FLAG, false);
    }

    /**
     * Accept a payment and persist it as PENDING. Fast, non-blocking, idempotent
     * per (orderId): a second initiate for an in-flight order returns the existing
     * payment rather than creating a duplicate.
     */
    public BitcoinPayment initiate(BitcoinPaymentRequest request) {
        Optional<BitcoinPayment> existing = repository.findInFlight().stream()
                .filter(p -> p.orderId().equals(request.orderId()))
                .findFirst();
        if (existing.isPresent()) {
            logger.info("Returning existing in-flight bitcoin payment {} for order {}",
                    existing.get().paymentId(), request.orderId());
            return existing.get();
        }

        OffsetDateTime now = OffsetDateTime.now();
        BitcoinPayment payment = new BitcoinPayment(
                UUID.randomUUID().toString(),
                request.orderId(),
                request.accountId(),
                request.amount(),
                request.currency().toUpperCase(),
                null,
                null,
                BitcoinPaymentStatus.PENDING,
                now,
                now);
        repository.save(payment);
        logger.info("Accepted bitcoin payment {} for order {} ({} {})",
                payment.paymentId(), payment.orderId(), payment.amount(), payment.currency());
        return payment;
    }

    public Optional<BitcoinPayment> get(String paymentId) {
        return repository.findById(paymentId);
    }

    /** In-flight (non-terminal) payments, for the background scheduler to advance. */
    public java.util.List<BitcoinPayment> inFlight() {
        return repository.findInFlight();
    }

    /**
     * Advance one payment through its lifecycle by talking to the gateway.
     * Called by the background scheduler, never on a request thread. All gateway
     * failures are non-fatal — the payment is simply retried on the next tick.
     */
    public void advance(BitcoinPayment payment) {
        switch (payment.status()) {
            case PENDING -> {
                BitcoinGatewayClient.GatewayQuote quote =
                        gatewayClient.createPayment(payment.paymentId(), payment.amount(), payment.currency());
                if (quote != null && quote.paymentAddress() != null) {
                    repository.updateStatus(payment.paymentId(),
                            BitcoinPaymentStatus.AWAITING_CONFIRMATION,
                            quote.paymentAddress(), quote.btcAmount());
                    logger.info("Bitcoin payment {} moved to AWAITING_CONFIRMATION", payment.paymentId());
                }
            }
            case AWAITING_CONFIRMATION -> {
                BitcoinGatewayClient.BitcoinPaymentGatewayState state =
                        gatewayClient.getPaymentState(payment.paymentId());
                if (state == null) {
                    return; // transient; retry next tick
                }
                switch (state) {
                    case CONFIRMED -> {
                        repository.updateStatus(payment.paymentId(),
                                BitcoinPaymentStatus.CONFIRMED, null, null);
                        logger.info("Bitcoin payment {} CONFIRMED", payment.paymentId());
                    }
                    case FAILED -> {
                        repository.updateStatus(payment.paymentId(),
                                BitcoinPaymentStatus.FAILED, null, null);
                        logger.warn("Bitcoin payment {} FAILED at gateway", payment.paymentId());
                    }
                    case PENDING -> { /* still awaiting on-chain confirmation */ }
                }
            }
            default -> { /* terminal; nothing to do */ }
        }
    }
}
