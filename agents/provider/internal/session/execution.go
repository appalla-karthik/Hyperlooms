package session

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"log/slog"
	"time"

	agentv1 "github.com/appalla-karthik/Hyperlooms/agents/provider/gen/hyperlooms/agent/v1"
	"github.com/appalla-karthik/Hyperlooms/agents/provider/internal/containerruntime"
	"google.golang.org/grpc"
)

// One local worker per authenticated session. Session loss cancels Docker and
// local crash recovery removes only labeled containers; it never replays a run.
func executeLease(ctx context.Context, connection *grpc.ClientConn, lease savedLease,
	input []byte, logger *slog.Logger) (err error) {
	client := agentv1.NewAgentExecutionServiceClient(connection)
	advance := func(parent context.Context, action agentv1.ExecutionAdvanceAction) (time.Time, error) {
		callCtx, cancel := context.WithTimeout(parent, 10*time.Second)
		defer cancel()
		response, callErr := client.Advance(callCtx, &agentv1.AdvanceRequest{
			LeaseId: lease.LeaseID, CommandId: lease.Command.CommandID,
			FencingToken: lease.FencingToken, Action: action,
		})
		if callErr != nil {
			return time.Time{}, fmt.Errorf("execution authorization failed: %w", callErr)
		}
		if response.GetExpiresAt() == nil || response.GetExpiresAt().CheckValid() != nil {
			return time.Time{}, fmt.Errorf("execution authorization returned an invalid expiry")
		}
		return response.GetExpiresAt().AsTime(), nil
	}
	expires, err := advance(ctx, agentv1.ExecutionAdvanceAction_EXECUTION_ADVANCE_ACTION_STARTING)
	if err != nil {
		return err
	}
	started := true
	defer func() {
		if err != nil && started && ctx.Err() == nil {
			if _, failureErr := advance(ctx, agentv1.ExecutionAdvanceAction_EXECUTION_ADVANCE_ACTION_FAILED); failureErr != nil {
				logger.Warn("failed to report execution failure; lease expiry must reconcile", "error", failureErr)
			}
		}
	}()
	if !expires.After(time.Now().Add(10 * time.Second)) {
		return fmt.Errorf("server granted insufficient lease time")
	}
	maxRuntime := time.Duration(lease.Command.MaxRuntimeSeconds) * time.Second
	runCtx, stop := context.WithCancel(ctx)
	defer stop()
	renewDone := make(chan struct{})
	go func() {
		defer close(renewDone)
		ticker := time.NewTicker(15 * time.Second)
		defer ticker.Stop()
		current := expires
		for {
			select {
			case <-runCtx.Done():
				return
			case <-ticker.C:
				if !current.After(time.Now().Add(10 * time.Second)) {
					stop()
					return
				}
				next, renewErr := advance(runCtx, agentv1.ExecutionAdvanceAction_EXECUTION_ADVANCE_ACTION_RENEW)
				if renewErr != nil || !next.After(time.Now().Add(5*time.Second)) {
					stop()
					return
				}
				current = next
			}
		}
	}()
	defer func() { stop(); <-renewDone }()
	result, err := containerruntime.Execute(runCtx, containerruntime.Spec{
		NodeID: lease.NodeID, LeaseID: lease.LeaseID, GPUUUID: lease.GPUUUID,
		Image:       lease.Command.ImageRepository + "@" + lease.Command.ImageDigest,
		MemoryBytes: 2 << 30, MaxRuntime: maxRuntime,
		LeaseExpiresAt: time.Now().Add(maxRuntime), InputJSON: input,
		OnStarted: func(startCtx context.Context) error {
			_, startErr := advance(startCtx, agentv1.ExecutionAdvanceAction_EXECUTION_ADVANCE_ACTION_RUNNING)
			return startErr
		},
	})
	if err != nil {
		return err
	}
	if result.ExitCode != 0 || len(result.OutputJSON) == 0 || runCtx.Err() != nil {
		return fmt.Errorf("container exited without a valid result (exit %d)", result.ExitCode)
	}
	uploadCtx, cancelUpload := context.WithTimeout(runCtx, 30*time.Second)
	defer cancelUpload()
	stream, err := client.UploadResult(uploadCtx)
	if err != nil {
		return fmt.Errorf("open result upload: %w", err)
	}
	hash := sha256.Sum256(result.OutputJSON)
	digest := hex.EncodeToString(hash[:])
	for offset := 0; offset < len(result.OutputJSON); offset += 32 * 1024 {
		end := min(offset+32*1024, len(result.OutputJSON))
		if err := stream.Send(&agentv1.UploadResultRequest{
			LeaseId: lease.LeaseID, CommandId: lease.Command.CommandID,
			FencingToken: lease.FencingToken, Offset: uint64(offset),
			Data: result.OutputJSON[offset:end], TotalBytes: uint64(len(result.OutputJSON)),
			Sha256: digest,
		}); err != nil {
			return fmt.Errorf("send result chunk: %w", err)
		}
	}
	receipt, err := stream.CloseAndRecv()
	if err != nil {
		return fmt.Errorf("result completion not verified: %w", err)
	}
	if receipt.GetSha256() != digest || receipt.GetTotalBytes() != uint64(len(result.OutputJSON)) {
		return fmt.Errorf("result completion receipt does not match uploaded artifact")
	}
	started = false
	logger.Info("result uploaded and server completion confirmed", "job_id", lease.JobID, "sha256", digest)
	return nil
}
