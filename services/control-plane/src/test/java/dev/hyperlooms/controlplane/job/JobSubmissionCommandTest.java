package dev.hyperlooms.controlplane.job;

import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class JobSubmissionCommandTest {

    @Test
    void rejectsMissingOrMalformedIdentityAndRequestHash() {
        UUID recipeId = UUID.randomUUID();
        String digest = "a".repeat(64);

        assertThrows(IllegalArgumentException.class,
                () -> new JobSubmissionCommand(" ", recipeId, "key", digest));
        assertThrows(IllegalArgumentException.class,
                () -> new JobSubmissionCommand("requester", recipeId, " ", digest));
        assertThrows(IllegalArgumentException.class,
                () -> new JobSubmissionCommand("requester", null, "key", digest));
        assertThrows(IllegalArgumentException.class,
                () -> new JobSubmissionCommand("requester", recipeId, "key", "not-a-sha256"));
    }
}
