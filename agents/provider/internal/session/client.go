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
	"google.golang.org/grpc"
	"google.golang.org/grpc/credentials"
	"google.golang.org/protobuf/types/known/timestamppb"
)

const retryDelay = 10 * time.Second

// Run reconnects outbound only. Readiness is false whenever no authenticated session exists.
func Run(ctx context.Context, cfg config.Config, version string, logger *slog.Logger, setReady func(bool)) {
	for ctx.Err() == nil {
		setReady(false)
		if err := connectOnce(ctx, cfg, version, logger, setReady); err != nil && ctx.Err() == nil {
			logger.Warn("agent session disconnected", "error", err)
		}
		setReady(false)
		select {
		case <-ctx.Done():
			return
		case <-time.After(retryDelay):
		}
	}
}

func connectOnce(ctx context.Context, cfg config.Config, version string, logger *slog.Logger, setReady func(bool)) error {
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
	gpus, err := DiscoverGPUs(ctx)
	if err != nil {
		return err
	}
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
	ticker := time.NewTicker(interval)
	defer ticker.Stop()
	var sequence uint64
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
			response, err := stream.Recv()
			ackTimeout.Stop()
			if err != nil {
				return fmt.Errorf("receive heartbeat acknowledgement: %w", err)
			}
			if response.GetHeartbeatAcknowledgement() == nil || response.GetHeartbeatAcknowledgement().GetRequestMessageId() != requestID {
				return fmt.Errorf("invalid heartbeat acknowledgement")
			}
		}
	}
}
