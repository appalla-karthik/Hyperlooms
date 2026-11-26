package dev.hyperlooms.controlplane.shared.security;

import java.net.URI;
import java.util.Objects;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.security.web.SecurityFilterChain;

@Configuration(proxyBeanMethods = false)
public class RequesterApiSecurityConfiguration {

    @Bean
    SecurityFilterChain httpSecurity(HttpSecurity http,
            @Value("${hyperlooms.requester-api.enabled:false}") boolean enabled) throws Exception {
        http.csrf(csrf -> csrf.disable())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .httpBasic(basic -> basic.disable())
                .formLogin(form -> form.disable())
                .authorizeHttpRequests(auth -> {
                    auth.requestMatchers("/api/v1/system/status", "/actuator/health",
                            "/actuator/health/**", "/actuator/info", "/v3/api-docs/**",
                            "/swagger-ui/**", "/swagger-ui.html").permitAll();
                    if (enabled) {
                        auth.requestMatchers(HttpMethod.POST, "/api/v1/jobs")
                                .hasAuthority("SCOPE_jobs:write");
                        auth.requestMatchers(HttpMethod.PUT, "/api/v1/jobs/*/inputs/**")
                                .hasAuthority("SCOPE_jobs:write");
                        auth.requestMatchers(HttpMethod.POST, "/api/v1/jobs/*/queue")
                                .hasAuthority("SCOPE_jobs:write");
                        auth.requestMatchers(HttpMethod.POST, "/api/v1/jobs/*/cancel")
                                .hasAuthority("SCOPE_jobs:write");
                        auth.requestMatchers(HttpMethod.GET, "/api/v1/jobs/*")
                                .hasAuthority("SCOPE_jobs:read");
                    }
                    auth.anyRequest().denyAll();
        });
        if (enabled) {
            http.oauth2ResourceServer(resourceServer -> resourceServer.jwt(jwt ->
                    jwt.jwtAuthenticationConverter(requesterAuthenticationConverter())));
        }
        return http.build();
    }

    static JwtAuthenticationConverter requesterAuthenticationConverter() {
        JwtGrantedAuthoritiesConverter scopes = new JwtGrantedAuthoritiesConverter();
        scopes.setAuthoritiesClaimName("scope");
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setJwtGrantedAuthoritiesConverter(scopes);
        return converter;
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = "hyperlooms.requester-api.enabled", havingValue = "true")
    static class EnabledJwtConfiguration {

        @Bean
        JwtDecoder requesterJwtDecoder(
                @Value("${hyperlooms.requester-api.issuer-uri:}") String issuer,
                @Value("${hyperlooms.requester-api.jwk-set-uri:}") String jwkSet,
                @Value("${hyperlooms.requester-api.audience:}") String audience,
                @Value("${hyperlooms.requester-api.allow-insecure-local-issuer:false}")
                boolean allowInsecureLocalIssuer) {
            requireTrustedUri(issuer, "issuer URI", allowInsecureLocalIssuer);
            requireTrustedUri(jwkSet, "JWK set URI", allowInsecureLocalIssuer);
            if (audience == null || audience.isBlank()) {
                throw new IllegalStateException("Requester API audience is required");
            }
            NimbusJwtDecoder decoder = NimbusJwtDecoder.withJwkSetUri(jwkSet).build();
            decoder.setJwtValidator(requesterValidator(issuer, audience));
            return decoder;
        }

        static OAuth2TokenValidator<Jwt> requesterValidator(String issuer, String audience) {
            OAuth2TokenValidator<Jwt> audienceAndSubject = token -> {
                if (!token.getAudience().contains(audience)
                        || token.getSubject() == null || token.getSubject().isBlank()) {
                    return OAuth2TokenValidatorResult.failure(new OAuth2Error(
                            "invalid_token", "Requester audience or subject is invalid", null));
                }
                return OAuth2TokenValidatorResult.success();
            };
            return new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(issuer), audienceAndSubject);
        }

        private static void requireTrustedUri(String value, String label,
                boolean allowInsecureLocalIssuer) {
            try {
                URI uri = URI.create(Objects.requireNonNull(value));
                boolean localHttp = allowInsecureLocalIssuer
                        && "http".equalsIgnoreCase(uri.getScheme())
                        && ("localhost".equalsIgnoreCase(uri.getHost())
                                || "127.0.0.1".equals(uri.getHost()));
                if (!("https".equalsIgnoreCase(uri.getScheme()) || localHttp)
                        || uri.getHost() == null || uri.getUserInfo() != null
                        || uri.getFragment() != null || uri.getQuery() != null) {
                    throw new IllegalStateException("Requester API " + label
                            + " must be HTTPS or explicitly allowed loopback HTTP");
                }
            } catch (IllegalArgumentException | NullPointerException exception) {
                throw new IllegalStateException("Requester API " + label
                        + " must be HTTPS or explicitly allowed loopback HTTP", exception);
            }
        }
    }
}
