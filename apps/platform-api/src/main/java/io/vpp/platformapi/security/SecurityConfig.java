package io.vpp.platformapi.security;

import java.io.IOException;

import jakarta.servlet.http.HttpServletResponse;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import io.vpp.platformapi.config.PlatformProperties;
import io.vpp.platformapi.config.RealtimeProperties;

@Configuration
public class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, PlatformProperties properties,
            ActorLookupService actors) throws Exception {
        http.csrf(csrf -> csrf.disable()).cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/**", "/livez", "/readyz", "/api/v1/health",
                                "/api/v1/internal/**", "/ws/v1").permitAll()
                        .anyRequest().authenticated())
                .exceptionHandling(errors -> errors
                        .authenticationEntryPoint((request, response, exception) -> unauthorized(response))
                        .accessDeniedHandler((request, response, exception) -> forbidden(response)));
        if (properties.auth().mode() == PlatformProperties.AuthMode.LOCAL) {
            http.addFilterAfter(new LocalAuthenticationFilter(actors), SecurityContextHolderFilter.class);
        } else {
            http.oauth2ResourceServer(resource -> resource.jwt(Customizer.withDefaults()));
        }
        return http.build();
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(RealtimeProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(properties.allowedOrigins());
        configuration.setAllowedMethods(java.util.List.of("GET", "POST", "OPTIONS"));
        configuration.setAllowedHeaders(java.util.List.of("Authorization", "Content-Type",
                "X-VPP-Tenant-Id", "X-VPP-Subject", "Idempotency-Key"));
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/v1/**", configuration);
        return source;
    }

    @Bean
    JwtDecoder jwtDecoder(PlatformProperties properties) {
        if (properties.auth().mode() == PlatformProperties.AuthMode.JWT) {
            return JwtDecoders.fromIssuerLocation(properties.auth().jwtIssuerUri());
        }
        return token -> { throw new UnsupportedOperationException("JWT authentication is disabled"); };
    }

    private static void unauthorized(HttpServletResponse response) throws IOException {
        response.setStatus(401);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"code\":\"UNAUTHORIZED\",\"message\":\"authentication required\",\"trace_id\":\"00000000000000000000000000000000\",\"details\":[]}");
    }

    private static void forbidden(HttpServletResponse response) throws IOException {
        response.setStatus(403);
        response.setContentType("application/problem+json");
        response.getWriter().write("{\"code\":\"FORBIDDEN\",\"message\":\"permission denied\",\"trace_id\":\"00000000000000000000000000000000\",\"details\":[]}");
    }
}
