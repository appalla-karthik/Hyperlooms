package dev.hyperlooms.controlplane.job;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class JobSubmissionCommandTest {

    @Test
    void rejectsMissingIdentityAndMalformedVersionedRequest() {
        UUID recipeId = UUID.randomUUID();
        String digest = "a".repeat(64);

        assertThrows(IllegalArgumentException.class,
                () -> new JobSubmissionCommand(" ", "key", new JobRequestV1(recipeId, digest)));
        assertThrows(IllegalArgumentException.class,
                () -> new JobSubmissionCommand("requester", " ", new JobRequestV1(recipeId, digest)));
        assertThrows(IllegalArgumentException.class,
                () -> new JobSubmissionCommand("requester", "key", null));
        assertThrows(IllegalArgumentException.class,
                () -> new JobRequestV1(null, digest));
        assertThrows(IllegalArgumentException.class,
                () -> new JobRequestV1(recipeId, "not-a-sha256"));
    }
}
