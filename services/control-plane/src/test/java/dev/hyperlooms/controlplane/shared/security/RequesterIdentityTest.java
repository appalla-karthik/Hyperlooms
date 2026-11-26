package dev.hyperlooms.controlplane.shared.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.Jwt;

class RequesterIdentityTest {

    @Test
    void identityIsStableBoundedAndScopedToIssuerAndSubject() {
        String first = RequesterIdentity.fromVerifiedJwt(jwt("https://issuer.example", "alice"));

        assertEquals(first, RequesterIdentity.fromVerifiedJwt(
                jwt("https://issuer.example", "alice")));
        assertEquals(69, first.length());
        assertTrue(first.startsWith("oidc:"));
        assertNotEquals(first, RequesterIdentity.fromVerifiedJwt(
                jwt("https://issuer.example", "bob")));
        assertNotEquals(first, RequesterIdentity.fromVerifiedJwt(
                jwt("https://another.example", "alice")));
    }

    @Test
    void blankSubjectCannotBecomeRequesterIdentity() {
        assertThrows(IllegalArgumentException.class,
                () -> RequesterIdentity.fromVerifiedJwt(jwt("https://issuer.example", " ")));
    }

    private static Jwt jwt(String issuer, String subject) {
        return Jwt.withTokenValue("test-token").header("alg", "RS256")
                .claim("iss", issuer).claim("sub", subject).build();
    }
}
