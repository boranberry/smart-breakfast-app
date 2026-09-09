package com.smartoffice.breakfast.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.smartoffice.breakfast.dto.ErrorResponse;
import com.smartoffice.breakfast.security.CustomUserDetailsService;
import com.smartoffice.breakfast.security.JwtAuthFilter;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.NoOpPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity // enables @PreAuthorize on controller/service methods
@RequiredArgsConstructor
public class SecurityConfig {

    private final CustomUserDetailsService userDetailsService;
    private final JwtAuthFilter jwtAuthFilter;

    /**
     * Comma-separated allow-list of origins permitted to make credentialed
     * requests (cookies / Authorization headers), injected from the
     * CORS_ALLOWED_ORIGINS environment variable (see application.yml).
     * Wildcards are intentionally NOT supported here: with
     * allowCredentials(true), Spring Security refuses "*" outright, and
     * even allowedOriginPatterns("*") would let any site ride a logged-in
     * user's session. Keep this list to the exact frontend origins that
     * need to call the API with credentials.
     */
    @Value("${app.cors.allowed-origins}")
    private String allowedOrigins;

    @Bean
    public PasswordEncoder passwordEncoder() {
        return NoOpPasswordEncoder.getInstance();
    }

    @Bean
    public DaoAuthenticationProvider authenticationProvider() {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider();
        provider.setUserDetailsService(userDetailsService);
        provider.setPasswordEncoder(passwordEncoder());
        return provider;
    }

    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration config) throws Exception {
        return config.getAuthenticationManager();
    }

    /**
     * Fires for anyone who is NOT authenticated at all: no token, a malformed
     * token, an expired token, or a token for a user that no longer exists.
     * Without this bean, Spring Security's default {@code Http403ForbiddenEntryPoint}
     * answers every one of those cases with a bare 403 and no body — which is
     * indistinguishable, from the frontend's point of view, from "you're
     * logged in but not allowed to do this". That is what caused stale
     * sessions to spin forever instead of bouncing the user back to login:
     * api.js only clears localStorage on a 401.
     */
    @Bean
    public AuthenticationEntryPoint authenticationEntryPoint(ObjectMapper securityObjectMapper) {
        return (request, response, authException) -> writeError(
                response, HttpStatus.UNAUTHORIZED,
                "Authentication required or your session has expired. Please log in again.",
                securityObjectMapper);
    }

    /**
     * Fires when the caller IS authenticated but lacks the role/permission
     * for the endpoint (e.g. a USER hitting an ADMIN-only route). Kept
     * distinct from the entry point above so 403 keeps meaning exactly one
     * thing: "you're logged in, but not allowed to do this."
     */
    @Bean
    public AccessDeniedHandler accessDeniedHandler(ObjectMapper securityObjectMapper) {
        return (request, response, accessDeniedException) -> writeError(
                response, HttpStatus.FORBIDDEN,
                "You do not have permission to perform this action",
                securityObjectMapper);
    }

    /** Jackson instance for the security layer, which runs outside Spring MVC's message converters. */
    @Bean
    public ObjectMapper securityObjectMapper() {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        return mapper;
    }

    private static void writeError(HttpServletResponse response, HttpStatus status, String message,
                                   ObjectMapper mapper) throws java.io.IOException {
        ErrorResponse body = ErrorResponse.builder()
                .timestamp(LocalDateTime.now())
                .status(status.value())
                .error(status.getReasonPhrase())
                .message(message)
                .build();
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(mapper.writeValueAsString(body));
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http,
                                                   AuthenticationEntryPoint authenticationEntryPoint,
                                                   AccessDeniedHandler accessDeniedHandler) throws Exception {
        http
                .csrf(csrf -> csrf.disable())
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint)
                        .accessDeniedHandler(accessDeniedHandler)
                )
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints
                        .requestMatchers("/api/auth/**").permitAll()
                        .requestMatchers("/actuator/health","/swagger-ui/**","/swagger-ui.html","/v3/api-docs","/v3/api-docs/**").permitAll()

                        // Frontend static assets (served from src/main/resources/static)
                        .requestMatchers("/", "/index.html", "/favicon.ico",
                                "/css/**", "/js/**", "/assets/**").permitAll()

                        // The whole post-delivery approval surface: receipt review,
                        // bill preview and the approve action that writes verified
                        // prices into the restaurant menu. Matched by prefix so a new
                        // endpoint on AdminApprovalController is locked down by default.
                        .requestMatchers("/api/admin/**").hasRole("ADMIN")

                        // Admin-only endpoints (also enforced via @PreAuthorize at method level)
                        .requestMatchers(HttpMethod.POST, "/api/rooms").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/rooms/*/close").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/rooms/*/receipt").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/rooms/*/calculate-bill").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.GET, "/api/rooms/*/orders/summary").hasRole("ADMIN")

                        // Verified menus are readable by any signed-in user: this is how
                        // a new room offers pre-priced items. Writes only ever happen
                        // through the admin approval endpoint above.
                        .requestMatchers(HttpMethod.GET, "/api/restaurants/**").authenticated()
                        .requestMatchers(HttpMethod.GET, "/api/rooms/*/menu").authenticated()

                        // Everything else under /api requires authentication
                        .requestMatchers("/api/**").authenticated()
                        .anyRequest().authenticated()
                )
                .authenticationProvider(authenticationProvider())
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        List<String> origins = Arrays.stream(allowedOrigins.split(","))
                .map(String::trim)
                .filter(origin -> !origin.isEmpty())
                .toList();

        CorsConfiguration configuration = new CorsConfiguration();
        // Explicit origin allow-list, NOT "*" or a wildcard pattern: combined
        // with allowCredentials(true) below, a wildcard would let any site on
        // the internet send cookies/JWTs on behalf of a logged-in user.
        // Set CORS_ALLOWED_ORIGINS in the environment to override the
        // localhost defaults for staging/production.
        configuration.setAllowedOrigins(origins);
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}