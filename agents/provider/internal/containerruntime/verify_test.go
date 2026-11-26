package containerruntime

import (
	"context"
	"errors"
	"reflect"
	"testing"
)

func TestVerifyRequiresExactWorkflowIdentityAndDigest(t *testing.T) {
	fake := &fakeDocker{}
	if err := verifyWith(context.Background(), testImage, fake); err != nil {
		t.Fatalf("verification command failed: %v", err)
	}
	want := []string{"verify", "--certificate-identity=" + trustedWorkflowIdentity,
		"--certificate-oidc-issuer=" + trustedOIDCIssuer, testImage}
	if len(fake.calls) != 1 || !reflect.DeepEqual(fake.calls[0], want) {
		t.Fatalf("unexpected verification command: %v", fake.calls)
	}
	if err := verifyWith(context.Background(), "ubuntu:latest", fake); err == nil || len(fake.calls) != 1 {
		t.Fatalf("unpinned image reached verifier: %v", fake.calls)
	}
	fake.errors = map[string]error{"verify": errors.New("untrusted signer")}
	if err := verifyWith(context.Background(), testImage, fake); err == nil {
		t.Fatal("untrusted signature was accepted")
	}
}
