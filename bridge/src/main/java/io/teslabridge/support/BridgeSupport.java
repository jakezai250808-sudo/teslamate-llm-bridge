package io.teslabridge.support;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Shared utilities for the bridge play engine.
 *
 * <p>The two integration points are:
 * <ol>
 *   <li>{@code auditApiCall} — logs at INFO for lightweight request auditing.</li>
 *   <li>{@code carIdOutOfWhitelist} — honours env {@code CAR_IDS} (comma-separated).
 *       When {@code CAR_IDS} is unset or blank, every car ID is allowed (single-tenant mode).</li>
 * </ol>
 */
@Component
public class BridgeSupport {

    private static final Logger log = LoggerFactory.getLogger(BridgeSupport.class);

    /** Returned as 404 body when a car ID is not in the whitelist. */
    public static final Map<String, Object> NO_INFO = Map.of("data", Map.of());

    // Populated from env; null/blank = allow all.
    private static Set<Long> allowedCarIds = null;

    @Value("${CAR_IDS:}")
    public void setCarIds(String carIds) {
        if (carIds == null || carIds.isBlank()) {
            allowedCarIds = null; // allow all
            log.info("BridgeSupport: CAR_IDS not set — all car IDs allowed (single-tenant mode)");
        } else {
            try {
                allowedCarIds =
                        Arrays.stream(carIds.split(","))
                                .map(String::trim)
                                .filter(s -> !s.isEmpty())
                                .map(Long::parseLong)
                                .collect(Collectors.toSet());
                log.info("BridgeSupport: restricting play engine to car IDs: {}", allowedCarIds);
            } catch (NumberFormatException e) {
                log.error(
                        "BridgeSupport: CAR_IDS '{}' contains non-numeric token — "
                                + "refusing to start with ambiguous whitelist. Fix CAR_IDS.",
                        carIds);
                throw new IllegalStateException("CAR_IDS parse error: " + e.getMessage(), e);
            }
        }
    }

    /**
     * Audit an API call — logs at INFO.
     *
     * @param target endpoint path (e.g. "/api/v1/plays")
     */
    public static void auditApiCall(String target) {
        log.info("play-api call: {}", target);
    }

    /**
     * Returns {@code true} when the given car ID is NOT in the whitelist.
     * Always returns {@code false} (allow) when {@code CAR_IDS} is unset.
     */
    public static boolean carIdOutOfWhitelist(long carId) {
        if (allowedCarIds == null) return false;
        return !allowedCarIds.contains(carId);
    }
}
