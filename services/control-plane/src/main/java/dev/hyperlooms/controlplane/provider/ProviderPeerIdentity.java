package dev.hyperlooms.controlplane.provider;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Collection;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Extracts an identity from a certificate. This does not validate its CA chain or prove key
 * possession; a transport adapter may use it as a peer only after a successful mTLS handshake.
 */
public final class ProviderPeerIdentity {

    private static final String SAN_PREFIX = "urn:hyperlooms:provider:";
    private static final int URI_SAN_TYPE = 6;

    private final UUID nodeId;
    private final String certificateSha256;
    private final Instant notAfter;

    private ProviderPeerIdentity(UUID nodeId, String certificateSha256, Instant notAfter) {
        this.nodeId = nodeId;
        this.certificateSha256 = certificateSha256;
        this.notAfter = notAfter;
    }

    public static ProviderPeerIdentity fromCertificate(X509Certificate certificate) {
        if (certificate == null) {
            throw new ProviderIdentityDeniedException();
        }
        try {
            certificate.checkValidity();
            Collection<List<?>> names = certificate.getSubjectAlternativeNames();
            UUID nodeId = null;
            if (names != null) {
                for (List<?> name : names) {
                    if (name.size() >= 2 && Integer.valueOf(URI_SAN_TYPE).equals(name.get(0))
                            && name.get(1) instanceof String uri && uri.startsWith(SAN_PREFIX)) {
                        if (nodeId != null) {
                            throw new ProviderIdentityDeniedException();
                        }
                        String claimedId = uri.substring(SAN_PREFIX.length());
                        nodeId = UUID.fromString(claimedId);
                        if (!nodeId.toString().equals(claimedId)) {
                            throw new ProviderIdentityDeniedException();
                        }
                    }
                }
            }
            if (nodeId == null) {
                throw new ProviderIdentityDeniedException();
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            String fingerprint = HexFormat.of().formatHex(digest.digest(certificate.getEncoded()));
            return new ProviderPeerIdentity(nodeId, fingerprint, certificate.getNotAfter().toInstant());
        } catch (CertificateException | NoSuchAlgorithmException | IllegalArgumentException exception) {
            throw new ProviderIdentityDeniedException();
        }
    }

    public UUID nodeId() {
        return nodeId;
    }

    public String certificateSha256() {
        return certificateSha256;
    }

    public Instant notAfter() {
        return notAfter;
    }

    @Override
    public String toString() {
        return "ProviderPeerIdentity[nodeId=" + nodeId + ", certificateSha256=<redacted>]";
    }
}
