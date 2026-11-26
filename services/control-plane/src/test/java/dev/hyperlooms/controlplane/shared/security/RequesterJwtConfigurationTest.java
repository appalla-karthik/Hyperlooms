package dev.hyperlooms.controlplane.shared.security;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class RequesterJwtConfigurationTest {

    private final RequesterApiSecurityConfiguration.EnabledJwtConfiguration configuration =
            new RequesterApiSecurityConfiguration.EnabledJwtConfiguration();

    @Test
    void refusesMissingOrInsecureTrustConfiguration() {
        assertThrows(IllegalStateException.class, () -> configuration.requesterJwtDecoder(
                "", "https://issuer.example/jwks", "hyperlooms-api", false));
        assertThrows(IllegalStateException.class, () -> configuration.requesterJwtDecoder(
                "http://issuer.example", "https://issuer.example/jwks", "hyperlooms-api", false));
        assertThrows(IllegalStateException.class, () -> configuration.requesterJwtDecoder(
                "https://issuer.example", "http://issuer.example/jwks", "hyperlooms-api", false));
        assertThrows(IllegalStateException.class, () -> configuration.requesterJwtDecoder(
                "https://issuer.example", "https://issuer.example/jwks", "", false));
        assertThrows(IllegalStateException.class, () -> configuration.requesterJwtDecoder(
                "http://issuer.example", "http://issuer.example/jwks", "hyperlooms-api", true));
        assertThrows(IllegalStateException.class, () -> configuration.requesterJwtDecoder(
                "http://localhost:8180/realms/dev", "http://issuer.example/jwks",
                "hyperlooms-api", true));
    }

    @Test
    void acceptsConfiguredHttpsIssuerJwksAndAudience() {
        assertNotNull(configuration.requesterJwtDecoder(
                "https://issuer.example", "https://issuer.example/jwks", "hyperlooms-api", false));
        assertNotNull(configuration.requesterJwtDecoder(
                "http://localhost:8180/realms/dev", "http://localhost:8180/realms/dev/protocol/openid-connect/certs",
                "hyperlooms-api", true));
    }

    @Test
    void checksIssuerAudienceSubjectAndExpiry() {
        var validator = RequesterApiSecurityConfiguration.EnabledJwtConfiguration
                .requesterValidator("https://issuer.example", "hyperlooms-api");
        assertFalse(validator.validate(token("https://issuer.example", "hyperlooms-api",
                "alice", Instant.now().plusSeconds(60))).hasErrors());
        assertTrue(validator.validate(token("https://other.example", "hyperlooms-api",
                "alice", Instant.now().plusSeconds(60))).hasErrors());
        assertTrue(validator.validate(token("https://issuer.example", "other-api",
                "alice", Instant.now().plusSeconds(60))).hasErrors());
        assertTrue(validator.validate(token("https://issuer.example", "hyperlooms-api",
                "", Instant.now().plusSeconds(60))).hasErrors());
        assertTrue(validator.validate(token("https://issuer.example", "hyperlooms-api",
                "alice", Instant.now().minusSeconds(120))).hasErrors());
    }

    @Test
    void mapsKeycloakScopeClaimToRequesterAuthorities() {
        Jwt token = Jwt.withTokenValue("synthetic-test-token").header("alg", "RS256")
                .claim("sub", "alice")
                .claim("scope", "openid jobs:read jobs:write")
                .build();
        var authentication = RequesterApiSecurityConfiguration.requesterAuthenticationConverter()
                .convert(token);
        assertNotNull(authentication);
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("SCOPE_jobs:read")));
        assertTrue(authentication.getAuthorities().stream()
                .anyMatch(authority -> authority.getAuthority().equals("SCOPE_jobs:write")));
    }

    private static Jwt token(String issuer, String audience, String subject, Instant expiresAt) {
        return Jwt.withTokenValue("synthetic-test-token").header("alg", "RS256")
                .issuedAt(Instant.now().minusSeconds(300)).expiresAt(expiresAt)
                .claim("iss", issuer).claim("aud", List.of(audience))
                .claim("sub", subject).build();
    }
}
