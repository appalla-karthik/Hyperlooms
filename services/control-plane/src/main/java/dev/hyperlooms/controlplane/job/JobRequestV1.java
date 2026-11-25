package dev.hyperlooms.controlplane.job;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/** Versioned, canonical request identity; the input manifest is not yet staged or trusted. */
public record JobRequestV1(UUID recipeId, String inputManifestSha256) {

    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    public JobRequestV1 {
        if (recipeId == null) {
            throw new IllegalArgumentException("recipeId is required");
        }
        if (inputManifestSha256 == null || !SHA256.matcher(inputManifestSha256).matches()) {
            throw new IllegalArgumentException("inputManifestSha256 must be lowercase SHA-256 hex");
        }
    }

    /** SHA-256 of the fixed UTF-8 v1 representation, independent of JSON field order. */
    public String requestSha256() {
        String canonical = "hyperlooms-job-request-v1\n"
                + "recipeId=" + recipeId + "\n"
                + "inputManifestSha256=" + inputManifestSha256 + "\n";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }
}
