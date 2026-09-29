package com.dynatrace.easytrade.creditcardorderservice;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.concurrent.ConcurrentMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the short-TTL cache added to {@link FeatureFlagClient} for scale
 * hardening. We don't have a live feature-flag-service in unit tests, so these
 * assertions focus on the cache structure and env-tunable knobs rather than a
 * real HTTP round trip (the network path is exercised by integration/e2e).
 */
public class FeatureFlagClientCacheTest {

    @Test
    void clientExposesAConcurrentCache() throws Exception {
        FeatureFlagClient client = new FeatureFlagClient();
        Field cacheField = FeatureFlagClient.class.getDeclaredField("cache");
        cacheField.setAccessible(true);
        Object cache = cacheField.get(client);
        assertNotNull(cache, "cache should be initialized");
        assertTrue(cache instanceof ConcurrentMap, "cache must be a ConcurrentMap for thread-safe access");
    }

    @Test
    void cacheTtlIsConfigurableWithSafeDefault() throws Exception {
        // Default TTL is 5000ms when the env var is unset (as in the test JVM).
        Field ttlField = FeatureFlagClient.class.getDeclaredField("CACHE_TTL_MS");
        ttlField.setAccessible(true);
        long ttl = (long) ttlField.get(null);
        assertEquals(5000L, ttl, "default cache TTL should be 5s");
    }
}
