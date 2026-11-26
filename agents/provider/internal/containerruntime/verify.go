package containerruntime

import (
	"context"
	"fmt"
	"io"
	"os/exec"
	"time"
)

const (
	trustedWorkflowIdentity = "https://github.com/appalla-karthik/Hyperlooms/.github/workflows/recipe-image.yml@refs/heads/main"
	trustedOIDCIssuer       = "https://token.actions.githubusercontent.com"
	verificationTimeout     = time.Minute
)

// verifyImage refuses unsigned or differently signed digests before Docker create.
// A valid signature is necessary but does not replace operator recipe approval.
func verifyImage(ctx context.Context, image string) error {
	return verifyWith(ctx, image, cosignCLI{})
}

func verifyWith(ctx context.Context, image string, cli commandRunner) error {
	repository, digest, ok := imageParts(image)
	if !ok {
		return fmt.Errorf("image must be an exact registry repository and SHA-256 digest")
	}
	verificationCtx, cancel := context.WithTimeout(ctx, verificationTimeout)
	defer cancel()
	_, err := cli.run(verificationCtx, "verify",
		"--certificate-identity="+trustedWorkflowIdentity,
		"--certificate-oidc-issuer="+trustedOIDCIssuer,
		repository+"@"+digest)
	if err != nil {
		return fmt.Errorf("image signature verification failed: %w", err)
	}
	return nil
}

func imageParts(image string) (string, string, bool) {
	for i := 0; i < len(image); i++ {
		if image[i] == '@' {
			repository, digest := image[:i], image[i+1:]
			return repository, digest, repositoryPattern.MatchString(repository) && digestPattern.MatchString(digest)
		}
	}
	return "", "", false
}

type cosignCLI struct{}

func (cosignCLI) run(ctx context.Context, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, "cosign", args...)
	cmd.Stdout = io.Discard
	stderr := &boundedOutput{limit: maxCommandOutput}
	cmd.Stderr = stderr
	if err := cmd.Run(); err != nil {
		return "", fmt.Errorf("cosign command failed: %w: %s", err, stderr.String())
	}
	return "", nil
}
