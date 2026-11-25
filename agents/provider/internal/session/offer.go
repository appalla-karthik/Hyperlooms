package session

import (
	"encoding/hex"
	"fmt"
	"log/slog"
	"math"
	"strings"
	"time"

	agentv1 "github.com/appalla-karthik/Hyperlooms/agents/provider/gen/hyperlooms/agent/v1"
)

// leaseExchange is kept narrow so the transport receipt can be tested without a GPU.
type leaseExchange interface {
	Send(*agentv1.ConnectRequest) error
	Recv() (*agentv1.ConnectResponse, error)
}

func receiveHeartbeatAcknowledgement(stream leaseExchange, requestID, nodeID string,
	gpus []*agentv1.GpuCapability, pending map[string]string, journal *leaseJournal,
	logger *slog.Logger) error {
	for messageCount := 0; messageCount < 8; messageCount++ {
		response, err := stream.Recv()
		if err != nil {
			return err
		}
		switch {
		case response.GetLeaseOffer() != nil:
			if err := receiveOffer(stream, response.GetLeaseOffer(), nodeID, gpus, pending, journal, logger); err != nil {
				return err
			}
		case response.GetLeaseReceiptAcknowledgement() != nil:
			ack := response.GetLeaseReceiptAcknowledgement()
			leaseID, ok := pending[ack.GetRequestMessageId()]
			if !ok || leaseID != ack.GetLeaseId() {
				return fmt.Errorf("unexpected lease receipt acknowledgement")
			}
			if err := journal.confirm(leaseID); err != nil {
				return err
			}
			delete(pending, ack.GetRequestMessageId())
		case response.GetHeartbeatAcknowledgement() != nil:
			if response.GetHeartbeatAcknowledgement().GetRequestMessageId() != requestID {
				return fmt.Errorf("invalid heartbeat acknowledgement")
			}
			return nil
		default:
			return fmt.Errorf("unexpected control-plane response")
		}
	}
	return fmt.Errorf("too many messages before heartbeat acknowledgement")
}

func receiveOffer(stream leaseExchange, offer *agentv1.LeaseOffer, nodeID string,
	gpus []*agentv1.GpuCapability, pending map[string]string, journal *leaseJournal,
	logger *slog.Logger) error {
	if offer.GetNodeId() != nodeID || !validUUID(offer.GetJobId()) ||
		!validUUID(offer.GetAttemptId()) || !validUUID(offer.GetLeaseId()) ||
		offer.GetFencingToken() == 0 || offer.GetFencingToken() > math.MaxInt64 ||
		offer.GetExpiresAt() == nil || offer.GetExpiresAt().CheckValid() != nil ||
		!offer.GetExpiresAt().AsTime().After(time.Now()) {
		return fmt.Errorf("invalid or expired lease offer")
	}
	foundGPU := false
	for _, gpu := range gpus {
		if gpu.GetGpuUuid() == offer.GetGpuUuid() {
			foundGPU = true
			break
		}
	}
	if !foundGPU || len(pending) >= 4 {
		return fmt.Errorf("lease offer GPU unavailable or receipt backlog full")
	}
	// Persist and sync before admitting receipt. There is no execution runtime yet.
	if err := journal.store(offer); err != nil {
		return fmt.Errorf("persist lease offer before receipt: %w", err)
	}
	requestID := "receipt-" + offer.GetLeaseId()
	if err := stream.Send(&agentv1.ConnectRequest{
		MessageId: requestID,
		Body: &agentv1.ConnectRequest_LeaseReceipt{LeaseReceipt: &agentv1.AgentLeaseReceipt{
			NodeId: nodeID, LeaseId: offer.GetLeaseId(),
			FencingToken: offer.GetFencingToken(),
		}},
	}); err != nil {
		return fmt.Errorf("send lease receipt: %w", err)
	}
	pending[requestID] = offer.GetLeaseId()
	logger.Info("lease offer received; execution not started", "lease_id", offer.GetLeaseId())
	return nil
}

func validUUID(value string) bool {
	if len(value) != 36 || value[8] != '-' || value[13] != '-' ||
		value[18] != '-' || value[23] != '-' {
		return false
	}
	_, err := hex.DecodeString(strings.ReplaceAll(value, "-", ""))
	return err == nil
}
