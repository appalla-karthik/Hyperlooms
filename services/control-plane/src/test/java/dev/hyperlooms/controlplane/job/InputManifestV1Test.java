package dev.hyperlooms.controlplane.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;

class InputManifestV1Test {

    private static final String A = "a".repeat(64);
    private static final String SDK_VECTOR = "{\"files\":[{\"path\":\"nested/weights.bin\","
            + "\"sha256\":\"9a129038d9a00aed0cf6a7ea059ca50a813449061ab87848cf1a13eafdf33b2c\","
            + "\"sizeBytes\":7},{\"path\":\"request.json\","
            + "\"sha256\":\"44136fa355b3678a1146ad16f7e8649e94fb4fc21fe77e8310c060f61caaff8a\","
            + "\"sizeBytes\":2}],\"schemaVersion\":1}";

    @Test
    void matchesPythonSdkCanonicalVector() {
        InputManifestV1 manifest = parse(SDK_VECTOR);

        assertEquals("b79596acff3aa23683569154b052ff4543778fdb5999eaab3a5e1ba7c24375b4",
                manifest.sha256());
        assertEquals(2, manifest.files().size());
        assertEquals("nested/weights.bin", manifest.files().get(0).path());
        assertEquals(7, manifest.files().get(0).sizeBytes());
        assertTrue(manifest.matchesDeclaredDigest(manifest.sha256()));
        assertFalse(manifest.matchesDeclaredDigest(A));
        assertThrows(UnsupportedOperationException.class,
                () -> manifest.files().clear());
    }

    @Test
    void acceptsEmptyCanonicalManifest() {
        assertTrue(parse("{\"files\":[],\"schemaVersion\":1}").files().isEmpty());
    }

    @Test
    void rejectsNoncanonicalAndMalformedManifest() {
        String valid = single("input.bin", "0");
        for (String rejected : new String[] {
                valid + "\n",
                valid.replace("{\"files\":", "{ \"files\":"),
                valid.replace("\"files\":", "\"schemaVersion\":1,\"files\":"),
                valid.replace("\"sizeBytes\":0", "\"sizeBytes\":0.0"),
                valid.replace("\"sizeBytes\":0", "\"sizeBytes\":-1"),
                valid.replace("\"sizeBytes\":0", "\"sizeBytes\":9223372036854775808"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":2"),
                valid.replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"extra\":0"),
                valid.replace("\"sha256\":\"" + A, "\"sha256\":\"" + "A".repeat(64)),
                valid.replace("\"sizeBytes\":0", "\"sizeBytes\":0,\"extra\":0"),
                "not JSON"
        }) {
            assertThrows(IllegalArgumentException.class, () -> parse(rejected), rejected);
        }
        assertThrows(IllegalArgumentException.class, () -> InputManifestV1.parse(null));
        assertThrows(IllegalArgumentException.class,
                () -> InputManifestV1.parse(new byte[InputManifestV1.MAX_BYTES + 1]));
    }

    @Test
    void rejectsUnsafeOrUnsortedPaths() {
        for (String path : new String[] {"../escape", "./same", "/absolute", "a//b",
                "a\\b", "C:/drive", "a/", "caf\u00e9"}) {
            assertThrows(IllegalArgumentException.class, () -> parse(single(path, "0")), path);
        }
        String first = entry("z", "0");
        String second = entry("a", "0");
        assertThrows(IllegalArgumentException.class,
                () -> parse("{\"files\":[" + first + "," + second + "],\"schemaVersion\":1}"));
        assertThrows(IllegalArgumentException.class,
                () -> parse("{\"files\":[" + first + "," + first + "],\"schemaVersion\":1}"));
    }

    @Test
    void rejectsMoreThan256EntriesEvenWhenUnderByteLimit() {
        StringBuilder json = new StringBuilder("{\"files\":[");
        for (int index = 0; index < 257; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append(entry(String.format("file-%03d", index), "0"));
        }
        json.append("],\"schemaVersion\":1}");
        assertThrows(IllegalArgumentException.class, () -> parse(json.toString()));
    }

    private static String single(String path, String size) {
        return "{\"files\":[" + entry(path, size) + "],\"schemaVersion\":1}";
    }

    private static String entry(String path, String size) {
        return "{\"path\":\"" + path + "\",\"sha256\":\"" + A
                + "\",\"sizeBytes\":" + size + "}";
    }

    private static InputManifestV1 parse(String json) {
        return InputManifestV1.parse(json.getBytes(StandardCharsets.UTF_8));
    }
}
