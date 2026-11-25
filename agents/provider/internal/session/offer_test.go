package session

import (
	"errors"
	"io"
	"log/slog"
	"os"
	"path/filepath"
	"testing"
	"time"

	agentv1 "github.com/appalla-karthik/Hyperlooms/agents/provider/gen/hyperlooms/agent/v1"
	"google.golang.org/protobuf/types/known/timestamppb"
)

const (
	testNodeID  = "cafe0000-0000-4000-8000-000000000001"
	testJobID   = "cafe0000-0000-4000-8000-000000000002"
	testTryID   = "cafe0000-0000-4000-8000-000000000003"
	testLeaseID = "cafe0000-0000-4000-8000-000000000004"
)

type fakeLeaseExchange struct {
	responses []*agentv1.ConnectResponse
	sent      []*agentv1.ConnectRequest
}

func (fake *fakeLeaseExchange) Send(request *agentv1.ConnectRequest) error {
	fake.sent = append(fake.sent, request)
	return nil
}

func (fake *fakeLeaseExchange) Recv() (*agentv1.ConnectResponse, error) {
	if len(fake.responses) == 0 {
		return nil, errors.New("no more responses")
	}
	response := fake.responses[0]
	fake.responses = fake.responses[1:]
	return response, nil
}

func testOffer() *agentv1.LeaseOffer {
	return &agentv1.LeaseOffer{
		JobId: testJobID, AttemptId: testTryID, LeaseId: testLeaseID,
		NodeId: testNodeID, GpuUuid: "GPU-test", FencingToken: 42,
		ExpiresAt: timestamppb.New(time.Now().Add(time.Minute)),
	}
}

func testGPUs() []*agentv1.GpuCapability {
	return []*agentv1.GpuCapability{{GpuUuid: "GPU-test"}}
}

func TestHeartbeatExchangeReceivesOfferWithoutStartingWork(t *testing.T) {
	exchange := &fakeLeaseExchange{responses: []*agentv1.ConnectResponse{
		{Body: &agentv1.ConnectResponse_LeaseOffer{LeaseOffer: testOffer()}},
		{Body: &agentv1.ConnectResponse_HeartbeatAcknowledgement{
			HeartbeatAcknowledgement: &agentv1.HeartbeatAcknowledgement{
				RequestMessageId: "heartbeat-1",
			},
		}},
	}}
	pending := map[string]string{}
	journal, err := openLeaseJournal(privateStateDir(t), testNodeID)
	if err != nil {
		t.Fatal(err)
	}
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	if err := receiveHeartbeatAcknowledgement(exchange, "heartbeat-1", testNodeID,
		testGPUs(), pending, journal, logger); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(filepath.Join(journal.dir, journalFile)); err != nil {
		t.Fatalf("lease was acknowledged without durable journal: %v", err)
	}
	if len(exchange.sent) != 1 || exchange.sent[0].GetLeaseReceipt().GetLeaseId() != testLeaseID {
		t.Fatalf("expected one transport receipt, got %v", exchange.sent)
	}
	if len(pending) != 1 {
		t.Fatalf("expected pending receipt acknowledgement, got %v", pending)
	}
	exchange.responses = []*agentv1.ConnectResponse{
		{Body: &agentv1.ConnectResponse_LeaseReceiptAcknowledgement{
			LeaseReceiptAcknowledgement: &agentv1.LeaseReceiptAcknowledgement{
				RequestMessageId: "receipt-" + testLeaseID, LeaseId: testLeaseID,
			},
		}},
		{Body: &agentv1.ConnectResponse_HeartbeatAcknowledgement{
			HeartbeatAcknowledgement: &agentv1.HeartbeatAcknowledgement{
				RequestMessageId: "heartbeat-2",
			},
		}},
	}
	if err := receiveHeartbeatAcknowledgement(exchange, "heartbeat-2", testNodeID,
		testGPUs(), pending, journal, logger); err != nil {
		t.Fatal(err)
	}
	if len(pending) != 0 {
		t.Fatalf("receipt acknowledgement was not cleared: %v", pending)
	}
	reopened, err := openLeaseJournal(journal.dir, testNodeID)
	if err != nil || reopened.active == nil || reopened.active.ConfirmedAt == nil {
		t.Fatalf("confirmed receipt did not survive reopening: %v", err)
	}
}

func TestInvalidOffersAreNotAcknowledged(t *testing.T) {
	tests := map[string]func(*agentv1.LeaseOffer){
		"wrong node":   func(offer *agentv1.LeaseOffer) { offer.NodeId = testJobID },
		"wrong GPU":    func(offer *agentv1.LeaseOffer) { offer.GpuUuid = "GPU-other" },
		"zero token":   func(offer *agentv1.LeaseOffer) { offer.FencingToken = 0 },
		"expired":      func(offer *agentv1.LeaseOffer) { offer.ExpiresAt = timestamppb.New(time.Now().Add(-time.Minute)) },
		"malformed ID": func(offer *agentv1.LeaseOffer) { offer.LeaseId = "bad" },
	}
	for name, change := range tests {
		t.Run(name, func(t *testing.T) {
			offer := testOffer()
			change(offer)
			exchange := &fakeLeaseExchange{responses: []*agentv1.ConnectResponse{
				{Body: &agentv1.ConnectResponse_LeaseOffer{LeaseOffer: offer}},
			}}
			journal, err := openLeaseJournal(privateStateDir(t), testNodeID)
			if err != nil {
				t.Fatal(err)
			}
			logger := slog.New(slog.NewTextHandler(io.Discard, nil))
			if err := receiveHeartbeatAcknowledgement(exchange, "heartbeat-1", testNodeID,
				testGPUs(), map[string]string{}, journal, logger); err == nil {
				t.Fatal("expected invalid offer to close the exchange")
			}
			if len(exchange.sent) != 0 {
				t.Fatal("invalid offer was acknowledged")
			}
		})
	}
}
