package dev.hyperlooms.controlplane.shared.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

import org.springframework.security.oauth2.jwt.Jwt;

/** Stable, bounded database identity scoped to the verified token issuer. */
public final class RequesterIdentity {

    private RequesterIdentity() {
    }

    public static String fromVerifiedJwt(Jwt jwt) {
        if (jwt == null || jwt.getIssuer() == null || jwt.getSubject() == null
                || jwt.getSubject().isBlank()) {
            throw new IllegalArgumentException("Verified requester identity is required");
        }
        String scoped = jwt.getIssuer() + "\0" + jwt.getSubject();
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(scoped.getBytes(StandardCharsets.UTF_8));
            return "oidc:" + HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
