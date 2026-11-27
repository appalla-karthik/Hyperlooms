package session

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"time"

	agentv1 "github.com/appalla-karthik/Hyperlooms/agents/provider/gen/hyperlooms/agent/v1"
	"google.golang.org/grpc"
)

const (
	maxRecipeInputBytes = 1 << 20
	maxInputChunks      = 64
	maxInputChunkBytes  = 32 << 10
)

type inputChunkStream interface {
	Recv() (*agentv1.ReadInputResponse, error)
}

// fetchInput is a bounded, certificate-authenticated read. It never grants
// permission to execute; the future runtime path must recheck lease validity.
func fetchInput(ctx context.Context, client agentv1.AgentArtifactServiceClient,
	journal *leaseJournal) ([]byte, error) {
	if journal == nil || journal.active == nil || journal.active.Command == nil ||
		journal.active.Command.ServerConfirmedAt == nil ||
		!journal.active.ExpiresAt.After(time.Now()) {
		return nil, fmt.Errorf("confirmed, unexpired staged command required")
	}
	lease := journal.active
	readCtx, cancel := context.WithTimeout(ctx, 30*time.Second)
	defer cancel()
	stream, err := client.ReadInput(readCtx, &agentv1.ReadInputRequest{
		LeaseId: lease.LeaseID, CommandId: lease.Command.CommandID,
		FencingToken: lease.FencingToken,
	}, grpc.MaxCallRecvMsgSize(64*1024))
	if err != nil {
		return nil, fmt.Errorf("request scoped input: %w", err)
	}
	data, err := assembleInput(stream)
	if err != nil {
		return nil, err
	}
	if !lease.ExpiresAt.After(time.Now()) {
		return nil, fmt.Errorf("lease expired during input transfer")
	}
	return data, nil
}

func assembleInput(stream inputChunkStream) ([]byte, error) {
	var data []byte
	var total uint64
	var digest string
	for chunkNumber := 0; chunkNumber < maxInputChunks; chunkNumber++ {
		chunk, err := stream.Recv()
		if err == io.EOF {
			if len(data) == 0 || uint64(len(data)) != total {
				return nil, fmt.Errorf("input stream ended before declared length")
			}
			actual := sha256.Sum256(data)
			if hex.EncodeToString(actual[:]) != digest || !json.Valid(data) {
				return nil, fmt.Errorf("input digest or JSON validation failed")
			}
			return data, nil
		}
		if err != nil {
			return nil, fmt.Errorf("read input chunk: %w", err)
		}
		if chunk == nil || chunk.GetTotalBytes() == 0 ||
			chunk.GetTotalBytes() > maxRecipeInputBytes ||
			len(chunk.GetData()) == 0 || len(chunk.GetData()) > maxInputChunkBytes ||
			chunk.GetOffset() != uint64(len(data)) ||
			chunk.GetTotalBytes() < uint64(len(data)+len(chunk.GetData())) ||
			!validSHA256(chunk.GetSha256()) {
			return nil, fmt.Errorf("invalid input chunk")
		}
		if chunkNumber == 0 {
			total, digest = chunk.GetTotalBytes(), chunk.GetSha256()
		} else if chunk.GetTotalBytes() != total || chunk.GetSha256() != digest {
			return nil, fmt.Errorf("input metadata changed across chunks")
		}
		data = append(data, chunk.GetData()...)
	}
	return nil, fmt.Errorf("too many input chunks")
}
