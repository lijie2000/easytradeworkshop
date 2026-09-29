package com.dynatrace.easytrade.creditcardorderservice;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Collectors;

import org.springframework.stereotype.Repository;

import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPayment;
import com.dynatrace.easytrade.creditcardorderservice.models.BitcoinPaymentStatus;

/**
 * Storage for bitcoin payments.
 *
 * <p><b>Implementation note.</b> This uses an in-memory concurrent map so the
 * feature is self-contained and does not require a schema migration in this
 * workshop repo. The interface is intentionally the seam where a durable,
 * <i>pooled</i> datastore is plugged in for production.
 *
 * <p>Production telemetry (Bluebox) shows the existing {@code DatabaseHelper}
 * opens a brand-new JDBC connection per call via
 * {@code DriverManager.getConnection(...)} with no pool, and there is currently
 * <i>no connection-pool telemetry</i> to confirm headroom. At 100x traffic the
 * per-call-connect pattern is a prime saturation risk. When this repository is
 * backed by a real DB it MUST use a bounded pool (e.g. HikariCP) with pool
 * metrics exported, per the design doc accompanying this feature.
 */
@Repository
public class BitcoinPaymentRepository {

    private final ConcurrentMap<String, BitcoinPayment> byId = new ConcurrentHashMap<>();

    public BitcoinPayment save(BitcoinPayment payment) {
        byId.put(payment.paymentId(), payment);
        return payment;
    }

    public Optional<BitcoinPayment> findById(String paymentId) {
        return Optional.ofNullable(byId.get(paymentId));
    }

    /** Payments still in flight, so the scheduler can advance them. */
    public List<BitcoinPayment> findInFlight() {
        return byId.values().stream()
                .filter(p -> !p.status().isTerminal())
                .collect(Collectors.toList());
    }

    /** Update status (and optionally quote fields) atomically, stamping updatedAt. */
    public Optional<BitcoinPayment> updateStatus(String paymentId, BitcoinPaymentStatus status,
            String paymentAddress, java.math.BigDecimal btcAmount) {
        return Optional.ofNullable(byId.computeIfPresent(paymentId, (id, existing) -> new BitcoinPayment(
                existing.paymentId(),
                existing.orderId(),
                existing.accountId(),
                existing.amount(),
                existing.currency(),
                btcAmount != null ? btcAmount : existing.btcAmount(),
                paymentAddress != null ? paymentAddress : existing.paymentAddress(),
                status,
                existing.createdAt(),
                OffsetDateTime.now())));
    }
}
