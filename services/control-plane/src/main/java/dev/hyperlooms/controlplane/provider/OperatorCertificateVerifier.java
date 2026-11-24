package dev.hyperlooms.controlplane.provider;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.InvalidAlgorithmParameterException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertPath;
import java.security.cert.CertPathValidator;
import java.security.cert.CertPathValidatorException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.PKIXParameters;
import java.security.cert.TrustAnchor;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Operator-side validation against the same CA trusted by the agent listener. */
@Component
public final class OperatorCertificateVerifier {

    private static final String CLIENT_AUTH_EKU = "1.3.6.1.5.5.7.3.2";
    private final String clientCaPath;

    public OperatorCertificateVerifier(
            @Value("${hyperlooms.agent-grpc.client-ca:}") String clientCaPath) {
        this.clientCaPath = clientCaPath;
    }

    public X509Certificate verifyClientCertificate(Path certificatePath) {
        if (clientCaPath.isBlank()) {
            throw new IllegalStateException("HYPERLOOMS_AGENT_GRPC_CLIENT_CA is required for certificate binding");
        }
        try {
            CertificateFactory factory = CertificateFactory.getInstance("X.509");
            X509Certificate ca;
            try (InputStream input = Files.newInputStream(Path.of(clientCaPath))) {
                ca = (X509Certificate) factory.generateCertificate(input);
            }
            ca.checkValidity();
            if (ca.getBasicConstraints() < 0) {
                throw new IllegalArgumentException("configured trust anchor is not a CA certificate");
            }
            Collection<? extends java.security.cert.Certificate> parsed;
            try (InputStream input = Files.newInputStream(certificatePath)) {
                parsed = factory.generateCertificates(input);
            }
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("client certificate file is empty");
            }
            List<X509Certificate> chain = new ArrayList<>();
            for (java.security.cert.Certificate certificate : parsed) {
                chain.add((X509Certificate) certificate);
            }
            X509Certificate leaf = chain.get(0);
            leaf.checkValidity();
            if (leaf.getBasicConstraints() >= 0 || leaf.getKeyUsage() == null
                    || leaf.getKeyUsage().length == 0 || !leaf.getKeyUsage()[0]
                    || leaf.getExtendedKeyUsage() == null
                    || !leaf.getExtendedKeyUsage().contains(CLIENT_AUTH_EKU)) {
                throw new IllegalArgumentException("certificate is not a client-auth leaf");
            }
            // The root is a trust anchor, not a member of the validated path.
            chain.removeIf(certificate -> certificate.equals(ca));
            CertPath path = factory.generateCertPath(chain);
            PKIXParameters parameters = new PKIXParameters(Set.of(new TrustAnchor(ca, null)));
            parameters.setRevocationEnabled(false);
            CertPathValidator.getInstance("PKIX").validate(path, parameters);
            ProviderPeerIdentity.fromCertificate(leaf);
            return leaf;
        } catch (IOException | CertificateException | NoSuchAlgorithmException
                | InvalidAlgorithmParameterException | CertPathValidatorException exception) {
            throw new IllegalArgumentException("client certificate is not trusted or valid", exception);
        }
    }
}
