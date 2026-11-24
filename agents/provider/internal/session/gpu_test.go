package session

import "testing"

func TestParseGPUs(t *testing.T) {
	gpus, err := parseGPUs("GPU-123, NVIDIA RTX A4000, 16376, 550.54\n", "12.4")
	if err != nil {
		t.Fatalf("parseGPUs: %v", err)
	}
	if len(gpus) != 1 || gpus[0].GetVramBytes() != 16376*1024*1024 || gpus[0].GetCudaVersion() != "12.4" {
		t.Fatalf("unexpected GPU report: %+v", gpus)
	}
}

func TestParseGPUsRejectsMalformedData(t *testing.T) {
	for _, input := range []string{"", "GPU-123, Model, nope, 550.54\n", "GPU-123, Model, 0, 550.54\n"} {
		if _, err := parseGPUs(input, "12.4"); err == nil {
			t.Fatalf("expected invalid input to fail: %q", input)
		}
	}
}
