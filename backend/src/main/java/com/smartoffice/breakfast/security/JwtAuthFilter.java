package com.smartoffice.breakfast.security;

import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.MalformedJwtException;
import io.jsonwebtoken.UnsupportedJwtException;
import io.jsonwebtoken.security.SignatureException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Reads the Authorization header and, if it carries a valid token, populates
 * the SecurityContext for this request.
 *
 * IMPORTANT: this filter never itself rejects a request. Any problem with the
 * token (missing, malformed, expired, bad signature, or naming a user that no
 * longer exists) is treated the same way: leave the context empty and move
 * on. Spring Security's AuthorizationFilter then sees an unauthenticated
 * request against a protected route and hands it to the
 * {@code AuthenticationEntryPoint} configured in SecurityConfig, which
 * answers with a clean JSON 401. That hand-off is *why* every failure mode
 * below is caught the same way — the only thing that matters here is that we
 * never leave a half-set, inconsistent SecurityContext behind.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;
    private final CustomUserDetailsService userDetailsService;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                     @NonNull HttpServletResponse response,
                                     @NonNull FilterChain filterChain) throws ServletException, IOException {

        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String token = authHeader.substring(7);

        try {
            final String phone = jwtUtil.extractPhone(token);

            if (phone != null && SecurityContextHolder.getContext().getAuthentication() == null) {
                UserDetails userDetails = userDetailsService.loadUserByUsername(phone);

                if (jwtUtil.isTokenValid(token, userDetails.getUsername())) {
                    UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                            userDetails, null, userDetails.getAuthorities());
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                } else {
                    // Token parses but fails validation (e.g. subject mismatch): make sure
                    // nothing partial is left behind.
                    SecurityContextHolder.clearContext();
                }
            }
        } catch (ExpiredJwtException ex) {
            log.debug("Rejected expired JWT for request {}: {}", request.getRequestURI(), ex.getMessage());
            SecurityContextHolder.clearContext();
        } catch (SignatureException ex) {
            log.warn("Rejected JWT with invalid signature for request {}", request.getRequestURI());
            SecurityContextHolder.clearContext();
        } catch (MalformedJwtException | UnsupportedJwtException | IllegalArgumentException ex) {
            log.debug("Rejected malformed/unsupported JWT for request {}: {}", request.getRequestURI(), ex.getMessage());
            SecurityContextHolder.clearContext();
        } catch (UsernameNotFoundException ex) {
            // Token is well-formed and unexpired, but names a user that no longer
            // exists (e.g. dev database was reset while a browser still held an
            // old token). This is NOT a JwtException, so it needs its own catch —
            // previously it escaped this filter entirely and surfaced as an
            // unhandled 500 instead of a clean 401.
            log.debug("JWT referenced a user that no longer exists: {}", ex.getMessage());
            SecurityContextHolder.clearContext();
        } catch (JwtException ex) {
            // Catch-all for any other jjwt exception not enumerated above.
            log.debug("Rejected invalid JWT for request {}: {}", request.getRequestURI(), ex.getMessage());
            SecurityContextHolder.clearContext();
        }

        filterChain.doFilter(request, response);
    }
}
