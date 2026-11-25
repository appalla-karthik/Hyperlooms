package session

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"fmt"
	"log/slog"
	"os"
	"time"

	agentv1 "github.com/appalla-karthik/Hyperlooms/agents/provider/gen/hyperlooms/agent/v1"
	"github.com/appalla-karthik/Hyperlooms/agents/provider/internal/config"
	"github.com/appalla-karthik/Hyperlooms/agents/provider/internal/status"
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/protobuf/types/known/timestamppb"
)

const retryDelay = 10 * time.Second

// Run reconnects outbound only. Readiness is false whenever no authenticated session exists.
func Run(ctx context.Context, cfg config.Config, version string, logger *slog.Logger, setReady func(bool), state *status.Store) {
	for ctx.Err() == nil {
		setReady(false)
		state.Connecting()
		if err := connectOnce(ctx, cfg, version, logger, setReady, state); err != nil && ctx.Err() == nil {
			state.Disconnected(err)
			logger.Warn("agent session disconnected", "error", err)
		}
		setReady(false)
		if ctx.Err() != nil {
			state.Disconnected(nil)
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(retryDelay):
		}
	}
}

func connectOnce(ctx context.Context, cfg config.Config, version string, logger *slog.Logger, setReady func(bool), state *status.Store) error {
	caPEM, err := os.ReadFile(cfg.ServerCA)
	if err != nil {
		return fmt.Errorf("read server CA: %w", err)
	}
	roots := x509.NewCertPool()
	if !roots.AppendCertsFromPEM(caPEM) {
		return fmt.Errorf("server CA contains no valid certificates")
	}
	identity, err := tls.LoadX509KeyPair(cfg.ClientCert, cfg.ClientKey)
	if err != nil {
		return fmt.Errorf("load client certificate: %w", err)
	}
	journal, err := openLeaseJournal(cfg.StateDir, cfg.NodeID)
	if err != nil {
		return fmt.Errorf("open durable lease journal: %w", err)
	}
	gpus, err := DiscoverGPUs(ctx)
	if err != nil {
		return err
	}
	state.ReportGPUs(StatusGPUs(gpus))
	connection, err := grpc.NewClient(cfg.GrpcAddress, grpc.WithTransportCredentials(credentials.NewTLS(&tls.Config{
		MinVersion:   tls.VersionTLS13,
		RootCAs:      roots,
		Certificates: []tls.Certificate{identity},
	})))
	if err != nil {
		return fmt.Errorf("create gRPC client: %w", err)
	}
	defer connection.Close()
	sessionCtx, cancelSession := context.WithCancel(ctx)
	defer cancelSession()
	helloTimeout := time.AfterFunc(15*time.Second, cancelSession)
	defer helloTimeout.Stop()
	stream, err := agentv1.NewAgentSessionServiceClient(connection).Connect(
		sessionCtx, grpc.MaxCallRecvMsgSize(64*1024))
	if err != nil {
		return fmt.Errorf("open agent stream: %w", err)
	}
	requestID := fmt.Sprintf("hello-%d", time.Now().UnixNano())
	if err := stream.Send(&agentv1.ConnectRequest{
		MessageId: requestID,
		Body: &agentv1.ConnectRequest_Hello{Hello: &agentv1.AgentHello{
			NodeId: cfg.NodeID, ProtocolVersion: 1, AgentVersion: version,
			Gpus: gpus, ReportedAt: timestamppb.Now(),
		}},
	}); err != nil {
		return fmt.Errorf("send agent hello: %w", err)
	}
	response, err := stream.Recv()
	if err != nil {
		return fmt.Errorf("receive hello acknowledgement: %w", err)
	}
	ack := response.GetHelloAcknowledgement()
	if ack == nil || ack.GetRequestMessageId() != requestID || !ack.GetAccepted() || ack.GetHeartbeatIntervalSeconds() == 0 {
		return fmt.Errorf("agent hello rejected or invalid acknowledgement")
	}
	helloTimeout.Stop()
	logger.Info("agent session authenticated", "node_id", cfg.NodeID, "gpu_count", len(gpus))
	interval := time.Duration(ack.GetHeartbeatIntervalSeconds()) * time.Second
	if interval < time.Second || interval > time.Minute {
		return fmt.Errorf("invalid heartbeat interval from control plane")
	}
	setReady(true)
	state.Connected()
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	var sequence uint64
	pendingReceipts := make(map[string]string)
	for {
		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
			sequence++
			requestID = fmt.Sprintf("heartbeat-%d", sequence)
			ackTimeout := time.AfterFunc(2*interval, cancelSession)
			if err := stream.Send(&agentv1.ConnectRequest{
				MessageId: requestID,
				Body: &agentv1.ConnectRequest_Heartbeat{Heartbeat: &agentv1.AgentHeartbeat{
					NodeId: cfg.NodeID, Sequence: sequence,
					Availability: agentv1.AvailabilityState_AVAILABILITY_STATE_OFFLINE,
					SentAt:       timestamppb.Now(),
				}},
			}); err != nil {
				ackTimeout.Stop()
				return fmt.Errorf("send heartbeat: %w", err)
			}
			err := receiveHeartbeatAcknowledgement(stream, requestID, cfg.NodeID,
				gpus, pendingReceipts, journal, logger)
			ackTimeout.Stop()
			if err != nil {
				return fmt.Errorf("heartbeat exchange: %w", err)
			}
			state.Heartbeat()
		}
	}
}

// StatusGPUs creates a local-console copy of a driver report.
func StatusGPUs(gpus []*agentv1.GpuCapability) []status.GPU {
	reported := make([]status.GPU, 0, len(gpus))
	for _, gpu := range gpus {
		reported = append(reported, status.GPU{
			UUID: gpu.GetGpuUuid(), Model: gpu.GetModel(), VRAMBytes: gpu.GetVramBytes(),
			DriverVersion: gpu.GetDriverVersion(), CUDAVersion: gpu.GetCudaVersion(),
		})
	}
	return reported
}
