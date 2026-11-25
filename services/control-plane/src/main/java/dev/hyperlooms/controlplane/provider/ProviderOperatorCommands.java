package dev.hyperlooms.controlplane.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.UUID;

import org.springframework.stereotype.Component;

/** Trusted local-operator commands; deliberately not exposed via REST. */
@Component
public final class ProviderOperatorCommands {

    private final ProviderEnrollmentService enrollment;
    private final ProviderIdentityService identities;
    private final OperatorCertificateVerifier certificates;
    private final ProviderHardwareReviewService hardwareReviews;

    public ProviderOperatorCommands(ProviderEnrollmentService enrollment,
            ProviderIdentityService identities, OperatorCertificateVerifier certificates,
            ProviderHardwareReviewService hardwareReviews) {
        this.enrollment = enrollment;
        this.identities = identities;
        this.certificates = certificates;
        this.hardwareReviews = hardwareReviews;
    }

    public String execute(String[] args, InputStream input) throws IOException {
        if (args.length == 0) {
            throw new IllegalArgumentException(usage());
        }
        return switch (args[0]) {
            case "issue-invite" -> {
                requireLength(args, 3);
                ProviderEnrollmentInvite invite = enrollment.issueInvite(args[1],
                        Duration.ofMinutes(Long.parseLong(args[2])));
                yield "INVITE_ID=" + invite.inviteId() + "\nINVITE_EXPIRES_AT="
                        + invite.expiresAt() + "\nINVITE_TOKEN=" + invite.token();
            }
            case "redeem-invite" -> {
                requireLength(args, 1);
                String token = new BufferedReader(new InputStreamReader(input,
                        StandardCharsets.US_ASCII)).readLine();
                ProviderEnrollmentResult result = enrollment.redeemInvite(token);
                yield "NODE_ID=" + result.nodeId() + "\nREPLAYED=" + result.replayed();
            }
            case "revoke-invite" -> {
                requireLength(args, 2);
                yield "REVOKED=" + enrollment.revokeUnusedInvite(UUID.fromString(args[1]));
            }
            case "bind-certificate" -> {
                requireLength(args, 3);
                UUID nodeId = UUID.fromString(args[1]);
                identities.bindExpectedCertificate(nodeId,
                        certificates.verifyClientCertificate(Path.of(args[2])));
                yield "CERTIFICATE_BOUND=" + nodeId;
            }
            case "stage-rotation" -> {
                requireLength(args, 3);
                UUID nodeId = UUID.fromString(args[1]);
                identities.stageReplacementCertificate(nodeId,
                        certificates.verifyClientCertificate(Path.of(args[2])));
                yield "ROTATION_STAGED=" + nodeId;
            }
            case "revoke-node" -> {
                requireLength(args, 2);
                yield "REVOKED=" + identities.revokeNode(UUID.fromString(args[1]));
            }
            case "inspect-gpu" -> {
                requireLength(args, 3);
                ProviderHardwareReviewService.Candidate candidate = hardwareReviews.inspect(
                        UUID.fromString(args[1]), args[2]);
                yield "NODE_ID=" + candidate.nodeId() + "\nGPU_UUID=" + candidate.gpuUuid()
                        + "\nMODEL=" + candidate.model() + "\nVRAM_BYTES=" + candidate.vramBytes()
                        + "\nDRIVER_VERSION=" + candidate.driverVersion()
                        + "\nCUDA_VERSION=" + candidate.cudaVersion()
                        + "\nVERIFICATION_STATE=" + candidate.state()
                        + "\nREPORT_SHA256=" + candidate.reportSha256();
            }
            case "approve-gpu" -> {
                requireLength(args, 6);
                hardwareReviews.approve(UUID.fromString(args[1]), args[2], args[3], args[4], args[5]);
                yield "GPU_REVIEW_RECORDED=" + args[2] + "\nNODE_REMAINS_OFFLINE=true";
            }
            default -> throw new IllegalArgumentException(usage());
        };
    }

    private static void requireLength(String[] args, int expected) {
        if (args.length != expected) {
            throw new IllegalArgumentException(usage());
        }
    }

    private static String usage() {
        return "operator <issue-invite OWNER TTL_MINUTES | redeem-invite (token on stdin) "
                + "| revoke-invite INVITE_ID | bind-certificate NODE_ID CERT_PEM "
                + "| stage-rotation NODE_ID CERT_PEM | revoke-node NODE_ID "
                + "| inspect-gpu NODE_ID GPU_UUID "
                + "| approve-gpu NODE_ID GPU_UUID REPORT_SHA256 EVIDENCE_SHA256 REVIEWER_SUBJECT>";
    }
}
