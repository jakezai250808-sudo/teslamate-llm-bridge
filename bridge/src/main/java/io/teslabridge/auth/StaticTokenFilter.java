package io.teslabridge.auth;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Single-tenant Bearer token filter.
 *
 * <p>Behaviour mirrors teslamateapi's own token check:
 * <ul>
 *   <li>When {@code API_TOKEN} env is set: require {@code Authorization: Bearer <token>}.
 *       Missing or wrong token → 401.</li>
 *   <li>When {@code API_TOKEN} is unset or blank: warn once at startup and allow all requests
 *       (local / dev mode).</li>
 * </ul>
 *
 * <p>Only applied to paths under {@code /api/}.
 */
@Component
public class StaticTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(StaticTokenFilter.class);

    private final String configuredToken;

    public StaticTokenFilter(@Value("${bridge.api-token:}") String configuredToken) {
        this.configuredToken = configuredToken == null ? "" : configuredToken.trim();
        if (this.configuredToken.isEmpty()) {
            log.warn(
                    "StaticTokenFilter: API_TOKEN is not set — authentication disabled. "
                            + "Set API_TOKEN env var to require Bearer token on /api/** endpoints.");
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Only guard /api/ paths; health/info etc. are public.
        String path = request.getRequestURI();
        return !path.startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain)
            throws ServletException, IOException {
        if (configuredToken.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            // Hash both inputs to a fixed-length digest before comparing so that
            // MessageDigest.isEqual() always receives arrays of equal length, preserving
            // constant-time behavior regardless of the raw token length.
            try {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                byte[] expectedHash = md.digest(configuredToken.getBytes(StandardCharsets.UTF_8));
                byte[] actualHash   = md.digest(token.getBytes(StandardCharsets.UTF_8));
                if (MessageDigest.isEqual(expectedHash, actualHash)) {
                    filterChain.doFilter(request, response);
                    return;
                }
            } catch (NoSuchAlgorithmException e) {
                // SHA-256 is mandated by the Java SE spec; this branch is unreachable.
                throw new IllegalStateException("SHA-256 unavailable", e);
            }
        }
        response.setStatus(HttpStatus.UNAUTHORIZED.value());
        response.setContentType("application/json");
        response.getWriter().write("{\"error\":\"UNAUTHORIZED\"}");
    }
}
