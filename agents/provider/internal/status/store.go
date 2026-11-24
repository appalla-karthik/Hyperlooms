package status

import (
	"slices"
	"sync"
	"time"
)

const maxEvents = 32

type Event struct {
	At      time.Time `json:"at"`
	Kind    string    `json:"kind"`
	Message string    `json:"message"`
}

// GPU is a provider claim. It does not imply that the control plane verified it.
type GPU struct {
	UUID          string `json:"uuid"`
	Model         string `json:"model"`
	VRAMBytes     uint64 `json:"vramBytes"`
	DriverVersion string `json:"driverVersion"`
	CUDAVersion   string `json:"cudaVersion"`
}

type Snapshot struct {
	AgentID       string     `json:"agentId"`
	Version       string     `json:"version"`
	NodeID        string     `json:"nodeId,omitempty"`
	ControlPlane  string     `json:"controlPlane,omitempty"`
	Connection    string     `json:"connection"`
	Ready         bool       `json:"ready"`
	GPUs          []GPU      `json:"gpus"`
	LastConnected *time.Time `json:"lastConnected,omitempty"`
	LastHeartbeat *time.Time `json:"lastHeartbeat,omitempty"`
	LastError     string     `json:"lastError,omitempty"`
	HardwareError string     `json:"hardwareError,omitempty"`
	Events        []Event    `json:"events"`
}

// Store is the in-memory, read-only view exposed by the loopback console.
type Store struct {
	mu       sync.RWMutex
	snapshot Snapshot
}

func New(agentID, version, nodeID, controlPlane string) *Store {
	connection := "not_configured"
	if controlPlane != "" {
		connection = "connecting"
	}
	return &Store{snapshot: Snapshot{
		AgentID: agentID, Version: version, NodeID: nodeID,
		ControlPlane: controlPlane, Connection: connection, GPUs: []GPU{},
		Events: []Event{},
	}}
}

func (s *Store) Get() Snapshot {
	s.mu.RLock()
	defer s.mu.RUnlock()
	copy := s.snapshot
	copy.GPUs = append([]GPU{}, s.snapshot.GPUs...)
	copy.Events = append([]Event{}, s.snapshot.Events...)
	return copy
}

func (s *Store) SetReady(ready bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.snapshot.Ready = ready
}

func (s *Store) Connecting() {
	s.mu.Lock()
	defer s.mu.Unlock()
	if s.snapshot.Connection != "connecting" {
		s.appendEvent("connection", "Reconnecting to the control plane")
	}
	s.snapshot.Connection = "connecting"
	s.snapshot.Ready = false
}

func (s *Store) ReportGPUs(gpus []GPU) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if !slices.Equal(s.snapshot.GPUs, gpus) || s.snapshot.HardwareError != "" {
		s.appendEvent("hardware", "NVIDIA inventory updated")
	}
	s.snapshot.GPUs = append([]GPU{}, gpus...)
	s.snapshot.HardwareError = ""
}

func (s *Store) HardwareFailed(err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	if err != nil && s.snapshot.HardwareError != err.Error() {
		s.appendEvent("hardware", "NVIDIA discovery failed")
	}
	s.snapshot.GPUs = []GPU{}
	if err != nil {
		s.snapshot.HardwareError = err.Error()
	}
}

func (s *Store) Connected() {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now().UTC()
	s.snapshot.Connection = "connected"
	s.snapshot.Ready = true
	s.snapshot.LastConnected = &now
	s.snapshot.LastError = ""
	s.appendEvent("connection", "mTLS session authenticated")
}

func (s *Store) Heartbeat() {
	s.mu.Lock()
	defer s.mu.Unlock()
	now := time.Now().UTC()
	s.snapshot.LastHeartbeat = &now
}

func (s *Store) Disconnected(err error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.snapshot.Connection = "disconnected"
	s.snapshot.Ready = false
	if err != nil {
		s.snapshot.LastError = err.Error()
		s.appendEvent("connection", "Session disconnected")
	}
}

func (s *Store) appendEvent(kind, message string) {
	s.snapshot.Events = append(s.snapshot.Events, Event{
		At: time.Now().UTC(), Kind: kind, Message: message,
	})
	if len(s.snapshot.Events) > maxEvents {
		s.snapshot.Events = append([]Event{}, s.snapshot.Events[len(s.snapshot.Events)-maxEvents:]...)
	}
}
