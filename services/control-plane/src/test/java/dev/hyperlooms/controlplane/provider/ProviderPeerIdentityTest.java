package dev.hyperlooms.controlplane.provider;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.security.cert.CertificateExpiredException;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProviderPeerIdentityTest {

    @Test
    void extractsOnlyExactProviderUriSan() throws Exception {
        UUID nodeId = UUID.randomUUID();
        X509Certificate certificate = certificate(nodeId);

        ProviderPeerIdentity identity = ProviderPeerIdentity.fromCertificate(certificate);

        assertEquals(nodeId, identity.nodeId());
        assertEquals(64, identity.certificateSha256().length());
        assertFalse(identity.toString().contains(identity.certificateSha256()));
    }

    @Test
    void rejectsMissingAmbiguousMalformedAndExpiredIdentity() throws Exception {
        X509Certificate missing = certificate(UUID.randomUUID());
        when(missing.getSubjectAlternativeNames()).thenReturn(List.<List<?>>of(
                List.of(2, "provider.example")));
        assertThrows(ProviderIdentityDeniedException.class,
                () -> ProviderPeerIdentity.fromCertificate(missing));

        X509Certificate ambiguous = certificate(UUID.randomUUID());
        when(ambiguous.getSubjectAlternativeNames()).thenReturn(List.<List<?>>of(
                List.of(6, "urn:hyperlooms:provider:" + UUID.randomUUID()),
                List.of(6, "urn:hyperlooms:provider:" + UUID.randomUUID())));
        assertThrows(ProviderIdentityDeniedException.class,
                () -> ProviderPeerIdentity.fromCertificate(ambiguous));

        X509Certificate malformed = certificate(UUID.randomUUID());
        when(malformed.getSubjectAlternativeNames()).thenReturn(List.<List<?>>of(
                List.of(6, "urn:hyperlooms:provider:not-a-uuid")));
        assertThrows(ProviderIdentityDeniedException.class,
                () -> ProviderPeerIdentity.fromCertificate(malformed));

        X509Certificate expired = certificate(UUID.randomUUID());
        doThrow(new CertificateExpiredException()).when(expired).checkValidity();
        assertThrows(ProviderIdentityDeniedException.class,
                () -> ProviderPeerIdentity.fromCertificate(expired));
    }

    private X509Certificate certificate(UUID nodeId) throws Exception {
        X509Certificate certificate = mock(X509Certificate.class);
        when(certificate.getSubjectAlternativeNames()).thenReturn(List.<List<?>>of(
                List.of(6, "urn:hyperlooms:provider:" + nodeId)));
        when(certificate.getEncoded()).thenReturn(("test-cert-" + nodeId).getBytes());
        when(certificate.getNotAfter()).thenReturn(Date.from(Instant.now().plusSeconds(86400)));
        return certificate;
    }
}
