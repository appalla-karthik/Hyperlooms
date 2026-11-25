package dev.hyperlooms.controlplane.job;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Strict reader for the Python SDK's canonical input-manifest v1 bytes. */
public final class InputManifestV1 {

    public static final int MAX_BYTES = 65_536;
    public static final int MAX_FILES = 256;

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern PATH = Pattern.compile("[A-Za-z0-9._/-]{1,240}");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-f]{64}");

    private final List<Entry> files;
    private final String sha256;

    private InputManifestV1(List<Entry> files, String sha256) {
        this.files = List.copyOf(files);
        this.sha256 = sha256;
    }

    public static InputManifestV1 parse(byte[] bytes) {
        if (bytes == null || bytes.length > MAX_BYTES) {
            throw new IllegalArgumentException("input manifest exceeds 64 KiB or is absent");
        }

        final JsonNode root;
        try {
            root = JSON.readTree(bytes);
        } catch (IOException exception) {
            throw new IllegalArgumentException("input manifest is not valid JSON", exception);
        }
        if (root == null || !root.isObject() || root.size() != 2
                || !root.path("schemaVersion").isInt()
                || root.path("schemaVersion").intValue() != 1
                || !root.path("files").isArray()
                || root.path("files").size() > MAX_FILES) {
            throw new IllegalArgumentException("input manifest v1 schema is invalid");
        }

        List<Entry> files = new ArrayList<>();
        String previousPath = null;
        StringBuilder canonical = new StringBuilder("{\"files\":[");
        for (JsonNode file : root.path("files")) {
            if (!file.isObject() || file.size() != 3) {
                throw new IllegalArgumentException("input manifest file schema is invalid");
            }
            String path = file.path("path").textValue();
            String fileSha256 = file.path("sha256").textValue();
            JsonNode size = file.path("sizeBytes");
            if (path == null || !PATH.matcher(path).matches()
                    || Arrays.stream(path.split("/", -1))
                            .anyMatch(part -> part.isEmpty() || part.equals(".") || part.equals(".."))
                    || (previousPath != null && previousPath.compareTo(path) >= 0)
                    || fileSha256 == null || !SHA256.matcher(fileSha256).matches()
                    || !size.isIntegralNumber() || !size.canConvertToLong()
                    || size.longValue() < 0) {
                throw new IllegalArgumentException("input manifest file entry is invalid");
            }
            if (!files.isEmpty()) {
                canonical.append(',');
            }
            canonical.append("{\"path\":\"").append(path)
                    .append("\",\"sha256\":\"").append(fileSha256)
                    .append("\",\"sizeBytes\":").append(size.longValue()).append('}');
            files.add(new Entry(path, size.longValue(), fileSha256));
            previousPath = path;
        }
        canonical.append("],\"schemaVersion\":1}");
        byte[] canonicalBytes = canonical.toString().getBytes(StandardCharsets.US_ASCII);
        if (!Arrays.equals(bytes, canonicalBytes)) {
            throw new IllegalArgumentException("input manifest bytes are not canonical v1 JSON");
        }
        return new InputManifestV1(files, sha256(canonicalBytes));
    }

    public List<Entry> files() {
        return files;
    }

    public String sha256() {
        return sha256;
    }

    public boolean matchesDeclaredDigest(String declaredSha256) {
        return sha256.equals(declaredSha256);
    }

    private static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    public record Entry(String path, long sizeBytes, String sha256) {
    }
}
