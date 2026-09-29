package com.dynatrace.easytrade.creditcardorderservice;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPayment;

/**
 * Background worker that drives in-flight bitcoin payments through the gateway.
 *
 * <p>This is where all slow, tail-latency-prone gateway I/O lives — deliberately
 * off the request path. It follows the existing {@link BaseScheduler}/
 * {@code WorkScheduler} pattern. Each tick it loads in-flight payments and asks
 * the service to advance each one; every gateway failure is non-fatal and simply
 * retried next tick (the gateway client already bounds each call with timeouts,
 * retries and a circuit breaker).
 *
 * <p>Config via env with safe defaults (no new required env vars):
 * {@code BITCOIN_SCHEDULER_DELAY} (seconds, default 5),
 * {@code BITCOIN_SCHEDULER_RATE} (seconds, default 5).
 */
@Service
public class BitcoinPaymentScheduler extends BaseScheduler {
    private static final Logger logger = LoggerFactory.getLogger(BitcoinPaymentScheduler.class);

    private final BitcoinPaymentService paymentService;

    public BitcoinPaymentScheduler(BitcoinPaymentService paymentService) {
        super("bitcoin-payment",
                intEnv("BITCOIN_SCHEDULER_DELAY", 5),
                intEnv("BITCOIN_SCHEDULER_RATE", 5));
        this.paymentService = paymentService;
    }

    @Override
    protected void run() {
        try {
            List<BitcoinPayment> inFlight = paymentService.inFlight();
            if (inFlight.isEmpty()) {
                return;
            }
            logger.info("Advancing {} in-flight bitcoin payment(s)", inFlight.size());
            for (BitcoinPayment payment : inFlight) {
                try {
                    paymentService.advance(payment);
                } catch (Exception e) {
                    // One bad payment must never stall the batch or crash the worker.
                    logger.error("Failed to advance bitcoin payment {}: {}",
                            payment.paymentId(), e.getMessage(), e);
                }
            }
        } catch (Exception e) {
            logger.error("BitcoinPaymentScheduler tick failed: {}", e.getMessage(), e);
        }
    }

    private static int intEnv(String key, int def) {
        String v = System.getenv(key);
        try {
            return (v == null || v.isBlank()) ? def : Integer.parseInt(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }
}
