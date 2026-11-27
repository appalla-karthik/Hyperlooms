package session

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"strings"
	"testing"
	"time"

	agentv1 "github.com/appalla-karthik/Hyperlooms/agents/provider/gen/hyperlooms/agent/v1"
	"google.golang.org/grpc"
)

type fakeInputChunks struct {
	chunks []*agentv1.ReadInputResponse
}

type fakeReadInputStream struct {
	grpc.ClientStream
	chunks fakeInputChunks
}

func (fake *fakeReadInputStream) Recv() (*agentv1.ReadInputResponse, error) {
	return fake.chunks.Recv()
}

type fakeArtifactClient struct {
	request *agentv1.ReadInputRequest
	stream  *fakeReadInputStream
}

func (fake *fakeArtifactClient) ReadInput(_ context.Context, request *agentv1.ReadInputRequest,
	_ ...grpc.CallOption) (grpc.ServerStreamingClient[agentv1.ReadInputResponse], error) {
	fake.request = request
	return fake.stream, nil
}

func TestFetchInputRequiresConfirmedCurrentLeaseAndSendsScopedIdentifiers(t *testing.T) {
	data := []byte(`{"small":"verified"}`)
	now := time.Now().UTC()
	journal := &leaseJournal{active: &savedLease{
		LeaseID: "lease-id", FencingToken: 42, ExpiresAt: now.Add(time.Minute),
		Command: &savedCommand{CommandID: "command-id", ServerConfirmedAt: &now},
	}}
	client := &fakeArtifactClient{stream: &fakeReadInputStream{chunks: fakeInputChunks{
		chunks: testInputChunks(data),
	}}}
	got, err := fetchInput(context.Background(), client, journal)
	if err != nil || string(got) != string(data) {
		t.Fatalf("confirmed input read failed: %v", err)
	}
	if client.request.GetLeaseId() != "lease-id" || client.request.GetCommandId() != "command-id" ||
		client.request.GetFencingToken() != 42 {
		t.Fatal("scoped input request omitted lease, command or fencing token")
	}
	journal.active.ExpiresAt = now.Add(-time.Second)
	client.request = nil
	if _, err := fetchInput(context.Background(), client, journal); err == nil || client.request != nil {
		t.Fatal("expired lease reached artifact service")
	}
	journal.active.ExpiresAt = now.Add(time.Minute)
	journal.active.Command.ServerConfirmedAt = nil
	if _, err := fetchInput(context.Background(), client, journal); err == nil || client.request != nil {
		t.Fatal("unconfirmed command reached artifact service")
	}
}

func (fake *fakeInputChunks) Recv() (*agentv1.ReadInputResponse, error) {
	if len(fake.chunks) == 0 {
		return nil, io.EOF
	}
	chunk := fake.chunks[0]
	fake.chunks = fake.chunks[1:]
	return chunk, nil
}

func testInputChunks(data []byte) []*agentv1.ReadInputResponse {
	digest := sha256.Sum256(data)
	chunks := make([]*agentv1.ReadInputResponse, 0)
	for offset := 0; offset < len(data); offset += maxInputChunkBytes {
		end := min(len(data), offset+maxInputChunkBytes)
		chunks = append(chunks, &agentv1.ReadInputResponse{
			Offset: uint64(offset), Data: data[offset:end],
			TotalBytes: uint64(len(data)), Sha256: hex.EncodeToString(digest[:]),
		})
	}
	return chunks
}

func TestAssembleInputValidatesBoundedStream(t *testing.T) {
	input := []byte(`{"padding":"` + strings.Repeat("x", 70_000) + `"}`)
	got, err := assembleInput(&fakeInputChunks{chunks: testInputChunks(input)})
	if err != nil || string(got) != string(input) {
		t.Fatalf("valid input was not assembled: %v", err)
	}
}

func TestAssembleInputRejectsReorderedTamperedAndIncompleteChunks(t *testing.T) {
	input := []byte(`{"padding":"` + strings.Repeat("x", 35_000) + `"}`)
	for name, mutate := range map[string]func([]*agentv1.ReadInputResponse) []*agentv1.ReadInputResponse{
		"wrong offset": func(chunks []*agentv1.ReadInputResponse) []*agentv1.ReadInputResponse {
			chunks[1].Offset++
			return chunks
		},
		"wrong digest": func(chunks []*agentv1.ReadInputResponse) []*agentv1.ReadInputResponse {
			chunks[1].Sha256 = strings.Repeat("a", 64)
			return chunks
		},
		"tampered bytes": func(chunks []*agentv1.ReadInputResponse) []*agentv1.ReadInputResponse {
			chunks[0].Data[0] = 'x'
			return chunks
		},
		"truncated": func(chunks []*agentv1.ReadInputResponse) []*agentv1.ReadInputResponse {
			return chunks[:1]
		},
		"oversized": func(chunks []*agentv1.ReadInputResponse) []*agentv1.ReadInputResponse {
			chunks[0].TotalBytes = maxRecipeInputBytes + 1
			return chunks
		},
	} {
		t.Run(name, func(t *testing.T) {
			if _, err := assembleInput(&fakeInputChunks{chunks: mutate(testInputChunks(append([]byte(nil), input...)))}); err == nil {
				t.Fatal("unsafe input stream was accepted")
			}
		})
	}
	invalidJSON := []byte("not JSON")
	if _, err := assembleInput(&fakeInputChunks{chunks: testInputChunks(invalidJSON)}); err == nil {
		t.Fatal("non-JSON request was accepted")
	}
}
