package com.dynatrace.easytrade.creditcardorderservice;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Client for the external bitcoin payment gateway.
 *
 * <p><b>Why this class is defensive.</b> Production telemetry (Bluebox) shows the
 * existing external payment dependency ({@code third-party-service}) has a p99 of
 * ~4.7s against a ~5ms median — a rare but severe latency tail — and the existing
 * HTTP clients in this service use {@link HttpClient} with <i>no timeout</i>. Under
 * higher volume that tail is the component that exhausts request-handling threads
 * first. A bitcoin gateway has the same shape (on-chain settlement is slow and
 * variable), so every call here is bounded by:
 * <ul>
 *   <li>a connect timeout and a per-request read timeout,</li>
 *   <li>bounded retries with backoff for transient failures, and</li>
 *   <li>a lightweight circuit breaker that fails fast when the gateway is
 *       unhealthy instead of piling threads onto a stalled dependency.</li>
 * </ul>
 *
 * <p>All tuning is read from the environment (consistent with the rest of the
 * service) with safe defaults, so the feature is self-configuring and can be
 * tightened in production without a redeploy where the platform allows env
 * changes.
 */
@Component
public class BitcoinGatewayClient {
    private static final Logger logger = LoggerFactory.getLogger(BitcoinGatewayClient.class);

    private final HttpClient httpClient;
    private final ObjectMapper mapper = new ObjectMapper();

    private final String baseUrl;
    private final Duration requestTimeout;
    private final int maxRetries;

    // --- Minimal circuit breaker state ---
    private final int failureThreshold;
    private final Duration openDuration;
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private final AtomicReference<Instant> openUntil = new AtomicReference<>(Instant.EPOCH);

    public BitcoinGatewayClient() {
        this(
                envOrDefault("BITCOIN_GATEWAY_URL", "http://bitcoin-gateway:8080"),
                Duration.ofMillis(Long.parseLong(envOrDefault("BITCOIN_GATEWAY_CONNECT_TIMEOUT_MS", "2000"))),
                Duration.ofMillis(Long.parseLong(envOrDefault("BITCOIN_GATEWAY_REQUEST_TIMEOUT_MS", "3000"))),
                Integer.parseInt(envOrDefault("BITCOIN_GATEWAY_MAX_RETRIES", "2")),
                Integer.parseInt(envOrDefault("BITCOIN_GATEWAY_CB_FAILURE_THRESHOLD", "5")),
                Duration.ofMillis(Long.parseLong(envOrDefault("BITCOIN_GATEWAY_CB_OPEN_MS", "15000"))));
    }

    BitcoinGatewayClient(String baseUrl, Duration connectTimeout, Duration requestTimeout,
            int maxRetries, int failureThreshold, Duration openDuration) {
        this.baseUrl = baseUrl;
        this.requestTimeout = requestTimeout;
        this.maxRetries = maxRetries;
        this.failureThreshold = failureThreshold;
        this.openDuration = openDuration;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .build();
    }

    /**
     * Ask the gateway to create a payment (issue an address and BTC quote).
     * Returns null if the gateway is unavailable/failing — the caller keeps the
     * payment PENDING and retries on the next scheduler tick rather than failing
     * the customer request.
     */
    public GatewayQuote createPayment(String paymentId, BigDecimal amount, String currency) {
        if (isCircuitOpen()) {
            logger.warn("Bitcoin gateway circuit is OPEN; skipping createPayment for {}", paymentId);
            return null;
        }
        String body;
        try {
            body = mapper.writeValueAsString(Map.of(
                    "paymentId", paymentId,
                    "amount", amount.toPlainString(),
                    "currency", currency));
        } catch (Exception e) {
            logger.error("Failed to serialize bitcoin payment request for {}", paymentId, e);
            return null;
        }

        HttpResponse<String> response = sendWithRetries(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/v1/payments"))
                        .timeout(requestTimeout)
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body))
                        .build(),
                "createPayment(" + paymentId + ")");

        if (response == null || response.statusCode() / 100 != 2) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(response.body());
            return new GatewayQuote(
                    node.path("paymentAddress").asText(null),
                    node.hasNonNull("btcAmount") ? new BigDecimal(node.get("btcAmount").asText()) : null);
        } catch (Exception e) {
            logger.error("Failed to parse gateway createPayment response for {}", paymentId, e);
            return null;
        }
    }

    /**
     * Poll the gateway for the current on-chain status of a payment.
     * Returns null on any failure — the caller leaves the status unchanged and
     * re-polls on the next tick.
     */
    public BitcoinPaymentGatewayState getPaymentState(String paymentId) {
        if (isCircuitOpen()) {
            logger.warn("Bitcoin gateway circuit is OPEN; skipping getPaymentState for {}", paymentId);
            return null;
        }
        HttpResponse<String> response = sendWithRetries(
                HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/v1/payments/" + paymentId))
                        .timeout(requestTimeout)
                        .GET()
                        .build(),
                "getPaymentState(" + paymentId + ")");

        if (response == null || response.statusCode() / 100 != 2) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(response.body());
            String state = node.path("state").asText("");
            return switch (state.toUpperCase()) {
                case "CONFIRMED", "SETTLED" -> BitcoinPaymentGatewayState.CONFIRMED;
                case "FAILED", "EXPIRED", "UNDERPAID" -> BitcoinPaymentGatewayState.FAILED;
                default -> BitcoinPaymentGatewayState.PENDING;
            };
        } catch (Exception e) {
            logger.error("Failed to parse gateway getPaymentState response for {}", paymentId, e);
            return null;
        }
    }

    private HttpResponse<String> sendWithRetries(HttpRequest request, String opName) {
        int attempt = 0;
        while (true) {
            try {
                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
                // 5xx is retryable; 4xx is a caller/permanent error and is not retried.
                if (response.statusCode() / 100 == 5 && attempt < maxRetries) {
                    attempt++;
                    backoff(attempt);
                    continue;
                }
                recordSuccess();
                return response;
            } catch (Exception e) {
                logger.warn("Bitcoin gateway {} attempt {} failed: {}", opName, attempt + 1, e.toString());
                if (attempt < maxRetries) {
                    attempt++;
                    backoff(attempt);
                    continue;
                }
                recordFailure();
                return null;
            }
        }
    }

    private void backoff(int attempt) {
        try {
            // Exponential backoff with a small base; keeps total worst-case bounded.
            Thread.sleep(Math.min(200L * (1L << (attempt - 1)), 1000L));
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    // --- Circuit breaker ---

    private boolean isCircuitOpen() {
        return Instant.now().isBefore(openUntil.get());
    }

    private void recordSuccess() {
        consecutiveFailures.set(0);
    }

    private void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        if (failures >= failureThreshold) {
            openUntil.set(Instant.now().plus(openDuration));
            consecutiveFailures.set(0);
            logger.error("Bitcoin gateway circuit OPENED for {} after {} consecutive failures",
                    openDuration, failures);
        }
    }

    private static String envOrDefault(String key, String def) {
        String v = System.getenv(key);
        return (v == null || v.isBlank()) ? def : v;
    }

    /** Quote returned when a payment is created: address + BTC amount. */
    public record GatewayQuote(String paymentAddress, BigDecimal btcAmount) {
    }

    /** Coarse gateway-side state used by the poller. */
    public enum BitcoinPaymentGatewayState {
        PENDING, CONFIRMED, FAILED
    }
}
