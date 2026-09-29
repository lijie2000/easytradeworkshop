package com.dynatrace.easytrade.creditcardorderservice;

import com.dynatrace.easytrade.creditcardorderservice.models.FeatureFlag;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public class FeatureFlagClient {
    private static final Logger logger = LoggerFactory.getLogger(FeatureFlagClient.class);

    // A timeout-less HTTP client blocks a request thread indefinitely when the
    // downstream stalls. feature-flag-service is called ~1:1 per request, so at
    // high traffic an unbounded stall here exhausts the Tomcat thread pool. Bound
    // both connect and read; tunable via env with safe defaults.
    private static final Duration CONNECT_TIMEOUT =
            Duration.ofMillis(longEnv("FEATURE_FLAG_CONNECT_TIMEOUT_MS", 1000));
    private static final Duration REQUEST_TIMEOUT =
            Duration.ofMillis(longEnv("FEATURE_FLAG_REQUEST_TIMEOUT_MS", 2000));

    // Short-TTL cache. feature-flag-service is called ~1:1 per inbound request
    // (production telemetry); at scale that is a synchronous downstream hop on
    // nearly every request. A brief cache collapses that to at most one refresh
    // per flag per TTL window, and lets a cached value serve as a fallback when a
    // refresh fails -- so a flag-service blip can't fail requests. TTL is short so
    // a flag flip still takes effect quickly (default 5s).
    private static final long CACHE_TTL_MS = longEnv("FEATURE_FLAG_CACHE_TTL_MS", 5000);

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(CONNECT_TIMEOUT)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.concurrent.ConcurrentMap<String, CacheEntry> cache =
            new java.util.concurrent.ConcurrentHashMap<>();
    private final String featureFlagServiceUrl = System.getenv("FEATURE_FLAG_SERVICE_PROTOCOL") + "://"
            + System.getenv("FEATURE_FLAG_SERVICE_BASE_URL") + ":" + System.getenv("FEATURE_FLAG_SERVICE_PORT")
            + "/v1/flags/";

    public FeatureFlag getFlag(String flagId) {
        CacheEntry entry = cache.get(flagId);
        long now = System.currentTimeMillis();
        if (entry != null && now - entry.fetchedAtMs < CACHE_TTL_MS) {
            return entry.flag;
        }

        logger.info("Getting feature flag with id: {}", flagId);

        // deepcode ignore Ssrf: trusted environment variable
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(featureFlagServiceUrl + flagId))
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();
        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            FeatureFlag flag = mapper.readValue(response.body(), FeatureFlag.class);
            cache.put(flagId, new CacheEntry(flag, now));
            return flag;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallbackOrThrow(flagId, e);
        } catch (IOException e) {
            // Includes HttpTimeoutException when the read timeout fires. Prefer a
            // stale cached value over failing the request; only throw if we have
            // nothing cached at all.
            return fallbackOrThrow(flagId, e);
        }
    }

    /** Serve the last known value if we have one, otherwise propagate the failure. */
    private FeatureFlag fallbackOrThrow(String flagId, Exception cause) {
        CacheEntry stale = cache.get(flagId);
        if (stale != null) {
            logger.warn("feature-flag fetch for '{}' failed ({}); serving cached value", flagId, cause.toString());
            return stale.flag;
        }
        throw new RuntimeException(cause);
    }

    private static long longEnv(String key, long def) {
        String v = System.getenv(key);
        try {
            return (v == null || v.isBlank()) ? def : Long.parseLong(v);
        } catch (NumberFormatException e) {
            return def;
        }
    }

    private record CacheEntry(FeatureFlag flag, long fetchedAtMs) {
    }
}
