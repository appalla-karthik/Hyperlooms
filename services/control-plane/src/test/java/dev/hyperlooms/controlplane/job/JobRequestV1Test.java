package dev.hyperlooms.controlplane.job;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class JobRequestV1Test {

    @Test
    void canonicalHashHasStableVersionedTestVector() {
        UUID recipeId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        JobRequestV1 request = new JobRequestV1(recipeId, "a".repeat(64));

        assertEquals("76e8248c0577e591f40f79fcc6e1211ff72ecc81107113524b98c0d8188d1df8",
                request.requestSha256());
        assertNotEquals(request.requestSha256(),
                new JobRequestV1(recipeId, "b".repeat(64)).requestSha256());
        assertNotEquals(request.requestSha256(), new JobRequestV1(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "a".repeat(64)).requestSha256());
    }

    @Test
    void rejectsMissingOrNoncanonicalFields() {
        UUID recipeId = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> new JobRequestV1(null, "a".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new JobRequestV1(recipeId, null));
        assertThrows(IllegalArgumentException.class, () -> new JobRequestV1(recipeId, "A".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> new JobRequestV1(recipeId, "a".repeat(63)));
    }
}
